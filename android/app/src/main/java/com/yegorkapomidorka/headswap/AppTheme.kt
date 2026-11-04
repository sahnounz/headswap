package com.yegorkapomidorka.headswap

import android.content.Context
import android.content.res.Configuration
import com.yegorkapomidorka.headswap.ui.UiPrefs

/**
 * The theme of the interface: "system" (by default, follows the system), "light", "medium" (neutral dark gray), "dark".
 * The choice is stored in prefs "ui" (UiPrefs.theme). MainActivity Turns the context into [wrap] in attachBaseContext:
 * light forces uiMode night=NO, medium and dark - night=YES (medium then replaces the colors with *_mid via [res]).
 */
object AppTheme {
    const val SYSTEM = "system"
    const val LIGHT = "light"
    const val MEDIUM = "medium"
    const val DARK = "dark"
    val MODES = listOf(SYSTEM, LIGHT, MEDIUM, DARK)

    /** Included "medium": Context.clr() and resource links go through [res]. Exhibited in [wrap]. */
    @Volatile
    var medium = false
        private set

    fun get(ctx: Context): String = UiPrefs(ctx).theme.let { if (it in MODES) it else SYSTEM }

    fun set(ctx: Context, mode: String) {
        UiPrefs(ctx).theme = if (mode in MODES) mode else SYSTEM
    }

    /** Context with forced night- mode; locale from already wrapped [base] is saved (copy of its configuration). */
    fun wrap(base: Context): Context {
        val mode = get(base)
        medium = mode == MEDIUM
        val night = when (mode) {
            LIGHT -> Configuration.UI_MODE_NIGHT_NO
            MEDIUM, DARK -> Configuration.UI_MODE_NIGHT_YES
            else -> return base
        }
        val cfg = Configuration(base.resources.configuration)
        cfg.uiMode = (cfg.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
        return base.createConfigurationContext(cfg)
    }

    /** Resource for the current topic: in the "average" version *_mid, otherwise the same id. Clear map, not getIdentifier (it will cut shrinkResources). */
    fun res(id: Int): Int = if (medium) MID[id] ?: id else id

    private val MID: Map<Int, Int> = mapOf(
        R.color.bg to R.color.bg_mid,
        R.color.surf to R.color.surf_mid,
        R.color.surf2 to R.color.surf2_mid,
        R.color.on to R.color.on_mid,
        R.color.on2 to R.color.on2_mid,
        R.color.line to R.color.line_mid,
        R.color.pri to R.color.pri_mid,
        R.color.onpri to R.color.onpri_mid,
        R.color.acc to R.color.acc_mid,
        R.color.pric to R.color.pric_mid,
        R.color.onpric to R.color.onpric_mid,
        R.color.ok to R.color.ok_mid,
        R.color.okc to R.color.okc_mid,
        R.color.onok to R.color.onok_mid,
        R.color.warn to R.color.warn_mid,
        R.color.warnc to R.color.warnc_mid,
        R.color.onwarnc to R.color.onwarnc_mid,
        R.color.err to R.color.err_mid,
        R.color.errc to R.color.errc_mid,
        R.color.onerr to R.color.onerr_mid,
        R.color.ripple to R.color.ripple_mid,
        R.color.radio_tint to R.color.radio_tint_mid,
        R.drawable.switch_thumb to R.drawable.switch_thumb_mid,
        R.drawable.switch_track to R.drawable.switch_track_mid,
        R.drawable.ic_ring_active to R.drawable.ic_ring_active_mid,
    )
}
