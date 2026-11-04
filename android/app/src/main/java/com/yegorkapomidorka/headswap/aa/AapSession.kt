package com.yegorkapomidorka.headswap.aa

import com.yegorkapomidorka.headswap.PbReader
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import com.yegorkapomidorka.headswap.aa.AapFraming as F
import com.yegorkapomidorka.headswap.aa.AapMessages as M

/** Where does the video from AA: fall clean Annex-B without header AAP. */
interface AaVideoSink {
    /** CODEC_CONFIG: SPS+PPS. */
    fun onCodecConfig(annexB: ByteArray)

    /** DATA: one frame (8-byte timestamp AAP removed), ptsUs of this timestamp. */
    fun onFrame(annexB: ByteArray, ptsUs: Long)

    /** The phone asks for VIDEO_FOCUS NATIVE (the "Get Out" plate in AA). The answer to AA sends caller later (AaPeer.sendVideoFocusNative). */
    fun onAaNativeFocusRequest() {}
}

class AapOptions(
    val host: String = "127.0.0.1",
    val port: Int = 5277,
    /** The guard of silence is only until the first VIDEO MEDIA_START; after it, the session can be silent (static screen, no Ping). */
    val preVideoTimeoutMs: Long = 30_000,
    val maxUnackedVideo: Int = 12,         // AapControl.kt maxUnackedFor(): 12 for socket
    val maxUnackedAudio: Int = 30,
    val sd: AapSdOptions = AapSdOptions(),
    /** If set, SDR is built at the time of the phone's request (bridge: sound depends on FEATURE_CONFIG head unit). */
    val sdProvider: (() -> AapSdOptions)? = null,
    val tls12Only: Boolean = false,
    /** false = on VIDEO MEDIA_SETUP we answer with the focus of NATIVE: video is not going (AaSessionProbe server test). */
    val videoFocus: Boolean = true,
)

class AapStats {
    @Volatile var rxFrames = 0L
    @Volatile var txFrames = 0L
    @Volatile var videoCodecConfig = 0L
    @Volatile var videoData = 0L
    @Volatile var videoBytes = 0L
    @Volatile var audioData = 0L
}

/**
 * Single session Android Auto head unit: TCP-client to server head unit in AA (127.0.0.1:5277). Port
 * pc-headunit/aap/session.py: Version -> TLS in plain-frame -> AuthComplete -> encrypted exchange ->
 * ByeBye/shutdownOutput/read to EOF/close (5277 server is disposable, dirty break it jams).
 *
 * Its thread; sendTouch/requestStop can be called from any. log(line, ui): ui=false for video stream-frame.
 */
/** What the bridge requires from the AA-session. */
/** Finger AA-touch: pointer_id and coordinates frame AA. */
data class TouchPoint(val id: Int, val x: Int, val y: Int)

interface AaPeer {
    /** touch with all active fingers; action_index - position in [points] finger causing POINTER_DOWN/UP. */
    fun sendTouch(action: Int, actionIndex: Int, points: List<TouchPoint>)

    /** One finger with id 0. */
    fun sendTouch(action: Int, x: Int, y: Int) = sendTouch(action, 0, listOf(TouchPoint(0, x, y)))

    fun requestKeyFrame()

    /** Pressing the button (Android KeyEvent keycode): down, then up. is ignored by default. */
    fun sendKey(keycode: Int) {}

    /** Rotate the washer by delta clicks (> 0 clockwise). Default ignored. */
    fun sendRotary(delta: Int) {}

    /** PCM16 mono 16 kHz microphone -> phone while open microphone session AA. Default ignored. */
    fun sendMicPcm(pcm: ByteArray, capturedAtUs: Long) {}

    /** VideoFocusNotification NATIVE (unsolicited = false): head unit is gone from the projection. Default ignored. */
    fun sendVideoFocusNative() {}

    /** Night mode AA (NIGHT sensor): Remembered and left as soon as the phone started the sensor. By default ignored. */
    fun sendNightMode(night: Boolean) {}
}

