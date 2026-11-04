package com.yegorkapomidorka.headswap.launcher

import kotlin.math.max

/** Pages instead of scroll: no swipes, no scrolling (touch by head unit comes with a delay). */
object Pager {
    fun pageCount(total: Int, perPage: Int): Int =
        if (perPage <= 0 || total <= 0) 1 else (total + perPage - 1) / perPage

    fun clamp(page: Int, total: Int, perPage: Int): Int = page.coerceIn(0, pageCount(total, perPage) - 1)

    fun <T> slice(items: List<T>, page: Int, perPage: Int): List<T> {
        if (perPage <= 0) return emptyList()
        val from = clamp(page, items.size, perPage) * perPage
        return items.subList(from.coerceAtMost(items.size), (from + perPage).coerceAtMost(items.size))
    }
}

/** How many columns and rows fit into the area with a minimum cell. */
object GridPlan {
    data class Plan(val cols: Int, val rows: Int) { val perPage: Int get() = cols * rows }

    fun of(availW: Int, availH: Int, minCellW: Int, minCellH: Int, gap: Int): Plan =
        Plan(max(1, (availW + gap) / (minCellW + gap)), max(1, (availH + gap) / (minCellH + gap)))
}

enum class Press { TAP, LONG }

/** Tap or long tap: count by time between DOWN and UP, not by timer (on head unit UP comes with delay). */
object PressLogic {
    const val LONG_MS = 600L
    const val DEBOUNCE_MS = 400L
    const val COLD_START_MS = 8000L

    fun classify(downMs: Long, upMs: Long, allowLong: Boolean): Press =
        if (allowLong && upMs - downMs >= LONG_MS) Press.LONG else Press.TAP
}

/** Anti-drill launch buttons: the second tap closer to gapMs to the first is ignored. */
class Debounce(private val gapMs: Long = PressLogic.DEBOUNCE_MS) {
    private var last = Long.MIN_VALUE

    fun allow(now: Long): Boolean {
        if (last != Long.MIN_VALUE && now - last in 0 until gapMs) return false
        last = now
        return true
    }
}

/** Cold start app: block restart until it has appeared or passed timeoutMs. */
class LaunchLock(private val timeoutMs: Long = PressLogic.COLD_START_MS) {
    var key: String? = null
        private set
    private var since = 0L

    fun begin(key: String, now: Long) { this.key = key; since = now }
    fun finish() { key = null }
    fun isLocked(now: Long): Boolean {
        if (key == null) return false
        if (now - since >= timeoutMs) { key = null; return false }
        return true
    }
}

/** The user application in the launcher: component list is not a package (the package can have multiple inputs). */
data class AppRef(val pkg: String, val cls: String) {
    val key: String get() = "$pkg/$cls"

    companion object {
        fun parse(key: String): AppRef? {
            val i = key.indexOf('/')
            if (i <= 0 || i == key.length - 1) return null
            return AppRef(key.substring(0, i), key.substring(i + 1))
        }
    }
}

/** Persistent list of applications: order is important, no duplicates. */
object AppList {
    fun encode(list: List<AppRef>): String = list.joinToString("\n") { it.key }
    fun decode(raw: String?): List<AppRef> =
        raw.orEmpty().split('\n').mapNotNull { AppRef.parse(it.trim()) }.distinct()

    fun add(list: List<AppRef>, ref: AppRef): List<AppRef> = if (ref in list) list else list + ref
    fun remove(list: List<AppRef>, ref: AppRef): List<AppRef> = list - ref
    /** The bottom row of the main = first n applications list. */
    fun quick(list: List<AppRef>, n: Int): List<AppRef> = list.take(n)
}

/**
 * Lists of application screens from the catalog installed (order and filter of our package has already made DesktopAppCatalog).
 * "All applications" - the entire directory, the choice for the bottom row - a directory without favorites.
 */
object CatalogLists {
    fun all(catalog: List<AppRef>): List<AppRef> = catalog.distinct()
    fun pickable(catalog: List<AppRef>, favorites: List<AppRef>): List<AppRef> = catalog.distinct().filter { it !in favorites }
}

