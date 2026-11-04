package com.yegorkapomidorka.headswap.aa

import android.content.Context
import android.os.SystemClock
import com.yegorkapomidorka.headswap.AnnexB
import com.yegorkapomidorka.headswap.AppLog
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Self-checking transcoder without AA and USB: `--es cmd transcode_test`. Takes the largest aa-*.h264 (raw Annex-B from AA, is put into getExternalFilesDir via adb push),
 * Drives him through decoder -> encoder ~10 seconds (30 fpsIn the middle of a pause 3 with no entrance frame: encoder Must
 * repeat frame) and logs frame on in/out, fps, the first NAL out, delay.
 *
 * The settings line goes extra-om `opts` (in AaTranscoder goes as tuning) and is sorted by substrings. Tokens of the check itself
 * (not transmitted): `slowout=NN`, `resetevery=NN`, `burst`, `nopause`, `placeholder`, `phonly`.
 * The rest (`nogl`, `nobase`, `cbr`, `norealtime`, `nolowlat`) goes into the transcoder as is.
 *
 * Like the bridge, the check after loss of frame (onNeedAaKeyFrame) and after resetDecoder() ceases to serve P-frame
 * and feeds the nearest IDR from a file with SPS/PPS.
 *
 * Run (log in AppLog, `adb logcat -s Headswap` or application log file; look for `transcode_test`):
 * ```
 * adb shell am start -n com.yegorkapomidorka.headswap/.MainActivity --es cmd transcode_test
 *  # Slow drain in head unit / busy bridge monitor: onEncoderFrame sleeps 20 ms (loop encoder -> GL -> decoder -> input).
 *  # Wait: no stop, frame output go (less often), there are losses and requests IDR
 * adb shell am start -n com.yegorkapomidorka.headswap/.MainActivity --es cmd transcode_test --es opts "slowout=20"
 * adb shell am start -n com.yegorkapomidorka.headswap/.MainActivity --es cmd transcode_test --es opts "slowout=80"
 *  # resetDecoder() with another thread times in 700 ms (as START after PAUSE). Wait: decoder is re-created on the next IDR, output is resumed
 * adb shell am start -n com.yegorkapomidorka.headswap/.MainActivity --es cmd transcode_test --es opts "resetevery=700"
 *  # packs: 10 frame without delay, pause 330 ms (overlapping the feeding queue). Waiting for: loss and recovery according to IDR, without hanging
 * adb shell am start -n com.yegorkapomidorka.headswap/.MainActivity --es cmd transcode_test --es opts "burst"
 *  # all together
 * adb shell am start -n com.yegorkapomidorka.headswap/.MainActivity --es cmd transcode_test --es opts "slowout=30 resetevery=900 burst"
 * ```
 * Guard: exit encoder silent > 3 out of the planned pause -> `!!!!!! выход encoder молчит N с`.
 * At the end of RESULT2 and check thread `aa-trans-*`/`aa-gl-relay` via 2 with after stop().
 */
object TranscodeSelfCheck {
    private const val TOTAL_MS = 10_000L
    private const val PAUSE_AT_FRAME = 90          // after 3 with at 30 fps
    private const val PAUSE_MS = 3_000L
    private const val FRAME_MS = 33L
    private const val BURST_FRAMES = 10
    private const val BURST_PAUSE_MS = 330L
    private const val SILENCE_MS = 3_000L
    private const val THREAD_CHECK_DELAY_MS = 2_000L

    fun run(ctx: Context, tuning: String = "") {
        Thread({ runBlocking(ctx, tuning) }, "transcode-test").start()
    }

