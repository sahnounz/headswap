package com.yegorkapomidorka.headswap.aa

import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** The life cycle of the AA- side of the bridge (implemented by AaConnector). */
interface AaLifecycle {
    fun start()

    /** Correctly close AA (ByeBye, flush, EOF, close); onDone is called from a separate thread when everything is closed. */
    fun stop(onDone: () -> Unit)
}

/** A one-shot source may fail before its host receives the asynchronous failure callback. */
interface TerminalSource {
    val isTerminated: Boolean
}

/** Optional source capability. A true result accepts an asynchronous request, not a hardware ACK. */
interface DesktopPowerControl {
    fun setPhoneScreenOff(off: Boolean): Boolean
}

/** Navigation belongs only to a currently attached desktop session. */
interface DesktopNavigation {
    fun showDesktop(): Boolean
}

/**
 * AA-Bridge Side for Lifetime SERVICE rather than one USB-session CarLife. Physical Breakdown USB (EIO)
 * closes CarLife-session, but AA-session remains "warm" graceMs: if USB is back, new
 * CarLife-session picks up live AA (core.attach + START -> keyframe) if not or press Stop -
 * AA closes correctly. A single 5277 server should not receive a new connection until
 * the previous one is live: one connector per host, and the new host waits until the previous one closes (previous).
 */
class BridgeHost(
    val core: AaBridgeCore,
    private val connector: AaLifecycle,
    private val scheduler: ScheduledExecutorService,
    private val log: (String, Boolean) -> Unit,
    private val graceMs: Long = GRACE_MS,
    private val previous: BridgeHost? = null,
    private val onTornDown: () -> Unit = {},
    private val previousStopTimeoutMs: Long = 15_000,
) {
    private var started = false
    private var tearingDown = false
    private var grace: ScheduledFuture<*>? = null
    private val tornDownLatch = CountDownLatch(1)

    /** AA is closed completely (ByeBye, EOF, close). */
    val isTornDown: Boolean get() = tornDownLatch.count == 0L

    /** Host is closed or closed: a new CarLife- session must be hung on a new host. */
    @get:Synchronized
    val isDead: Boolean get() = tearingDown || (connector as? TerminalSource)?.isTerminated == true

    @get:Synchronized
    val isInGrace: Boolean get() = grace != null

    fun awaitTornDown(timeoutMs: Long): Boolean = tornDownLatch.await(timeoutMs, TimeUnit.MILLISECONDS)

    /** Never create a source or apply a screen command to a retired / detached USB session. */
    @Synchronized
    fun setPhoneScreenOff(off: Boolean): Boolean {
        if (!started || isDead || grace != null) return false
        return (connector as? DesktopPowerControl)?.setPhoneScreenOff(off) ?: false
    }

    @Synchronized
    fun showDesktop(): Boolean {
        if (!started || isDead || grace != null) return false
        return (connector as? DesktopNavigation)?.showDesktop() ?: false
    }

    /** New CarLife-session: Cancel grace, at first - run AA. false = host already closes. */
    @Synchronized
    fun onUsbSessionStarted(): Boolean {
        if (isDead) return false
        grace?.let {
            it.cancel(false)
            grace = null
            log("BRIDGE USB вернулся в grace-окно: AA-сессия жива, переиспользую", true)
        }
        if (!started) {
            started = true
            val prev = previous
            if (prev != null && !prev.isTornDown) {
                // 5277 one-time server: new connection only after the previous one is completely closed
                log("BRIDGE жду полного закрытия предыдущей AA-сессии перед новым подключением", true)
                Thread({
                    val closed = prev.awaitTornDown(previousStopTimeoutMs)
                    synchronized(this) {
                        if (!tearingDown) {
                            if (closed && !isDead) connector.start()
                            else tearDown("предыдущий источник не подтвердил закрытие; новый запуск отменён")
                        }
                    }
                }, "aa-host-start").start()
            } else {
                connector.start()
            }
        }
        return true
    }

    /** CarLife-session closed. immediate (stop/off bridge button) - immediately close AA, otherwise keep graceMs. */
    @Synchronized
    fun onUsbSessionClosed(immediate: Boolean) {
        if (tearingDown) return
        if (immediate) {
            tearDown("остановлено пользователем")
            return
        }
        grace?.cancel(false)
        log("BRIDGE USB закрыт: держу AA тёплой ${graceMs} мс", true)
        AaState.status = "мост: USB потерян, AA жива ${graceMs / 1000} с"
        grace = scheduler.schedule({ onGraceExpired() }, graceMs, TimeUnit.MILLISECONDS)
    }

    private fun onGraceExpired() {
        synchronized(this) {
            if (tearingDown || grace == null) return
            grace = null
            tearDown("USB не вернулся за ${graceMs} мс")
        }
    }

    /** Close AA now (Stop, service destroyed, change mode). */
    @Synchronized
    fun shutdown(why: String) {
        if (tearingDown) return
        tearDown(why)
    }

    private fun tearDown(why: String) {
        tearingDown = true
        grace?.cancel(false)
        grace = null
        log("BRIDGE закрываю AA ($why): ByeBye, flush, EOF, close", true)
        if (!started) {
            tornDownLatch.countDown()
            onTornDown()
            return
        }
        connector.stop {
            tornDownLatch.countDown()
            onTornDown()
        }
    }

    companion object {
        /** How long to keep the AA session alive after losing USB. */
        const val GRACE_MS = 8_000L
    }
}
