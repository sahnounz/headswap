package com.yegorkapomidorka.headswap.ui

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import com.yegorkapomidorka.headswap.MainActivity

/** Screen inside MainActivity: itself View, update times in 500 ms while visible, reaction to "back". */
abstract class Screen(val host: MainActivity) {
    val ctx: Context get() = host
    abstract val view: View

    /** The screen became visible (including after returning from system settings). */
    open fun onShow() {}

    open fun onHide() {}

    /** Each 500 ms, as long as the screen is visible. */
    open fun tick() {}

    /** true = backstroke processed by screen. */
    open fun onBack(): Boolean = false

    open fun onPermissionResult() {}

    /** The result of the third-party activity/dialogue (the choice of the machine through CDM) has returned. */
    open fun onActivityResultDone() {}

    /** Debugging (adb --ei scroll_y N): Scroll the screen to remove the bottom. */
    fun scrollTo(y: Int) {
        fun find(v: View): ScrollView? {
            if (v is ScrollView) return v
            if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i))?.let { return it }
            return null
        }
        find(view)?.let { sv -> sv.post { sv.scrollTo(0, y) } }
    }
}

val Context.isNight: Boolean
    get() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

/** The UI: onboarding itself is passed, manual steps marks that cannot be determined. */
class UiPrefs(ctx: Context) {
    private val sp: SharedPreferences = ctx.getSharedPreferences("ui", Context.MODE_PRIVATE)

    var onboardingDone: Boolean
        get() = sp.getBoolean("onboardingDone", false)
        set(v) = sp.edit().putBoolean("onboardingDone", v).apply()

    /** The “Opt-out window” (2.6.0) has already been shown or it is not needed (the newcomer passed onboarding). */
    var a11yNoticeShown: Boolean
        get() = sp.getBoolean("a11yNoticeShown", false)
        set(v) = sp.edit().putBoolean("a11yNoticeShown", v).apply()

    /** The "Direct passthrough Video" window (3.0) has already been shown or it is not needed (the newcomer has passed onboarding). */
    var v30NoticeShown: Boolean
        get() = sp.getBoolean("v30NoticeShown", false)
        set(v) = sp.edit().putBoolean("v30NoticeShown", v).apply()

    /** The “launcher on tape” window (4.0) has already been shown or is not needed (newer, Android below 13). */
    var v40NoticeShown: Boolean
        get() = sp.getBoolean("v40NoticeShown", false)
        set(v) = sp.edit().putBoolean("v40NoticeShown", v).apply()

    /** Interface theme: system / light / medium / dark (see AppTheme). */
    var theme: String
        get() = sp.getString("theme", "system") ?: "system"
        set(v) = sp.edit().putString("theme", v).apply()

    fun isManualDone(id: String): Boolean = sp.getBoolean("manual_$id", false)

    fun setManualDone(id: String, v: Boolean) = sp.edit().putBoolean("manual_$id", v).apply()
}
