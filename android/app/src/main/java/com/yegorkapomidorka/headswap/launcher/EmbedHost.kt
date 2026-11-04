package com.yegorkapomidorka.headswap.launcher

import android.graphics.SurfaceTexture
import android.os.Binder
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.view.MotionEvent
import android.view.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.yegorkapomidorka.headswap.AppLog
import com.yegorkapomidorka.headswap.dex.DexState
import com.yegorkapomidorka.headswap.embed.EmbeddedSessionRegistry

internal enum class EmbedStatus { IDLE, CONNECTING, STARTING, READY, FAILED }

/** One child screen: navigator or "current application". The registry limit is 2 windows, so there are exactly two slots. */
internal class EmbedSlot(val name: String) {
    private var statusValue by mutableStateOf(EmbedStatus.IDLE)

    /** The state for UI; is written from the workflow. The change gets into the log: it shows what the layer showed. */
    var status: EmbedStatus
        get() = statusValue
        set(value) {
            if (value == statusValue) return
            AppLog.i("EMBED $name: статус $statusValue -> $value", false)
            statusValue = value
        }

    /** The desired contents are "package/class" and display size in px; null - window is not required. */
    @Volatile var wantKey: String? = null
    @Volatile var wantW = 0
    @Volatile var wantH = 0
    /** Touches in the window are sent (in the card of the navigator - no: tap = deploy). */
    @Volatile var interactive = false

    // The surface belongs to the slot, not the TextureView: when recreating the Activity texture remains
    // live under a working producer (onSurfaceTextureDestroyed returns false).
    val lock = Any()
    var texture: SurfaceTexture? = null
    var textureDisplay = -1

    // Just the work stream.
    var surface: Surface? = null
    var handle = -1
    var token: Binder? = null
    var leaseDisplay = -1
    @Volatile var geomW = 0
    @Volatile var geomH = 0
    var launchedKey: String? = null
    var launchedAt = 0L
    var createdAt = 0L
    var attempts = 0
    var recheck = false
    var touchDown = false
    var lastPacket: TouchPacket? = null
    var scheduled = false

    /** Last answer taskState: 2 - on top, 1 - in the background, 0 - no problem, -1 - could not find out; -2 - not asked yet. */
    var lastState = -2
    /** Since the launch, at least once saw the application from above: only then “no problem” means “closed”, and not “not yet appeared”. */
    var seenTop = false
    /** Touches are not sent after the "task is closed". Is removed by running or state 2. */
    @Volatile var touchBlocked = false
    /** uptime last Back: first second after it touch do not go (TaskCheck.BACK_BLOCK_MS), then again at any state. */
    @Volatile var backAt = 0L
    /** After Back, the state remained unknown (-1): the next time the application is displayed, it starts again. */
    var stale = false
    var dropped = 0
    val watchToken = Any()

    val touchQueue = ArrayDeque<TouchPacket>()

    val pkg: String? get() = wantKey?.substringBefore('/')
}

internal class TouchPacket(
    val action: Int, val index: Int, val ids: IntArray, val xs: FloatArray, val ys: FloatArray,
    val at: Long = SystemClock.uptimeMillis(),
) {
    fun cancel() = TouchPacket(3, 0, ids, xs, ys, at)
}

/**
 * launcher. child windows All registry calls blocking (up to 30 with on Binder parent), so live on one
 * working flow and are not called from the main. Touches come with main thread in line, MOVE collapses.
 */
internal object EmbedHost {
    private const val RETRY_MS = 500L
    private const val MAX_ATTEMPTS = 40
    private const val CREATE_BUDGET_MS = 20_000L
    /** Call registry longer than this gets into the log: so you can see that the workflow is hanging on Binder. */
    private const val SLOW_MS = 300L
    private const val WATCH_MS = 2000L
    /** Touch, which stood in line longer (the workflow was waiting for Binder), is no longer necessary. */
    private const val STALE_TOUCH_MS = 1000L

