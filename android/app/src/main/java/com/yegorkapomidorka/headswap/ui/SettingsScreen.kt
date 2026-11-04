package com.yegorkapomidorka.headswap.ui

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.yegorkapomidorka.headswap.AppLocale
import com.yegorkapomidorka.headswap.AppLog
import com.yegorkapomidorka.headswap.AppTheme
import com.yegorkapomidorka.headswap.BridgeService
import com.yegorkapomidorka.headswap.BuildConfig
import com.yegorkapomidorka.headswap.CarLifeState
import com.yegorkapomidorka.headswap.MainActivity
import com.yegorkapomidorka.headswap.R
import com.yegorkapomidorka.headswap.aa.AaDensity
import com.yegorkapomidorka.headswap.aa.AaPrefs
import com.yegorkapomidorka.headswap.aa.BridgeMode
import com.yegorkapomidorka.headswap.aa.CAR_MAKES
import com.yegorkapomidorka.headswap.aa.CallStateSource
import com.yegorkapomidorka.headswap.aa.CarPreset
import com.yegorkapomidorka.headswap.aa.CustomVideoMode
import com.yegorkapomidorka.headswap.aa.EQ_BAND_MAX
import com.yegorkapomidorka.headswap.aa.EQ_BAND_MIN
import com.yegorkapomidorka.headswap.aa.EQ_FREQS
import com.yegorkapomidorka.headswap.aa.EQ_PREAMP_MAX
import com.yegorkapomidorka.headswap.aa.EQ_PREAMP_MIN
import com.yegorkapomidorka.headswap.aa.EqPreset
import com.yegorkapomidorka.headswap.aa.EqSettings
import com.yegorkapomidorka.headswap.aa.FpsSetting
import com.yegorkapomidorka.headswap.aa.MicMode
import com.yegorkapomidorka.headswap.aa.MusicRate
import com.yegorkapomidorka.headswap.aa.VideoMode
import com.yegorkapomidorka.headswap.desktop.DesktopAppCatalog
import com.yegorkapomidorka.headswap.dex.DelegatedDesktopAccess
import com.yegorkapomidorka.headswap.dex.DesktopPick
import com.yegorkapomidorka.headswap.dex.DexPrefs
import com.yegorkapomidorka.headswap.link.car.CarNetwork
import com.yegorkapomidorka.headswap.link.car.CarPairing
import com.yegorkapomidorka.headswap.link.car.CarProfile
import com.yegorkapomidorka.headswap.link.car.CarWifi
import com.yegorkapomidorka.headswap.link.diag.NetProbe
import com.yegorkapomidorka.headswap.update.UpdatePrefs
import com.yegorkapomidorka.headswap.update.Updater
import java.text.NumberFormat

/** The hat is “back + header” and the body is scrolling. */
fun scaffold(ctx: Context, title: String, onBack: () -> Unit, body: View): LinearLayout = ctx.vbox().apply {
    val header = ctx.hbox(4).apply {
        pad(ctx, 4, 8, 8, 0)
        addView(ctx.iconButton(R.drawable.ic_back, ctx.clr(R.color.on), ctx.getString(R.string.set_back)).apply { setOnClickListener { onBack() } })
        addView(ctx.text(title, 22f, 600))
    }
    addView(header, ctx.lpDp(MATCH, 64))
    addView(ScrollView(ctx).apply {
        isFillViewport = true
        overScrollMode = View.OVER_SCROLL_NEVER
        addView(body, ViewGroup.LayoutParams(MATCH, WRAP))
    }, lp(MATCH, 0, 1f))
}

/** Settings sections: on the main screen on a line for each, inside - its own screen (SettingsSectionScreen). */
enum class SettingsSection(val key: String, val titleRes: Int, val iconRes: Int) {
    /** Bridge mode: Android Auto/Headswap Desktop and desktop activation with PC. */
    MODE("mode", R.string.bm_title, R.drawable.ic_plug),
    APPEARANCE("appearance", R.string.set_sec_appearance, R.drawable.ic_palette),
    VIDEO("video", R.string.set_video, R.drawable.ic_video),
    SOUND("sound", R.string.set_sec_sound, R.drawable.ic_mic),

    /** Equalizer: the input from the "Sound and microphone", on the main screen of its line is not. */
    EQ("eq", R.string.eq_title, R.drawable.ic_equalizer),
    HEADUNIT("headunit", R.string.set_sec_headunit, R.drawable.ic_car),

    /** Catalog of foreign AA- applications (Fermata Auto, ParkedTube, AABrowser) and warning about video in motion. */
    APPS("apps", R.string.set_sec_apps, R.drawable.ic_open),

    /** Wireless: only in assembly with BuildConfig.WIRELESS_BETA. */
    WIRELESS("wireless", R.string.wl_title, R.drawable.ic_wifi),
    UPDATES("updates", R.string.upd_title, R.drawable.ic_download);

    companion object {
        /** Sections of this build: without a wireless beta, it is not in the menu or by key; without updates - their section. */
        fun shown(): List<SettingsSection> = values().filter {
            (it != WIRELESS || BuildConfig.WIRELESS_BETA) && (it != UPDATES || Updater.ENABLED)
        }

        fun byKey(key: String?): SettingsSection? = shown().firstOrNull { it.key == key }

        /** Main Settings screen lines: subsections (EQ) open from their sections. */
        fun menu(): List<SettingsSection> = shown().filter { it != EQ }
    }
}

private val LANGS = listOf(
    AppLocale.SYSTEM to R.string.lang_system,
    "en" to R.string.lang_en,
    "ru" to R.string.lang_ru,
)

private val THEMES = listOf(
    AppTheme.SYSTEM to R.string.theme_system,
    AppTheme.LIGHT to R.string.theme_light,
    AppTheme.MEDIUM to R.string.theme_medium,
    AppTheme.DARK to R.string.theme_dark,
)

private val MICS = listOf(
    Triple(MicMode.CAR, R.string.mic_car_title, R.string.mic_car_sub),
    Triple(MicMode.PHONE, R.string.mic_phone_title, R.string.mic_phone_sub),
    Triple(MicMode.AUTO, R.string.mic_auto_title, R.string.mic_auto_sub),
)

private val EQ_PRESETS = listOf(
    EqPreset.FLAT to R.string.eq_preset_flat,
    EqPreset.BASS to R.string.eq_preset_bass,
    EqPreset.VOCAL to R.string.eq_preset_vocal,
    EqPreset.ROCK to R.string.eq_preset_rock,
    EqPreset.ELECTRONIC to R.string.eq_preset_electronic,
    EqPreset.CUSTOM to R.string.eq_preset_custom,
)

private fun Context.eqPresetName(p: EqPreset): String = getString(EQ_PRESETS.first { it.first == p }.second)

/** The status of desktop activation with PC: signature and color. Cheap (checking four permissions), suitable for ticker. */
private fun Context.desktopActivation(): Pair<String, Int> = when (DelegatedDesktopAccess.status(this)) {
    DelegatedDesktopAccess.Status.READY -> getString(R.string.bm_act_on) to R.color.ok
    DelegatedDesktopAccess.Status.PERMISSION_LOST -> getString(R.string.bm_act_lost) to R.color.warn
    DelegatedDesktopAccess.Status.INACTIVE -> getString(R.string.bm_act_off) to R.color.warn
}

private fun Context.micMissing(aa: AaPrefs): Boolean =
    aa.micMode != MicMode.CAR && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED

/** The update status bar and its color: the same on the main screen and in the "Updates" section. */
private fun Context.updateStatus(): Pair<String, Int> {
    val rel = Updater.available(this)
    val err = Updater.error
    return when {
        Updater.step == Updater.Step.CHECKING -> getString(R.string.upd_status_checking) to R.color.on2
        Updater.step == Updater.Step.DOWNLOADING -> getString(R.string.upd_status_downloading, rel?.version ?: "", Updater.progress) to R.color.on2
        Updater.step == Updater.Step.INSTALLING -> getString(R.string.upd_status_installing) to R.color.on2
        err != null -> err.get(this) to R.color.warn
        rel != null -> getString(R.string.upd_status_available, rel.version) to R.color.acc
        UpdatePrefs(this).lastCheck > 0 -> getString(R.string.upd_status_latest) to R.color.ok
        else -> getString(R.string.upd_status_never) to R.color.on2
    }
}

/** Main settings screen: sections with current values, help, version. */
class SettingsScreen(host: MainActivity) : Screen(host) {
    private val aa = AaPrefs(ctx)
    private val summaries = HashMap<SettingsSection, TextView>()
    override val view: View

    init {
        val c = ctx
        val body = c.vbox(12).apply { pad(c, 16, 4, 16, 16) }

        val sections = c.card()
        for ((i, s) in SettingsSection.menu().withIndex()) {
            if (i > 0) sections.addView(c.divider())
            sections.addView(sectionRow(s))
        }
        body.addView(sections, lp(MATCH, WRAP))

        body.addView(c.sectionTitle(c.getString(R.string.set_help)))
        val links = c.card().apply {
            addView(link(R.drawable.ic_report, c.getString(R.string.set_report_title), c.getString(R.string.set_report_note), false) { LogShare.shareReport(c) })
            addView(c.divider())
            addView(link(R.drawable.ic_alert, c.getString(R.string.diag_settings_item), null, false) { host.showDiag() })
            addView(c.divider())
            addView(link(R.drawable.ic_refresh_btn, c.getString(R.string.set_rerun_setup), null, false) { host.showOnboarding(rerun = true) })
            addView(c.divider())
            addView(link(R.drawable.ic_check, c.getString(R.string.set_check_ready), null, false) { host.showOnboarding(checklist = true) })
            addView(c.divider())
            addView(link(R.drawable.ic_terminal, c.getString(R.string.set_for_devs), null, true) { host.showDev() })
            addView(c.divider())
            addView(link(R.drawable.ic_logo, c.getString(R.string.about_title), c.getString(R.string.about_note), false) { host.showAbout() })
        }
        body.addView(links, lp(MATCH, WRAP))

        body.addView(View(c), lp(MATCH, c.dp(12), 1f))
        val ver = try {
            c.packageManager.getPackageInfo(c.packageName, 0).versionName
        } catch (_: Exception) {
            "?"
        }
        body.addView(c.text(c.getString(R.string.set_footer, ver), 13f, 400, c.clr(R.color.on2)).apply { gravity = Gravity.CENTER }, lp(MATCH, WRAP))
        body.addView(supportButton(), LinearLayout.LayoutParams(WRAP, c.dp(44)).margins(c, 0, 12, 0, 8).apply { gravity = Gravity.CENTER_HORIZONTAL })

        refreshSummaries()
        view = scaffold(c, c.getString(R.string.set_title), { host.back() }, body)
    }