/** Select the default navigator and check saved. */
object NavigatorPick {
    const val YANDEX_NAVI = "ru.yandex.yandexnavi"
    val KNOWN = listOf(YANDEX_NAVI, "ru.yandex.yandexmaps", "ru.dublgis.dgismobile",
        "com.google.android.apps.maps", "com.waze", "app.organicmaps")

    fun defaultFor(installed: Set<String>): String? = KNOWN.firstOrNull { it in installed }

    /** Saved while it is installed; otherwise by default. null - there is nothing to choose. */
    fun effective(saved: String?, installed: Set<String>): String? =
        saved?.takeIf { it in installed } ?: defaultFor(installed)
}

/** Answers taskState child display and solutions (EmbedHost): what to do after Back and when re-display. */
object TaskCheck {
    const val UNKNOWN = -1
    const val NONE = 0
    const val BACKGROUND = 1
    const val TOP = 2
    /** The application remained the first task, but not visible: Back made of root moveTaskToBack. */
    const val MOVED_TO_BACK = 3

    /** Pause before each task check after Back: 300, 700, 1100, 1500 ms from Back. */
    val BACK_CHECKS = longArrayOf(300, 400, 400, 400)
    /** Longer after Back, the touch is not blocked, even if the state is unknown: a black screen without clicks is worse than an extra touch. */
    const val BACK_BLOCK_MS = 1000L

    /** UNKNOWN: all checks unanswered (-1), believe the application has gone from root; caller covers the layer as in CLOSED. */
    enum class AfterBack { CLOSED, STAYED_MORE, STAYED, UNKNOWN_MORE, UNKNOWN }

    /** The result of the next check after Back; [last] - the check was the last. */
    fun afterBack(state: Int, last: Boolean): AfterBack = when {
        state == NONE || state == BACKGROUND || state == MOVED_TO_BACK -> AfterBack.CLOSED
        state == TOP -> if (last) AfterBack.STAYED else AfterBack.STAYED_MORE
        last -> AfterBack.UNKNOWN
        else -> AfterBack.UNKNOWN_MORE
    }

    /** The first second does not go through the window. Back ([backAt] - uptime Back, 0 - Back There was not). */
    fun blockedAfterBack(now: Long, backAt: Long): Boolean = backAt != 0L && now - backAt in 0 until BACK_BLOCK_MS

    /** The window showed again: run the application again if it is not on top; -1 counts only after Back with an unknown result. */
    fun needsRelaunch(state: Int, stale: Boolean): Boolean =
        state == NONE || state == BACKGROUND || state == MOVED_TO_BACK || (state < 0 && stale)

    /** The application disappeared from the display: “no problem” after it was already on top (before that, it could simply not appear). */
    fun vanished(state: Int, seenTop: Boolean): Boolean = state == NONE && seenTop
}

/**
 * "Back" on launcher screens (without the "Home" button): on the previous launcher screen, if it was not the main one, otherwise on the main one.
 * The same navigation rail button that opened the screen (logo, gear), when you press it again, also leads back.
 */
object BackNav {
    /** launcher screens where you can go back; built-in applications and the main one are not remembered. */
    fun isScreen(area: Area): Boolean =
        area == Area.AllApps || area == Area.Picker || area == Area.Settings || area == Area.Calls || area == Area.Players

    /** What to remember as "previous" when leaving with [from]. */
    fun remembered(from: Area): Area? = from.takeIf(::isScreen)

    /** Where to go Back from the screen [current]; [previous] - a memorable previous screen. */
    fun target(current: Area, previous: Area?): Area = previous?.takeIf { isScreen(it) && it != current } ?: Area.Home

    /**
     * Step "Back" inside the settings: license text -> list of licenses -> "System". The result is a page and whether the text is open;
     * null - there are no steps inside the settings, "Back" comes from the settings screen.
     */
    fun settingsStep(page: SettingsPage, licenseOpen: Boolean): Pair<SettingsPage, Boolean>? = when {
        page != SettingsPage.ABOUT -> null
        licenseOpen -> SettingsPage.ABOUT to false
        else -> SettingsPage.SYSTEM to false
    }

