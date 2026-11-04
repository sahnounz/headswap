package com.yegorkapomidorka.headswap.launcher

import android.app.ActivityOptions
import android.content.Intent
import android.view.Display
import com.yegorkapomidorka.headswap.AppLog
import com.yegorkapomidorka.headswap.MainActivity
import com.yegorkapomidorka.headswap.dex.DexState

/** launcher button actions in one place: transitions between screens, running applications, back. */
object LauncherActions {
    private val model get() = LauncherModel

    /** Logo on the rail: on the main - "All applications", on any other screen (and in the open application) - home. */
    fun logo() { if (model.area == Area.Home) model.go(Area.AllApps) else home() }

    /** Home is the only point of return to the main screen. */
    fun home() { if (model.foreign != null) ForeignActions.home() else model.home() }
    fun openPicker() { model.go(Area.Picker) }
    /** Gear on the rail: settings; re-press - back. */
    fun openSettings() {
        if (model.area == Area.Settings) { model.back(); return }
        model.settingsPage = SettingsPage.LOOK
        model.aboutLicense = null
        model.go(Area.Settings)
    }
    /** Tap on the "Phone" card: screen "Calls"; missed ones are considered viewed. */
    fun openCalls() {
        model.markMissedSeen()
        model.go(Area.Calls)
    }

    /** "Call back" / "Call": call to number, count missed is reset. */
    fun callBack(number: String) {
        if (!model.launchDebounce.allow(model.now())) return
        model.markMissedSeen()
        PhoneController.dial(model.context, number)
    }
    fun cycleCardView() { model.nextCardView() }

    /** Phone card without access: the Headswap window on the phone screen with a request for card permissions. */
    fun openPhoneRequest() {
        if (!model.launchDebounce.allow(model.now())) return
        val ctx = model.context
        val intent = Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_CMD, "phone_card_perm")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val opts = ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY)
        runCatching { ctx.startActivity(intent, opts.toBundle()) }
            .onSuccess { AppLog.i("LAUNCHER запрос разрешений карточки «Телефон» открыт на телефоне", false) }
            .onFailure { AppLog.i("LAUNCHER запрос разрешений на телефоне: ${it.javaClass.simpleName}: ${it.message}", false) }
    }

    /** The choice in the Picker: application goes to the bottom row of the main one, and we return there. */
    fun addApp(ref: AppRef) {
        model.addApp(ref)
        home()
    }

    fun confirmRemove() {
        model.confirmRemove?.let { model.removeApp(it) }
        model.confirmRemove = null
    }

    /** 400 ms anti-drill and re-tap lock while the application starts (up to 8 s). */
    fun openApp(ref: AppRef) {
        val now = model.now()
        if (!model.launchDebounce.allow(now) || model.launchLock.isLocked(now)) return
        // the navigator already lives in its window: an application from the list with the same package deploys it
        if (ref.pkg == model.navigatorPkg) { openNavigatorWindow(); return }
        // tap on an already open application: the screen does not change, so we start checking the task with our hands
        if (model.area == Area.App(ref)) { EmbedHost.refresh(EmbedHost.app); return }
        model.launchLock.begin(ref.key, now)
        model.starting = model.label(ref) ?: ref.pkg
        model.appKey = ref.key
        model.go(Area.App(ref))
    }

    /** Tap on the body of the music card: full-screen player application to the right of the rail. */
    fun openMusicApp() {
        val pkg = MusicController.state.pkg ?: model.musicPlayer ?: return
        val entry = model.catalog.firstOrNull { it.packageName == pkg } ?: return
        openApp(AppRef(entry.packageName, entry.className))
    }

    fun openPlayers() { model.go(Area.Players) }

    /** Start the session with "Include music automatically": play active session, otherwise selected or your player. main thread. */
    fun autoplay() {
        val display = DexState.displayId
        if (!model.autoplay || display < 0 || display == model.autoplayedDisplay) return
        model.autoplayedDisplay = display
        val st = MusicController.state
        val pkg = model.musicPlayer ?: model.localPlayer
        AppLog.i("LAUNCHER автозапуск музыки: ${if (st.playing) "уже играет" else st.pkg ?: pkg ?: "плеера нет"}", false)
        if (st.playing) return
        if (st.hasSession) MusicController.playPause(model.context, null) else pkg?.let { MusicController.startPlayer(model.context, it) }
    }

    /** "Phone Player" on the chip: without the selected application, any active session plays. */
    fun choosePhonePlayer() { model.chooseMusicPlayer(null) }

    /** The selected player is remembered and becomes current: it plays immediately if it has a session or MediaBrowserService. */
    fun choosePlayer(pkg: String) {
        model.chooseMusicPlayer(pkg)
        // your player from the list of all players becomes the first chip slot
        if (pkg !in PlayerChip.ONLINE) model.chooseLocalPlayer(pkg)
        MusicController.startPlayer(model.context, pkg) // main thread: MediaBrowser creates Handler, connect asynchronous
        home()
    }

    /** Reiki navigation unit and card tap: Navigator for the entire area to the right of the rail. */
    fun openNavigator() {
        if (!model.launchDebounce.allow(model.now())) return
        if (model.navigatorPkg == null) openNavigatorChoice() else openNavigatorWindow()
    }

    /** The whole area navigator. Repeated tap, when it is already deployed, rechecks its task (after Back it could go away). */
    private fun openNavigatorWindow() {
        if (model.area == Area.Navigator) EmbedHost.refresh(EmbedHost.nav) else model.go(Area.Navigator)
    }

    fun openNavigatorChoice() {
        model.settingsPage = SettingsPage.NAVIGATOR
        model.aboutLicense = null
        model.go(Area.Settings)
    }

    /**
     * Button "Back" rails: Back in the open window (if the application has left the root - on the main); from the screen launcher - on
     * previous screen or main (in "About the application" first step inside the settings); on the main nothing.
     */
    fun embeddedBack() {
        AppLog.i("LAUNCHER Назад на рейке: экран=${model.area}", false)
        when (model.area) {
            is Area.App -> EmbedHost.back(EmbedHost.app) { backClosed(EmbedHost.app) }
            Area.Navigator -> EmbedHost.back(EmbedHost.nav) { backClosed(EmbedHost.nav) }
            Area.Home -> Unit
            Area.Settings -> if (!model.settingsBack()) model.back()
            else -> model.back()
        }
    }

    /** Back took the application out of the root (no problem on the display): if it is still on the screen - on the main one. */
    private fun backClosed(slot: EmbedSlot) {
        val a = model.area
        val shown = (slot === EmbedHost.app && a is Area.App) || (slot === EmbedHost.nav && a == Area.Navigator)
        AppLog.i("LAUNCHER Back закрыл ${slot.name}, экран=$a${if (shown) ", возврат на главный" else ", экран уже другой"}", false)
        if (shown) { model.launchLock.finish(); model.starting = null; home() }
    }

    /** The slot reported that the application tasks are no more (process killed, Back from root, window lost): the layer is removed. */
    internal fun embedGone(slot: EmbedSlot) {
        if (model.foreign != null) { if (slot === EmbedHost.app) ForeignActions.gone(); return }
        backClosed(slot)
    }

    /** Hardware Back head unit - the same as "Back" on the rail (in the mode of someone else's launcher - ◀ panel). */
    fun hardwareBack() {
        AppLog.i("LAUNCHER аппаратный Back: экран=${model.area}", false)
        if (model.foreign != null) { ForeignActions.press(ForeignDesktop.NavKey.BACK); return }
        embeddedBack()
    }
}