    override fun onShow() = refreshSummaries()

    override fun tick() = refreshSummaries()

    override fun onPermissionResult() = refreshSummaries()

    private fun sectionRow(s: SettingsSection): View {
        val c = ctx
        val sum = c.text("", 14f, 400, c.clr(R.color.on2)).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        summaries[s] = sum
        return c.hbox(14).apply {
            pad(c, 14, 12, 12, 12)
            minimumHeight = c.dp(76)
            background = c.rippleBg(null, 0)
            isClickable = true
            setOnClickListener { host.showSettingsSection(s) }
            val badge = FrameLayout(c).apply {
                background = c.shape(c.clr(R.color.pric), 14)
                addView(c.icon(s.iconRes, 22, c.clr(R.color.acc)), FrameLayout.LayoutParams(c.dp(22), c.dp(22), Gravity.CENTER))
            }
            addView(badge, c.lpDp(c.dp(44), 44))
            val col = c.vbox(2).apply {
                addView(c.text(c.getString(s.titleRes), 17f, 500))
                addView(sum)
            }
            addView(col, lp(0, WRAP, 1f))
            addView(c.icon(R.drawable.ic_chevron, 20, c.clr(R.color.on2)))
        }
    }

    /** Signatures under the sections: current values, and in case of a problem, its description is the color of the warning. */
    private fun refreshSummaries() {
        val c = ctx
        fun set(s: SettingsSection, text: String, color: Int = R.color.on2) {
            val tv = summaries[s] ?: return
            if (tv.text.toString() != text) tv.text = text
            tv.setTextColor(c.clr(color))
        }
        val bridgeMode = BridgeMode.effective(aa.bridgeMode, Build.VERSION.SDK_INT)
        if (bridgeMode == BridgeMode.DESKTOP) {
            val (act, actColor) = c.desktopActivation()
            set(SettingsSection.MODE, c.getString(R.string.bm_sum_state, c.getString(R.string.bm_desktop), act), actColor)
        } else {
            set(SettingsSection.MODE, c.getString(R.string.bm_aa))
        }

        val lang = c.getString(LANGS.firstOrNull { it.first == AppLocale.get(c) }?.second ?: R.string.lang_system)
        val theme = c.getString(THEMES.firstOrNull { it.first == AppTheme.get(c) }?.second ?: R.string.theme_system)
        set(SettingsSection.APPEARANCE, c.getString(R.string.set_sum_appearance, lang, theme))

        val mode = c.getString(if (aa.transcode) R.string.set_transcode_title else R.string.set_direct_title)
        val geo = aa.videoGeometry.effective(aa.transcode)
        val res = if (geo is VideoMode) c.getString(geo.titleRes) else geo.outLabel
        val dpi = aa.aaDensity.takeIf { it != AaDensity.AUTO }?.let { c.getString(R.string.set_sum_density, it) }
        set(SettingsSection.VIDEO, listOfNotNull(mode, res, dpi, c.getString(aa.fpsSetting.titleRes)).joinToString(" · "))

        if (c.micMissing(aa)) {
            set(SettingsSection.SOUND, c.getString(R.string.mic_no_permission), R.color.warn)
        } else {
            val mic = c.getString(R.string.set_sum_mic, c.getString(MICS.first { it.first == aa.micMode }.second))
            val eq = if (aa.usbAudio && !aa.eq.isFlat) c.getString(R.string.eq_sum, c.eqPresetName(aa.eqPreset)) else null
            set(SettingsSection.SOUND, listOfNotNull(if (aa.usbAudio) c.getString(R.string.set_usb_audio_title) else null, eq, mic).joinToString(" · "))
        }

        set(SettingsSection.HEADUNIT, c.getString(R.string.set_sum_headunit, aa.certSet.uppercase()))

        set(SettingsSection.APPS, c.getString(R.string.set_sum_apps, HuApps.detect(c).installed.size))

        val (upd, color) = c.updateStatus()
        set(SettingsSection.UPDATES, upd, color)

        if (BuildConfig.WIRELESS_BETA) {
            val car = CarProfile(c)
            val carText = if (car.isSet) c.getString(R.string.wl_car_line, car.btName ?: car.btMac ?: "") else null
            set(SettingsSection.WIRELESS, listOfNotNull(CarLifeState.wireless ?: c.getString(R.string.wl_off), carText).joinToString(" · "))
        }
    }

    /**
     * “Support Yegor”: contour pill under the version, opens the support page on the site
     * (details there, in the application they are not). URL by language: ru - SBP on /headswap/support/,
     * Other languages use PayPal at /headswap/en/#donate (R.string.set_support_url).
     */
    private fun supportButton(): View {
        val c = ctx
        val fg = c.clr(R.color.acc)
        return c.hbox(8).apply {
            gravity = Gravity.CENTER
            pad(c, 18, 0, 18, 0)
            background = c.rippleBg(c.shape(0, 22, fg, 1.5f), 22)
            isClickable = true
            isFocusable = true
            addView(c.icon(R.drawable.ic_heart, 18, fg))
            addView(c.text(c.getString(R.string.set_support), 15f, 600, fg))
            setOnClickListener {
                try {
                    c.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(c.getString(R.string.set_support_url))))
                } catch (e: Exception) {
                    AppLog.e("поддержка: браузер не открылся: ${e.message}")
                }
            }
        }
    }

    private fun link(icon: Int, title: String, note: String?, chevron: Boolean, onClick: () -> Unit): View {
        val c = ctx
        return c.hbox(14).apply {
            pad(c, 16, 0, 16, 0)
            background = c.rippleBg(null, 0)
            isClickable = true
            setOnClickListener { onClick() }
            addView(c.icon(icon, 22, c.clr(R.color.on2)))
            addView(c.text(title, 16f, 500), lp(0, WRAP, 1f))
            if (note != null) addView(c.text(note, 13f, 400, c.clr(R.color.on2)))
            if (chevron) addView(c.icon(R.drawable.ic_chevron, 20, c.clr(R.color.on2)))
            layoutParams = c.lpDp(MATCH, 56)
        }
    }
}

/** Switch from multiple buttons in the frame; selected is filled with pric and marked " ✓ ". */
private class Segmented(val ctx: Context, private val labels: List<String>, sp: Float, val onPick: (Int) -> Unit) {
    private val buttons = ArrayList<TextView>()
    val view: LinearLayout = ctx.hbox().apply {
        background = ctx.shape(0, 24, ctx.clr(R.color.line), 1.5f)
        clipToOutline = true
    }

    init {
        for ((i, label) in labels.withIndex()) {
            val b = ctx.text(label, sp, 600).apply {
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                pad(ctx, 6, 0, 6, 0)
                isClickable = true
                setOnClickListener { onPick(i) }
            }
            buttons.add(b)
            view.addView(b, lp(0, ctx.dp(48), 1f))
        }
    }

    fun setSelected(sel: Int) {
        for ((i, b) in buttons.withIndex()) {
            val on = i == sel
            b.background = ctx.rippleBg(if (on) ctx.shape(ctx.clr(R.color.pric), 0) else null, 0)
            b.setTextColor(ctx.clr(if (on) R.color.onpric else R.color.on))
            b.text = if (on) "✓ ${labels[i]}" else labels[i]
        }
    }

    /** Unavailable switch is darkened, presses do not pass (click hangs on each button, not on the frame). */
    fun setEnabled(on: Boolean) {
        view.alpha = if (on) 1f else 0.45f
        for (b in buttons) {
            b.isEnabled = on
            b.isClickable = on
        }
    }
}

/** One section of settings (language and theme, video, sound, head unit, updates). */
class SettingsSectionScreen(host: MainActivity, val section: SettingsSection) : Screen(host) {
    private val aa = AaPrefs(ctx)
    private val langRows = LinkedHashMap<String, RadioRow>()
    private val themeRows = LinkedHashMap<String, RadioRow>()
    private val micRows = HashMap<MicMode, RadioRow>()
    private val rateRows = LinkedHashMap<MusicRate, RadioRow>()
    private var makeValue: TextView? = null
    private var modeSeg: Segmented? = null
    private var actHost: LinearLayout? = null
    private var phoneCardNote: TextView? = null
    private var phoneActivation: PhoneActivationBlock? = null
    private var transcodeSeg: Segmented? = null
    private var transcodeDesc: View? = null
    private var directDesc: View? = null
    private var customResNote: TextView? = null
    private var resField: LinearLayout? = null
    private var resValue: TextView? = null
    private var resRatio: TextView? = null
    private var resSub: TextView? = null
    private var resChevron: ImageView? = null
    private var resPopup: PopupWindow? = null
    private var fpsSeg: Segmented? = null
    private var densityValue: TextView? = null
    private var densitySub: TextView? = null
    private var densityMinus: View? = null
    private var densityPlus: View? = null
    private var densityAuto: TextView? = null
    private var fpsNote: TextView? = null
    private var certSeg: Segmented? = null
    private var micNote: TextView? = null
    private val eqPresetBtns = ArrayList<Pair<TextView, EqPreset>>()
    private val eqBars = ArrayList<SeekBar>()
    private val eqValues = ArrayList<TextView>()
    private var eqPreBar: SeekBar? = null
    private var eqPreValue: TextView? = null
    private var eqUsbNote: TextView? = null
    private var eqEntrySub: TextView? = null
    private var eqEntryRow: View? = null
    /** Lists “Installed on the phone” and “Recommendation” are reassembled at each return to the screen. */
    private var appsHost: LinearLayout? = null
    private var updStatus: TextView? = null
    private var updButton: TextView? = null
    private var wlStatus: TextView? = null
    private var wlNoPermission: TextView? = null
    private var wlAwaitingPermission = false
    private var wlCarLine: TextView? = null
    private var wlCarWarn: TextView? = null
    private var wlCarForget: View? = null
    private var wlAwaitingCarPermission = false
    private var callsRow: ToggleRow? = null
    private var callsAwaitingPermission = false
    private var wlCwLine: TextView? = null
    private var wlCwSsid: EditText? = null
    private var wlCwPass: EditText? = null
    private var wlCwResult: TextView? = null
    private var wlCwForget: View? = null
    /** Request a machine network from "Save and Verify"; null - no verification. */
    private var wlCwCheck: CarNetwork? = null
    private val cwHandler = Handler(Looper.getMainLooper())
    private val body: LinearLayout = ctx.vbox(6).apply { pad(ctx, 16, 4, 16, 16) }
    override val view: View