    val nav = EmbedSlot("nav")
    val app = EmbedSlot("app")
    private val slots = listOf(nav, app)

    private val thread = HandlerThread("headswap-launcher-embed").apply { start() }
    private val worker = Handler(thread.looper)
    private val main = Handler(android.os.Looper.getMainLooper())
    private val uid = Process.myUid()

    /** Density of the parent display: child windows live with it. */
    @Volatile var densityDpi = 160

    /** The application task on the child display has disappeared (Back from the root, process killed). Called to main thread. */
    @Volatile var onGone: ((EmbedSlot) -> Unit)? = null

    // --- calls with main thread ---

    /** The desired content and size of the slot. The same key and size - nothing happens. */
    fun want(slot: EmbedSlot, key: String?, w: Int, h: Int) {
        // another application in the same window: immediately "run" without showing for a moment the former
        if (key != slot.wantKey) slot.status = if (key == null) EmbedStatus.IDLE else EmbedStatus.STARTING
        slot.wantKey = key
        if (w > 0 && h > 0) { slot.wantW = w; slot.wantH = h }
        schedule(slot, 0)
    }

    /**
     * The window was shown again or tapped on the already opened: if the application tasks are not (Back from the root, the process is killed)
     * or state after Back remained unknown, restart.
     */
    fun refresh(slot: EmbedSlot) {
        worker.post { slot.recheck = true; reconcile(slot) }
    }

    /** The task packs of the child slot display (fresh first) for "Recent"; [onResult] on main thread, null - I couldn't find out. */
    fun taskPackages(slot: EmbedSlot, onResult: (List<String>?) -> Unit) {
        worker.post {
            val result = if (slot.handle <= 0) null else try {
                timed(slot, "taskPackages") { EmbeddedSessionRegistry.taskPackages(uid, slot.handle) }
            } catch (e: Exception) {
                AppLog.i("EMBED ${slot.name}: taskPackages не удался: ${e.javaClass.simpleName}: ${e.message}", false)
                if (leaseGone(e)) lostLease(slot)
                null
            }
            main.post { onResult(result) }
        }
    }

    fun retry(slot: EmbedSlot) {
        worker.post { slot.attempts = 0; slot.createdAt = SystemClock.uptimeMillis(); reconcile(slot) }
    }

    fun textureAvailable(slot: EmbedSlot, texture: SurfaceTexture) {
        worker.post {
            val old = synchronized(slot.lock) {
                val previous = slot.texture
                if (previous !== texture) {
                    slot.texture = texture
                    slot.textureDisplay = DexState.displayId
                }
                previous.takeIf { it !== texture }
            }
            if (old != null) {
                // window lived on the same surface: free it for real, reconcile will create a new one on this
                if (slot.handle > 0) releaseLease(slot) else dropSurface(slot)
                runCatching { old.release() }
            }
            reconcile(slot)
        }
    }

    /** Texture left over from the previous desktop is thrown away: its producer is already dead. */
    fun adoptable(slot: EmbedSlot): SurfaceTexture? = synchronized(slot.lock) {
        val t = slot.texture
        if (t != null && !t.isReleased && slot.textureDisplay == DexState.displayId) t else null
    }

    fun touch(slot: EmbedSlot, ev: MotionEvent, viewW: Int, viewH: Int) {
        val action = ev.actionMasked
        if (action !in intArrayOf(0, 1, 2, 3, 5, 6)) return
        val count = ev.pointerCount
        if (count > 2 || viewW <= 0 || viewH <= 0) return
        val gw = slot.geomW
        val gh = slot.geomH
        if (gw <= 0 || gh <= 0) return
        val sx = gw.toFloat() / viewW
        val sy = gh.toFloat() / viewH
        val ids = IntArray(count) { ev.getPointerId(it) }
        val xs = FloatArray(count) { (ev.getX(it) * sx).coerceIn(0f, gw - 1f) }
        val ys = FloatArray(count) { (ev.getY(it) * sy).coerceIn(0f, gh - 1f) }
        val packet = TouchPacket(action, if (action == 5 || action == 6) ev.actionIndex else 0, ids, xs, ys)
        synchronized(slot.touchQueue) {
            val last = slot.touchQueue.lastOrNull()
            if (action == 2 && last != null && last.action == 2 && last.ids.size == count) slot.touchQueue.removeLast()
            slot.touchQueue.addLast(packet)
        }
        worker.post { drainTouch(slot) }
    }

