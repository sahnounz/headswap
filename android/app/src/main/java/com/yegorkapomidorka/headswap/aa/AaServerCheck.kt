package com.yegorkapomidorka.headswap.aa

import android.content.Context
import android.hardware.usb.UsbManager
import com.yegorkapomidorka.headswap.AppLog
import com.yegorkapomidorka.headswap.CarLifeState
import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** The result of one sample server head unit (127.0.0.1:5277). */
enum class ProbeResult {
    /** The full handshake has passed, the session is closed normally. */
    ALIVE,

    /** ECONNREFUSED: port no one listens. */
    REFUSED,

    /** TCP accepted, but VERSION_RESPONSE or TLS did not come (or the port does not accept connections). */
    HUNG,

    /** Other: no certificates, the server immediately closed the connection, TLS rejected, socket. failed */
    ERROR,
}

/** One try of the sample. The real one is AaSessionProbe, replaced in the tests. */
fun interface ServerProbe {
    fun probeOnce(): ProbeResult
}

/**
 * head unit: server live trial is a real AA-session (AapSession, as in bridge and AaProbe), not a naked connect.
 * Naked connect/close without handshake hangs the server 5277, so after TCP attempt goes to the end:
 * VERSION, TLS, AuthComplete, SDR, opening channels (video focus NATIVE, video is not going), via [HOLD_MS]
 * closing (ByeBye, EOF, close). Closing exactly on SDR server hangs: 85+ with no VERSION_RESPONSE and manual restart
 * (aa_gap 30.10 stand; 24.10 trip to noa11y5). A second after AuthComplete the server experiences: next session via 1-5 p.
 * Returns only when the session has closed completely (onEnd): otherwise the bridge would have connected on top of it.
 */
