package com.yegorkapomidorka.headswap.launcher

import com.yegorkapomidorka.headswap.desktop.DesktopAppCatalog

/**
 * Third-party launcher (DexPrefs.desktopComponent) in the child window above our Back / Home / Recent panel:
 * desktop selection, button actions, circle restart protection and the composition of "Recent". Without Android and Compose.
 */
internal object ForeignDesktop {
    /** How many packets do you remember the session history? */
    const val HISTORY_MAX = 30

    enum class NavKey { BACK, HOME, RECENTS }

    enum class NavStep {
        /** Back in the child window (the launcher task is closed - it restarts caller once). */
        BACK_IN_WINDOW,
        CLOSE_RECENTS,
        /** Raise the launcher over anything that is open in the window; "Recent" is closed. */
        RAISE_LAUNCHER,
        OPEN_RECENTS,
    }

    /** What to do with the panel button; [recentsOpen] - the recent overlay is now on the screen. */
    fun press(key: NavKey, recentsOpen: Boolean): NavStep = when (key) {
        NavKey.BACK -> if (recentsOpen) NavStep.CLOSE_RECENTS else NavStep.BACK_IN_WINDOW
        NavKey.HOME -> NavStep.RAISE_LAUNCHER
        NavKey.RECENTS -> if (recentsOpen) NavStep.CLOSE_RECENTS else NavStep.OPEN_RECENTS
    }

    /**
     * A valid alien launcher: selection from the settings while its input is valid ([usable]) and rollback is not included on the same screen head unit.
     * null - our desktop works (launcher is not selected, missing, disabled or rolled back).
     */
    fun resolve(component: String?, usable: Boolean, brokenKey: String?, displayId: Int): AppRef? {
        val ref = AppRef.parse(component ?: return null) ?: return null
        if (!usable || brokenKey == brokenKey(ref, displayId)) return null
        return ref
    }

    /** The same launcher on the same screen head unit do not try again, a new session or a new choice - try. */
    fun brokenKey(ref: AppRef, displayId: Int): String = "${ref.key}@$displayId"

    /**
     * You need to roll back to our desktop only if launcher has not started: the window went to FAILED, while launcher. is in it
     * Fallen after the launch of launcher is not replaceable: ● and ◀ restart it. FAILED for the application from the "Recent" - no rollback,
     * just raise launcher.
     */
    fun afterWindowFailed(trackedIsLauncher: Boolean): Failed = if (trackedIsLauncher) Failed.FALLBACK else Failed.RAISE_LAUNCHER

    enum class Failed { FALLBACK, RAISE_LAUNCHER }

    /** Session history: a fresh snapshot of screen tasks ([current], null I couldn’t find it first, then the old one. */
    fun mergeHistory(history: List<String>, current: List<String>?): List<String> =
        ((current ?: emptyList()) + history).distinct().take(HISTORY_MAX)

    /**
     * "Recent": session history packets that have a directory login, without the launcher itself (our catalog package already
     * filtered). [known] - the list of screen tasks could be obtained: then the empty result is honestly empty. Failed and
     * no history - stub: all directory applications (one input per package).
     */
    fun recents(history: List<String>, known: Boolean, launcherPkg: String, catalog: List<DesktopAppCatalog.Entry>): List<AppRef> {
        val entries = catalog.filter { it.packageName != launcherPkg }.distinctBy { it.packageName }
        val byPackage = entries.associateBy { it.packageName }
        val used = history.mapNotNull { byPackage[it] }
        val list = if (known || used.isNotEmpty()) used else entries
        return list.map { AppRef(it.packageName, it.className) }
    }
}
