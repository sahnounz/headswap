package com.yegorkapomidorka.headswap.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.yegorkapomidorka.headswap.AppLog
import com.yegorkapomidorka.headswap.desktop.DesktopAppCatalog
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** What is shown in the area to the right of the reiki. Reiki remains always: it is the way home. */
sealed interface Area {
    data object Home : Area
    data object AllApps : Area
    data object Picker : Area
    data object Settings : Area
    data object Calls : Area
    /** List of media applications (player switch on music card). */
    data object Players : Area
    /** Navigator deployed to the entire area. */
    data object Navigator : Area
    /** A foreign application in the child window. */
    data class App(val ref: AppRef) : Area
}

/**
 * The state of launcher lives on, not in Activity: child windows and app list survive
 * Activity re-creation (rotation, configuration change head unit).
 */
internal object LauncherModel {
    private lateinit var appCtx: Context
    private lateinit var prefs: LauncherPrefs
    private var ready = false
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "headswap-launcher-io").apply { isDaemon = true } }

    val context: Context get() = appCtx

    var area by mutableStateOf<Area>(Area.Home)
    var theme by mutableStateOf(ThemeChoice.DARK)
    var mapLeft by mutableStateOf(false)
    var animations by mutableStateOf(true)
    var autoplay by mutableStateOf(true)
    var localPlayer by mutableStateOf<String?>(null)
    var onlinePlayer by mutableStateOf<String?>(null)
    /** Theme mode head unit (CHANGE_MD_THEME, 0 day, 1 night, 2 car); null - head unit has not yet sent. Updates [LauncherRoot]. */
    var huTheme by mutableStateOf<Int?>(null)
    /** Display, on which the music has already been turned on automatically: autorun once on your desktop. */
    var autoplayedDisplay = -1
    /** Whether the palette is light now: the choice of "Theme" and, for "Auto", the headlight mode of the car. */
    val lightTheme: Boolean get() = ThemeChoice.light(theme, huTheme)
    var cardView by mutableStateOf(CardView.NAVIGATOR)
    var apps by mutableStateOf<List<AppRef>>(emptyList())
    var savedNavigator by mutableStateOf<String?>(null)
    var musicPlayer by mutableStateOf<String?>(null)
    var lastOnlinePlayer by mutableStateOf<String?>(null)
    var catalog by mutableStateOf<List<DesktopAppCatalog.Entry>>(emptyList())
    var catalogLoaded by mutableStateOf(false)
    var status by mutableStateOf(StatusInfo())
    /** Confirming the removal of an application from the list (“Remove?”). */
    var confirmRemove by mutableStateOf<AppRef?>(null)
    /** Area to the right of the rail and navigation card - in the coordinates of the root Compose. */
    var areaRect by mutableStateOf<Rect?>(null)
    var navCardRect by mutableStateOf<Rect?>(null)

    /** Alien launcher from DexPrefs.desktopComponent in the window above the navigation bar; null is our desktop. Led by [ForeignActions]. */
    var foreign by mutableStateOf<AppRef?>(null)
    /** Overlay "Recent" on the screen. */
    var recentsOpen by mutableStateOf(false)
    /** Overlay applications; null - list still requested. */
    var recents by mutableStateOf<List<AppRef>?>(null)
    /** The packages seen on the head unit screen in this session are fresh first ([ForeignDesktop.mergeHistory]). */
    var recentsHistory: List<String> = emptyList()
    /** The last choice from DexPrefs, which has already been disassembled (empty line - not yet disassembled, null - our table). */
    var foreignSeen: String? = ""
    /** The [ForeignDesktop.brokenKey] rollback mark: this launcher on this screen head unit did not start. No writing is written in prefs. */
    var foreignBroken: String? = null
    /** Screen head unit, for which the story is collected. */
    var foreignDisplay = -1

    /** The key "package / class" of the application in the application window; null - has never been opened. */
    var appKey by mutableStateOf<String?>(null)
    /** The settings page from which to open the screen (selecting a navigator by tap on an empty card). */
    var settingsPage by mutableStateOf(SettingsPage.LOOK)
    /** License (index in About.licenses), whose full text is open in "About the application"; null - list. */
    var aboutLicense by mutableStateOf<Int?>(null)

    /** "Back" inside the settings (see. [BackNav.settingsStep]); false - no steps, get off the screen. */
    fun settingsBack(): Boolean {
        val (page, licenseOpen) = BackNav.settingsStep(settingsPage, aboutLicense != null) ?: return false
        settingsPage = page
        if (!licenseOpen) aboutLicense = null
        return true
    }
    /** Display density head unit: child windows live with it. */
    var densityDpi = 160
    /** The "All Apps" page is remembered between visits. */
    var allAppsPage = 0

    val launchDebounce = Debounce()
    val launchLock = LaunchLock()
    /** The application that is now starting: under "Start <name>...". */
    var starting by mutableStateOf<String?>(null)

    fun init(ctx: Context) {
        if (ready) return
        appCtx = ctx.applicationContext
        prefs = LauncherPrefs(appCtx)
        theme = prefs.theme
        mapLeft = prefs.mapLeft
        animations = prefs.animations
        autoplay = prefs.autoplay
        localPlayer = prefs.localPlayer
        onlinePlayer = prefs.onlinePlayer
        cardView = prefs.cardView
        apps = prefs.apps
        savedNavigator = prefs.navigator
        musicPlayer = prefs.musicPlayer
        lastOnlinePlayer = prefs.lastOnlinePlayer
        missedSeenMs = prefs.missedSeenMs
        EmbedHost.onGone = { LauncherActions.embedGone(it) }
        ready = true
        reloadCatalog()
    }

    fun reloadCatalog() {
        io.execute {
            val pm = appCtx.packageManager
            val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val found = runCatching {
                pm.queryIntentActivities(query, 0).mapNotNull { info ->
                    val a = info.activityInfo ?: return@mapNotNull null
                    DesktopAppCatalog.Entry(a.packageName, a.name,
                        runCatching { info.loadLabel(pm).toString() }.getOrDefault(a.packageName),
                        enabled = a.enabled && a.applicationInfo.enabled, exported = a.exported)
                }
            }.getOrElse { AppLog.i("Launcher catalog: ${it.javaClass.simpleName}", false); emptyList() }
            val selected = DesktopAppCatalog.select(found, appCtx.packageName)
            main.post { catalog = selected; catalogLoaded = true }
        }
    }

    fun packageLabel(pkg: String): String =
        runCatching { appCtx.packageManager.getApplicationLabel(appCtx.packageManager.getApplicationInfo(pkg, 0)).toString() }
            .getOrDefault(pkg)

    fun loadPackageIcon(pkg: String): ImageBitmap? {
        icons["pkg:$pkg"]?.let { return it }
        val drawable = runCatching { appCtx.packageManager.getApplicationIcon(pkg) }.getOrNull() ?: return null
        return drawableToImage(drawable).also { icons["pkg:$pkg"] = it }
    }

    private fun drawableToImage(drawable: android.graphics.drawable.Drawable): ImageBitmap {
        val size = 144
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(Canvas(bmp))
        return bmp.asImageBitmap()
    }

    fun label(ref: AppRef): String? = catalog.firstOrNull { it.packageName == ref.pkg && it.className == ref.cls }?.label

    /** User list without applications removed from the system. */
    val visibleApps: List<AppRef>
        get() = if (!catalogLoaded) apps else apps.filter { label(it) != null }

    fun addApp(ref: AppRef) { apps = AppList.add(apps, ref).also { prefs.apps = it } }
    fun removeApp(ref: AppRef) { apps = AppList.remove(apps, ref).also { prefs.apps = it } }

    fun chooseTheme(value: ThemeChoice) { theme = value; prefs.theme = value }
    fun chooseMapLeft(left: Boolean) { mapLeft = left; prefs.mapLeft = left }
    fun chooseAnimations(on: Boolean) { animations = on; prefs.animations = on }
    fun chooseAutoplay(on: Boolean) { autoplay = on; prefs.autoplay = on }
    fun chooseLocalPlayer(pkg: String?) { localPlayer = pkg; prefs.localPlayer = pkg }
    fun chooseOnlinePlayer(pkg: String?) { onlinePlayer = pkg; prefs.onlinePlayer = pkg }
    fun nextCardView() { cardView = cardView.next().also { prefs.cardView = it } }
    fun setNavigator(pkg: String?) { savedNavigator = pkg; prefs.navigator = pkg }
    fun chooseMusicPlayer(pkg: String?) { musicPlayer = pkg; prefs.musicPlayer = pkg; noteOnlinePlayer(pkg) }
    /** Choose or hear [pkg]: if it is an online player, it becomes the second chip slot. */
    fun noteOnlinePlayer(pkg: String?) {
        val next = PlayerChip.lastOnline(lastOnlinePlayer, pkg)
        if (next != lastOnlinePlayer) { lastOnlinePlayer = next; prefs.lastOnlinePlayer = next }
    }
    /** Missed not newer than this moment viewed: the counter is reset after tap on the card. */
    var missedSeenMs by mutableLongStateOf(0L)
        private set
    fun markMissedSeen() {
        val now = System.currentTimeMillis()
        missedSeenMs = now
        prefs.missedSeenMs = now
    }

    /** Installed known navigators (according to the application directory with launcher input). */
    val installedPackages: Set<String> get() = catalog.mapTo(HashSet()) { it.packageName }
    val navigatorPkg: String? get() = NavigatorPick.effective(savedNavigator, installedPackages)
    val installedNavigators: List<String> get() = NavigatorPick.KNOWN.filter { it in installedPackages }

    /** Previous: launcher screen for “Back”; after the “Back” itself is empty, so as not to walk in a circle. */
    var previous: Area? = null
        private set

    fun go(next: Area) {
        if (next != area) {
            AppLog.i("LAUNCHER экран: $area -> $next", false)
            previous = BackNav.remembered(area)
        }
        area = next
    }
    fun home() { go(Area.Home); previous = null }

    /** “Back” from the launcher: screen to the previous screen, if it was not the main one, otherwise to the main one. */
    fun back() {
        val target = BackNav.target(area, previous)
        go(target)
        previous = null
    }

    fun now(): Long = SystemClock.uptimeMillis()

    // --- app icons ---

    private val icons = ConcurrentHashMap<String, ImageBitmap>()

    fun cachedIcon(ref: AppRef): ImageBitmap? = icons[ref.key]

    /** Call not from UI-flow: package icon can be loaded through another process. */
    fun loadIcon(ref: AppRef): ImageBitmap? {
        icons[ref.key]?.let { return it }
        val drawable = runCatching { appCtx.packageManager.getActivityIcon(ComponentName(ref.pkg, ref.cls)) }.getOrNull()
            ?: return null
        return drawableToImage(drawable).also { icons[ref.key] = it }
    }
}