    /**
     * Back out the window; if the application has gone from the root (its task is no longer on top), onClosed will call on main thread.
     * The task is checked several times: the closing activity of top still holds, and the answer is -1 (list of tasks)
     * previously left a layer on top of an empty display forever.
     */
    fun back(slot: EmbedSlot, onClosed: () -> Unit) {
        worker.post {
            AppLog.i("EMBED ${slot.name}: Back handle=${slot.handle} pkg=${slot.pkg} статус=${slot.status} " +
                "state=${slot.lastState} касаний отброшено=${slot.dropped}", false)
            if (slot.handle <= 0) {
                AppLog.i("EMBED ${slot.name}: окна нет, Back = на главный", false)
                main.post(onClosed)
                return@post
            }
            slot.backAt = SystemClock.uptimeMillis()
            try {
                cancelTouch(slot)
                timed(slot, "back") { EmbeddedSessionRegistry.back(uid, slot.handle) }
            } catch (e: Exception) {
                AppLog.i("EMBED ${slot.name}: Back отклонён: ${e.javaClass.simpleName}: ${e.message}", false)
                if (leaseGone(e)) {
                    lostLease(slot)
                    AppLog.i("EMBED ${slot.name}: окно закрыто реестром, Back = на главный", false)
                    main.post(onClosed)
                    return@post
                }
            }
            worker.postDelayed({ checkAfterBack(slot, 0, onClosed) }, TaskCheck.BACK_CHECKS[0])
        }
    }

    private fun checkAfterBack(slot: EmbedSlot, attempt: Int, onClosed: () -> Unit) {
        val state = queryState(slot, "back#$attempt", strict = true)
        val last = attempt >= TaskCheck.BACK_CHECKS.size - 1
        when (TaskCheck.afterBack(state, last)) {
            TaskCheck.AfterBack.CLOSED -> {
                AppLog.i("EMBED ${slot.name}: после Back задачи на экране нет (state=$state), возврат на главный", false)
                slot.launchedKey = null
                slot.seenTop = false
                slot.backAt = 0L
                main.post(onClosed)
            }
            TaskCheck.AfterBack.STAYED_MORE -> {
                slot.backAt = 0L // the application has adopted Back inside itself, the touches are coming again
                worker.postDelayed({ checkAfterBack(slot, attempt + 1, onClosed) }, TaskCheck.BACK_CHECKS[attempt + 1])
            }
            TaskCheck.AfterBack.STAYED -> {
                slot.backAt = 0L
                AppLog.i("EMBED ${slot.name}: после Back приложение осталось сверху", false)
            }
            TaskCheck.AfterBack.UNKNOWN_MORE ->
                worker.postDelayed({ checkAfterBack(slot, attempt + 1, onClosed) }, TaskCheck.BACK_CHECKS[attempt + 1])
            TaskCheck.AfterBack.UNKNOWN -> {
                // could not find out for 1,5 with: we believe that the application has gone from the root; the next show will launch it again
                AppLog.i("EMBED ${slot.name}: после Back состояние неизвестно (state=$state), считаю закрытым, возврат на главный", false)
                slot.stale = true
                slot.launchedKey = null
                slot.seenTop = false
                slot.backAt = 0L
                main.post(onClosed)
            }
        }
    }

