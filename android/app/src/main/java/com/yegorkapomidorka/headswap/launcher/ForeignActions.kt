package com.yegorkapomidorka.headswap.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.yegorkapomidorka.headswap.AppLog
import com.yegorkapomidorka.headswap.dex.DexPrefs

/**
 * Alien Mode launcher: window [EmbedHost.app] above our navigation panel. Display head unit still given
 * LauncherActivity, so the change of choice and button HOME reach it as onNewIntent. All on main thread.
 */
internal object ForeignActions {
    private val model get() = LauncherModel

    /**
     * Reread DexPrefs.desktopComponent (onCreate, onNewIntent, onResume, stand). [goHome] is also Home
     * (HOME head unit, change of choice in the settings); without it, launcher is already running mode do not touch.
     * [displayId] - head display unit: rollback and history are valid within one session.
     */
    fun reload(ctx: Context, displayId: Int, goHome: Boolean) {
        if (displayId != model.foreignDisplay) {
            model.foreignDisplay = displayId
            model.recentsHistory = emptyList()
            model.recentsOpen = false
        }
        val component = DexPrefs(ctx).desktopComponent
        val changed = component != model.foreignSeen
        if (changed) {
            model.foreignSeen = component
            model.foreignBroken = null // New choice: try again
            model.recentsHistory = emptyList()
        }
        val ref = component?.let(AppRef::parse)
        val usable = ref != null && desktopUsable(ctx, ref)
        val resolved = ForeignDesktop.resolve(component, usable, model.foreignBroken, displayId)
        if (changed && component != null && resolved == null) {
            AppLog.i("LAUNCHER чужой лаунчер $component: ${if (usable) "уже не стартовал на этом экране" else "пакета или входа нет"}, " +
                "показываю свой рабочий стол", false)
        }
        val previous = model.foreign
        if (resolved == null) {
            if (previous != null) leave(if (component == null) "выбран Headswap" else "лаунчер недоступен")
            return
        }
        if (resolved != previous) {
            AppLog.i("LAUNCHER чужой лаунчер ${resolved.key} в окне над панелью навигации", false)
            model.foreign = resolved
            model.areaRect = null // The window is considered anew according to the new layout
            model.area = Area.Home
            home()
        } else if (goHome) home()
    }

    /** The package is included and opens in the same way as running the registry window: exported-Log in under LAUNCHER of this class. */
    private fun desktopUsable(ctx: Context, ref: AppRef): Boolean = runCatching {
        ctx.packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(ref.pkg), 0,
        ).any {
            val a = it.activityInfo
            a != null && ComponentName(a.packageName, a.name).flattenToString() == ComponentName(ref.pkg, ref.cls).flattenToString() &&
                a.enabled && a.exported && a.applicationInfo.enabled
        }
    }.getOrDefault(false)

    /**
     *  ● : launcher on top of everything that is open in the window. If there is another application in the window now, change the key of the window and start
     * goes the usual way (the slot is already following launcher); if launcher and so in the window - check its task, restart
     * Only when it's not on top. refresh, along with the key change, is not called: on the workflow, he would read the old key.
     */
    fun home() {
        val ref = model.foreign ?: return
        model.recentsOpen = false
        if (EmbedHost.app.status == EmbedStatus.FAILED) {
            // The former FAILED should not immediately roll back a new attempt: the status is reset before the layer sees it
            EmbedHost.app.status = EmbedStatus.CONNECTING
            EmbedHost.retry(EmbedHost.app)
        }
        if (model.appKey != ref.key) {
            model.starting = model.packageLabel(ref.pkg)
            model.appKey = ref.key
        } else EmbedHost.refresh(EmbedHost.app)
    }

    /** Pressing the panel button or hardware Back head unit. */
    fun press(key: ForeignDesktop.NavKey) {
        AppLog.i("LAUNCHER панель: $key, недавние=${model.recentsOpen}, окно=${EmbedHost.app.pkg}", false)
        when (ForeignDesktop.press(key, model.recentsOpen)) {
            ForeignDesktop.NavStep.BACK_IN_WINDOW -> EmbedHost.back(EmbedHost.app) { backClosed() }
            ForeignDesktop.NavStep.CLOSE_RECENTS -> model.recentsOpen = false
            ForeignDesktop.NavStep.RAISE_LAUNCHER -> if (model.launchDebounce.allow(model.now())) home()
            ForeignDesktop.NavStep.OPEN_RECENTS -> openRecents()
        }
    }

    /** The Back took the problem out of the root: if it was a launcher or an open-source application from the "Recent" - launcher again, once to press. */
    fun backClosed() {
        model.launchLock.finish()
        model.starting = null
        home()
    }

    /** The task in the window is lost itself (process killed, Back is not ours): launcher restarts, rollback is not needed. */
    fun gone() {
        AppLog.i("LAUNCHER задача в окне пропала (${EmbedHost.app.pkg}), поднимаю лаунчер", false)
        home()
    }

    /** Slot went to FAILED: not starting launcher replace our table, unsuccessful application from "Recent" - launcher. */
    fun windowFailed() {
        val ref = model.foreign ?: return
        when (ForeignDesktop.afterWindowFailed(EmbedHost.app.pkg == ref.pkg)) {
            ForeignDesktop.Failed.FALLBACK -> {
                AppLog.i("LAUNCHER чужой лаунчер ${ref.key} не стартовал (окно не открылось), показываю свой рабочий стол", false)
                model.foreignBroken = ForeignDesktop.brokenKey(ref, model.foreignDisplay)
                leave("окно не открылось")
            }
            ForeignDesktop.Failed.RAISE_LAUNCHER -> home()
        }
    }

    /** Go to our desktop: someone else's launcher window is free (it holds one of two leases). */
    private fun leave(why: String) {
        AppLog.i("LAUNCHER выход из режима чужого лаунчера: $why", false)
        model.foreign = null
        model.areaRect = null
        model.recentsOpen = false
        model.recents = null
        model.starting = null
        model.launchLock.finish()
        model.appKey = null
        model.home()
    }

    // --- "Recent" ---

    private fun openRecents() {
        val ref = model.foreign ?: return
        model.recents = null
        model.recentsOpen = true
        EmbedHost.taskPackages(EmbedHost.app) { packages ->
            if (packages != null) model.recentsHistory = ForeignDesktop.mergeHistory(model.recentsHistory, packages)
            model.recents = ForeignDesktop.recents(model.recentsHistory, packages != null, ref.pkg, model.catalog)
            AppLog.i("LAUNCHER недавние: задач на экране=${packages?.size ?: "неизвестно"}, в списке=${model.recents?.size}", false)
        }
    }

    /**
     * The application from "Recent" in the same window. The launch is a change of the key of the window, not a direct launch registry: the slot follows
     * for the last packet running, and then the open application task is not considered missing launcher, but ●
     * returns launcher in the same way.
     */
    fun openRecent(ref: AppRef) {
        model.recentsOpen = false
        if (!model.launchDebounce.allow(model.now())) return
        model.recentsHistory = ForeignDesktop.mergeHistory(model.recentsHistory, listOf(ref.pkg))
        AppLog.i("LAUNCHER недавние: открываю ${ref.key}", false)
        if (model.appKey == ref.key) { EmbedHost.refresh(EmbedHost.app); return }
        model.starting = model.label(ref) ?: ref.pkg
        model.appKey = ref.key
    }
}