    private fun runBlocking(ctx: Context, tuning: String) {
        val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        val file = dir.listFiles { f -> f.name.startsWith("aa-") && f.name.endsWith(".h264") }?.maxByOrNull { it.length() }
        if (file == null) {
            AppLog.i("transcode_test: в $dir нет aa-*.h264")
            return
        }
        val (cfg, aus) = H264Stream.split(file.readBytes())
        AppLog.i("transcode_test: файл ${file.name} ${file.length() / 1024} КиБ, access unit-ов ${aus.size} (IDR ${aus.count { it.idr }}), config ${cfg?.size ?: 0} Б")
        if (cfg == null || aus.isEmpty()) {
            AppLog.i("transcode_test: нет SPS/PPS или кадров")
            return
        }
        val firstIdr = aus.indexOfFirst { it.idr }
        if (firstIdr < 0) {
            AppLog.i("transcode_test: в файле нет IDR")
            return
        }

        // tokens of the verification itself; a string goes into the transcoder without them
        val slowOutMs = Regex("""slowout=(\d+)""").find(tuning)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val resetEveryMs = Regex("""resetevery=(\d+)""").find(tuning)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val burst = Regex("""\bburst\b""").containsMatchIn(tuning)
        val tcTuning = tuning.replace(Regex("""\s*\b(?:slowout|resetevery)=\d+|\s*\bburst\b"""), "").trim()
        if (slowOutMs > 0 || resetEveryMs > 0 || burst) {
            AppLog.i("transcode_test: режим: slowout=$slowOutMs мс, resetevery=$resetEveryMs мс, burst=$burst; в транскодер tuning='$tcTuning'")
        }

        // encoder output as it goes to head unit (SPS/PPS glued with only the first IDR, as in AaBridge.onEncoderFrame):
        // enc-out.h264 near, watch ffmpeg on PC
        val dump = File(dir, "enc-out.h264").outputStream().buffered()
        var dumpGotIdr = false

        class Out : TranscodeOutput {
            val frames = AtomicLong()
            val keys = AtomicLong()
            val needKeys = AtomicLong()
            @Volatile var firstNals: List<Int>? = null
            @Volatile var csd: ByteArray? = null
            @Volatile var pauseStartFrames = -1L
            @Volatile var pauseEndFrames = -1L
            @Volatile var errors = 0
            @Volatile var gotIdr = false               // as AaBridge.gotIdr: false -> P-frame do not file, waiting for IDR from file
            @Volatile var lastFrameMs = 0L             // last time frame was released (0 - not yet)
            @Volatile var maxGapMs = 0L
            @Volatile var silentReported = false

            override fun onEncoderConfig(csd: ByteArray) {
                this.csd = csd
            }

            override fun onEncoderFrame(data: ByteArray, key: Boolean) {
                val now = SystemClock.elapsedRealtime()
                val prev = lastFrameMs
                if (prev > 0 && now - prev > maxGapMs) maxGapMs = now - prev
                lastFrameMs = now
                silentReported = false
                if (slowOutMs > 0) Thread.sleep(slowOutMs)      // slow drain in head unit / busy bridge monitor
                if (frames.getAndIncrement() == 0L) {
                    val c = csd
                    firstNals = AnnexB.nalTypes(if (c != null) AnnexB.glue(c, data) else data)
                }
                if (key) keys.incrementAndGet()
                val c = csd
                synchronized(dump) {
                    if (!dumpGotIdr && !key) return
                    dump.write(if (!dumpGotIdr && c != null && !AnnexB.startsWithSps(data)) AnnexB.glue(c, data) else data)
                    dumpGotIdr = true
                }
            }

            override fun onTranscodeError(msg: String) {
                errors++
                AppLog.i("transcode_test: ОШИБКА $msg")
            }

            override fun onNeedAaKeyFrame() {
                gotIdr = false
                needKeys.incrementAndGet()
                AppLog.i("transcode_test: декодер потерял кадр")
            }
        }

        val out = Out()
        // "placeholder": parallel to the second encoder stub, as in live session (VideoSource + transcoder)
        val ph = if (tuning.contains("placeholder")) try {
            com.yegorkapomidorka.headswap.VideoSource(1280, 720, 30, 4_000_000, object : com.yegorkapomidorka.headswap.VideoSource.Listener {
                override fun onConfig(csd: ByteArray) {}
                override fun onFrame(data: ByteArray, key: Boolean) {}
                override fun onError(msg: String, t: Throwable?) {
                    AppLog.e("transcode_test: ОШИБКА заглушки $msg", t)
                }
            }).also { it.banner = "test"; it.start(); AppLog.i("transcode_test: заглушка ${it.encoderName} запущена") }
        } catch (e: Exception) {
            AppLog.e("transcode_test: заглушка не создана", e)
            null
        } else null
        if (tuning.contains("phonly")) {
            Thread.sleep(TOTAL_MS)
            ph?.let { AppLog.i("transcode_test: только заглушка, отрисовала ${it.framesRendered} кадров"); it.stop() }
            return
        }
        val t = try {
            AaTranscoder(1280, 720, 30, 4_000_000, out, { AppLog.i("transcode_test: $it") }, tcTuning).also { it.start() }
        } catch (e: Exception) {
            AppLog.e("transcode_test: транскодер не создан", e)
            return
        }
        var dropSum = 0L                                // "lost" from statsLine (windows are reset on each call)
        fun stats(): String {
            val s = t.statsLine()
            dropSum += Regex("""потеряно (\d+)""").find(s)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            return s
        }
        t.feedConfig(cfg)
        t.requestSyncFrame()
        val t0 = SystemClock.elapsedRealtime()
        var fed = 0
        var skippedP = 0                                // P-frame file not filed due to waiting for IDR
        var idx = firstIdr
        var pauseStartMs = -1L
        val pauseEndMs = AtomicLong(-1L)
        var lastStats = t0
        val inPause = AtomicBoolean(false)
        val active = AtomicBoolean(true)                // auxiliary thread work while the feed is in progress
        val resets = AtomicInteger()
        val resetGen = AtomicInteger()                  // reset-s counter: the thread feeder learns from it that you need to wait for IDR
        val silentEpisodes = AtomicInteger()
        val maxSilentMs = AtomicLong()

        // gatekeeper: encoder exit silent > 3 with out scheduled pause
        val watch = Thread({
            try {
                while (active.get()) {
                    Thread.sleep(200)
                    if (inPause.get()) continue
                    val now = SystemClock.elapsedRealtime()
                    val ref = maxOf(out.lastFrameMs, t0, pauseEndMs.get())
                    val silent = now - ref
                    if (silent > SILENCE_MS && !out.silentReported) {
                        out.silentReported = true
                        silentEpisodes.incrementAndGet()
                        AppLog.i("transcode_test: !!!!!! выход энкодера молчит ${silent / 1000.0} с")
                    }
                    if (silent > SILENCE_MS && silent > maxSilentMs.get()) maxSilentMs.set(silent)
                }
            } catch (_: InterruptedException) {
            }
        }, "transcode-test-watch").also { it.isDaemon = true; it.start() }

        // resetevery: resetDecoder() with another thread, as bridge on START after PAUSE
        val resetter = if (resetEveryMs > 0) Thread({
            try {
                while (active.get()) {
                    Thread.sleep(resetEveryMs)
                    if (!active.get()) break
                    t.resetDecoder()
                    resetGen.incrementAndGet()
                    AppLog.i("transcode_test: resetDecoder #${resets.incrementAndGet()}")
                }
            } catch (_: InterruptedException) {
            }
        }, "transcode-test-reset").also { it.isDaemon = true; it.start() } else null

        var seenGen = 0
        var inBurst = 0
        while (SystemClock.elapsedRealtime() - t0 < TOTAL_MS) {
            if (fed == PAUSE_AT_FRAME && pauseStartMs < 0 && !tuning.contains("nopause")) {
                pauseStartMs = SystemClock.elapsedRealtime()
                out.pauseStartFrames = out.frames.get()
                AppLog.i("transcode_test: пауза входа ${PAUSE_MS / 1000} с (кадров на выходе до паузы ${out.pauseStartFrames})")
                inPause.set(true)
                Thread.sleep(PAUSE_MS)
                val pauseEnd = SystemClock.elapsedRealtime()
                pauseEndMs.set(pauseEnd)
                inPause.set(false)
                out.pauseEndFrames = out.frames.get()
                AppLog.i("transcode_test: пауза кончилась: за ${(pauseEnd - pauseStartMs) / 1000.0} с энкодер выдал ${out.pauseEndFrames - out.pauseStartFrames} кадров = %.1f fps".format((out.pauseEndFrames - out.pauseStartFrames) * 1000.0 / (pauseEnd - pauseStartMs)))
            }
            val gen = resetGen.get()
            if (gen != seenGen) {                       // was reset: to the nearest IDR from file P do not submit
                seenGen = gen
                out.gotIdr = false
            }
            if (idx >= aus.size) idx = firstIdr          // end of file: in a circle with IDR
            val au = aus[idx++]
            if (out.gotIdr) {
                t.feedFrame(au.data, fed * 33_333L)
                fed++
            } else if (au.idr) {
                // first IDR after start/loss of frame/reset: SPS/PPS from the config if there are no frame in itself (after reset the transcoder forgot them)
                val types = AnnexB.nalTypes(au.data)
                val payload = if (AnnexB.startsWithSps(au.data) && 8 in types) au.data else AnnexB.glue(cfg, au.data)
                out.gotIdr = true                        // up to feedFrame: it can call onNeedAaKeyFrame by itself
                t.feedFrame(payload, fed * 33_333L)
                fed++
                t.requestSyncFrame()
            } else {
                skippedP++
                continue                                 // IDR in the AA dump once per minute: we walk to it without delay, like a bridge on socket
            }
            if (SystemClock.elapsedRealtime() - lastStats >= 5000) {
                AppLog.i("transcode_test: ${stats()}")
                lastStats = SystemClock.elapsedRealtime()
            }
            if (burst) {
                if (++inBurst >= BURST_FRAMES) {
                    inBurst = 0
                    Thread.sleep(BURST_PAUSE_MS)
                }
            } else {
                Thread.sleep(FRAME_MS)
            }
        }
        Thread.sleep(500)
        val totalMs = SystemClock.elapsedRealtime() - t0
        AppLog.i("transcode_test: ${stats()}")
        val frames = out.frames.get()
        AppLog.i(
            "transcode_test ИТОГ: вход AA=$fed кадров, на выходе энкодера=$frames кадров (ключевых ${out.keys.get()}), %.1f fps за %.1f с, ".format(frames * 1000.0 / totalMs, totalMs / 1000.0) +
                "декодировано=${t.totalDecoded.get()}, первые NAL выхода (SPS+PPS склеены с первым кадром)=${out.firstNals}, ошибок=${out.errors}, энкодер=${t.encoderName}"
        )
        active.set(false)
        watch.interrupt()
        resetter?.interrupt()
        try {
            watch.join(1000)
            resetter?.join(2000)
        } catch (_: InterruptedException) {
        }
        AppLog.i(
            "transcode_test ИТОГ2: вход=$fed (P пропущено до IDR $skippedP), выход=$frames, потеряно транскодером=$dropSum, запросов IDR (onNeedAaKeyFrame)=${out.needKeys.get()}, " +
                "макс. разрыв между кадрами выхода=${out.maxGapMs} мс, эпизодов молчания>${SILENCE_MS / 1000} с=${silentEpisodes.get()}" +
                (if (silentEpisodes.get() > 0) " (!!!!!! макс. ${maxSilentMs.get() / 1000.0} с)" else "") + ", reset-ов=${resets.get()}"
        )
        t.stop()
        synchronized(dump) { dump.close() }
        AppLog.i("transcode_test: выход энкодера записан в ${File(dir, "enc-out.h264")}")
        Thread.sleep(THREAD_CHECK_DELAY_MS)
        val leaked = Thread.getAllStackTraces().keys
            .filter { it.isAlive && (it.name.startsWith("aa-trans-") || it.name.startsWith("aa-gl-relay")) }
            .map { it.name }
        if (leaked.isEmpty()) AppLog.i("transcode_test: нити aa-trans-*/aa-gl-relay завершились")
        else AppLog.i("transcode_test: !!!!!! нити не завершились: ${leaked.joinToString(", ")}")
        ph?.let { AppLog.i("transcode_test: заглушка отрисовала ${it.framesRendered} кадров"); it.stop() }
    }
}