    /** The “back” is only on the main thing: there is nowhere to go. */
    fun enabled(current: Area): Boolean = current != Area.Home
}

/**
 * Chip players on the music card: two slots - your phone player (any active session) and the latest online player
 * (Ya.Music, YouTube Music, VK Music - that listened last, otherwise the first installed). Other media applications -
 * long tap on "note" (player list).
 */
object PlayerChip {
    const val YANDEX = "ru.yandex.music"
    const val YT_MUSIC = "com.google.android.apps.youtube.music"
    const val VK = "com.uma.musicvk"
    /** Online players of the second slot; order is the default choice until they have listened to nothing. */
    val ONLINE = listOf(YANDEX, YT_MUSIC, VK)

    enum class Kind { PHONE, YANDEX, YT_MUSIC, VK }
    data class Slot(val kind: Kind, val pkg: String?, val selected: Boolean)

    private fun kindOf(pkg: String): Kind = when (pkg) {
        YANDEX -> Kind.YANDEX
        YT_MUSIC -> Kind.YT_MUSIC
        else -> Kind.VK
    }

    /**
     * @param local the first slot from the settings (its player); null - any active session
     * @param fixedOnline the second slot from the settings; null - "The Last"
     */
    fun slots(installed: Set<String>, chosen: String?, lastOnline: String?, local: String? = null, fixedOnline: String? = null): List<Slot> =
        buildList {
            add(Slot(Kind.PHONE, local, chosen == null || (local != null && chosen == local)))
            val online = onlineSlot(installed, lastOnline, fixedOnline)
            if (online != null) add(Slot(kindOf(online), online, chosen == online))
        }

    /** Second slot package: fixed in the settings, otherwise last heard, otherwise first installed. */
    fun onlineSlot(installed: Set<String>, lastOnline: String?, fixedOnline: String?): String? =
        fixedOnline?.takeIf { it in installed && it in ONLINE }
            ?: lastOnline?.takeIf { it in installed && it in ONLINE } ?: ONLINE.firstOrNull { it in installed }

    /** Your players for the first slot: everything found on the phone, except for the online services of the second. */
    fun localPlayers(players: List<String>): List<String> = players.filter { it !in ONLINE }

    /** The last online player after choosing or hearing [pkg]: changes only if it is an online player. */
    fun lastOnline(previous: String?, pkg: String?): String? = pkg?.takeIf { it in ONLINE } ?: previous
}

/**
 * Setup screen grid: 5 rows at full height, common to the section and card column on the right - borders match
 * (Egor's main requirement). The card on the span series takes from top(start) to bottom(start + span - 1).
 */
object SettingsGrid {
    const val ROWS = 5
    fun top(row: Int, height: Int, gap: Int): Int = Math.round(row * (height + gap).toDouble() / ROWS).toInt()
    fun bottom(row: Int, height: Int, gap: Int): Int = top(row + 1, height, gap) - gap
}

/** Network type signature in rail. Types - constants TelephonyManager.NETWORK_TYPE_*. */
object NetLabel {
    const val NONE = "нет"
    private const val NR = 20
    private const val LTE = 13
    private val G3 = setOf(3, 8, 9, 10, 15, 17)
    private val G2 = setOf(1, 2, 4, 7, 11, 16)

    fun cellular(type: Int?): String = when {
        type == NR -> "5G"
        type == LTE -> "4G"
        type in G3 -> "3G"
        type in G2 -> "2G"
        else -> "4G"
    }
}

/** View of the central card at the navigation location (the icon "layers" switches in a circle). */
enum class CardView { NAVIGATOR, CLOCK;
    fun next(): CardView = entries[(ordinal + 1) % entries.size]

    companion object {
        fun restore(name: String?): CardView = entries.firstOrNull { it.name == name } ?: NAVIGATOR
    }
}
