package com.yegorkapomidorka.headswap.aa

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.Surface
import com.yegorkapomidorka.headswap.AnnexB
import com.yegorkapomidorka.headswap.AppLog
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * AA H.264 -> MediaCodec-decoder (Surface output) -> Surface input encoder AVC Baseline -> CarLife.
 * Zero copying via Surface. encoder lives from start() to stop() (all CarLife-session) and itself
 * repeats the last frame times in 100 ms on a static picture (KEY_REPEAT_PREVIOUS_FRAME_AFTER, as
 * MD Baidu: core/screen/video/Recorder.java:1364), so head unit is not left without frame. decoder
 * lazy on the first IDR AA and re-created with the new SPS or resetDecoder().
 *
 * feedFrame/feedConfig/resetDecoder do not block caller thread (thread AA under the bridge monitor): put in
 * [FeedQueue], and createDecoder, dequeueInputBuffer and queueInputBuffer performs thread aa-trans-feed.
 * The queue at [FeedQueue.DEFAULT_MAX_FRAMES] frame (~0,5 at 30 fps: while creating decoder, 80-300 ms, frame They are hoarding, not
 * lost); when overflowing frame lost until the next IDR, the bridge gets onNeedAaKeyFrame.
 * [out] is not named under lock. "nofeedthread" in tuning - old synchronous path on thread caller.
 */