class AapSession(
    private val creds: AapCredentials,
    private val sink: AaVideoSink,
    private val opts: AapOptions = AapOptions(),
    private val log: (String, Boolean) -> Unit = { _, _ -> },
    private val socketFactory: (() -> Socket)? = null,
    private val onEstablished: () -> Unit = {},
    private val onFirstVideo: () -> Unit = {},
    private val onEnd: (reason: String, ok: Boolean) -> Unit = { _, _ -> },
) : AaPeer {
    val stats = AapStats()

    @Volatile private var stopReason: String? = null
    @Volatile private var userStop = false
    @Volatile var exitOk = false
        private set
    @Volatile var established = false
        private set

    /** TCP to server head unit installed (false at the end of session = connection refused / not available). */
    @Volatile var connected = false
        private set

    /** The phone opened a video channel (VIDEO MEDIA_START). */
    @Volatile var videoStarted = false
        private set
    private var establishedAtNs = 0L

    private val audioSink: AaAudioSink? = sink as? AaAudioSink
    private val micSink: AaMicSink? = sink as? AaMicSink
    private val metaSink: AaMetaSink? = sink as? AaMetaSink
    private val uplink = AaMicUplink({ sid, ts, pcm ->
        outQueue.offer(MsgJob(M.micData(ts, pcm), true, "mic sid=$sid ts=${ts}us"))
    })
    private var micSeq = 0
    @Volatile private var micSid = 0
    private var thread: Thread? = null
    private lateinit var sock: Socket
    private lateinit var tls: AapTls
    private val parser = F.Parser()
    private val reasm = F.Reassembler { log("AA reassembly: $it", true) }
    private val frames = ArrayDeque<F.Frame>()
    private val sessionIds = HashMap<Int, Int>()
    private val outQueue = LinkedBlockingQueue<Job>()
    private var writer: Thread? = null
    @Volatile private var writerActive = false
    @Volatile private var fatal = false
    private val clockBaseNs = System.nanoTime()
    private val readBuf = ByteArray(65536)

    @Volatile private var peerClosed = false
    private var byeSent = false
    private var byeAcked = false
    private var lastRx = System.nanoTime()
    private val firstVideo = AtomicLong(0)

    fun start() {
        val t = Thread({ run() }, "aa-session")
        thread = t
        t.start()
    }

    fun join(timeoutMs: Long) {
        thread?.join(timeoutMs)
    }

    /** Correct completion: thread itself will send ByeBye and close socket. */
    fun requestStop(reason: String) {
        userStop = true
        stop(reason)
    }

    private fun stop(reason: String) {
        if (stopReason == null) stopReason = reason
    }

    /** action 0 DOWN, 1 UP, 2 MOVE, 5 POINTER_DOWN, 6 POINTER_UP; coordinates in pixels frame AA. Ignored until session ready. */
    override fun sendTouch(action: Int, actionIndex: Int, points: List<TouchPoint>) {
        if (!established || stopReason != null) {
            log("AA touch($action,$actionIndex,$points) пропущен: сессия ${if (established) "завершается" else "ещё не готова"}", true)
            return
        }
        try {
            // uint64 ns, strictly > 0 and monotonous; System.nanoTime() itself can be negative/arbitrary
            val ts = System.nanoTime() - clockBaseNs + 1_000_000_000L
            outQueue.offer(MsgJob(M.touchEvent(action, actionIndex, points, ts), false, "touch ts=${ts}ns"))
        } catch (e: Throwable) {
            log("AA ошибка постановки touch в очередь: ${e.javaClass.simpleName}: ${e.message}", true)
        }
    }

    /** Pressing the button: down, then up (Android KeyEvent keycode). Ignored until session ready. */
    override fun sendKey(keycode: Int) {
        if (!established || stopReason != null) {
            log("AA key($keycode) пропущен: сессия ${if (established) "завершается" else "ещё не готова"}", true)
            return
        }
        try {
            val ts = System.nanoTime() - clockBaseNs + 1_000_000_000L
            outQueue.offer(MsgJob(M.keyEvent(ts, keycode, true), false, "key down $keycode ts=${ts}ns"))
            outQueue.offer(MsgJob(M.keyEvent(ts + 1_000_000L, keycode, false), false, "key up $keycode ts=${ts + 1_000_000L}ns"))
        } catch (e: Throwable) {
            log("AA ошибка постановки key в очередь: ${e.javaClass.simpleName}: ${e.message}", true)
        }
    }

    /** Puck rotation: relative_event KEYCODE_ROTARY_CONTROLLER. Ignored until session ready. */
    override fun sendRotary(delta: Int) {
        if (!established || stopReason != null) {
            log("AA rotary($delta) пропущен: сессия ${if (established) "завершается" else "ещё не готова"}", true)
            return
        }
        try {
            val ts = System.nanoTime() - clockBaseNs + 1_000_000_000L
            outQueue.offer(MsgJob(M.rotaryEvent(ts, delta), false, "rotary $delta ts=${ts}ns"))
        } catch (e: Throwable) {
            log("AA ошибка постановки rotary в очередь: ${e.javaClass.simpleName}: ${e.message}", true)
        }
    }

    /**
     * Asks for phone key frame cycle VideoFocus NATIVE -> (pause) -> PROJECTED unsolicited
     * (headunit-revived AapTransport.sendKeyframeCycleRelease + focusCycleGainRunnable).
     */
    override fun requestKeyFrame() {
        if (!established || !videoStarted || stopReason != null || !writerActive) {
            log("AA keyframe-цикл пропущен: сессия ${if (!established) "не готова" else if (!videoStarted) "без видео" else "завершается"}", false)
            return
        }
        outQueue.offer(MsgJob(M.videoFocusNotification(projected = false, unsolicited = false), false, "keyframe cycle: NATIVE"))
        val t = Thread({
            try {
                Thread.sleep(KEYFRAME_CYCLE_GAP_MS)
            } catch (_: InterruptedException) {
                return@Thread
            }
            if (stopReason == null && writerActive) {
                outQueue.offer(MsgJob(M.videoFocusNotification(projected = true, unsolicited = true), false, "keyframe cycle: PROJECTED"))
            }
        }, "aa-keyframe")
        t.isDaemon = true
        t.start()
    }

    /** VideoFocus NATIVE with one message (no return in PROJECTED): head unit left CarLife after "Exit". */
    override fun sendVideoFocusNative() {
        if (!established || stopReason != null || !writerActive) {
            log("AA VideoFocus NATIVE пропущен: сессия ${if (!established) "не готова" else "завершается"}", true)
            return
        }
        log("AA VideoFocus NATIVE (ГУ вышел из CarLife)", true)
        outQueue.offer(MsgJob(M.videoFocusNotification(projected = false, unsolicited = false), false, "exit: NATIVE"))
    }

    @Volatile private var nightMode = false
    @Volatile private var nightSensorStarted = false

    override fun sendNightMode(night: Boolean) {
        val changed = night != nightMode
        nightMode = night
        if (!nightSensorStarted) {
            if (changed) log("AA night=$night запомнила, сенсор NIGHT ещё не запущен телефоном", true)
            return
        }
        if (!established || stopReason != null || !writerActive) {
            log("AA night=$night пропущен: сессия ${if (!established) "не готова" else "завершается"}", true)
            return
        }
        log("AA SENSOR night=$night (тема ГУ)", true)
        outQueue.offer(MsgJob(M.nightModeEvent(night), false, "night=$night"))
    }

    /** PCM microphone: cuts into frame by 128 ms and exits the maxUnacked window; without an open microphone session, it is ignored. */
    override fun sendMicPcm(pcm: ByteArray, capturedAtUs: Long) {
        if (stopReason != null || !writerActive) return
        if (uplink.feed(pcm, capturedAtUs) == AaMicUplink.Feed.OVERFLOW) micAbort("телефон не подтверждает кадры микрофона (очередь переполнена)")
    }

    /** Close microphone session on the initiative of HU: phone leaves an error, the source of sound - close. */
    private fun micAbort(why: String) {
        val sum = uplink.close() ?: return
        log("AA MIC прервана: $why; $sum", true)
        if (writerActive) outQueue.offer(MsgJob(M.micResponse(false, sum.sessionId), false, "mic abort"))
        micSink?.onMicClose(why)
    }

    private fun onMicRequest(payload: ByteArray) {
        val open = (firstVarint(payload, 1) ?: 0L) != 0L
        val window = (firstVarint(payload, 4) ?: 2L).toInt()
        val ms = micSink
        if (open) {
            if (ms == null) {
                send(M.micResponse(success = false))                    // No capture: opening rejected
                return
            }
            if (uplink.isOpen) {                                         // repeat Open: same session
                send(M.micResponse(success = true, sessionId = micSid))
                return
            }
            if (window !in 1..4096) {
                log("AA MIC open: maxUnacked=$window вне 1..4096, отказ", true)
                send(M.micResponse(success = false))
                return
            }
            val sid = micSeq + 1
            uplink.open(sid, window)
            val accepted = try {
                ms.onMicOpen()
            } catch (e: Throwable) {
                log("AA MIC open: источник звука упал: ${e.javaClass.simpleName}: ${e.message}", true)
                false
            }
            if (!accepted) {
                uplink.close()
                log("AA MIC open отклонён (нет источника звука)", true)
                send(M.micResponse(success = false))                    // as before: failure with session_id 0
                return
            }
            micSeq = sid
            micSid = sid
            log("AA MIC open принят: session=$sid, окно maxUnacked=$window, кадры по ${AaMicFormat.CHUNK_BYTES} Б", true)
            send(M.micResponse(success = true, sessionId = sid))
            uplink.activate()                                            // DATA only after reply in line
        } else {
            val sid = micSid
            val sum = uplink.close()
            if (sum != null) {
                log("AA MIC close: $sum", true)
                ms?.onMicClose("AA закрыла микрофон")
            }
            send(M.micResponse(success = true, sessionId = sid))
        }
    }

    private fun onMicAck(payload: ByteArray) {
        val f = PbReader.parse(payload)
        val sid = f?.let { PbReader.int(it, 1) } ?: 0
        val stamps = f?.count { it.number == 3 } ?: 0
        val count = if (stamps > 0) stamps else (f?.let { PbReader.int(it, 2) } ?: 0)
        if (!uplink.ack(sid, count)) log("AA MIC ack проигнорирован: session=$sid count=$count", false)
    }

    // ---------------------------------------------------------------------------------------------
    private class SessionError(msg: String) : Exception(msg)
    private class PeerClosed(msg: String) : Exception(msg)

    private fun run() {
        try {
            try {
                sock = (socketFactory ?: {
                    Socket().also { it.connect(InetSocketAddress(opts.host, opts.port), 5000) }
                }).invoke()
            } catch (e: IOException) {
                // server head unit does not listen: quiet output without ByeBye and the results, the bridge will repeat itself
                log("AA сервер ${opts.host}:${opts.port} недоступен: ${e.javaClass.simpleName}: ${e.message}", false)
                stopReason = "$UNAVAILABLE_REASON${e.javaClass.simpleName}"
                return
            }
            connected = true
            sock.tcpNoDelay = true
            log("AA TCP подключено ${opts.host}:${opts.port}", true)
            tls = AapTls(creds, serverMode = false, tls12Only = opts.tls12Only)
            versionExchange()
            tlsHandshake()
            sendAuthComplete()
            startWriter()
            established = true
            establishedAtNs = System.nanoTime()
            lastRx = System.nanoTime()
            log("AA сессия установлена (${tls.describe()}), ждём телефон", true)
            onEstablished()
            serve()
        } catch (e: PeerClosed) {
            peerClosed = true
            if (stopReason == null) stopReason = "peer closed: ${e.message}"
            log("AA ${e.message}", true)
        } catch (e: SessionError) {
            if (stopReason == null) stopReason = e.message
            log("AA ${e.message}", true)
        } catch (e: javax.net.ssl.SSLException) {
            if (stopReason == null) stopReason = "TLS: ${e.message}"
            log("AA TLS ошибка: ${e.javaClass.simpleName}: ${e.message}", true)
        } catch (e: IOException) {
            peerClosed = true
            if (stopReason == null) stopReason = "ошибка сокета: ${e.javaClass.simpleName}: ${e.message}"
            log("AA ошибка сокета: ${e.javaClass.simpleName}: ${e.message}", true)
        } catch (e: Throwable) {
            if (stopReason == null) stopReason = "${e.javaClass.simpleName}: ${e.message}"
            log("AA сбой: ${e.javaClass.simpleName}: ${e.message}", true)
        } finally {
            if (!connected) {
                onEnd(stopReason ?: "не подключено", false)
                return
            }
            if (uplink.close() != null) micSink?.onMicClose("сессия AA закрыта")
            try {
                shutdown()
                if (userStop && byeAcked) exitOk = true
            } catch (e: Throwable) {
                log("AA сбой в shutdown: ${e.javaClass.simpleName}: ${e.message}", true)
            }
            val s = stats
            log(
                "AA итог: кадров rx=${s.rxFrames} tx=${s.txFrames}; видео config=${s.videoCodecConfig} data=${s.videoData} " +
                    "байт=${s.videoBytes}; аудио data=${s.audioData}; причина: $stopReason", true
            )
            onEnd(stopReason ?: "конец", exitOk)
        }
    }

    // --- socket / frame ---
    private fun sendRaw(data: ByteArray) {
        stats.txFrames++
        sock.getOutputStream().write(data)
        sock.getOutputStream().flush()
    }

    private fun readMore(timeoutMs: Int) {
        sock.soTimeout = maxOf(timeoutMs, 1)
        val n = try {
            sock.getInputStream().read(readBuf)
        } catch (_: SocketTimeoutException) {
            return
        } catch (e: java.net.SocketException) {
            throw PeerClosed("соединение сброшено телефоном (${e.message})")
        }
        if (n < 0) throw PeerClosed("телефон закрыл соединение (EOF)")
        lastRx = System.nanoTime()
        for (fr in parser.feed(readBuf, 0, n)) {
            stats.rxFrames++
            frames.add(fr)
        }
    }

    private fun nextFrame(deadlineNs: Long): F.Frame? {
        while (frames.isEmpty()) {
            val leftMs = (deadlineNs - System.nanoTime()) / 1_000_000
            if (leftMs <= 0) return null
            readMore(minOf(leftMs, POLL_MS.toLong()).toInt())
            stopReason?.let { throw SessionError("прервано во время handshake: $it") }
        }
        return frames.removeFirst()
    }

    // --- outgoing queue ---
    // After handshake ALL outgoing frame (messages, touch, answers of TLS-layer) go through one queue and
    // one thread: wrap and write is strictly atomic, otherwise the recording of TLS with the number N can go after N+1
    // (or not leave at all), and the phone rips TLS. to handshake writes the thread session itself directly.
    private sealed interface Job
    private class MsgJob(val out: AapOut, val media: Boolean, val note: String) : Job
    private object TlsFlushJob : Job
    private class MarkerJob(val latch: CountDownLatch) : Job
    private object PoisonJob : Job

    private fun startWriter() {
        writerActive = true
        val t = Thread({ writerLoop() }, "aa-writer")
        t.isDaemon = true
        writer = t
        t.start()
    }

    private fun writerLoop() {
        while (true) {
            val job = outQueue.take()
            try {
                when (job) {
                    is PoisonJob -> return
                    is MarkerJob -> job.latch.countDown()
                    is TlsFlushJob -> {
                        val data = tls.drainOutgoing()
                        if (data.isNotEmpty()) {
                            log("AA TX CONTROL(0) ENCAPSULATED_SSL len=${data.size} plain frame", false)
                            sendRaw(F.buildPlain(F.CHANNEL_CONTROL, M.ENCAPSULATED_SSL, data))
                        }
                    }
                    is MsgJob -> {
                        logMsg("TX", job.out.chan, job.out.type, job.out.payload, job.media, job.note)
                        val plaintext = F.typed(job.out.type, job.out.payload)
                        for (fr in F.splitMessage(plaintext, job.out.chan, job.out.type)) {
                            val record = tls.encrypt(fr.chunk)
                            sendRaw(F.buildFrame(job.out.chan, fr.flags, record, fr.total))
                        }
                    }
                }
            } catch (e: Throwable) {
                // wrap could pass, but the record is not: stream TLS desynchronized, you can not continue
                fatal = true
                peerClosed = true
                if (stopReason == null) stopReason = "сбой исходящей записи: ${e.javaClass.simpleName}: ${e.message}"
                log("AA СБОЙ ЗАПИСИ, TLS-поток рассинхронизирован, закрываю сессию: ${e.javaClass.simpleName}: ${e.message}", true)
                outQueue.clear()
                (job as? MarkerJob)?.latch?.countDown()
                writerActive = false
                return
            }
        }
    }

    /** Puts the message in the outgoing queue (never writes to socket from caller thread). */
    private fun send(out: AapOut, media: Boolean = false, note: String = "") {
        if (!writerActive) {
            if (fatal) return                       // writer has died: session is already closed
            throw IllegalStateException("writer не запущен")
        }
        outQueue.offer(MsgJob(out, media, note))
    }

    private fun flushTls() {
        if (tls.wantsWrap()) outQueue.offer(TlsFlushJob)
    }

    /** Wait until writer sends everything accumulated (or dies). */
    private fun flushWriter(timeoutMs: Long) {
        if (!writerActive) return
        val latch = CountDownLatch(1)
        outQueue.offer(MarkerJob(latch))
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    private fun stopWriter() {
        val t = writer ?: return
        if (writerActive) outQueue.offer(PoisonJob)
        t.join(3000)
        writerActive = false
    }

    private fun sendSsl(data: ByteArray) {
        if (data.isEmpty()) return
        log("AA TX CONTROL(0) ENCAPSULATED_SSL len=${data.size} plain frame", false)
        sendRaw(F.buildPlain(F.CHANNEL_CONTROL, M.ENCAPSULATED_SSL, data))
    }

    private fun logMsg(dir: String, chan: Int, type: Int, payload: ByteArray, media: Boolean, note: String = "", text: String? = null) {
        val body = text ?: M.describe(chan, type, payload)
        log(
            "AA $dir ${M.channelName(chan)}($chan) ${M.messageName(chan, type)} 0x%04x len=${payload.size}".format(type) +
                (if (body.isNotEmpty()) " | $body" else "") + (if (note.isNotEmpty()) "  [$note]" else ""),
            !media
        )
    }

    // --- handshake ---
    private fun versionExchange() {
        val body = M.versionRequestBody()
        val frame = F.buildFrame(F.CHANNEL_CONTROL, F.FLAGS_PLAIN_BULK, body)
        log("AA TX CONTROL(0) VERSION_REQUEST: ${frame.joinToString(" ") { "%02x".format(it) }}", true)
        sendRaw(frame)
        val deadline = System.nanoTime() + VERSION_TIMEOUT_MS * 1_000_000
        while (true) {
            val fr = nextFrame(deadline) ?: break
            if (fr.chan == 0 && !fr.encrypted && fr.body.size >= 2 && u16(fr.body, 0) == M.VERSION_RESPONSE) {
                val v = M.parseVersionResponse(fr.body.copyOfRange(2, fr.body.size))
                    ?: throw SessionError("VERSION_RESPONSE слишком короткий")
                val extra = if (v.options.isNotEmpty()) " options=" + v.options.joinToString(" ") { "%02x".format(it) } else ""
                log("AA RX CONTROL(0) VERSION_RESPONSE | телефон выбрал ${v.major}.${v.minor} (просили ${M.VERSION_MAJOR}.${M.VERSION_MINOR}) status=${v.status}$extra", true)
                if (v.status != 0) throw SessionError("VERSION_RESPONSE status ${v.status}, телефон отказал протоколу ${v.major}.${v.minor}")
                return
            }
            log("AA игнорирую кадр до version response: ch=${fr.chan} flags=0x%02x".format(fr.flags), true)
        }
        throw SessionError(
            "$NO_VERSION_RESPONSE_REASON. Если соединение открыто, но тишина: сервер ГУ завис на прошлой сессии, " +
                "перезапусти его в настройках разработчика Android Auto (или force-stop AA)."
        )
    }

    private fun tlsHandshake() {
        val deadline = System.nanoTime() + TLS_TIMEOUT_MS * 1_000_000
        sendSsl(tls.handshakeStep())
        while (!tls.handshakeDone) {
            val fr = nextFrame(deadline) ?: throw SessionError("TLS handshake: таймаут")
            if (fr.encrypted || fr.body.size < 2) {
                log("AA неожиданный кадр во время TLS handshake: ch=${fr.chan} flags=0x%02x".format(fr.flags), true)
                continue
            }
            val type = u16(fr.body, 0)
            when (type) {
                M.ENCAPSULATED_SSL -> {
                    log("AA RX CONTROL(0) ENCAPSULATED_SSL len=${fr.body.size - 2} plain frame", false)
                    val out = try {
                        tls.handshakeStep(fr.body.copyOfRange(2, fr.body.size))
                    } catch (e: javax.net.ssl.SSLException) {
                        throw SessionError("TLS handshake не прошёл: ${e.javaClass.simpleName}: ${e.message}")
                    }
                    sendSsl(out)
                }
                M.VERSION_RESPONSE -> log("AA отброшен запоздавший VERSION_RESPONSE", true)
                else -> log("AA неожиданное plain-сообщение во время TLS handshake: type=0x%04x".format(type), true)
            }
        }
    }

    private fun sendAuthComplete() {
        log("AA TX CONTROL(0) AUTH_COMPLETE | AuthResponse{status: 0}", true)
        sendRaw(F.buildPlain(F.CHANNEL_CONTROL, M.AUTH_COMPLETE, M.AUTH_COMPLETE_DATA))
    }

    // --- main cycle ---
    private fun serve() {
        while (stopReason == null) {
            readMore(POLL_MS)
            while (frames.isNotEmpty() && stopReason == null) onFrame(frames.removeFirst())
            if (stopReason == null && !videoStarted && (System.nanoTime() - establishedAtNs) / 1_000_000 > opts.preVideoTimeoutMs) {
                stop("видео не началось за ${opts.preVideoTimeoutMs / 1000} с после AuthComplete")
            }
        }
    }

    private fun onFrame(fr: F.Frame) {
        if (!fr.encrypted) {
            onPlainFrame(fr)
            return
        }
        val d = try {
            tls.decrypt(fr.body, drain = false)
        } catch (e: javax.net.ssl.SSLException) {
            throw SessionError("TLS-ошибка от телефона: ${e.javaClass.simpleName}: ${e.message} (ch${fr.chan} flags=0x%02x)".format(fr.flags))
        }
        flushTls()
        if (tls.closed) throw PeerClosed("телефон прислал TLS close_notify")
        if (d.plaintext.isEmpty()) {
            log("AA RX ch${fr.chan} TLS-запись без данных приложения flags=0x%02x".format(fr.flags), false)
            return
        }
        val whole = reasm.push(fr.chan, fr.flags, d.plaintext, fr.total) ?: return
        if (whole.size < 2) {
            log("AA ch${fr.chan}: сообщение короче поля type", true)
            return
        }
        dispatch(fr.chan, u16(whole, 0), whole.copyOfRange(2, whole.size))
    }

    private fun onPlainFrame(fr: F.Frame) {
        if (fr.body.size < 2) {
            log("AA plain-кадр без type: ch=${fr.chan} flags=0x%02x".format(fr.flags), true)
            return
        }
        val type = u16(fr.body, 0)
        if (type == M.ENCAPSULATED_SSL) {
            // Post-handshake records TLS (tickets TLS 1.3) can come plain-frame; they are required to pass
            // through TLS-layer: he counts the records, the pass would break the decryption of all the following.
            tls.decrypt(fr.body.copyOfRange(2, fr.body.size), drain = false)
            log("AA RX CONTROL(0) ENCAPSULATED_SSL len=${fr.body.size - 2} post-handshake TLS (plain frame)", false)
            flushTls()
            return
        }
        log("AA RX ch${fr.chan} type=0x%04x неожиданный plain-кадр после handshake flags=0x%02x".format(type, fr.flags), true)
    }

    private fun dispatch(chan: Int, type: Int, payload: ByteArray) {
        val isMediaData = (chan == M.CH_VIDEO || chan in M.AUDIO_CHANNELS) && (type == M.MEDIA_DATA || type == M.MEDIA_CODEC_CONFIG)
        if (isMediaData) {
            onMediaData(chan, type, payload)
            return
        }
        // ack microphone goes 8 once per second, player status and navigation distance - once per second: in a file, not in UI
        logMsg("RX", chan, type, payload, media = (chan == M.CH_MIC && type == M.MEDIA_ACK) || M.isPeriodicMeta(chan, type))

        if (type == M.CHANNEL_OPEN_REQUEST) {
            send(M.channelOpenResponse(chan))
            if (chan == M.CH_SENSOR) send(M.drivingStatusEvent(0))          // UNRESTRICTED
            return
        }
        when {
            chan == M.CH_CONTROL -> onControl(type, payload)
            chan == M.CH_SENSOR -> onSensor(type, payload)
            chan == M.CH_INPUT -> if (type == M.INPUT_BINDING_REQUEST) send(M.bindingResponse(chan))
            chan == M.CH_VIDEO || chan in M.AUDIO_CHANNELS || chan == M.CH_MIC -> onMediaControl(chan, type, payload)
            // MEDIA_PLAYBACK/NAVIGATION: phone only sends status, no reply; metadata -> bridge
            chan == M.CH_MEDIA_PLAYBACK || chan == M.CH_NAVIGATION -> AaMeta.dispatch(chan, type, payload, metaSink)?.let {
                log("AA META ${M.channelName(chan)} ${M.messageName(chan, type)}: $it", !M.isPeriodicMeta(chan, type))
            }
        }
    }

    private fun firstVarint(payload: ByteArray, field: Int): Long? =
        PbReader.parse(payload)?.firstOrNull { it.number == field && it.wire == 0 }?.num

    private fun onControl(type: Int, payload: ByteArray) {
        when (type) {
            M.SERVICE_DISCOVERY_REQUEST -> send(M.serviceDiscoveryResponse(opts.sdProvider?.invoke() ?: opts.sd))
            M.PING_REQUEST -> send(M.pingResponse(firstVarint(payload, 1) ?: 0L))
            M.NAV_FOCUS_REQUEST -> send(M.navFocusNotification())
            M.AUDIO_FOCUS_REQUEST -> M.audioFocusResponse((firstVarint(payload, 1) ?: 0L).toInt())?.let { send(it) }
            M.BYEBYE_REQUEST -> {
                send(M.byebyeResponse())
                Thread.sleep(500)                                  // AapControl.kt:378
                byeAcked = true
                stop("телефон прислал ByeByeRequest")
                exitOk = true
            }
            M.BYEBYE_RESPONSE -> {
                byeAcked = true
                stop("получен ByeByeResponse")
                exitOk = true
            }
            M.VOICE_SESSION_NOTIFICATION -> audioSink?.onVoiceSession((firstVarint(payload, 1) ?: 0L).toInt())
            // CHANNEL_CLOSE_NOTIFICATION and more: log only
        }
    }

    private fun onSensor(type: Int, payload: ByteArray) {
        if (type != M.SENSOR_START_REQUEST) return
        val sensorType = firstVarint(payload, 1)?.toInt()
        send(M.sensorStartResponse())
        if (sensorType == 10) {                                            // NIGHT
            nightSensorStarted = true                                      // up to send: change of theme at this point will follow
            send(M.nightModeEvent(nightMode))
            log("AA SENSOR NIGHT запущен, night=$nightMode", true)
        }
    }

    private fun onMediaControl(chan: Int, type: Int, payload: ByteArray) {
        when {
            type == M.MEDIA_SETUP -> {
                val unacked = if (chan == M.CH_VIDEO) opts.maxUnackedVideo else opts.maxUnackedAudio
                send(M.mediaConfig(chan, unacked))
                if (chan == M.CH_VIDEO) send(M.videoFocusNotification(projected = opts.videoFocus, unsolicited = true))
                else if (chan in M.AUDIO_CHANNELS) send(M.audioFocusGainUnsolicited())
            }
            type == M.MEDIA_START -> {
                sessionIds[chan] = (firstVarint(payload, 1) ?: 0L).toInt()
                if (chan == M.CH_VIDEO) videoStarted = true
                if (chan in M.AUDIO_CHANNELS) audioSink?.onAudioStart(chan)
            }
            type == M.MEDIA_STOP && chan in M.AUDIO_CHANNELS -> audioSink?.onAudioStop(chan)
            type == M.MEDIA_VIDEO_FOCUS_REQUEST && chan == M.CH_VIDEO -> {
                val mode = PbReader.parse(payload)?.firstOrNull { it.number == 2 && it.wire == 0 }?.num?.toInt()
                if (mode == 1) send(M.videoFocusNotification(projected = opts.videoFocus, unsolicited = false))
                else if (mode == 2) {
                    log("AA VIDEO_FOCUS_REQUEST NATIVE («Выйти»)", true)
                    sink.onAaNativeFocusRequest()      // Do not reply: NATIVE will be sent when the head unit actually exits CarLife.
                }
            }
            type == M.MEDIA_MIC_REQUEST && chan == M.CH_MIC -> onMicRequest(payload)
            type == M.MEDIA_ACK && chan == M.CH_MIC -> onMicAck(payload)
            // MEDIA_STOP, MEDIA_ACK other channels, UPDATE_UI_CONFIG_*: log only
        }
    }

    private fun onMediaData(chan: Int, type: Int, payload: ByteArray) {
        val isData = type == M.MEDIA_DATA
        val body: ByteArray
        var ptsUs = 0L
        var note: String
        if (isData) {
            if (payload.size < 8) {
                log("AA ch$chan: DATA короче своего timestamp (${payload.size} Б)", true)
                return
            }
            for (i in 0 until 8) ptsUs = (ptsUs shl 8) or (payload[i].toLong() and 0xFF)
            body = payload.copyOfRange(8, payload.size)
            note = "ts=${ptsUs}us"
        } else {
            body = payload
            note = "codec config"
        }

        if (chan == M.CH_VIDEO) {
            stats.videoBytes += body.size
            if (isData) {
                stats.videoData++
                sink.onFrame(body, ptsUs)
                if (firstVideo.compareAndSet(0, System.nanoTime())) onFirstVideo()
            } else {
                stats.videoCodecConfig++
                sink.onCodecConfig(body)
            }
        } else if (isData) {
            stats.audioData++
            audioSink?.onAudioData(chan, body, ptsUs)
        }

        logMsg("RX", chan, type, body, media = true, note = note, text = "")
        // Video confirms both DATA and CODEC_CONFIG (AapTransport.kt dispatchVideo), audio only DATA.
        if (chan == M.CH_VIDEO || isData) send(M.mediaAck(chan, sessionIds[chan] ?: 0), media = true)
    }

    // --- ending ---
    /**
     * ByeBye, wait for the answer, close so that the server sees a clean end: ByeByeRequest (if phone
     * not finished myself), wait ByeByeResponse or EOF to 2 with, shutdownOutput, add to read to EOF to 1.5 with
     * (closing with unread data sends RST), and only then close.
     */
    private fun shutdown() {
        log("AA завершаю: ${stopReason ?: "конец"}", true)
        if (!::sock.isInitialized) return
        try {
            if (::tls.isInitialized && tls.handshakeDone && !peerClosed && !byeAcked && !byeSent) {
                byeSent = true
                send(M.byebyeRequest())
                flushWriter(2000)
                Thread.sleep(150)                                  // AapTransport.kt:706
                waitBye(BYE_WAIT_MS)
            }
        } catch (e: Exception) {
            log("AA при отправке ByeBye: ${e.javaClass.simpleName}: ${e.message}", true)
        }
        stopWriter()                                               // all accumulated goes to shutdownOutput
        try {
            try {
                sock.shutdownOutput()
            } catch (_: IOException) {
            }
            if (!peerClosed) drainUntilEof(DRAIN_WAIT_MS)
        } finally {
            try {
                sock.close()
            } catch (_: IOException) {
            }
            log("AA сокет закрыт", true)
        }
    }

    /** Read (and reply to bye phone) to ByeByeResponse, EOF or timeout. */
    private fun waitBye(timeoutMs: Long) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        try {
            while (!byeAcked && System.nanoTime() < deadline) {
                readMore(minOf(POLL_MS.toLong(), maxOf((deadline - System.nanoTime()) / 1_000_000, 1)).toInt())
                while (frames.isNotEmpty()) {
                    val fr = frames.removeFirst()
                    if (!fr.encrypted) continue
                    val d = tls.decrypt(fr.body, drain = false)
                    flushTls()
                    val whole = if (d.plaintext.isNotEmpty()) reasm.push(fr.chan, fr.flags, d.plaintext, fr.total) else null
                    if (whole == null || whole.size < 2) continue
                    val t = u16(whole, 0)
                    if (fr.chan == M.CH_CONTROL && (t == M.BYEBYE_RESPONSE || t == M.BYEBYE_REQUEST)) {
                        logMsg("RX", fr.chan, t, whole.copyOfRange(2, whole.size), false)
                        if (t == M.BYEBYE_REQUEST) send(M.byebyeResponse())
                        byeAcked = true
                    }
                }
            }
        } catch (_: PeerClosed) {
            peerClosed = true
            log("AA телефон закрыл соединение после ByeBye", true)
        }
        if (!byeAcked && !peerClosed) log("AA нет ByeByeResponse за ${timeoutMs / 1000.0} с, закрываю всё равно", true)
    }

    private fun drainUntilEof(timeoutMs: Long) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            sock.soTimeout = maxOf(((deadline - System.nanoTime()) / 1_000_000).toInt(), 1)
            try {
                if (sock.getInputStream().read(readBuf) < 0) return
            } catch (_: IOException) {
                return
            }
        }
    }

    private fun u16(b: ByteArray, i: Int) = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)

    companion object {
        /** The beginning of the reason for the end of the session, when TCP server head unit is not installed (hereinafter the exception name; ConnectException = failure). */
        const val UNAVAILABLE_REASON = "сервер недоступен: "

        /** The beginning of the reason for the end of the session when TCP was accepted, but VERSION_RESPONSE did not come. */
        const val NO_VERSION_RESPONSE_REASON = "нет VERSION_RESPONSE"

        private const val VERSION_TIMEOUT_MS = 10_000L      // AapTransport.kt HANDSHAKE_TIMEOUT_MS
        private const val TLS_TIMEOUT_MS = 15_000L          // AapSslContext.kt SSL_HANDSHAKE_TIMEOUT_MS
        private const val POLL_MS = 100
        private const val BYE_WAIT_MS = 2_000L
        private const val DRAIN_WAIT_MS = 1_500L
        private const val KEYFRAME_CYCLE_GAP_MS = 500L
    }
}