    init {
        when (section) {
            SettingsSection.MODE -> buildMode()
            SettingsSection.APPEARANCE -> buildAppearance()
            SettingsSection.VIDEO -> buildVideo()
            SettingsSection.SOUND -> buildSound()
            SettingsSection.EQ -> buildEq()
            SettingsSection.HEADUNIT -> buildHeadunit()
            SettingsSection.APPS -> buildApps()
            SettingsSection.UPDATES -> body.addView(updateCard(), lp(MATCH, WRAP).margins(ctx, 0, 8, 0, 0))
            SettingsSection.WIRELESS -> body.addView(wirelessCard(), lp(MATCH, WRAP).margins(ctx, 0, 8, 0, 0))
        }
        view = scaffold(ctx, ctx.getString(section.titleRes), { host.back() }, body)
    }

    override fun onShow() {
        if (section == SettingsSection.MODE) refreshMode()      // activation could be performed while the screen was closed
        if (section == SettingsSection.VIDEO) refreshVideoMode()      // your permission could be changed in the section for developers
        refreshMic()
        refreshEq()
        refreshEqEntry()
        refreshApps()
        refreshUpdate()
        refreshWireless()
        if (section == SettingsSection.APPS) maybeShowAppsWarning()
    }

    override fun onHide() {
        resPopup?.dismiss()
    }

    override fun tick() {
        if (section == SettingsSection.MODE) refreshMode()
        refreshUpdate()
        refreshWireless()
    }

    override fun onPermissionResult() {
        refreshMic()
        refreshPhoneCardPermissions()
        phoneActivation?.onPermissionResult()
        onCallsPermissionResult()
        if (wlAwaitingPermission) {
            wlAwaitingPermission = false
            val lan = Build.VERSION.SDK_INT < 37 || NetProbe.localNetworkGranted(ctx) == "granted"
            if (lan) startWireless() else wlNoPermission?.visibility = View.VISIBLE
        }
        if (wlAwaitingCarPermission) {
            wlAwaitingCarPermission = false
            CarPairing.start(host)      // without permission, the choice is still possible, refreshWireless will warn
        }
        refreshWireless()
    }

    override fun onActivityResultDone() = refreshWireless()

    /** Wireless (beta): explanation, start, choice of car for autostart according to Bluetooth, report. */
    private fun wirelessCard(): View {
        val c = ctx
        val card = c.vbox(8).apply {
            background = c.shape(c.clr(R.color.surf), 20)
            pad(c, 16, 12, 16, 12)
        }
        card.addView(c.text(c.getString(R.string.wl_note), 14f, 400, c.clr(R.color.on2)), lp(MATCH, WRAP))
        wlStatus = c.text("", 15f, 500).also { card.addView(it, lp(MATCH, WRAP)) }
        wlNoPermission = c.text(c.getString(R.string.wl_no_permission), 13f, 400, c.clr(R.color.warn)).apply { visibility = View.GONE }
            .also { card.addView(it, lp(MATCH, WRAP)) }
        // button height is specified explicitly (as on other screens): pill with WRAP shrinks to text height
        card.addView(c.primaryButton(c.getString(R.string.wl_start), heightDp = 60, sp = 17f).apply { setOnClickListener { onWirelessStart() } },
            lp(MATCH, c.dp(60)).apply { topMargin = c.dp(4) })
        // choice of machine: further with its Bluetooth- connection, the waiting for Wi-Fi will start itself
        wlCarLine = c.text("", 15f, 500).also { card.addView(it, lp(MATCH, WRAP).apply { topMargin = c.dp(8) }) }
        card.addView(c.text(c.getString(R.string.wl_car_note), 13f, 400, c.clr(R.color.on2)), lp(MATCH, WRAP))
        wlCarWarn = c.text("", 13f, 400, c.clr(R.color.warn)).apply { visibility = View.GONE }.also { card.addView(it, lp(MATCH, WRAP)) }
        card.addView(c.outlineButton(c.getString(R.string.wl_car_pick), heightDp = 56, sp = 16f).apply { setOnClickListener { onCarPick() } },
            lp(MATCH, c.dp(56)))
        wlCarForget = c.outlineButton(c.getString(R.string.wl_car_forget), heightDp = 56, sp = 16f).apply {
            setOnClickListener { CarPairing.forget(c); refreshWireless() }
        }.also { card.addView(it, lp(MATCH, c.dp(56))) }
        // Wi-Fi machines: the phone enters its access point itself (WifiNetworkSpecifier), the Internet remains on the main network
        val cw = CarWifi(c)
        wlCwLine = c.text("", 15f, 500).also { card.addView(it, lp(MATCH, WRAP).apply { topMargin = c.dp(8) }) }
        card.addView(c.text(c.getString(R.string.wl_cw_note), 13f, 400, c.clr(R.color.on2)), lp(MATCH, WRAP))
        wlCwSsid = wifiField(c.getString(R.string.wl_cw_ssid_hint), password = false).apply { setText(cw.ssid.orEmpty()) }
            .also { card.addView(it, lp(MATCH, c.dp(52))) }
        wlCwPass = wifiField(c.getString(R.string.wl_cw_pass_hint), password = true).apply { setText(cw.pass) }
            .also { card.addView(it, lp(MATCH, c.dp(52))) }
        wlCwResult = c.text("", 13f, 400, c.clr(R.color.on2)).apply { visibility = View.GONE }.also { card.addView(it, lp(MATCH, WRAP)) }
        card.addView(c.outlineButton(c.getString(R.string.wl_cw_save), heightDp = 56, sp = 16f).apply { setOnClickListener { onCarWifiSave() } },
            lp(MATCH, c.dp(56)))
        wlCwForget = c.outlineButton(c.getString(R.string.wl_cw_forget), heightDp = 56, sp = 16f).apply {
            setOnClickListener { onCarWifiForget() }
        }.also { card.addView(it, lp(MATCH, c.dp(56))) }
        card.addView(c.linkButton(c.getString(R.string.wl_report), heightDp = 52, sp = 16f).apply { setOnClickListener { LogShare.shareReport(c) } },
            lp(MATCH, c.dp(52)))
        refreshWireless()
        return card
    }

    private fun refreshWireless() {
        val st = wlStatus ?: return
        val c = ctx
        val text = c.getString(R.string.wl_status_line, CarLifeState.wireless ?: c.getString(R.string.wl_off))
        if (st.text.toString() != text) st.text = text
        val car = CarProfile(c)
        val carText = c.getString(R.string.wl_car_line, if (car.isSet) car.btName ?: car.btMac ?: "" else c.getString(R.string.wl_car_none))
        wlCarLine?.let { if (it.text.toString() != carText) it.text = carText }
        wlCarForget?.visibility = if (car.isSet) View.VISIBLE else View.GONE
        // without BLUETOOTH_CONNECT (31+) system does not deliver ACL selected machine: trigger is silent
        val warn = when {
            !CarPairing.available(c) -> c.getString(R.string.wl_car_unavailable)
            car.isSet && !carBtGranted() -> c.getString(R.string.wl_car_no_permission)
            else -> ""
        }
        wlCarWarn?.let {
            if (it.text.toString() != warn) it.text = warn
            it.visibility = if (warn.isEmpty()) View.GONE else View.VISIBLE
        }
        val cw = CarWifi(c)      // SSID on the screen show, in logs and report it does not go away
        val cwText = c.getString(R.string.wl_cw_line, if (cw.isSet) c.getString(R.string.wl_cw_set, cw.ssid) else c.getString(R.string.wl_cw_none))
        wlCwLine?.let { if (it.text.toString() != cwText) it.text = cwText }
        wlCwForget?.visibility = if (cw.isSet) View.VISIBLE else View.GONE
    }

    private fun wifiField(hint: String, password: Boolean): EditText = EditText(ctx).apply {
        this.hint = hint
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setTextColor(ctx.clr(R.color.on))
        setHintTextColor(ctx.clr(R.color.on2))
        typeface = ctx.sans(400)
        background = ctx.shape(ctx.clr(R.color.surf2), 14)
        pad(ctx, 16, 0, 16, 0)
        isSingleLine = true
        inputType = InputType.TYPE_CLASS_TEXT or
            if (password) InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        typeface = ctx.sans(400)      // setInputType for password itself puts a mono-width
    }

    private fun showCwResult(text: String, color: Int = R.color.on2) {
        wlCwResult?.apply {
            this.text = text
            setTextColor(ctx.clr(color))
            visibility = View.VISIBLE
        }
    }