class AaTranscoder(
    private val width: Int,
    private val height: Int,
    /** Initial target frequency: KEY_FRAME_RATE encoder and gate GL-rele; then changes via [setTargetFps]. */
    private val fps: Int,
    private val bitrate: Int,
    private val out: TranscodeOutput,
    private val log: (String) -> Unit = { AppLog.i(it, false) },
    /** Experiments: "vbr", "repeat=NNN" ( microseconds ), "norealtime", "nolowlat", "nobase", "nofeedthread". */
    private val tuning: String = "",
    /** Size frame AA at the input (tip decoder) and cut from it; default input = output, without crop. */
    private val srcWidth: Int = width,
    private val srcHeight: Int = height,
    private val crop: VideoCrop = VideoCrop.NONE,
    /** Match Baidu encoder settings (MediaFormatUtils :41-60): Baseline, level 256 (AVCLevel3), VBR, I-frame interval one second, repeated frame every 100 ms; the caller selects fps and bitrate. */
    private val baidu: Boolean = false,
    /** Virtual display -> GL -> one encoder. No decoder or compressed-input thread is created. */
    private val directSurface: Boolean = false,
    /** Validate the requested ceiling even when thermal protection starts the encoder at a lower rate. */
    private val capabilityFps: Int = fps,
) : VideoTranscoder {
    private var encoder: MediaCodec? = null
    private var surface: Surface? = null
    private var relay: GlRelay? = null
    private var lastRedraws = 0L
    private var lastGated = 0L
    @Volatile private var target = fps
    @Volatile private var curBitrate = bitrate
    private var bitrateMode = -1                       // mode with which encoder is configured (-1 = not specified/unknown)
    @Volatile private var bitrateFailed = false
    private var encThread: Thread? = null
    @Volatile private var running = false
    private var encoderNote = ""
    var encoderName = "?"
        private set

    private val lock = Any()
    private var decoder: Dec? = null
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var decoderCfg: ByteArray? = null          // SPS+PPS, with which the current decoder was created
    private var cfgStale = false
    private var needIdr = true

    // thread feeding: all work with decoder leaves with thread AA here
    private val threaded = !directSurface && !tuning.contains("nofeedthread")
    private val queue = FeedQueue()
    private var feedThread: Thread? = null

    /** frame processing result under lock; [out] call caller code after exiting lock. */
    private sealed class Outcome {
        object None : Outcome()
        object Skip : Outcome()                      // frame is missing, Colback is not needed
        object NeedIdr : Outcome()
        class Error(val msg: String) : Outcome()
    }

    // Statistics window
    private val inTimes = ConcurrentHashMap<Long, Long>()
    private val winIn = AtomicLong()
    private val winDecoded = AtomicLong()
    private val winOut = AtomicLong()
    private val winDrop = AtomicLong()
    private val winDecLatUs = AtomicLong()
    private val winEncLatUs = AtomicLong()
    private val winEncLatN = AtomicLong()
    private var winStartMs = SystemClock.elapsedRealtime()
    val totalIn = AtomicLong()
    val totalDecoded = AtomicLong()
    val totalOut = AtomicLong()

    @Synchronized
    fun start() {
        if (running) return
        require(!directSurface || (srcWidth == width && srcHeight == height && crop.isNone)) {
            "Direct desktop input must use native output geometry without crop"
        }
        val c = if (directSurface) MediaCodec.createByCodecName(
            DesktopEncoderSupport.encoderName(width, height, maxOf(fps, capabilityFps), bitrate))
            else MediaCodec.createEncoderByType(MIME)
        encoderName = c.name
        var configured = false
        var lastErr: Exception? = null
        // Baidu-format: first with level 256, when failing without it (with log entry), then ordinary ladder
        val levels = if (baidu) listOf(BAIDU_WITH_LEVEL, BAIDU_NO_LEVEL, 0, 1, 2) else listOf(0, 1, 2)
        for ((attempt, level) in levels.withIndex()) {
            try {
                if (attempt > 0) c.reset()
                c.configure(format(level), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                encoderNote = when (level) {
                    BAIDU_WITH_LEVEL -> "baidu: baseline+level256+VBR+repeat100ms"
                    BAIDU_NO_LEVEL -> "baidu: baseline+VBR+repeat100ms, без level"
                    0 -> "baseline+CBR+noB+repeat100ms+realtime"
                    1 -> "baseline+CBR+noB+repeat100ms"
                    else -> "минимальный формат+repeat100ms"
                }
                configured = true
                break
            } catch (e: Exception) {
                lastErr = e
                log("транскодер: configure уровня $level не прошёл: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        if (!configured) {
            try {
                c.release()
            } catch (_: Exception) {
            }
            throw lastErr ?: IllegalStateException("encoder configure")
        }
        var inSurface: Surface? = null
        try {
            inSurface = c.createInputSurface()
            c.start()
        } catch (e: Exception) {
            // codec and Surface are not yet in the fields: stop() will not see them, hardware encoder free here
            try {
                inSurface?.release()
            } catch (_: Exception) {
            }
            try {
                c.release()
            } catch (_: Exception) {
            }
            throw e
        }
        surface = inSurface
        encoder = c
        // bitrate: mode CBR/VBR take PARAMETER_KEY_VIDEO_BITRATE on the fly, CQ - no; at the level of 2 mode was not set
        bitrateMode = try {
            c.inputFormat.getInteger(MediaFormat.KEY_BITRATE_MODE)
        } catch (_: Exception) {
            -1
        }
        if (directSurface || !tuning.contains("nogl")) {
            // decoder -> GL-relay -> Surface encoder: relay redraws latest frame on static ( >= 10 fps)
            try {
                relay = GlRelay(surface!!, width, height, REDRAW_IDLE_MS, log, srcWidth, srcHeight, crop, target, limitHighFps = directSurface).also { it.start() }
            } catch (e: Exception) {
                if (directSurface) {
                    // Direct input depends on GL for pacing and idle redraw. Never silently
                    // switch to an unpaced surface or leave a configured codec allocated.
                    stop()
                    throw IllegalStateException("Desktop GL input could not be created", e)
                }
                log("транскодер: GL-реле не поднялось (${e.message}), декодер пишет прямо в энкодер")
                relay = null
            }
        }
        if (relay == null && !crop.isNone) log("транскодер: !!!!!! без GL-реле кроп невозможен, кадр AA будет сжат целиком")
        running = true
        encThread = Thread({ encodeLoop(c) }, "aa-trans-enc").also { it.start() }
        if (threaded) {
            queue.clear()
            feedThread = Thread({ feedLoop() }, "aa-trans-feed").also { it.start() }
        }
        log("транскодер: энкодер $encoderName ${width}x$height (вход ${if (directSurface) "Surface DeX, одно кодирование" else "AA"} ${srcWidth}x$srcHeight${if (crop.isNone) "" else ", кроп $crop"}) ${fps}fps ${bitrate / 1000}kbps ($encoderNote)")
    }

    override val surfaceInput: SurfaceVideoInput?
        @Synchronized get() {
            if (!directSurface || !running) return null
            val input = relay?.inputSurface ?: return null
            return object : SurfaceVideoInput { override val surface: Surface = input }
        }

    override val targetFps: Int get() = target

    /** Target frequency on the fly: only GL-rele gate, codec is not re-created (KEY_FRAME_RATE remains initial). */
    override fun setTargetFps(fps: Int) {
        if (directSurface && fps > maxOf(capabilityFps, target)) DesktopEncoderSupport.requireSupported(
            checkNotNull(encoder) { "Энкодер рабочего стола ещё не создан" }.codecInfo, width, height, fps, curBitrate)
        val old = target
        target = fps
        val r = relay
        if (r != null) r.setTargetFps(fps) else log("транскодер: без GL-реле частоту не ограничить, целевой fps $fps только в статистике")
        log("транскодер: целевой fps $old -> $fps")
    }

    override val currentBitrate: Int get() = curBitrate

    /** bitrate on the fly via setParameters (without recreating). CQ or codec exception: once in the log, no further try. */
    override fun setBitrate(bps: Int) {
        if (bitrateFailed || bps == curBitrate) return
        val c = encoder ?: return
        if (bitrateMode == MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ) {
            bitrateFailed = true
            log("транскодер: энкодер в BITRATE_MODE_CQ, битрейт на лету не меняется")
            return
        }
        try {
            c.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bps) })
            curBitrate = bps
        } catch (e: Exception) {
            bitrateFailed = true
            log("транскодер: setParameters(VIDEO_BITRATE=$bps) не прошёл, битрейт больше не меняю: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun format(level: Int): MediaFormat {
        val f = MediaFormat.createVideoFormat(MIME, width, height)
        f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        f.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        f.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
        f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        // as in Baidu (Recorder.java:1364, MediaFormatUtils.java:48): on the static screen frame repeats once in 100 ms
        val repeat = Regex("""repeat=(\d+)""").find(tuning)?.groupValues?.get(1)?.toLong() ?: REPEAT_AFTER_US
        f.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, repeat)
        if (level == BAIDU_WITH_LEVEL || level == BAIDU_NO_LEVEL) {
            f.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            f.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            if (level == BAIDU_WITH_LEVEL) f.setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel3)
            f.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            f.setInteger(MediaFormat.KEY_PRIORITY, 0)
            if (Build.VERSION.SDK_INT >= 30) f.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            return f
        }
        if (level <= 1 && !tuning.contains("nobase")) {
            f.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            // VBR: during the AA-logo-to-map transition, CBR c2.qti emitted P-frames up to 129 KB, about 4× the original AA size, or 850 KB in 0.3 seconds.
            // and head unit painted strips; VBR on the same stream holds peaks up to ~ 40 KB in the same image (transcode_test, 12.10.2026)
            f.setInteger(
                MediaFormat.KEY_BITRATE_MODE,
                if (tuning.contains("cbr")) MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR else MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
            )
            f.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
        }
        if (level == 0) {
            if (!tuning.contains("norealtime")) f.setInteger(MediaFormat.KEY_PRIORITY, 0)   // realtime
            if (Build.VERSION.SDK_INT >= 30 && !tuning.contains("nolowlat")) f.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        }
        return f
    }

    // --------------- AA input ------------

    /** Does not block: in the thread mode, the SPS/PPS parsing makes the thread feeding. */
    override fun feedConfig(annexB: ByteArray) {
        if (directSurface) return
        if (threaded) queue.post(FeedItem.Config(annexB)) else processConfig(annexB)
    }

    private fun processConfig(annexB: ByteArray) {
        val (s, p) = H264Stream.spsPps(annexB)
        if (s == null || p == null) return
        synchronized(lock) {
            val changed = sps?.contentEquals(s) != true || pps?.contentEquals(p) != true
            sps = s
            pps = p
            if (changed && decoder != null) cfgStale = true     // decoder will be re-released on the next IDR
        }
    }

    /**
     * Does not block (O(1)): in thread mode puts in line. Queue full -> frame is lost until IDR arrives,
     * bridge gets onNeedAaKeyFrame once per episode. Kolback is called on thread caller, not under lock.
     */
    override fun feedFrame(annexB: ByteArray, ptsUs: Long) {
        if (directSurface) return
        if (!running) return
        winIn.incrementAndGet()
        totalIn.incrementAndGet()
        val idr = 5 in AnnexB.nalTypes(annexB)
        val f = FeedItem.Frame(annexB, ptsUs, idr, SystemClock.elapsedRealtimeNanos())
        if (!threaded) {
            report(processFrame(f))
            return
        }
        when (queue.offerFrame(f)) {
            Offer.ACCEPTED -> {}
            Offer.DROPPED_FULL -> {
                winDrop.incrementAndGet()
                log("транскодер: очередь кормления полна (декодер не успевает), кадры до следующего IDR теряются")
                out.onNeedAaKeyFrame()
            }
            Offer.DROPPED_GATED -> winDrop.incrementAndGet()
        }
    }

    /** Kolbacks by processing result; only outside lock. */
    private fun report(o: Outcome) {
        when (o) {
            Outcome.NeedIdr -> out.onNeedAaKeyFrame()
            is Outcome.Error -> out.onTranscodeError(o.msg)
            Outcome.None, Outcome.Skip -> {}
        }
    }

    /** Feed decoder one frame (creates it on IDR). Under lock, [out] calls - the result returns. */
    private fun processFrame(f: FeedItem.Frame): Outcome = synchronized(lock) {
        val annexB = f.annexB
        val ptsUs = f.ptsUs
        val idr = f.idr
        if (decoder == null || (cfgStale && idr)) {
            if (!idr) {
                winDrop.incrementAndGet()
                return Outcome.Skip                  // decoder needs IDR to start
            }
            val created = createDecoder(annexB)
            if (created !== Outcome.None) return created
        }
        if (needIdr && !idr) {
            winDrop.incrementAndGet()
            return Outcome.Skip
        }
        val d = decoder ?: return Outcome.Skip
        val idx = try {
            d.codec.dequeueInputBuffer(15_000)
        } catch (e: Exception) {
            return Outcome.Error("декодер: dequeueInputBuffer: ${e.message}")
        }
        if (idx < 0) {
            // decoder fails: frame is lost, then P can not be - we are waiting for IDR from AA
            winDrop.incrementAndGet()
            needIdr = true
            return Outcome.NeedIdr
        }
        val buf = d.codec.getInputBuffer(idx)
        if (buf == null || buf.capacity() < annexB.size) {
            d.codec.queueInputBuffer(idx, 0, 0, ptsUs, 0)
            // frame is lost, as well as without an input buffer: P after it decoder will spoil - waiting for IDR from AA
            winDrop.incrementAndGet()
            needIdr = true
            return Outcome.NeedIdr
        }
        buf.clear()
        buf.put(annexB)
        inTimes[ptsUs] = f.atNs                      // from coming from AA: waiting in line feeding enters into delay decoder
        if (inTimes.size > 256) inTimes.keys.take(128).forEach { inTimes.remove(it) }
        try {
            d.codec.queueInputBuffer(idx, 0, annexB.size, ptsUs, 0)
            if (idr) needIdr = false
            Outcome.None
        } catch (e: Exception) {
            Outcome.Error("декодер: queueInputBuffer: ${e.message}")
        }
    }

    /** thread feeding: takes out the queue of frame and command, everything with decoder does here. */
    private fun feedLoop() {
        try {
            while (true) {
                val item = queue.take(500) ?: if (running) continue else break
                if (item is FeedItem.Stop) break
                if (!running) continue               // stop: decoder no longer touch, go on Stop or timeout
                try {
                    when (item) {
                        is FeedItem.Frame -> report(processFrame(item))
                        is FeedItem.Config -> processConfig(item.annexB)
                        FeedItem.Reset -> processReset()
                        FeedItem.Stop -> {}
                    }
                } catch (e: Exception) {
                    out.onTranscodeError("нить кормления: ${e.javaClass.simpleName}: ${e.message}")
                }
            }
        } finally {
            // any output (Stop, timeout after running=false, unprocessed exception): decoder releases the thread that owned it
            synchronized(lock) { releaseDecoderLocked() }
        }
    }

    /** Creates decoder by SPS/PPS (from CODEC_CONFIG or from IDR itself). Under lock. None = created. */
    private fun createDecoder(idrFrame: ByteArray): Outcome {
        releaseDecoderLocked()
        var s = sps
        var p = pps
        if (s == null || p == null) {
            val (s2, p2) = H264Stream.spsPps(idrFrame)
            s = s2
            p = p2
        }
        if (s == null || p == null) {
            log("транскодер: нет SPS/PPS для декодера, жду")
            return Outcome.Skip
        }
        val surf = relay?.inputSurface ?: surface ?: return Outcome.Skip
        return try {
            val f = MediaFormat.createVideoFormat(MIME, srcWidth, srcHeight)
            f.setByteBuffer("csd-0", ByteBuffer.wrap(s))
            f.setByteBuffer("csd-1", ByteBuffer.wrap(p))
            f.setInteger(MediaFormat.KEY_PRIORITY, 0)
            if (Build.VERSION.SDK_INT >= 30) f.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 512 * 1024)
            val c = MediaCodec.createDecoderByType(MIME)
            try {
                try {
                    c.configure(f, surf, null, 0)
                } catch (e: Exception) {
                    // without low-latency/priority (not all decoder accept)
                    c.reset()
                    val f2 = MediaFormat.createVideoFormat(MIME, srcWidth, srcHeight)
                    f2.setByteBuffer("csd-0", ByteBuffer.wrap(s))
                    f2.setByteBuffer("csd-1", ByteBuffer.wrap(p))
                    c.configure(f2, surf, null, 0)
                }
                c.start()
            } catch (e: Exception) {
                // decoder is not yet in the field decoder: releaseDecoderLocked() it will not free
                try {
                    c.release()
                } catch (_: Exception) {
                }
                throw e
            }
            val d = Dec(c)
            d.thread = Thread({ decodeLoop(d) }, "aa-trans-dec").also { it.start() }
            decoder = d
            decoderCfg = s + p
            cfgStale = false
            needIdr = true
            log("транскодер: декодер ${c.name} создан (SPS ${s.size} Б, PPS ${p.size} Б)")
            Outcome.None
        } catch (e: Exception) {
            Outcome.Error("декодер не создан: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private class Dec(val codec: MediaCodec) {
        @Volatile var alive = true
        var thread: Thread? = null
    }

    private fun decodeLoop(d: Dec) {
        val info = MediaCodec.BufferInfo()
        try {
            while (d.alive && running) {
                val idx = d.codec.dequeueOutputBuffer(info, 20_000)
                if (idx >= 0) {
                    val render = info.size > 0
                    val inAt = inTimes.remove(info.presentationTimeUs)
                    d.codec.releaseOutputBuffer(idx, render)
                    if (render) {
                        winDecoded.incrementAndGet()
                        totalDecoded.incrementAndGet()
                        if (inAt != null) winDecLatUs.addAndGet((SystemClock.elapsedRealtimeNanos() - inAt) / 1000)
                    }
                } else if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    log("транскодер: формат вывода декодера ${d.codec.outputFormat}")
                }
            }
        } catch (e: Exception) {
            if (d.alive && running) out.onTranscodeError("цикл декодера: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** Does not block: in thread mode, command queues after the already adopted frame, those up to reset and are processed. */
    override fun resetDecoder() {
        if (directSurface) return
        if (threaded) queue.post(FeedItem.Reset) else processReset()
    }

    private fun processReset() {
        synchronized(lock) {
            releaseDecoderLocked()
            relay?.clear()
            sps = null
            pps = null
            needIdr = true
        }
    }

    private fun releaseDecoderLocked() {
        val d = decoder ?: return
        decoder = null
        d.alive = false
        try {
            d.thread?.join(500)
        } catch (_: InterruptedException) {
        }
        try {
            d.codec.stop()
        } catch (_: Exception) {
        }
        try {
            d.codec.release()
        } catch (_: Exception) {
        }
        inTimes.clear()
    }

    // -------------- encoder output ------------

    private fun encodeLoop(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        try {
            while (running) {
                val idx = c.dequeueOutputBuffer(info, 50_000)
                if (idx >= 0) {
                    val buf = c.getOutputBuffer(idx)
                    if (buf != null && info.size > 0) {
                        val arr = ByteArray(info.size)
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        buf.get(arr)
                        val data = AnnexB.normalize(arr)
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            out.onEncoderConfig(data)
                        } else {
                            val lat = System.nanoTime() - info.presentationTimeUs * 1000
                            if (lat in 0..2_000_000_000L) {
                                winEncLatUs.addAndGet(lat / 1000)
                                winEncLatN.incrementAndGet()
                            }
                            winOut.incrementAndGet()
                            totalOut.incrementAndGet()
                            out.onEncoderFrame(data, info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0)
                        }
                    }
                    c.releaseOutputBuffer(idx, false)
                }
            }
        } catch (e: Exception) {
            if (running) out.onTranscodeError("цикл энкодера: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    override fun startIntro(banner: android.graphics.Bitmap) {
        relay?.startIntro(banner)
    }

    override fun requestSyncFrame() {
        try {
            encoder?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
        } catch (e: Exception) {
            log("транскодер: requestSyncFrame: ${e.message}")
        }
    }

    override fun statsLine(): String {
        val now = SystemClock.elapsedRealtime()
        val sec = maxOf((now - winStartMs) / 1000.0, 0.001)
        winStartMs = now
        val inN = winIn.getAndSet(0)
        val dec = winDecoded.getAndSet(0)
        val outN = winOut.getAndSet(0)
        val drop = winDrop.getAndSet(0)
        val decLat = winDecLatUs.getAndSet(0)
        val encLat = winEncLatUs.getAndSet(0)
        val encN = winEncLatN.getAndSet(0)
        val decMs = if (dec > 0) decLat / dec / 1000.0 else 0.0
        val encMs = if (encN > 0) encLat / encN / 1000.0 else 0.0
        val r = relay
        val redrawsNow = r?.redraws?.get() ?: 0L
        val repeats = if (r != null) redrawsNow - lastRedraws else maxOf(outN - dec, 0)
        lastRedraws = redrawsNow
        val gatedNow = r?.gated?.get() ?: 0L
        val gatedWin = gatedNow - lastGated
        lastGated = gatedNow
        val queueNote = if (threaded) ", очередь кормления макс ${queue.resetMaxPending()}" else ""
        if (directSurface) return "Surface: одно кодирование за %.1f с, выход энкодера %d (%.1f fps), повторов GL %d, задержано гейтом fps %d, потеряно %d; задержка энкодера %.1f мс"
            .format(sec, outN, outN / sec, repeats, gatedWin, drop, encMs)
        return ("транскод за %.1f с: вход AA %d, декодировано %d, выход энкодера %d (%.1f fps), повторов (перерисовок GL) %d, задержано гейтом fps %d, потеряно %d; задержка декодер %.1f мс + энкодер %.1f мс = %.1f мс"
            .format(sec, inN, dec, outN, outN / sec, repeats, gatedWin, drop, decMs, encMs, decMs + encMs)) + queueNote
    }

    @Synchronized
    override fun stop() {
        if (!running && encoder == null) return
        running = false
        var decoderReleased = false
        val ft = feedThread
        feedThread = null
        if (ft != null) {
            queue.post(FeedItem.Stop)
            if (ft !== Thread.currentThread()) {
                try {
                    ft.join(1500)
                } catch (_: InterruptedException) {
                }
            }
            decoderReleased = !ft.isAlive
            if (ft.isAlive) log("транскодер: !!!!!! нить кормления не завершилась за 1,5 с, освобождаю декодер сама")
        }
        if (!decoderReleased) synchronized(lock) { releaseDecoderLocked() }
        try {
            relay?.stop()
        } catch (_: Exception) {
        }
        relay = null
        try {
            encThread?.join(1000)
        } catch (_: InterruptedException) {
        }
        try {
            encoder?.stop()
        } catch (_: Exception) {
        }
        try {
            encoder?.release()
        } catch (_: Exception) {
        }
        try {
            surface?.release()
        } catch (_: Exception) {
        }
        encoder = null
        surface = null
    }

    companion object {
        const val MIME = "video/avc"
        const val REPEAT_AFTER_US = 100_000L

        /** configure attempts in Baidu- format: with and without KEY_LEVEL=AVCLevel3 (256). */
        private const val BAIDU_WITH_LEVEL = -2
        private const val BAIDU_NO_LEVEL = -1

        /** Relay redraws the last frame if there are no new ones as many ms (like repeat-previous-frame-after in Baidu). */
        const val REDRAW_IDLE_MS = 100L
    }
}
