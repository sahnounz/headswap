package com.yegorkapomidorka.headswap.ui

import android.content.Context
import android.content.res.Configuration
import com.yegorkapomidorka.headswap.CarLifeState
import com.yegorkapomidorka.headswap.R
import com.yegorkapomidorka.headswap.aa.AaPrefs
import com.yegorkapomidorka.headswap.dex.DelegatedDesktopAccess
import com.yegorkapomidorka.headswap.dex.DesktopAudioState
import com.yegorkapomidorka.headswap.dex.DexPrefs
import com.yegorkapomidorka.headswap.dex.DexState
import com.yegorkapomidorka.headswap.dex.PhoneActivationState
import java.util.Locale

/** The main screen in the mode of "Your launcher": the same skeleton as that of Android Auto (HomeState), but its stages, texts and buttons. */
enum class DesktopPhase { ACTIVATE, WAITING, RECOVERING, ERROR, WORKING }

/** Buttons at the bottom of the screen, top to bottom. */
enum class DesktopButton { ACTIVATE, STOP, DIAG }

data class DesktopHomeState(
    val phase: DesktopPhase,
    val tone: Tone,
    val icon: HeroIcon,
    val title: UiText,
    val sub: UiText,
    val stages: List<Pair<UiText, Pair<StageState, UiText>>>,
    val buttons: List<DesktopButton>,
)

class DesktopInputs(
    /** Activating READY: without it will not start the desktop. */
    val activationReady: Boolean,
    /** The adb key is already accepted by the phone: no reactivation code is needed. */
    val paired: Boolean,
    /** The bridge service works (CarLifeState.running). */
    val bridgeRunning: Boolean,
    /** The desktop on the virtual display is running (DexState.running). */
    val desktopRunning: Boolean,
    /** DexState.status is now - recovery string (desk_recovering). */
    val recovering: Boolean,
    val status: String,
    val error: String?,
    val wireless: Boolean = false,
    /** The video size of the current session ("1280×720"). */
    val videoRes: String = "1280×720",
    /** The signature of the selected launcher on the machine screen. */
    val desktopName: String = "Headswap",
    /** The sound is on the CarLife channel (the capture works and there is no silence in it); otherwise - Bluetooth magnetols. */
    val usbAudio: Boolean = false,
)

object DesktopHomeLogic {
    val LABELS = listOf(
        UiText.res(R.string.home_desk_stage_activation), UiText.res(R.string.home_desk_stage_headunit),
        UiText.res(R.string.home_desk_stage_desktop), UiText.res(R.string.home_desk_stage_video),
        UiText.res(R.string.home_desk_stage_audio),
    )
    private val NONE = UiText.raw("")
    private val CARLIFE = UiText.raw("CarLife 2.0")
    private val CARLIFE_WIFI = UiText.raw("CarLife 2.0 · Wi-Fi")
    private val ACTIVE = UiText.res(R.string.home_desk_st_active)