    /**
     * “Save and check”: the request of the machine network from the foreground so that the person can see and approve the system dialogue (from the background)
     * This is only an approval/check: through 5 with after entering the request remove, then it keeps the service.
     */
    private fun onCarWifiSave() {
        val ssid = wlCwSsid?.text?.toString().orEmpty()
        val pass = wlCwPass?.text?.toString().orEmpty()
        if (ssid.isEmpty()) {
            showCwResult(ctx.getString(R.string.wl_cw_need_ssid), R.color.warn)
            return
        }
        CarWifi(ctx).save(ssid, pass)
        wlCwCheck?.release("recheck")
        val net = CarNetwork(ctx)
        wlCwCheck = net
        showCwResult(ctx.getString(R.string.wl_cw_checking))
        val fail = {
            if (wlCwCheck === net) {
                showCwResult(ctx.getString(R.string.wl_cw_fail), R.color.warn)
                wlCwCheck = null
            }
        }
        net.request(
            ssid, pass, CW_CHECK_TIMEOUT_MS,
            onAvailable = {
                if (wlCwCheck === net) {
                    showCwResult(ctx.getString(R.string.wl_cw_ok), R.color.ok)
                    cwHandler.postDelayed({
                        if (wlCwCheck === net) {
                            net.release("check")
                            wlCwCheck = null
                        }
                    }, CW_CHECK_HOLD_MS)
                }
            },
            onUnavailable = { fail() },
            onLost = { fail() },
        )
        refreshWireless()
    }

    private fun onCarWifiForget() {
        CarWifi(ctx).clear()
        wlCwCheck?.release("forget")
        wlCwCheck = null
        wlCwSsid?.setText("")
        wlCwPass?.setText("")
        wlCwResult?.visibility = View.GONE
        refreshWireless()
    }

