package com.yegorkapomidorka.headswap.dex

import android.content.Context
import android.os.PowerManager
import com.yegorkapomidorka.headswap.AppLog

/**
 * The display in its own DisplayGroup (OWN_DISPLAY_GROUP, needed for ALWAYS_UNLOCKED) goes out on its screen timeout,
 * like a virtual device. Keep it on with wake lock's attached to displayId (hidden
 * PowerManager.newWakeLock(level, tag, displayId), works in the main process under --no-hidden-api-checks.
 * Best effort: failed - line in the log, the display works as before.
 */
internal class DisplayWakeLocks(context: Context) {
    private val power = context.getSystemService(PowerManager::class.java)
    private val locks = HashMap<Int, PowerManager.WakeLock>()

    @Suppress("DEPRECATION")
    fun hold(displayId: Int) {
        release(displayId)
        try {
            val create = PowerManager::class.java.getMethod("newWakeLock",
                Int::class.javaPrimitiveType, String::class.java, Int::class.javaPrimitiveType)
            val lock = create.invoke(power, PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "headswap:display$displayId", displayId) as PowerManager.WakeLock
            lock.setReferenceCounted(false)
            lock.acquire()
            locks[displayId] = lock
            AppLog.i("WAKE экран $displayId удерживается включённым (wake lock по дисплею)", false)
        } catch (e: Throwable) {
            AppLog.i("WAKE экран $displayId: wake lock по дисплею не взят: ${e.javaClass.simpleName}: ${e.message}", false)
        }
    }

    companion object {
        /** Display.FLAG_OWN_DISPLAY_GROUP / FLAG_ALWAYS_UNLOCKED (android-16 Display.java, not DisplayManager.VIRTUAL_DISPLAY_FLAG_*). */
        const val FLAG_OWN_DISPLAY_GROUP = 1 shl 8
        const val FLAG_ALWAYS_UNLOCKED = 1 shl 9

        /** The lock on the display is kept only at the display in its group: in general, it would keep the screen of the phone itself turned on. */
        fun shouldHold(displayFlags: Int): Boolean = displayFlags and FLAG_OWN_DISPLAY_GROUP != 0

        fun describe(displayFlags: Int): String =
            "flags=0x${Integer.toHexString(displayFlags)} ownGroup=${displayFlags and FLAG_OWN_DISPLAY_GROUP != 0} " +
                "alwaysUnlocked=${displayFlags and FLAG_ALWAYS_UNLOCKED != 0}"
    }

    fun release(displayId: Int) {
        val lock = locks.remove(displayId) ?: return
        runCatching { if (lock.isHeld) lock.release() }
    }

    /** Releases display locks, which are no longer among the [alive] (closed parent carries and child). */
    fun retain(alive: Set<Int>) {
        for (id in locks.keys.toList()) if (id !in alive) release(id)
    }

    fun releaseAll() {
        for (id in locks.keys.toList()) release(id)
    }
}
