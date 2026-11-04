package com.yegorkapomidorka.headswap

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * Application interface language: "system" (default), "en" or "ru".
 * Android 13+: System language per-app (LocaleManager, also visible in system settings, see res/xml/locales_config.xml).
 * Android 10–12: selection is stored in prefs, contexts of activity/services are wrapped through [wrap] in attachBaseContext.
 */
object AppLocale {
    const val SYSTEM = "system"
    val TAGS = listOf(SYSTEM, "en", "ru")

    private const val PREFS = "app_locale"
    private const val KEY = "tag"

    /** Current selection: 33+ from LocaleManager (it could be changed in system settings), below from prefs. */
    fun get(ctx: Context): String {
        if (Build.VERSION.SDK_INT >= 33) {
            val list = ctx.getSystemService(LocaleManager::class.java).applicationLocales
            if (list.isEmpty) return SYSTEM
            return list[0].language.takeIf { it in TAGS } ?: SYSTEM
        }
        return stored(ctx)
    }

    /** Change language. On 33+, the system itself recreates activity; below caller does recreate(). */
    fun set(ctx: Context, tag: String) {
        val t = if (tag in TAGS) tag else SYSTEM
        if (Build.VERSION.SDK_INT >= 33) {
            ctx.getSystemService(LocaleManager::class.java).applicationLocales =
                if (t == SYSTEM) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(t)
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, t).apply()
    }

    /** Do I need a manual recreate() after [set]. */
    val needsRecreate: Boolean get() = Build.VERSION.SDK_INT < 33

    private fun stored(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, SYSTEM) ?: SYSTEM

    /**
     * Context with the selected language for Android 10–12 (on 33+ the system has already substituted per-app language, returns as is).
     * Use in attachBaseContext and where lines are taken from applicationContext.
     */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return base
        val tag = stored(base)
        if (tag == SYSTEM) return base
        val locale = Locale.forLanguageTag(tag)
        val cfg = Configuration(base.resources.configuration)
        cfg.setLocales(LocaleList(locale))
        return base.createConfigurationContext(cfg)
    }
}