    private fun carBtGranted() =
        Build.VERSION.SDK_INT < 31 || ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    /** On Android 12+, before choosing, please ask BLUETOOTH_CONNECT (device name, delivery ACL); opt-out does not block. */
    private fun onCarPick() {
        if (!carBtGranted()) {
            wlAwaitingCarPermission = true
            host.requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQ_CAR_BT)
            return
        }
        CarPairing.start(host)
    }

    /**
     * On Android 17+ without ACCESS_LOCAL_NETWORK LAN cut silently: first ask, start - in onPermissionResult.
     * BLUETOOTH_CONNECT (Android 12+) and "Devices Nearby" (Wi-Fi Direct) - for the A branch, without them, the wireless will still start.
     */
    private fun onWirelessStart() {
        wlNoPermission?.visibility = View.GONE
        val ask = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 37 && NetProbe.localNetworkGranted(ctx) != "granted") ask += Manifest.permission.ACCESS_LOCAL_NETWORK
        if (!carBtGranted()) ask += Manifest.permission.BLUETOOTH_CONNECT
        // A: branch Wi-Fi entry Direct-group head unit; without it, the wireless starts, but only by branch B
        val p2p = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION
        if (ctx.checkSelfPermission(p2p) != PackageManager.PERMISSION_GRANTED) {
            ask += p2p
            // Android 12 silently rejects FINE without COARSE in the same request
            if (p2p == Manifest.permission.ACCESS_FINE_LOCATION) ask += Manifest.permission.ACCESS_COARSE_LOCATION
        }
        if (ask.isNotEmpty()) {
            wlAwaitingPermission = true
            host.requestPermissions(ask.toTypedArray(), REQ_LOCAL_NETWORK)
            return
        }
        startWireless()
    }

    private fun startWireless() {
        AppLog.i("кнопка «Ждать машину по Wi-Fi»")
        ctx.startForegroundService(Intent(ctx, BridgeService::class.java).setAction(BridgeService.ACTION_WIFI_B))
    }

    private companion object {
        /** The same code that MainActivity.REQ_LOCAL_NETWORK: results in its log. */
        const val REQ_LOCAL_NETWORK = 37
        /** BLUETOOTH_CONNECT before choosing a machine (38 is engaged in the dialogue of CDM, CarPairing.REQ_CAR_PAIR). */
        const val REQ_CAR_BT = 39
        /** READ_PHONE_STATE when enabled "Report head unit about calls"; the same code as MainActivity.REQ_PHONE_STATE (log result). */
        const val REQ_PHONE_STATE = 40
        /** Phone card permissions launcher Headswap Desktop. */
        const val REQ_PHONE_CARD = 41
        val PHONE_CARD_PERMISSIONS = listOf(
            Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE, Manifest.permission.ANSWER_PHONE_CALLS,
        )
        /** Checking the Wi-Fi machine from settings: how much we wait for approval and login, and how much we hold the network after logging in. */
        const val CW_CHECK_TIMEOUT_MS = 60_000L
        const val CW_CHECK_HOLD_MS = 5_000L
        /** The group of drop-down permissions; WIDE_836 is not a separate line (this is WIDE_976 in passthrough). */
        val RES_STANDARD = listOf(VideoMode.R800, VideoMode.R1024, VideoMode.R720, VideoMode.R1080)
        val RES_WIDE = listOf(VideoMode.WIDE, VideoMode.WIDE_MID, VideoMode.WIDE_HD, VideoMode.WIDE_640, VideoMode.WIDE_2_1, VideoMode.WIDE_976)
        val RES_PORTRAIT = listOf(VideoMode.PORTRAIT_960, VideoMode.PORTRAIT_1152)
        /** The words of the name of the car that are not needed in the list of permits: years, engine, hybrid, restyling. */
        val CAR_NAME_NOISE = Regex("""(?i)(19|20)\d\d([–-](19|20)?\d\d)?|\d\.\dT|гибрид|hybrid|дорестайл|рестайлинг|restyling|facelift|pre-facelift""")
    }

    private fun note(text: String, color: Int = R.color.on2): TextView =
        ctx.text(text, 13f, 400, ctx.clr(color)).also { body.addView(it, lp(WRAP, WRAP).margins(ctx, 8, 2, 8, 0)) }

    // --- bridge mode ---

    private fun buildMode() {
        val c = ctx
        body.addView(c.sectionTitle(c.getString(R.string.bm_title)))
        if (!BridgeMode.desktopSupported(Build.VERSION.SDK_INT)) {
            // below Android 13 mode DESKTOP is not available and is treated as Android Auto
            body.addView(c.text(c.getString(R.string.bm_need_13), 16f, 600, c.clr(R.color.warn)), lp(WRAP, WRAP).margins(c, 8, 4, 8, 0))
            note(c.getString(R.string.bm_aa_note))
            return
        }
        val seg = Segmented(c, listOf(c.getString(R.string.bm_aa), c.getString(R.string.bm_own_launcher)), 15f) { setBridgeMode(BridgeMode.values()[it]) }
        modeSeg = seg
        body.addView(seg.view, lp(MATCH, WRAP))
        actHost = c.vbox(8).also { body.addView(it, lp(MATCH, WRAP)) }
        refreshMode()
    }

    private fun setBridgeMode(m: BridgeMode) {
        if (aa.bridgeMode == m) return
        aa.bridgeMode = m
        val running = BridgeService.alive && CarLifeState.running
        AppLog.i("настройка: режим моста -> ${m.key}" + if (running) ", применится к следующему подключению (текущая сессия не прерывается)" else "")
        refreshMode()
    }

    private fun refreshMode() {
        val c = ctx
        val seg = modeSeg ?: return
        val desktop = aa.bridgeMode == BridgeMode.DESKTOP
        seg.setSelected(if (desktop) 1 else 0)
        val box = actHost ?: return
        if (!desktop) {
            box.visibility = View.GONE
            return
        }
        box.visibility = View.VISIBLE
        if (box.childCount == 0) buildActivation(box)      // A token is created only when a block is first needed.
        phoneActivation?.refresh()
    }

    /** Block of the "Mode" section for its launcher: choice launcher, under it the permissions of the "Phone" card (only Headswap), activation. */
    private fun buildActivation(box: LinearLayout) {
        val c = ctx
        // only our launcher: call log, contacts, call and response from the screen head unit
        val permBox = c.vbox().apply { visibility = View.GONE }
        buildDesktopLauncher(box) { ownLauncher -> permBox.visibility = if (ownLauncher) View.VISIBLE else View.GONE }
        permBox.addView(c.tonalButton(c.getString(R.string.l_perm_button)).apply {
            setOnClickListener { requestPhoneCardPermissions() }
        }, c.lpDp(MATCH, 52).margins(c, 0, 12, 0, 0))
        phoneCardNote = c.text(c.getString(R.string.l_perm_summary), 13f, 400, c.clr(R.color.on2))
            .also { permBox.addView(it, lp(WRAP, WRAP).margins(c, 8, 2, 8, 0)) }
        box.addView(permBox, lp(MATCH, WRAP))
        phoneActivation = PhoneActivationBlock(host).also { box.addView(it.view, lp(MATCH, WRAP)) }
        refreshPhoneCardPermissions()
    }

    /**
     * launcher on head unit: our Headswap or any installed HOME-launcher (CarDash, DriveDeck) in the window above our panel
     * «Back / Home / Recent». The choice is written in DexPrefs and applies to the upcoming session via showDesktop: our
     * LauncherActivity by onNewIntent rereads the selection. The list is not read on main thread.
     */
    private fun buildDesktopLauncher(box: LinearLayout, onSelected: (ownLauncher: Boolean) -> Unit) {
        val c = ctx
        val dex = DexPrefs(c)
        var options = emptyList<DesktopAppCatalog.Entry>()
        var loaded = false      // while the list of launcher is not read, selected by someone else do not consider missing
        val rows = LinkedHashMap<String?, RadioRow>()      // key - "package / class", null - Headswap
        fun mark() {
            val current = DesktopPick.selected(dex.desktopComponent, options)?.key      // launcher is missing: show Headswap, it will start
            for ((key, row) in rows) row.setSelected(key == current)
            onSelected(current == null && (loaded || dex.desktopComponent == null))
        }
        val list = c.vbox(8)
        fun add(key: String?, title: String) {
            val row = RadioRow(c, title, "") {
                if (dex.desktopComponent == key) return@RadioRow
                dex.desktopComponent = key
                AppLog.i("настройка: лаунчер на ГУ -> ${key ?: "Headswap"}")
                mark()
                if (BridgeService.alive) BridgeService.requestDesktopHome(c)
            }
            rows[key] = row
            list.addView(row.view, lp(MATCH, WRAP))
        }
        add(null, c.getString(R.string.bm_launcher_own))
        mark()
        box.addView(c.sectionTitle(c.getString(R.string.bm_launcher_title)))
        box.addView(list, lp(MATCH, WRAP))
        Thread({
            val found = DesktopPick.installed(c)
            Handler(Looper.getMainLooper()).post {
                options = found
                loaded = true
                for (e in found) add(e.key, e.label)
                mark()
            }
        }, "desktop-launchers").apply { isDaemon = true; start() }
    }

    private fun missingPhoneCardPermissions(): List<String> = PHONE_CARD_PERMISSIONS.filter {
        ctx.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
    }

    private fun requestPhoneCardPermissions() {
        val ask = missingPhoneCardPermissions()
        if (ask.isEmpty()) {
            Toast.makeText(ctx, R.string.l_perm_granted, Toast.LENGTH_SHORT).show()
            return
        }
        host.requestPermissions(ask.toTypedArray(), REQ_PHONE_CARD)
    }

    private fun refreshPhoneCardPermissions() {
        val granted = missingPhoneCardPermissions().isEmpty()
        phoneCardNote?.text = ctx.getString(if (granted) R.string.l_perm_granted else R.string.l_perm_summary)
    }

    // --- Language and theme ---

    private fun buildAppearance() {
        val c = ctx
        body.addView(c.sectionTitle(c.getString(R.string.lang_title)))
        for ((tag, titleRes) in LANGS) {
            val row = RadioRow(c, c.getString(titleRes), "") { setLang(tag) }
            langRows[tag] = row
            body.addView(row.view, lp(MATCH, WRAP))
        }
        refreshLang()

        body.addView(c.sectionTitle(c.getString(R.string.theme_title)))
        for ((mode, titleRes) in THEMES) {
            val row = RadioRow(c, c.getString(titleRes), "") { setTheme(mode) }
            themeRows[mode] = row
            body.addView(row.view, lp(MATCH, WRAP))
        }
        refreshTheme()
    }

    private fun setLang(tag: String) {
        if (AppLocale.get(ctx) == tag) return
        AppLocale.set(ctx, tag)
        refreshLang()
        if (AppLocale.needsRecreate) host.recreate()      // the 33+ activity
    }

    private fun refreshLang() {
        val cur = AppLocale.get(ctx)
        for ((tag, row) in langRows) row.setSelected(cur == tag)
    }

    private fun setTheme(mode: String) {
        if (AppTheme.get(ctx) == mode) return
        AppTheme.set(ctx, mode)
        refreshTheme()
        host.recreate()      // theme and colors are read when creating activity
    }

    private fun refreshTheme() {
        val cur = AppTheme.get(ctx)
        for ((mode, row) in themeRows) row.setSelected(cur == mode)
    }

    // --- video ---

    private fun buildVideo() {
        val c = ctx
        body.addView(c.sectionTitle(c.getString(R.string.set_mode)))
        if (BridgeMode.effective(aa.bridgeMode, Build.VERSION.SDK_INT) == BridgeMode.DESKTOP) {
            // “Your launcher”: the phone encodes the screen once and gives head unit ready H.264 - there is nothing to choose, this is passthrough.
            // Setup AA (aa.transcode) do not touch: it works again when returning to Android mode Auto.
            body.addView(Segmented(c, listOf(c.getString(R.string.set_direct_title)), 15f) {}.apply { setSelected(0) }.view, lp(MATCH, WRAP))
            body.addView(c.text(c.getString(R.string.set_direct_desktop_desc), 13f, 400, c.clr(R.color.on2)).apply { setLineSpacing(0f, 1.4f) },
                lp(MATCH, WRAP).margins(c, 8, 4, 8, 0))
        } else {
            val seg = Segmented(c, listOf(c.getString(R.string.set_transcode_title), c.getString(R.string.set_direct_title)), 15f) { setTranscode(it == 0) }
            transcodeSeg = seg
            body.addView(seg.view, lp(MATCH, WRAP))
            body.addView(modeDescription(), lp(MATCH, WRAP).margins(c, 8, 4, 8, 0))
        }

        body.addView(c.sectionTitle(c.getString(R.string.set_resolution)))
        customResNote = note("").apply { visibility = View.GONE }
        body.addView(resolutionField(), lp(MATCH, WRAP))
        note(c.getString(R.string.set_apply_next))

        body.addView(c.sectionTitle(c.getString(R.string.set_aa_density)))
        body.addView(densityCard(), lp(MATCH, WRAP))
        note(c.getString(R.string.set_aa_density_note))

        body.addView(c.sectionTitle(c.getString(R.string.set_fps)))
        val fps = FpsSetting.values().filter { it != FpsSetting.FPS60 }
        val fSeg = Segmented(c, fps.map { c.getString(it.titleRes) }, 15f) { setFps(fps[it]) }
        fpsSeg = fSeg
        body.addView(fSeg.view, lp(MATCH, WRAP))
        fpsNote = note("")
        note(c.getString(R.string.set_fps_note))

        setTranscode(aa.transcode, save = false)
        refreshFps()
    }

    private fun setTranscode(on: Boolean, save: Boolean = true) {
        if (save) aa.transcode = on
        transcodeSeg?.setSelected(if (on) 0 else 1)
        transcodeDesc?.visibility = if (on) View.VISIBLE else View.INVISIBLE
        directDesc?.visibility = if (on) View.INVISIBLE else View.VISIBLE
        refreshVideoMode()
        refreshFps()
    }

    /**
     * Description of the mode under the switch: the essence is gray, plus the color ok, minus the color warn. Both blocks lie on each other,
     * invisible INVISIBLE: height is always higher, below nothing jumps when switching.
     */
    private fun modeDescription(): View {
        val c = ctx
        fun block(desc: Int, plus: Int, minus: Int) = c.vbox(4).apply {
            for ((res, color) in listOf(desc to R.color.on2, plus to R.color.ok, minus to R.color.warn)) {
                addView(c.text(c.getString(res), 13f, 400, c.clr(color)).apply { setLineSpacing(0f, 1.4f) }, lp(MATCH, WRAP))
            }
        }
        val t = block(R.string.set_transcode_desc, R.string.set_transcode_plus, R.string.set_transcode_minus)
        val d = block(R.string.set_direct_desc, R.string.set_direct_plus, R.string.set_direct_minus)
        transcodeDesc = t
        directDesc = d
        return FrameLayout(c).apply {
            addView(t, FrameLayout.LayoutParams(MATCH, WRAP))
            addView(d, FrameLayout.LayoutParams(MATCH, WRAP))
        }
    }

    private fun setVideoMode(m: VideoMode) {
        if (blocked(m)) return      // crop: transcoding or SPS-crop (h529)
        aa.videoMode = m
        refreshVideoMode()
    }

    /** The mode is not available without transcoding: in passthrough it would collapse in 1280×720 (crop with scale SPS-crop do not). */
    private fun blocked(m: VideoMode): Boolean = m != VideoMode.R720 && m.effective(aa.transcode) == VideoMode.R720

    /** The list string that shows the mode: WIDE_836 is not shown separately, this is WIDE_976 in passthrough. */
    private fun rowOf(m: VideoMode): VideoMode = if (m == VideoMode.WIDE_836) VideoMode.WIDE_976 else m

    /** Line resolution: passthrough WIDE_976 shows 1920×836; the unreachable string is its own, not 1280×720. */
    private fun rowLabel(m: VideoMode): String = (if (blocked(m)) m else m.effective(aa.transcode)).outLabel

    private fun ratioOf(m: VideoMode): String = when (m) {
        VideoMode.R800 -> "5:3"
        VideoMode.R1024 -> "17:10"
        VideoMode.R720, VideoMode.R1080 -> "16:9"
        VideoMode.WIDE, VideoMode.WIDE_MID, VideoMode.WIDE_HD -> "8:3"
        VideoMode.WIDE_640 -> "3:1"
        VideoMode.WIDE_2_1 -> "2:1"
        VideoMode.WIDE_976, VideoMode.WIDE_836 -> ctx.getString(R.string.set_res_ratio_wide_976)
        VideoMode.PORTRAIT_960 -> "3:4"
        VideoMode.PORTRAIT_1152 -> "5:6"
    }

    /**
     * Cars from "My car" with this mode: "Honda CR-V..., Vezel... · Volkswagen Bora..., Golf..." (brand - the first word of the name).
     * Default Mode (1280×720) stands at all machines without a special screen, we do not list them. null = No cars.
     */
    private fun carsFor(m: VideoMode): String? {
        if (m == VideoMode.DEFAULT) return null
        val names = CarPreset.values().filter { it.settings.videoMode == m }.map { shortCarName(ctx.getString(it.titleRes)) }
        if (names.isEmpty()) return null
        return names.distinct().joinToString(", ")
    }

    /** Brand and model without years, generations, restyles and engine: «Honda CR-V, 6 generation, dorestile» -> «Honda CR-V». */
    private fun shortCarName(title: String): String =
        title.substringBefore(',').substringBefore('(')
            .split(' ').filter { it.isNotEmpty() && !CAR_NAME_NOISE.matches(it) }
            .joinToString(" ")
            .replaceFirst("Volkswagen ", "VW ")

    /** Mode signature: the machines for which it is specified, otherwise a short description. */
    private fun subOf(m: VideoMode): String = carsFor(m) ?: ctx.getString(m.subRes)

    /** Video resolution field: current resolution, proportion, machines; click on the drop-down list under the field. */
    private fun resolutionField(): View {
        val c = ctx
        val value = c.text("", 20f, 600)
        val ratio = c.text("", 12f, 600, c.clr(R.color.on2)).apply {
            background = c.shape(0, 8, c.clr(R.color.line), 1)
            pad(c, 6, 2, 6, 2)
        }
        val sub = c.text("", 14f, 400, c.clr(R.color.on2)).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        val chevron = c.icon(R.drawable.ic_chevron, 20, c.clr(R.color.on2))
        resValue = value
        resRatio = ratio
        resSub = sub
        resChevron = chevron
        val field = c.hbox(12).apply {
            minimumHeight = c.dp(76)
            pad(c, 16, 12, 14, 12)
            isClickable = true
            isFocusable = true
            contentDescription = c.getString(R.string.set_res_pick)
            addView(c.vbox(4).apply {
                addView(c.hbox(8).apply {
                    addView(value)
                    addView(ratio)
                }, lp(WRAP, WRAP))
                addView(sub, lp(MATCH, WRAP))
            }, lp(0, WRAP, 1f))
            addView(chevron)
            setOnClickListener { showResPopup() }
        }
        resField = field
        setResFieldOpen(false)
        return field
    }

    /** Open list: pri frame and chevron up. */
    private fun setResFieldOpen(open: Boolean) {
        val c = ctx
        resField?.background = c.rippleBg(c.shape(c.clr(R.color.surf), 20, if (open) c.clr(R.color.pri) else 0, 1.5f), 20)
        resChevron?.rotation = if (open) -90f else 90f
    }

    /** The drop-down list of permissions under the field, the width of the field; does not fit in height - scrolling. */
    private fun showResPopup() {
        val anchor = resField ?: return
        if (resPopup != null) return
        val c = ctx
        val selected = rowOf(aa.videoMode.effective(aa.transcode))
        val list = c.vbox().apply { pad(c, 0, 4, 0, 8) }
        for ((titleRes, modes) in listOf(
            R.string.set_res_group_standard to RES_STANDARD, R.string.set_res_group_wide to RES_WIDE, R.string.set_res_group_portrait to RES_PORTRAIT,
        )) {
            list.addView(c.text(c.getString(titleRes).uppercase(), 12f, 600, c.clr(R.color.acc)).apply {
                letterSpacing = 0.06f
                pad(c, 16, 12, 16, 6)
            }, lp(MATCH, WRAP))
            for (m in modes) list.addView(resRow(m, m == selected), lp(MATCH, WRAP).margins(c, 6, 0, 6, 0))
        }
        val content = c.vbox().apply {
            background = c.shape(c.clr(R.color.surf2), 16)
            clipToOutline = true
            addView(ScrollView(c).apply { addView(list) }, lp(MATCH, 0, 1f))
        }
        // height: the whole list or place under the field (not less than 280 dp: then PopupWindow will scroll the screen to the field itself)
        content.measure(View.MeasureSpec.makeMeasureSpec(anchor.width, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
        val loc = IntArray(2)
        anchor.getLocationOnScreen(loc)
        val frame = Rect()
        anchor.getWindowVisibleDisplayFrame(frame)
        val gap = c.dp(6)
        val below = frame.bottom - (loc[1] + anchor.height) - gap - c.dp(8)
        val height = minOf(content.measuredHeight, maxOf(below, c.dp(280)))
        val pw = PopupWindow(content, anchor.width, height, true).apply {
            setBackgroundDrawable(c.shape(c.clr(R.color.surf2), 16))
            elevation = c.dp(8).toFloat()
            isOutsideTouchable = true
            setOnDismissListener {
                resPopup = null
                setResFieldOpen(false)
            }
        }
        resPopup = pw
        setResFieldOpen(true)
        pw.showAsDropDown(anchor, 0, gap)
    }

    /** List string: resolution and proportion on the left, machine or description on the right; not available in mode - darkened and not pressed. */
    private fun resRow(m: VideoMode, selected: Boolean): View {
        val c = ctx
        val blocked = blocked(m)
        val fg = c.clr(if (selected) R.color.onpric else R.color.on)
        val fg2 = c.clr(if (selected) R.color.onpric else R.color.on2)
        return c.hbox(12).apply {
            minimumHeight = c.dp(56)
            pad(c, 10, 8, 10, 8)
            background = c.rippleBg(if (selected) c.shape(c.clr(R.color.pric), 12) else null, 12)
            alpha = if (blocked) 0.45f else 1f
            isClickable = true
            // silhouette of the screen in the proportion of the mode, below it the proportion of the digit
            val label = rowLabel(m)
            val (w, h) = label.split('×').map { it.trim().toIntOrNull() ?: 1 }.let { it[0] to it.getOrElse(1) { 1 } }
            addView(c.vbox(3).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                addView(View(c).apply { background = c.shape(0, 3, fg2, 1.5f) },
                    LinearLayout.LayoutParams(c.dp(36), c.dp(36f * h / w)))
                addView(c.text(ratioOf(m), 10f, 400, fg2).apply { gravity = Gravity.CENTER_HORIZONTAL }, lp(MATCH, WRAP))
            }, LinearLayout.LayoutParams(c.dp(40), WRAP).apply { gravity = Gravity.CENTER_VERTICAL })
            addView(c.vbox(2).apply {
                addView(c.text(label, 16f, 600, fg))
                addView(c.text(if (blocked) c.getString(R.string.set_needs_transcode) else subOf(m), 13f, 400, fg2).apply {
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                })
            }, lp(0, WRAP, 1f))
            if (selected) addView(c.icon(R.drawable.ic_check, 20, fg))
            setOnClickListener {
                if (blocked) return@setOnClickListener
                resPopup?.dismiss()
                setVideoMode(m)
            }
        }
    }

    /** The field shows the mode that will actually apply (without transcoding unavailable - 1280×720, there is still a choice in prefs). */
    private fun refreshVideoMode() {
        val shown = aa.videoMode.effective(aa.transcode)
        val row = rowOf(shown)
        // its permission from the developer section overlaps the choice (without transcoding it goes to R720)
        val custom = aa.videoGeometry.effective(aa.transcode) as? CustomVideoMode
        customResNote?.apply {
            visibility = if (custom != null) View.VISIBLE else View.GONE
            if (custom != null) text = ctx.getString(R.string.set_custom_res_active, custom.outLabel, ctx.getString(R.string.dev_title))
        }
        resValue?.text = shown.outLabel
        resRatio?.text = ratioOf(row)
        resSub?.text = subOf(row)
        resField?.alpha = if (custom != null) 0.6f else 1f
        refreshDensity()      // Autos depend on the regime.
    }

    /** "Auto - value +": 11 variants (auto and 100..280 via 20) do not fit into Segmented, so stepper. */
    private fun densityCard(): View {
        val c = ctx
        fun stepButton(label: String, desc: String, up: Boolean) = c.text(label, 24f, 500, c.clr(R.color.acc)).apply {
            gravity = Gravity.CENTER
            contentDescription = desc
            background = c.rippleBg(c.shape(c.clr(R.color.pric), 24), 24)
            isClickable = true
            setOnClickListener { setDensity(AaDensity.step(aa.aaDensity, autoDensity(), up)) }
        }
        val minus = stepButton("−", c.getString(R.string.set_aa_density_smaller), false)
        val plus = stepButton("+", c.getString(R.string.set_aa_density_larger), true)
        val value = c.text("", 17f, 600).apply { gravity = Gravity.CENTER }
        val sub = c.text("", 13f, 400, c.clr(R.color.on2)).apply { gravity = Gravity.CENTER }
        val auto = c.text(c.getString(R.string.set_aa_density_reset), 15f, 600).apply {
            gravity = Gravity.CENTER
            pad(c, 14, 0, 14, 0)
            isClickable = true
            setOnClickListener { setDensity(AaDensity.AUTO) }
        }
        densityMinus = minus
        densityPlus = plus
        densityValue = value
        densitySub = sub
        densityAuto = auto
        val row = c.hbox(10).apply {
            background = c.shape(c.clr(R.color.surf), 20)
            pad(c, 12, 10, 12, 10)
            addView(auto, c.lpDp(WRAP, 44))
            addView(minus, c.lpDp(c.dp(48), 48))
            addView(c.vbox(2).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                addView(value, lp(MATCH, WRAP))
                addView(sub, lp(MATCH, WRAP))
            }, lp(0, WRAP, 1f))
            addView(plus, c.lpDp(c.dp(48), 48))
        }
        refreshDensity()
        return row
    }

    /** density mode that will actually apply (with passthrough in mind). */
    private fun autoDensity(): Int = aa.videoGeometry.effective(aa.transcode).density

    private fun setDensity(v: Int) {
        aa.aaDensity = v
        refreshDensity()
    }

    private fun refreshDensity() {
        val value = densityValue ?: return
        val c = ctx
        val cur = aa.aaDensity
        val auto = autoDensity()
        val isAuto = cur == AaDensity.AUTO
        value.text = if (isAuto) c.getString(R.string.set_aa_density_auto, auto) else c.getString(R.string.set_aa_density_manual, cur)
        densitySub?.text = if (isAuto) c.getString(R.string.set_aa_density_auto_sub) else c.getString(R.string.set_aa_density_manual_sub, auto)
        for ((b, up) in listOf(densityMinus to false, densityPlus to true)) {
            val can = AaDensity.step(cur, auto, up) != cur
            b?.isEnabled = can
            b?.alpha = if (can) 1f else 0.35f
        }
        densityAuto?.apply {
            background = c.rippleBg(if (isAuto) c.shape(c.clr(R.color.pric), 22) else c.shape(0, 22, c.clr(R.color.line), 1.5f), 22)
            setTextColor(c.clr(if (isAuto) R.color.onpric else R.color.on))
            text = if (isAuto) "✓ ${c.getString(R.string.set_aa_density_reset)}" else c.getString(R.string.set_aa_density_reset)
        }
    }

    private fun setFps(f: FpsSetting) {
        aa.fpsSetting = f
        refreshFps()
    }

    /** In direct passthrough frequency sets AA: switch is darkened and not pressed, the choice in prefs remains. */
    private fun refreshFps() {
        val direct = !aa.transcode
        fpsSeg?.setSelected(FpsSetting.values().filter { it != FpsSetting.FPS60 }.indexOf(aa.fpsSetting).coerceAtLeast(0))
        fpsSeg?.setEnabled(!direct)
        fpsNote?.text = ctx.getString(if (direct) R.string.set_fps_direct else aa.fpsSetting.subRes)
    }

    // --- sound and microphone ---

    private fun buildSound() {
        val c = ctx
        body.addView(c.sectionTitle(c.getString(R.string.set_sound)))
        body.addView(ToggleRow(c, c.getString(R.string.set_usb_audio_title), c.getString(R.string.set_usb_audio_sub), aa.usbAudio) {
            aa.usbAudio = it
            refreshEqEntry()
        }.view)
        body.addView(eqEntry(), lp(MATCH, WRAP).margins(c, 0, 6, 0, 0))

        body.addView(c.sectionTitle(c.getString(R.string.set_music_rate)))
        for (r in MusicRate.values()) {
            val row = RadioRow(c, c.getString(r.titleRes), "") { setMusicRate(r) }
            rateRows[r] = row
            body.addView(row.view, lp(MATCH, WRAP))
        }
        note(c.getString(R.string.set_music_rate_note))
        refreshMusicRate()

        body.addView(c.sectionTitle(c.getString(R.string.set_mic)))
        for ((m, titleRes, subRes) in MICS) {
            val row = RadioRow(c, c.getString(titleRes), c.getString(subRes)) { setMic(m) }
            micRows[m] = row
            body.addView(row.view, lp(MATCH, WRAP))
        }
        micNote = note("", R.color.warn)
        refreshMic()

        callsRow = ToggleRow(c, c.getString(R.string.set_calls_title), c.getString(R.string.set_calls_sub), aa.callsToHu) { onCallsToggle(it) }
        body.addView(callsRow!!.view, lp(MATCH, WRAP).margins(c, 0, 12, 0, 0))
    }

    /** Inclusion asks READ_PHONE_STATE (API 31+); result is in onPermissionResult. */
    private fun onCallsToggle(on: Boolean) {
        aa.callsToHu = on
        if (!on || callsPermissionGranted()) return
        callsAwaitingPermission = true
        host.requestPermissions(arrayOf(Manifest.permission.READ_PHONE_STATE), REQ_PHONE_STATE)
    }

    private fun callsPermissionGranted() =
        Build.VERSION.SDK_INT < CallStateSource.PERMISSION_SDK ||
            ctx.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED

    /** Refusal to "Phone": check box return to off. */
    private fun onCallsPermissionResult() {
        if (!callsAwaitingPermission) return
        callsAwaitingPermission = false
        if (callsPermissionGranted()) return
        AppLog.i("звонки: READ_PHONE_STATE отказано, флажок «Сообщать ГУ о звонках» выключен")
        aa.callsToHu = false
        callsRow?.setChecked(false)
    }

    /** The line "Equalizer · preset › " for "Sound by USB": opens the screen EQ; without sound by USB darkened. */
    private fun eqEntry(): View {
        val c = ctx
        val sub = c.text("", 14f, 400, c.clr(R.color.on2))
        eqEntrySub = sub
        return c.hbox(12).apply {
            minimumHeight = c.dp(72)
            pad(c, 16, 12, 12, 12)
            background = c.rippleBg(c.shape(c.clr(R.color.surf), 20), 20)
            isClickable = true
            setOnClickListener { host.showSettingsSection(SettingsSection.EQ) }
            addView(c.vbox(2).apply {
                addView(c.text(c.getString(R.string.eq_title), 17f, 500))
                addView(sub)
            }, lp(0, WRAP, 1f))
            addView(c.icon(R.drawable.ic_chevron, 20, c.clr(R.color.on2)))
            eqEntryRow = this
        }.also { refreshEqEntry() }
    }

    private fun refreshEqEntry() {
        val sub = eqEntrySub ?: return
        val usb = aa.usbAudio
        sub.text = if (usb) ctx.eqPresetName(aa.eqPreset) else ctx.getString(R.string.eq_sum_usb_only)
        eqEntryRow?.alpha = if (usb) 1f else 0.45f
    }

    private fun setMusicRate(r: MusicRate) {
        aa.musicRate = r
        refreshMusicRate()
    }

    private fun refreshMusicRate() {
        for ((r, row) in rateRows) row.setSelected(aa.musicRate == r)
    }

    private fun setMic(m: MicMode) {
        aa.micMode = m
        if (m != MicMode.CAR && ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            host.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 2)
        }
        refreshMic()
    }

    private fun refreshMic() {
        val note = micNote ?: return
        for ((m, row) in micRows) row.setSelected(aa.micMode == m)
        val missing = ctx.micMissing(aa)
        note.text = if (missing) ctx.getString(R.string.mic_no_permission) else ""
        note.visibility = if (missing) View.VISIBLE else View.GONE
    }

    // --- equalizer ---

    private fun buildEq() {
        val c = ctx
        eqUsbNote = note(c.getString(R.string.eq_usb_only), R.color.warn)
        body.addView(c.sectionTitle(c.getString(R.string.eq_presets)))
        // each button a separate pill, two in a row
        val grid = c.vbox(8)
        for (row in EQ_PRESETS.chunked(2)) {
            val line = c.hbox(8)
            for ((p, titleRes) in row) {
                val b = c.text(c.getString(titleRes), 15f, 600).apply {
                    gravity = Gravity.CENTER
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    pad(c, 8, 0, 8, 0)
                    isClickable = true
                    setOnClickListener { pickEqPreset(p) }
                }
                eqPresetBtns.add(b to p)
                line.addView(b, lp(0, c.dp(48), 1f))
            }
            grid.addView(line, lp(MATCH, WRAP))
        }
        body.addView(grid, lp(MATCH, WRAP))

        body.addView(c.sectionTitle(c.getString(R.string.eq_bands)))
        val bands = c.card().apply { pad(c, 0, 6, 0, 6) }
        for ((i, f) in EQ_FREQS.withIndex()) {
            val (row, bar, value) = eqSliderRow(freqLabel(f), EQ_BAND_MIN, EQ_BAND_MAX) { setEqBand(i, it) }
            eqBars.add(bar)
            eqValues.add(value)
            bands.addView(row)
        }
        body.addView(bands, lp(MATCH, WRAP))

        body.addView(c.sectionTitle(c.getString(R.string.eq_preamp)))
        val pre = c.card().apply { pad(c, 0, 6, 0, 6) }
        val (preRow, preBar, preValue) = eqSliderRow("", EQ_PREAMP_MIN, EQ_PREAMP_MAX) { setEqPreamp(it) }
        eqPreBar = preBar
        eqPreValue = preValue
        pre.addView(preRow)
        body.addView(pre, lp(MATCH, WRAP))

        body.addView(c.tonalButton(c.getString(R.string.eq_reset)).apply { setOnClickListener { pickEqPreset(EqPreset.FLAT) } },
            c.lpDp(MATCH, 52).margins(c, 0, 12, 0, 0))
        note(c.getString(R.string.eq_note))
        refreshEq()
    }

    /** Line "Signature | slider | value in dB"; onChange from finger only. */
    private fun eqSliderRow(label: String, min: Int, max: Int, onChange: (Int) -> Unit): Triple<View, SeekBar, TextView> {
        val c = ctx
        val value = c.text("", 14f, 500, c.clr(R.color.on2)).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
        val bar = SeekBar(c).apply {
            this.max = max - min
            progressTintList = ColorStateList.valueOf(c.clr(R.color.acc))
            thumbTintList = ColorStateList.valueOf(c.clr(R.color.acc))
            progressBackgroundTintList = ColorStateList.valueOf(c.clr(R.color.line))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) onChange(progress + min)
                }

                override fun onStartTrackingTouch(sb: SeekBar) {}

                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        val row = c.hbox(4).apply {
            pad(c, 16, 0, 16, 0)
            if (label.isNotEmpty()) addView(c.text(label, 14f, 500).apply { maxLines = 1 }, c.lpDp(c.dp(64), 44))
            addView(bar, c.lpDp(0, 44, 1f))
            addView(value, c.lpDp(c.dp(64), 44))
        }
        return Triple(row, bar, value)
    }

    private fun freqLabel(f: Int): String {
        if (f < 1000) return ctx.getString(R.string.eq_hz, f.toString())
        val nf = NumberFormat.getInstance(ctx.resources.configuration.locales[0]).apply { maximumFractionDigits = 1 }
        return ctx.getString(R.string.eq_khz, nf.format(f / 1000.0))
    }

    private fun dbLabel(v: Int): String = ctx.getString(R.string.eq_db, if (v > 0) "+$v" else if (v < 0) "−${-v}" else "0")

    /** Any slider movement = "Own"; its values are remembered separately. */
    private fun applyCustomEq(s: EqSettings) {
        aa.eq = s
        aa.eqCustom = s
        aa.eqPreset = EqPreset.CUSTOM
        refreshEq()
    }

    private fun setEqBand(i: Int, v: Int) {
        val cur = aa.eq
        applyCustomEq(cur.copy(bands = cur.bands.toMutableList().also { it[i] = v }))
    }

    private fun setEqPreamp(v: Int) = applyCustomEq(aa.eq.copy(preamp = v))

    private fun pickEqPreset(p: EqPreset) {
        aa.eq = p.settings ?: aa.eqCustom
        aa.eqPreset = p
        refreshEq()
    }

    private fun refreshEq() {
        val usbNote = eqUsbNote ?: return
        val s = aa.eq
        for ((i, bar) in eqBars.withIndex()) {
            val v = s.bands[i]
            if (bar.progress != v - EQ_BAND_MIN) bar.progress = v - EQ_BAND_MIN
            eqValues[i].text = dbLabel(v)
        }
        eqPreBar?.let { if (it.progress != s.preamp - EQ_PREAMP_MIN) it.progress = s.preamp - EQ_PREAMP_MIN }
        eqPreValue?.text = dbLabel(s.preamp)
        val preset = aa.eqPreset
        val c = ctx
        for ((b, p) in eqPresetBtns) {
            val on = p == preset
            b.background = c.rippleBg(if (on) c.shape(c.clr(R.color.pric), 24) else c.shape(0, 24, c.clr(R.color.line), 1.5f), 24)
            b.setTextColor(c.clr(if (on) R.color.onpric else R.color.on))
            val title = c.eqPresetName(p)
            b.text = if (on) "✓ $title" else title
        }
        usbNote.visibility = if (aa.usbAudio) View.GONE else View.VISIBLE
    }

    // --- head unit ---

    private fun buildHeadunit() {
        val c = ctx
        body.addView(c.sectionTitle(c.getString(R.string.set_cert)))
        body.addView(certCard())
        body.addView(c.sectionTitle(c.getString(R.string.set_car_make)))
        val value = c.text("", 13f, 400, c.clr(R.color.on2))
        makeValue = value
        val row = c.hbox(14).apply {
            pad(c, 16, 0, 16, 0)
            background = c.rippleBg(null, 0)
            isClickable = true
            setOnClickListener { pickCarMake() }
            addView(c.icon(R.drawable.ic_car, 22, c.clr(R.color.on2)))
            addView(c.text(c.getString(R.string.set_car_make), 16f, 500), lp(0, WRAP, 1f))
            addView(value)
            addView(c.icon(R.drawable.ic_chevron, 20, c.clr(R.color.on2)))
            layoutParams = c.lpDp(MATCH, 56)
        }
        body.addView(c.card().apply { addView(row) }, lp(MATCH, WRAP))
        note(c.getString(R.string.set_car_make_note))
        refreshCarMake()
    }

    private fun pickCarMake() {
        val makes = listOf<String?>(null) + CAR_MAKES
        val labels = makes.map { it ?: ctx.getString(R.string.set_car_make_default) }.toTypedArray()
        AlertDialog.Builder(ctx, R.style.Theme_Headswap_Dialog)
            .setTitle(R.string.set_car_make)
            .setSingleChoiceItems(labels, makes.indexOf(aa.carMake)) { d, i ->
                aa.carMake = makes[i]
                refreshCarMake()
                d.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun refreshCarMake() {
        makeValue?.text = aa.carMake ?: ctx.getString(R.string.set_car_make_default_short)
    }

    private fun certCard(): View {
        val c = ctx
        val card = c.vbox(8).apply {
            background = c.shape(c.clr(R.color.surf), 20)
            pad(c, 12, 12, 12, 12)
        }
        val sets = listOf("jvc", "hur")
        val seg = Segmented(c, sets.map { it.uppercase() }, 16f) {
            aa.certSet = sets[it]
            certSeg?.setSelected(it)
        }
        certSeg = seg
        seg.setSelected(sets.indexOf(aa.certSet))
        card.addView(seg.view, lp(MATCH, WRAP))
        card.addView(c.text(c.getString(R.string.set_cert_hint), 14f, 400, c.clr(R.color.on2)).apply { pad(c, 6, 0, 6, 0) })
        return card
    }

    // --- magnetola applications ---

    private fun buildApps() {
        val c = ctx
        body.addView(
            c.hbox(12).apply {
                background = c.shape(c.clr(R.color.warnc), 16)
                pad(c, 14, 12, 14, 12)
                addView(c.icon(R.drawable.ic_alert, 22, c.clr(R.color.warn)))
                addView(c.text(c.getString(R.string.apps_warn_banner), 14f, 500).apply { lineHeight = c.dp(20) }, lp(0, WRAP, 1f))
            },
            lp(MATCH, WRAP).margins(c, 0, 8, 0, 0),
        )

        appsHost = c.vbox(6).also { body.addView(it, lp(MATCH, WRAP)) }

        body.addView(c.sectionTitle(c.getString(R.string.apps_hint_title)))
        note(c.getString(R.string.apps_hint_text))
        refreshApps()
    }

    private fun appButton(label: String, onClick: () -> Unit): TextView {
        val c = ctx
        return c.text(label, 15f, 600, c.clr(R.color.acc)).apply {
            gravity = Gravity.CENTER
            pad(c, 14, 0, 14, 0)
            background = c.rippleBg(null, 20)
            isClickable = true
            setOnClickListener { onClick() }
        }
    }

    /** Found on the phone: the icon and name of the application itself, a description from the catalog (if any), a signature about “Unknown sources”. */
    private fun installedAppRow(pkg: String, label: CharSequence, icon: Drawable?, catalog: HuApp?): View {
        val c = ctx
        val canOpen = HuApps.launchIntent(c, pkg) != null
        return c.hbox(12).apply {
            pad(c, 16, 12, 12, 12)
            icon?.let { addView(ImageView(c).apply { setImageDrawable(it) }, c.lpDp(c.dp(24), 24)) }
            addView(c.vbox(4).apply {
                addView(c.text(label, 17f, 500))
                catalog?.let { addView(c.text(c.getString(it.descRes), 14f, 400, c.clr(R.color.on2))) }
                if (catalog != null && HuApps.status(c, catalog).unsupported) {
                    addView(c.text(c.getString(R.string.apps_unsupported), 13f, 400, c.clr(R.color.warn)))
                }
            }, lp(0, WRAP, 1f))
            if (canOpen) addView(appButton(c.getString(R.string.apps_open)) { HuApps.open(c, pkg) }, c.lpDp(WRAP, 44))
        }
    }

    /** Record the catalog, which is not on the phone: description, requirement, "Download". */
    private fun recommendedAppRow(app: HuApp): View {
        val c = ctx
        val unsupported = HuApps.status(c, app).unsupported
        return c.vbox(6).apply {
            pad(c, 16, 12, 12, 8)
            addView(c.text(app.name, 17f, 500))
            addView(c.text(c.getString(app.descRes), 14f, 400, c.clr(R.color.on2)))
            app.reqRes?.let { addView(c.text(c.getString(it), 13f, 400, c.clr(R.color.on2))) }
            if (unsupported) {
                addView(c.text(c.getString(R.string.apps_unsupported), 13f, 400, c.clr(R.color.warn)))
            } else {
                addView(c.hbox(4).apply {
                    gravity = Gravity.END or Gravity.CENTER_VERTICAL
                    addView(appButton(c.getString(R.string.apps_download)) { HuApps.download(c, app) }, c.lpDp(WRAP, 44))
                }, lp(MATCH, WRAP))
            }
        }
    }

    /** The search for PackageManager: is called every time you return to the screen (after the browser and installer). */
    private fun refreshApps() {
        val host = appsHost ?: return
        val c = ctx
        val pm = c.packageManager
        val sel = HuApps.detect(c)
        host.removeAllViews()

        host.addView(c.sectionTitle(c.getString(R.string.apps_installed_title)))
        val rows = sel.installed.mapNotNull { pkg ->
            try {
                val info = pm.getApplicationInfo(pkg, 0)
                Triple(pkg, info.loadLabel(pm), info)
            } catch (_: PackageManager.NameNotFoundException) {
                null
            }
        }.sortedBy { it.second.toString().lowercase() }
        if (rows.isEmpty()) {
            host.addView(c.text(c.getString(R.string.apps_installed_empty), 14f, 400, c.clr(R.color.on2)).apply { lineHeight = c.dp(20) })
        } else {
            // one signature for the entire section: in the list of applications only not from Play
            host.addView(c.text(c.getString(R.string.apps_installed_note), 13f, 400, c.clr(R.color.on2)).apply { lineHeight = c.dp(18) },
                lp(MATCH, WRAP).apply { bottomMargin = c.dp(8) })
            val card = c.card()
            for ((i, r) in rows.withIndex()) {
                if (i > 0) card.addView(c.divider())
                val icon = try { r.third.loadIcon(pm) } catch (_: Exception) { null }
                card.addView(installedAppRow(r.first, r.second, icon, HuApps.CATALOG.firstOrNull { it.pkg == r.first }), lp(MATCH, WRAP))
            }
            host.addView(card, lp(MATCH, WRAP))
        }

        if (sel.recommended.isNotEmpty()) {
            host.addView(c.sectionTitle(c.getString(R.string.apps_recommended_title)))
            val card = c.card()
            for ((i, app) in sel.recommended.withIndex()) {
                if (i > 0) card.addView(c.divider())
                card.addView(recommendedAppRow(app), lp(MATCH, WRAP))
            }
            host.addView(card, lp(MATCH, WRAP))
        }
    }

    /** Once for installation: video on the go do not block, responsibility on the driver. */
    private fun maybeShowAppsWarning() {
        if (aa.appsWarningShown) return
        aa.appsWarningShown = true
        AlertDialog.Builder(ctx, R.style.Theme_Headswap_Dialog)
            .setTitle(R.string.apps_warn_title)
            .setMessage(R.string.apps_warn_text)
            .setPositiveButton(R.string.apps_warn_ok, null)
            .show()
    }

    // --- updates ---

    /** Check switch + status bar with "Check" / "Update" button (like autostart card). */
    private fun updateCard(): View {
        val c = ctx
        val prefs = UpdatePrefs(c)
        val card = c.vbox().apply { background = c.shape(c.clr(R.color.surf), 20) }
        card.addView(
            ToggleRow(c, c.getString(R.string.upd_check_title), c.getString(R.string.upd_check_sub), prefs.enabled, 72, false, 17f, 14f) {
                prefs.enabled = it
                if (it) Updater.maybeCheck(c)
            }.view,
            lp(MATCH, WRAP),
        )
        card.addView(c.divider())
        val row = c.hbox(12).apply { pad(c, 16, 10, 12, 10); minimumHeight = c.dp(64) }
        val status = c.text("", 14f, 400, c.clr(R.color.on2))
        val button = c.text("", 15f, 600, c.clr(R.color.acc)).apply {
            gravity = Gravity.CENTER
            pad(c, 14, 0, 14, 0)
            background = c.rippleBg(null, 20)
            isClickable = true
            setOnClickListener { if (Updater.available(c) != null) Updater.start(host, confirm = false) else Updater.check(c, manual = true) }
        }
        updStatus = status
        updButton = button
        row.addView(status, lp(0, WRAP, 1f))
        row.addView(button, c.lpDp(WRAP, 44))
        card.addView(row, lp(MATCH, WRAP))
        refreshUpdate()
        return card
    }

    private fun refreshUpdate() {
        val status = updStatus ?: return
        val button = updButton ?: return
        val c = ctx
        val (text, color) = c.updateStatus()
        if (status.text.toString() != text) status.text = text
        status.setTextColor(c.clr(color))
        val label = c.getString(if (Updater.available(c) != null) R.string.upd_btn_update_now else R.string.upd_btn_check)
        if (button.text.toString() != label) button.text = label
        button.visibility = if (Updater.step != Updater.Step.IDLE) View.GONE else View.VISIBLE
    }
}