    /** Ask the registry about the slot task. The window that the registry has already closed is considered to be "no problem" (0). Only the workflow. */
    private fun queryState(slot: EmbedSlot, source: String, strict: Boolean = false): Int {
        val pkg = slot.pkg
        val raw = if (pkg == null || slot.handle <= 0) 0 else try {
            timed(slot, "taskState") { EmbeddedSessionRegistry.taskState(uid, slot.handle, pkg) }
        } catch (e: Exception) {
            AppLog.i("EMBED ${slot.name}: taskState ($source) не удался: ${e.javaClass.simpleName}: ${e.message}", false)
            if (leaseGone(e)) { lostLease(slot); 0 } else -1
        }
        // "not visible" (3) distinguish only Back and re-display; launch and observer consider such application from above
        val state = if (raw == TaskCheck.MOVED_TO_BACK && !strict) TaskCheck.TOP else raw
        if (raw == TaskCheck.TOP || raw == TaskCheck.MOVED_TO_BACK) slot.seenTop = true
        if (state != slot.lastState || raw != state || source.startsWith("back")) {
            AppLog.i("EMBED ${slot.name}: taskState=$raw ($source) pkg=$pkg статус=${slot.status}", false)
        }
        slot.lastState = state
        return state
    }

    /** Register call; if it took more than [SLOW_MS], line in log. */
    private inline fun <T> timed(slot: EmbedSlot, what: String, block: () -> T): T {
        val t0 = SystemClock.uptimeMillis()
        try {
            return block()
        } finally {
            val dt = SystemClock.uptimeMillis() - t0
            if (dt >= SLOW_MS) AppLog.i("EMBED ${slot.name}: $what занял $dt мс", false)
        }
    }

    /** The application is missing from the child display: the layer must be removed from the screen, touch in the void not send. */
    private fun gone(slot: EmbedSlot, why: String) {
        AppLog.i("EMBED ${slot.name}: задача приложения исчезла ($why), pkg=${slot.pkg}", false)
        slot.launchedKey = null
        slot.seenTop = false
        slot.touchBlocked = true
        main.post { onGone?.invoke(slot) }
    }

    /** While the layer on the screen and takes a touch, once in 2 with check that the task is in place (process killed, Back from the root). */
    private fun watchTick(slot: EmbedSlot) {
        if (slot.wantKey == null || slot.handle <= 0 || slot.status != EmbedStatus.READY) return
        if (slot.interactive && !slot.touchBlocked && slot.backAt == 0L) { // Checks after Back go their own way
            val state = queryState(slot, "watch")
            if (TaskCheck.vanished(state, slot.seenTop)) { gone(slot, "watch"); return }
        }
        worker.postAtTime({ watchTick(slot) }, slot.watchToken, SystemClock.uptimeMillis() + WATCH_MS)
    }

    private fun startWatch(slot: EmbedSlot) {
        worker.removeCallbacksAndMessages(slot.watchToken)
        worker.postAtTime({ watchTick(slot) }, slot.watchToken, SystemClock.uptimeMillis() + WATCH_MS)
    }

    // --- workflow ---

    private fun schedule(slot: EmbedSlot, delayMs: Long) {
        worker.removeCallbacksAndMessages(slot)
        worker.postAtTime({ reconcile(slot) }, slot, SystemClock.uptimeMillis() + delayMs)
    }

    private fun reconcile(slot: EmbedSlot) {
        try {
            reconcileInternal(slot)
        } catch (e: Exception) {
            AppLog.i("EMBED ${slot.name}: reconcile не удался: ${e.javaClass.simpleName}: ${e.message}", false)
            if (slot.handle > 0 && leaseGone(e)) lostLease(slot)
            retryLater(slot)
        }
    }

    /** The registry says that the window is no more (closed, the desktop session is over). */
    private fun leaseGone(e: Exception): Boolean {
        val m = e.message.orEmpty()
        return e is android.os.DeadObjectException || m.contains("закрыто") || m.contains("завершился")
    }