    /** Check order: activation, error, recovery, working, waiting. */
    fun compute(i: DesktopInputs): DesktopHomeState = when {
        !i.activationReady -> state(
            DesktopPhase.ACTIVATE, Tone.PRI, HeroIcon.KEY, UiText.res(R.string.home_desk_title_activate),
            UiText.res(if (i.paired) R.string.home_desk_sub_activate_paired else R.string.home_desk_sub_activate),
            listOf(
                StageState.TODO to UiText.res(if (i.paired) R.string.home_desk_st_needed_again else R.string.home_desk_st_needed),
                StageState.PEND to NONE, StageState.PEND to NONE, StageState.PEND to NONE, StageState.PEND to NONE,
            ),
            listOf(DesktopButton.ACTIVATE),
        )
        // true error resets (BridgeService), and the broken one leaves both it and the line "(1/2)...": then this is an error
        i.error != null -> state(
            DesktopPhase.ERROR, Tone.ERR, HeroIcon.ALERT, UiText.res(R.string.home_desk_title_error), UiText.raw(i.error),
            listOf(
                StageState.DONE to ACTIVE,
                if (i.bridgeRunning) StageState.DONE to (if (i.wireless) CARLIFE_WIFI else CARLIFE) else StageState.PEND to NONE,
                StageState.ERR to UiText.res(R.string.home_desk_st_error), StageState.PEND to NONE, StageState.PEND to NONE,
            ),
            // “Stop” only while the session is alive
            if (i.bridgeRunning) listOf(DesktopButton.DIAG, DesktopButton.STOP) else listOf(DesktopButton.DIAG),
        )
        i.recovering && i.bridgeRunning -> state(
            DesktopPhase.RECOVERING, Tone.WARN, HeroIcon.REFRESH, UiText.res(R.string.home_title_reconnecting), UiText.raw(i.status),
            listOf(
                StageState.DONE to ACTIVE, StageState.ACTIVE to UiText.res(R.string.home_stage_reconnecting),
                StageState.PAUSE to UiText.res(R.string.home_stage_paused), StageState.PAUSE to UiText.res(R.string.home_stage_paused),
                StageState.PAUSE to UiText.res(R.string.home_stage_paused),
            ),
            listOf(DesktopButton.STOP),
        )
        i.bridgeRunning && i.desktopRunning -> state(
            DesktopPhase.WORKING, Tone.OK, if (i.wireless) HeroIcon.CAR_WIFI else HeroIcon.CAR_USB, UiText.res(R.string.home_title_ok),
            UiText.res(R.string.home_desk_sub_ok, i.desktopName),
            listOf(
                StageState.DONE to ACTIVE,
                StageState.DONE to (if (i.wireless) CARLIFE_WIFI else CARLIFE),
                StageState.DONE to UiText.raw(i.desktopName),
                StageState.DONE to UiText.raw(i.videoRes),
                StageState.DONE to UiText.raw(if (!i.usbAudio) "Bluetooth" else if (i.wireless) "Wi-Fi" else "USB"),
            ),
            listOf(DesktopButton.STOP),
        )
        // the service is already up (the cable is there), the desktop is still starting: do not ask for a cable that has already been plugged
        i.bridgeRunning -> state(
            DesktopPhase.WAITING, Tone.PRI, HeroIcon.PLUG, UiText.res(R.string.home_title_connecting),
            UiText.res(R.string.home_desk_sub_starting),
            listOf(
                StageState.DONE to ACTIVE, StageState.ACTIVE to UiText.res(R.string.home_stage_connecting),
                StageState.PEND to NONE, StageState.PEND to NONE, StageState.PEND to NONE,
            ),
            listOf(DesktopButton.STOP),
        )
        else -> state(
            DesktopPhase.WAITING, Tone.PRI, HeroIcon.PLUG, UiText.res(R.string.home_title_ready), UiText.res(R.string.home_sub_ready),
            listOf(
                StageState.DONE to ACTIVE, StageState.ACTIVE to UiText.res(R.string.home_stage_wait_cable),
                StageState.PEND to NONE, StageState.PEND to NONE, StageState.PEND to NONE,
            ),
            emptyList(),
        )
    }

    private fun state(
        phase: DesktopPhase, tone: Tone, icon: HeroIcon, title: UiText, sub: UiText,
        stages: List<Pair<StageState, UiText>>, buttons: List<DesktopButton>,
    ) = DesktopHomeState(phase, tone, icon, title, sub, LABELS.zip(stages), buttons)
}

/** DesktopInputs assembly from DexState/CarLifeState/DelegatedDesktopAccess; called with main thread. */
object DesktopHomeStatus {
    /** desk_recovering lines of all languages: the bridge service writes the status in its locale, the screen can be in another. */
    private var recoveringTexts: Set<String>? = null

    /** launcher signature by saved "package/class"; PackageManager does not pull on every tick. */
    private var labelFor: Pair<String, String>? = null

    fun inputs(ctx: Context): DesktopInputs {
        val status = DexState.status
        val aa = AaPrefs(ctx)
        return DesktopInputs(
            activationReady = DelegatedDesktopAccess.status(ctx) == DelegatedDesktopAccess.Status.READY,
            paired = PhoneActivationState(ctx).paired,
            bridgeRunning = CarLifeState.running,
            desktopRunning = DexState.running,
            recovering = status in recoveringTexts(ctx),
            status = status,
            error = DexState.error,
            wireless = CarLifeState.wirelessSession,
            videoRes = (CarLifeState.videoMode ?: aa.videoGeometry.effective(aa.transcode)).outLabel,
            desktopName = desktopName(ctx),
            usbAudio = DesktopAudioState.running && DesktopAudioState.nonSilentBuffers > 0,
        )
    }

    fun current(ctx: Context): DesktopHomeState = DesktopHomeLogic.compute(inputs(ctx))

    private fun recoveringTexts(ctx: Context): Set<String> = recoveringTexts ?: buildSet {
        val locales = listOf(Locale.forLanguageTag("ru"), Locale.ENGLISH, ctx.resources.configuration.locales[0])
        for (loc in locales) {
            val lctx = ctx.createConfigurationContext(Configuration(ctx.resources.configuration).apply { setLocale(loc) })
            for (n in 1..2) add(lctx.getString(R.string.desk_recovering, n))
        }
    }.also { recoveringTexts = it }

    private fun desktopName(ctx: Context): String {
        val component = DexPrefs(ctx).desktopComponent ?: return "Headswap"
        labelFor?.let { if (it.first == component) return it.second }
        val pkg = component.substringBefore('/')
        val label = runCatching {
            val pm = ctx.packageManager
            pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString()
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: pkg
        labelFor = component to label
        return label
    }
}
