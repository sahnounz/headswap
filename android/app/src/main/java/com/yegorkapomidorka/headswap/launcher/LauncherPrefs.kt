package com.yegorkapomidorka.headswap.launcher

import android.content.Context

/** launcher: theme dark or light gold; "Auto" - according to the headlights of the machine (CHANGE_MD_THEME from head unit). */
enum class ThemeChoice {
    DARK, LIGHT, AUTO;

    companion object {
        /** Old accent themes (GOLD, GOLD_DEEP, GOLD_LIGHT - gold on dark) become dark gold. */
        fun restore(name: String?): ThemeChoice = entries.firstOrNull { it.name == name } ?: DARK

        /**
         * Whether the palette is light. "Auto" is light only according to the daily mode head unit (themeModes 0); night, "auto" head unit and
         * "head unit hasn't sent anything yet" - dark.
         */
        fun light(choice: ThemeChoice, huTheme: Int?): Boolean = when (choice) {
            DARK -> false
            LIGHT -> true
            AUTO -> huTheme == 0
        }
    }
}

/** Settings launcher - SharedPreferences, like the rest of the project (DexPrefs/AaSettings). */
class LauncherPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("headswap_launcher", Context.MODE_PRIVATE)

    var apps: List<AppRef>
        get() = AppList.decode(prefs.getString("apps", null))
        set(value) { prefs.edit().putString("apps", AppList.encode(value)).apply() }

    var theme: ThemeChoice
        get() = ThemeChoice.restore(prefs.getString("theme", null))
        set(value) { prefs.edit().putString("theme", value.name).apply() }

    /** The "map on the main" is on the left: the rows of the main mirror, the rail remains on the left. New key: the old right_hand moved the rail. */
    var mapLeft: Boolean
        get() = prefs.getBoolean("map_left", false)
        set(value) { prefs.edit().putBoolean("map_left", value).apply() }

    /** "Reiki button animations". */
    var animations: Boolean
        get() = prefs.getBoolean("animations", true)
        set(value) { prefs.edit().putBoolean("animations", value).apply() }

    /** “Include music automatically”: at the start of the session, immediately plays like in Android Auto. */
    var autoplay: Boolean
        get() = prefs.getBoolean("autoplay", true)
        set(value) { prefs.edit().putBoolean("autoplay", value).apply() }

    /** The first chip player slot: its player on the phone; null is the first found. */
    var localPlayer: String?
        get() = prefs.getString("local_player", null)
        set(value) { prefs.edit().putString("local_player", value).apply() }

    /** The second slot: a fixed online player; null - "The Last" (which was listened to last). */
    var onlinePlayer: String?
        get() = prefs.getString("online_player", null)
        set(value) { prefs.edit().putString("online_player", value).apply() }

    var cardView: CardView
        get() = CardView.restore(prefs.getString("card_view", null))
        set(value) { prefs.edit().putString("card_view", value.name).apply() }

    /** The package of the selected navigator; null - by default ([NavigatorPick]). */
    var navigator: String?
        get() = prefs.getString("navigator", null)
        set(value) { prefs.edit().putString("navigator", value).apply() }

    /** Package of current music player. */
    var musicPlayer: String?
        get() = prefs.getString("music_player", null)
        set(value) { prefs.edit().putString("music_player", value).apply() }

    /** The last online player (the second slot of the chip of players): what was listened to or chosen last. */
    var lastOnlinePlayer: String?
        get() = prefs.getString("last_online_player", null)
        set(value) { prefs.edit().putString("last_online_player", value).apply() }

    /** Missed no newer than this moment has already been viewed (tap on the "Phone" card). */
    var missedSeenMs: Long
        get() = prefs.getLong("missed_seen", 0L)
        set(value) { prefs.edit().putLong("missed_seen", value).apply() }
}