    /** The register answers “not ready” the first seconds of the session: repeat up to 40 times and 20 with, as a sample client. */
    private fun retryLater(slot: EmbedSlot) {
        if (slot.wantKey == null) return
        val now = SystemClock.uptimeMillis()
        if (slot.createdAt == 0L) slot.createdAt = now
        if (slot.attempts++ >= MAX_ATTEMPTS || now - slot.createdAt > CREATE_BUDGET_MS) {
            AppLog.i("EMBED ${slot.name}: попытки исчерпаны (${slot.attempts}), окно не открылось", false)
            slot.status = EmbedStatus.FAILED
            return
        }
        schedule(slot, RETRY_MS)
    }

    private fun reconcileInternal(slot: EmbedSlot) {
        val key = slot.wantKey
        if (key == null) { releaseLease(slot); slot.status = EmbedStatus.IDLE; return }
        val display = DexState.displayId
        if (display <= 0) { slot.status = EmbedStatus.CONNECTING; schedule(slot, 1000); return }
        if (slot.handle > 0 && slot.leaseDisplay != display) lostLease(slot) // new desktop session
        val texture = synchronized(slot.lock) { slot.texture }
        if (texture == null || texture.isReleased) { slot.status = EmbedStatus.CONNECTING; return }
        val w = slot.wantW
        val h = slot.wantH
        if (w < 128 || h < 128) return
        if (slot.handle <= 0) {
            slot.status = EmbedStatus.CONNECTING
            val surface = slot.surface ?: Surface(texture).also { slot.surface = it }
            texture.setDefaultBufferSize(w, h)
            val token = Binder()
            slot.handle = timed(slot, "create") { EmbeddedSessionRegistry.create(uid, surface, token, display, w, h, densityDpi) }
            AppLog.i("EMBED ${slot.name}: окно создано handle=${slot.handle} display=$display ${w}x$h dpi=$densityDpi", false)
            slot.token = token
            slot.leaseDisplay = display
            slot.geomW = w
            slot.geomH = h
            slot.launchedKey = null
        } else if (slot.geomW != w || slot.geomH != h) {
            cancelTouch(slot)
            AppLog.i("EMBED ${slot.name}: размер ${slot.geomW}x${slot.geomH} -> ${w}x$h", false)
            timed(slot, "resize") { EmbeddedSessionRegistry.resize(uid, slot.handle, w, h, densityDpi) }
            texture.setDefaultBufferSize(w, h)
            slot.geomW = w
            slot.geomH = h
        }
        var launch = slot.launchedKey != key
        if (!launch && slot.recheck) {
            // -1 (task list not available) restart only after Back with unknown result
            val state = queryState(slot, "refresh", strict = true)
            launch = TaskCheck.needsRelaunch(state, slot.stale)
            if (state == 2) slot.touchBlocked = false
        }
        slot.recheck = false
        if (launch) {
            slot.status = EmbedStatus.STARTING
            val pkg = key.substringBefore('/')
            val cls = key.substringAfter('/')
            AppLog.i("EMBED ${slot.name}: запуск $key handle=${slot.handle} (state=${slot.lastState} stale=${slot.stale})", false)
            timed(slot, "launch") { EmbeddedSessionRegistry.launch(uid, slot.handle, pkg, cls) }
            slot.launchedKey = key
            slot.launchedAt = SystemClock.uptimeMillis()
            slot.stale = false
            slot.seenTop = false
            slot.touchBlocked = false
            slot.backAt = 0L
        }
        slot.attempts = 0
        slot.createdAt = 0L
        if (slot.status == EmbedStatus.STARTING) pollStarted(slot)
        else {
            if (slot.status != EmbedStatus.READY) slot.status = EmbedStatus.READY
            startWatch(slot)
        }
    }

    /** Cold start: wait until the app is top on the child display, but no longer than 8 s. */
    private fun pollStarted(slot: EmbedSlot) {
        val key = slot.launchedKey ?: return
        val elapsed = SystemClock.uptimeMillis() - slot.launchedAt
        val state = queryState(slot, "start")
        if (state == 2 || elapsed >= PressLogic.COLD_START_MS || (state < 0 && elapsed >= 1500)) {
            if (state != 2) AppLog.i("EMBED ${slot.name}: готовым считаю без подтверждения (state=$state, ${elapsed} мс после запуска)", false)
            slot.status = EmbedStatus.READY
            startWatch(slot)
            return
        }
        worker.postAtTime({ if (slot.status == EmbedStatus.STARTING) reconcile(slot) }, slot, SystemClock.uptimeMillis() + 400)
    }