class AaSessionProbe(
    private val loadCreds: () -> AapCredentials,
    private val sd: () -> AapSdOptions,
    private val log: (String, Boolean) -> Unit,
    private val host: String = "127.0.0.1",
    private val port: Int = 5277,
    private val connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
    private val holdMs: Long = HOLD_MS,
) : ServerProbe {
    private object NoSink : AaVideoSink {
        override fun onCodecConfig(annexB: ByteArray) {}
        override fun onFrame(annexB: ByteArray, ptsUs: Long) {}
    }

    /** What ended the last attempt (connection error or reason for the end of the session): for toast at ERROR. */
    @Volatile var lastDetail: String = ""
        private set

    override fun probeOnce(): ProbeResult {
        lastDetail = ""
        val creds = try {
            loadCreds()
        } catch (e: Throwable) {
            lastDetail = "${e.javaClass.simpleName}: ${e.message}"
            log("SRVCHECK сертификаты не загрузились: $lastDetail", true)
            return ProbeResult.ERROR
        }
        val ended = CountDownLatch(1)
        val endReason = AtomicReference("-")
        val connectError = AtomicReference<IOException?>(null)
        val session = AapSession(
            creds, NoSink, AapOptions(host = host, port = port, sdProvider = sd, videoFocus = false),
            log = { line, _ ->
                // "AA" -> "aa": LogDiag/logdiag.py search bridge markers ("AA TCP connected", "AA server ... unavailable") substring along the entire line
                log("SRVCHECK | " + (if (line.startsWith("AA ")) "aa " + line.substring(3) else line), false)
            },
            socketFactory = {
                Socket().also { s ->
                    try {
                        s.connect(InetSocketAddress(host, port), connectTimeoutMs)
                    } catch (e: IOException) {
                        connectError.set(e)
                        try {
                            s.close()
                        } catch (_: IOException) {
                        }
                        throw e
                    }
                }
            },
            onEnd = { reason, _ ->
                endReason.set(reason)
                ended.countDown()
            },
        )
        session.start()
        // While TCP is accepted, you can not interrupt: stop to TLS would close socket without ByeBye. Session itself is limited
        // timeouts (version 10 s, TLS 15 s, bye and EOF a few more seconds), so we are waiting for its end.
        var establishedAtMs = 0L
        var stopAsked = false
        var interrupted = false
        var polls = 0
        while (true) {
            try {
                if (ended.await(50, TimeUnit.MILLISECONDS)) break
            } catch (_: InterruptedException) {
                interrupted = true      // thread not quit: session still keeps socket
            }
            if (session.established && !stopAsked) {
                val nowMs = System.nanoTime() / 1_000_000
                if (establishedAtMs == 0L) establishedAtMs = nowMs
                if (nowMs - establishedAtMs >= holdMs) {
                    stopAsked = true
                    session.requestStop("проба: сессия отработала")
                }
            }
            if (++polls > MAX_POLLS) {
                log("SRVCHECK сессия не закрылась за ${MAX_POLLS * 50 / 1000} с, выхожу не дожидаясь", true)
                break
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
        val result = classify(connectError.get(), session.connected, session.established, endReason.get())
        lastDetail = connectError.get()?.let { "${it.javaClass.simpleName}: ${it.message}" } ?: endReason.get()
        log(
            "SRVCHECK сессия: connected=${session.connected} established=${session.established} закрытие чистое=${session.exitOk} " +
                "rx=${session.stats.rxFrames} tx=${session.stats.txFrames}, причина: ${endReason.get()}",
            true,
        )
        return result
    }

    companion object {
        const val CONNECT_TIMEOUT_MS = 3000
        /** How long to hold a session after the AuthComplete: phone has time to ask SDR and open channels. */
        const val HOLD_MS = 1000L
        private const val MAX_POLLS = 120 * 20      // 120 s when polling times in 50 ms

        /** The result is where the session came from. Timeouts handshake = HUNG, everything else is unusual = ERROR. */
        fun classify(connectError: Throwable?, connected: Boolean, established: Boolean, endReason: String): ProbeResult = when {
            !connected -> when {
                connectError is ConnectException && connectError.message?.contains("refused", ignoreCase = true) == true -> ProbeResult.REFUSED
                connectError is SocketTimeoutException -> ProbeResult.HUNG       // backlog is full: port does not accept
                else -> ProbeResult.ERROR
            }
            established -> ProbeResult.ALIVE
            endReason.startsWith(AapSession.NO_VERSION_RESPONSE_REASON) || endReason.startsWith("TLS handshake: таймаут") -> ProbeResult.HUNG
            else -> ProbeResult.ERROR
        }
    }
}

/**
 * Test on demand (button or screen opener): one try in the background of thread, without series and repetitions.
 * Does not start with a live bridge and connected USB (a one-time server 5277 then they need) and not in parallel with another.
 * An attempt to reach TCP cannot be interrupted, so the bridge before connecting is waiting for its end (awaitIdle).
 */
class ManualServerCheck(
    private val probe: ServerProbe,
    private val busy: () -> Refusal?,
    private val log: (String) -> Unit,
) {
    /** Why didn't you run the test? */
    enum class Refusal { BRIDGE, USB, RUNNING }

    private val lock = ReentrantLock()
    private val idle = lock.newCondition()
    private var running = false

    /** null = sample is running, onDone will be called from the working thread (detail - text failure if sample dropped exception); otherwise the reason for failure. */
    fun start(onDone: (ProbeResult, String?) -> Unit): Refusal? {
        lock.withLock {
            if (running) return Refusal.RUNNING
            busy()?.let { return it }
            running = true
        }
        val t = Thread({
            var result = ProbeResult.ERROR
            var detail: String? = null
            try {
                log("SRVCHECK ручная проба: старт")
                result = probe.probeOnce()
            } catch (e: Throwable) {
                detail = "${e.javaClass.simpleName}: ${e.message}"
                log("SRVCHECK сбой: $detail")
            } finally {
                lock.withLock {
                    running = false
                    idle.signalAll()
                }
            }
            log("SRVCHECK ИТОГ: $result")
            onDone(result, detail)
        }, "aa-srvcheck")
        t.isDaemon = true
        t.start()
        return null
    }

    /** Wait for the end of the running test; false = not over for timeoutMs. */
    fun awaitIdle(timeoutMs: Long): Boolean = lock.withLock {
        var left = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        var interrupted = false
        while (running && left > 0) {
            try {
                left = idle.awaitNanos(left)
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
        !running
    }
}

/**
 * Passive diagnostics of the head unit server by real bridge connections (AaConnector), does not go to the server itself:
 * How long has he been refusing (ECONNREFUSED) and how many times in a row has he accepted TCP but not sent VERSION_RESPONSE.
 * Short refusals ( ~ 6 S after ByeByebye. AA again listens to the port) norm: solves the main screen (HomeLogic) at the thresholds.
 * The series belongs to a single connector cycle: reset() at the beginning and at the end, old failures do not fall into the new trip.
 */
class ServerFailures(private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    @Volatile private var refusedSince = NONE

    /** When the last set session ended (our ByeBye or break); NONE - this cycle did not. */
    @Volatile private var lastEndAt = NONE

    /** Subscribe to TCP connections without VERSION_RESPONSE. */
    @Volatile var versionHangs = 0
        private set

    /** At least once during the process, a real AA-session was established: the server worked. Does not reset. */
    @Volatile var worked = false
        private set

    /** How many milliseconds does the server fail without a break (0 = does not fail). */
    val refusedForMs: Long get() = refusedSince.let { if (it == NONE) 0L else now() - it }

    fun onRefused() {
        if (refusedSince == NONE) refusedSince = now()
        versionHangs = 0
    }

    /** TCP is accepted: the port no longer fails (VERSION_RESPONSE will decide further). */
    fun onTcpAccepted() {
        refusedSince = NONE
    }

    fun onNoVersionResponse() {
        refusedSince = NONE
        versionHangs++
    }

    /** The session is established: the server is running, the series are reset. */
    fun onEstablished() {
        reset()
        worked = true
    }

    /** The set session is over (our ByeBye or break): the nearest [RELISTEN_MS] failure is the norm, AA raises the port again. */
    fun onSessionEnded() {
        lastEndAt = now()
    }

    /** Our set session ended less than [RELISTEN_MS] ago: AA may not listen to the port yet. */
    fun inRelistenWindow(): Boolean = lastEndAt.let { it != NONE && now() - it < RELISTEN_MS }

    /**
     * Server fails, and this is not a window [RELISTEN_MS] after the close of our own session: at the fresh start of the bridge failure
     * means that the server is not running. Stock to restart the port is needed only immediately after your ByeBye.
     */
    val refusedFresh: Boolean
        get() = refusedSince != NONE && (lastEndAt == NONE || refusedSince - lastEndAt >= RELISTEN_MS)

    /**
     * It's time to ask the user to turn on the server (text stub on head unit): hanging in a row, failure at the server that
     * in this process has not yet worked, failure out of the window after closing its session ([refusedFresh]) or refusal longer
     * [START_HINT_REFUSED_MS] in working (as HomeLogic.REFUSED_DOWN_MS).
     */
    fun needsStart(): Boolean =
        versionHangs >= START_HINT_HANGS ||
            (refusedSince != NONE && (!worked || refusedFresh || refusedForMs >= START_HINT_REFUSED_MS))

    /** Any other outcome (naked EOF, TLS, etc.), start and end of the connector cycle. */
    fun reset() {
        refusedSince = NONE
        versionHangs = 0
    }

    private companion object {
        const val NONE = Long.MIN_VALUE
        const val START_HINT_REFUSED_MS = 15_000L
        const val RELISTEN_MS = 10_000L
        const val START_HINT_HANGS = 2
    }
}

/**
 * Sample server head unit: button in "For developers" (Actions.checkServer) and autotest when opening the main screen.
 * The bridge before the connection is waiting for the end of the test.
 */
object AaServerCheck {
    @Volatile private var manual: ManualServerCheck? = null
    @Volatile private var probe: AaSessionProbe? = null

    /** How much is the bridge waiting for the end of the manual test before connecting. The attempt itself fits into ~ 35 c. */
    private const val AWAIT_MS = 45_000L

    /** Re-opening screen more often this server does not touch: each sample - short session AA. */
    private const val AUTO_EVERY_MS = 15_000L

    /** Auto test result: true - session set, false - failure or freeze, null - not checked, failed or forgotten. */
    @Volatile var autoRunning: Boolean? = null
        private set

    /** The test is now running (the screen shows "Checking the head unit server"). */
    @Volatile var autoBusy = false
        private set

    @Volatile private var autoWanted = false
    @Volatile private var autoAtMs = 0L

    /** The main screen is open: check the server as soon as possible (autoTick). */
    fun wantAuto() {
        autoWanted = true
    }

    /** The result is outdated (the user went to turn on the server, the bridge began): check again on the next opening. */
    fun forgetAuto() {
        autoRunning = null
        autoAtMs = 0L
    }

    /**
     * Main screen tick (main). Only tries if the bridge does not go, USB is not connected
     * and its session of the bridge has closed not only that (otherwise refusal - just AA does not listen to the port yet).
     */
    fun autoTick(ctx: Context) {
        if (!autoWanted || autoBusy) return
        if (AaState.serverFailures.inRelistenWindow()) return
        val now = System.nanoTime() / 1_000_000
        if (autoAtMs != 0L && now - autoAtMs < AUTO_EVERY_MS) {
            autoWanted = false
            return
        }
        autoBusy = true
        val refusal = checker(ctx).start { result, _ ->
            autoRunning = when (result) {
                ProbeResult.ALIVE -> true
                ProbeResult.REFUSED, ProbeResult.HUNG -> false
                ProbeResult.ERROR -> null       // server immediately closed, etc.: neither approve
            }
            autoAtMs = System.nanoTime() / 1_000_000
            autoBusy = false
        }
        if (refusal != null) {
            autoBusy = false        // bridge, USB or manual sample: try the next tick when free
            return
        }
        autoWanted = false
    }

    private fun checker(ctx: Context): ManualServerCheck {
        manual?.let { return it }
        synchronized(this) {
            manual?.let { return it }
            val app = ctx.applicationContext
            val usb = app.getSystemService(UsbManager::class.java)
            val p = AaSessionProbe(
                loadCreds = { AaCreds.load(app, AaPrefs(app).certSet).creds },
                sd = { VideoMode.DEFAULT.sdOptions(fullAudio = false, carMake = AaPrefs(app).carMake) },
                log = { line, ui -> AppLog.i(line, ui) },
            )
            probe = p
            val c = ManualServerCheck(
                probe = p,
                busy = {
                    when {
                        CarLifeState.running -> ManualServerCheck.Refusal.BRIDGE
                        usb.accessoryList?.isNotEmpty() == true -> ManualServerCheck.Refusal.USB
                        else -> null
                    }
                },
                log = { AppLog.i(it) },
            )
            manual = c
            return c
        }
    }

    /** Button in "For developers". null = sample went, onDone will be called from the working thread (detail is the reason for ERROR). */
    fun start(ctx: Context, onDone: (ProbeResult, String?) -> Unit): ManualServerCheck.Refusal? =
        checker(ctx).start { result, err -> onDone(result, err ?: if (result == ProbeResult.ERROR) probe?.lastDetail else null) }

    /** AaConnector before each connection to the server head unit (thread connector, not main): manual sample should not hold the server. */
    fun beforeBridgeConnect() {
        val c = manual ?: return
        if (!c.awaitIdle(AWAIT_MS)) AppLog.i("SRVCHECK проба не закончилась за $AWAIT_MS мс, подключаюсь всё равно")
    }
}