    private fun drainTouch(slot: EmbedSlot) {
        while (true) {
            val p = synchronized(slot.touchQueue) { slot.touchQueue.removeFirstOrNull() } ?: return
            if (slot.handle <= 0 || !slot.interactive) continue
            // in the display without a live task touch do not go: after Back before confirmation, after "task closed", until ready
            val reason = when {
                slot.touchBlocked -> "заблокированы (задача закрыта)"
                TaskCheck.blockedAfterBack(SystemClock.uptimeMillis(), slot.backAt) -> "заблокированы (сразу после Back)"
                slot.status != EmbedStatus.READY -> "окно ещё не готово (${slot.status})"
                p.action == 0 && SystemClock.uptimeMillis() - p.at > STALE_TOUCH_MS -> "устарели в очереди"
                else -> null
            }
            if (reason != null) {
                if (p.action == 0) noteDropped(slot, reason)
                continue
            }
            if (p.action == 0) slot.touchDown = true
            if (!slot.touchDown) continue
            slot.lastPacket = p
            try {
                timed(slot, "touch") { EmbeddedSessionRegistry.touchPoints(uid, slot.handle, p.action, p.index, p.ids, p.xs, p.ys) }
            } catch (e: Exception) {
                AppLog.i("EMBED ${slot.name}: касание отклонено: ${e.javaClass.simpleName}: ${e.message}", false)
                if (leaseGone(e)) { lostLease(slot); gone(slot, "касание") }
            }
            if (p.action == 1 || p.action == 3) { slot.touchDown = false; slot.lastPacket = null }
        }
    }

    /** Retracted touches: line to the first and on each 20th, so that the log does not clog. */
    private fun noteDropped(slot: EmbedSlot, reason: String) {
        slot.dropped++
        if (slot.dropped == 1 || slot.dropped % 20 == 0) {
            AppLog.i("EMBED ${slot.name}: касание отброшено, $reason (всего отброшено ${slot.dropped})", false)
        }
    }

    private fun cancelTouch(slot: EmbedSlot) {
        synchronized(slot.touchQueue) { slot.touchQueue.clear() }
        val last = slot.lastPacket
        if (!slot.touchDown || last == null) return
        slot.touchDown = false
        slot.lastPacket = null
        val c = last.cancel()
        runCatching { EmbeddedSessionRegistry.touchPoints(uid, slot.handle, c.action, c.index, c.ids, c.xs, c.ys) }
    }

    /** The registry has already lost a window (the desktop session is over): forget it without calling release. */
    private fun lostLease(slot: EmbedSlot) {
        AppLog.i("EMBED ${slot.name}: окно потеряно (handle=${slot.handle})", false)
        slot.handle = -1
        slot.token = null
        slot.launchedKey = null
        slot.touchDown = false
        slot.lastPacket = null
        slot.geomW = 0
        slot.geomH = 0
    }

    private fun releaseLease(slot: EmbedSlot) {
        val handle = slot.handle
        if (handle > 0) {
            cancelTouch(slot)
            AppLog.i("EMBED ${slot.name}: release handle=$handle", false)
            runCatching { timed(slot, "release") { EmbeddedSessionRegistry.release(uid, handle) } }
                .onFailure { AppLog.i("EMBED ${slot.name}: release не удался: ${it.javaClass.simpleName}", false) }
        }
        lostLease(slot)
        // The surface is released only after the confirmed release: producer could stay alive.
        dropSurface(slot)
    }

    private fun dropSurface(slot: EmbedSlot) {
        slot.surface?.let { runCatching { it.release() } }
        slot.surface = null
    }
}
