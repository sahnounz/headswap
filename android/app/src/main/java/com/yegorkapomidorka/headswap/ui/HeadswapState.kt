package com.yegorkapomidorka.headswap.ui

import android.content.Context
import android.hardware.usb.UsbManager
import android.os.SystemClock
import com.yegorkapomidorka.headswap.CarLifeState
import com.yegorkapomidorka.headswap.R
import com.yegorkapomidorka.headswap.aa.AaCreds
import com.yegorkapomidorka.headswap.aa.AaPrefs
import com.yegorkapomidorka.headswap.aa.AaServerCheck
import com.yegorkapomidorka.headswap.aa.AaState

/** Status summary for main screen and notification. Pure logic without Android: counts from Inputs. */
enum class Phase { OFF, WAITING, CONNECTED, RECONNECTING, PARKED, ERROR }

enum class StageState { DONE, ACTIVE, PAUSE, ERR, TODO, PEND }

enum class Tone { PRI, OK, WARN, ERR }

enum class HeroIcon { POWER, PLUG, CAR, CAR_USB, CAR_WIFI, REFRESH, ALERT, KEY }

enum class PrimaryAction { START_SERVER }

enum class OutlineAction { OPEN_AA }

data class HomeState(
    val phase: Phase,
    val tone: Tone,
    val icon: HeroIcon,
    val title: UiText,
    val sub: UiText,
    val stages: List<Pair<UiText, Pair<StageState, UiText>>>,
    /** The remaining seconds are grace (only RECONNECTING), otherwise -1. */
    val graceLeftS: Int,
    val graceTotalS: Int,
    val primary: PrimaryAction?,
    val outline: OutlineAction?,
    val logLink: Boolean,
    val showChain: Boolean,
    val notifTitle: UiText,
    val notifText: UiText,
)

class Inputs(
    val bridgeRunning: Boolean,
    val aaStatus: String,
    val protocolKnown: Boolean,
    /** The head unit AA server is running on a sample when the screen is opened; null - did not check or the sample did not give an answer. */
    val serverRunning: Boolean? = null,
    /** The connected session ended (break USB or "Stop"), AA closed normally: the head server unit was to remain. */
    val ended: Boolean,
    val graceLeftS: Int,
    val graceTotalS: Int,
    val usbAudio: Boolean,
    /** Bridge does not start due to configuration (no head unit certificate): text for user. */
    val setupError: UiText? = null,
    /** Video size on head unit ("1280×720"): actual session mode. */
    val videoRes: String = "1280×720",
    /** The session is on Wi-Fi: CarLife and the sound goes to head unit on wireless connection, there is no cable. */
    val wireless: Boolean = false,
    /** How many ms server head unit refuses (ECONNREFUSED) real bridge connections; 0 = does not refuse. */
    val refusedForMs: Long = 0,
    /** How many consecutive bridge attempts have been made on the TCP without the VERSION_RESPONSE (hanging server). */
    val versionHangs: Int = 0,
    /** At least once the real AA-session was installed: the head unit server worked. Without this, we do not show "not responding" (to the beginner - "Start the head unit server"). */
    val serverWorked: Boolean = false,
    /** Rejection is not in the window10 c after closing of our session: at the fresh start of the bridge server head unit not started, nothing to wait. */
    val refusedFresh: Boolean = false,
    /** Auto Test of Server head unit (short session) AA, ~1 (c) */
    val serverChecking: Boolean = false,
)

object HomeLogic {
    /** The head unit server failure is shorter than this - the norm: after ByeBye AA again listens to the port through about 6 s. */
    const val REFUSED_DOWN_MS = 15_000L

    /** So many attempts in a row "TCP is, VERSION_RESPONSE is not" - the server is stuck. */
    const val HANGS_DOWN = 2

    val LABELS = listOf(
        UiText.res(R.string.home_stage_server), UiText.res(R.string.home_stage_headunit), UiText.res(R.string.home_stage_aa),
        UiText.res(R.string.home_stage_video), UiText.res(R.string.home_stage_audio),
    )
    private val NONE = UiText.raw("")
    private val CARLIFE = UiText.raw("CarLife 2.0")
    private val CARLIFE_WIFI = UiText.raw("CarLife 2.0 · Wi-Fi")
    private val TEXT_START_CHAIN = UiText.res(R.string.home_sub_start_chain)

    fun compute(i: Inputs): HomeState {
        val st = i.aaStatus
        val reconnecting = i.bridgeRunning && "USB потерян" in st
        val connected = i.bridgeRunning && st.startsWith("мост: AA -> CarLife")
        val refused = i.bridgeRunning && "жду сервер ГУ" in st
        val total = if (i.graceTotalS > 0) i.graceTotalS else 8
        // cable is there, the bridge tries to connect, and the server that used to work does not respond to real attempts
        val serverDown = i.bridgeRunning && i.serverWorked && (i.refusedForMs >= REFUSED_DOWN_MS || i.versionHangs >= HANGS_DOWN)
        return when {
            i.setupError != null && !i.bridgeRunning -> state(
                Phase.ERROR, Tone.ERR, HeroIcon.ALERT, UiText.res(R.string.home_title_no_bridge), i.setupError,
                listOf(
                    StageState.ERR to UiText.res(R.string.home_stage_no_cert), StageState.PEND to NONE, StageState.PEND to NONE,
                    StageState.PEND to NONE, StageState.PEND to NONE,
                ),
                logLink = true,
                notifTitle = UiText.res(R.string.home_title_no_bridge), notifText = i.setupError,
            )
            reconnecting -> {
                val left = i.graceLeftS.coerceAtLeast(0)
                state(
                    Phase.RECONNECTING, Tone.WARN, HeroIcon.REFRESH, UiText.res(R.string.home_title_reconnecting),
                    UiText.res(R.string.home_sub_reconnecting, total),
                    listOf(
                        StageState.DONE to UiText.res(R.string.home_stage_holds), StageState.ACTIVE to UiText.res(R.string.home_stage_reconnecting),
                        StageState.PAUSE to UiText.res(R.string.home_stage_paused), StageState.PAUSE to UiText.res(R.string.home_stage_paused),
                        StageState.PAUSE to UiText.res(R.string.home_stage_paused),
                    ),
                    graceLeft = left, graceTotal = total,
                    notifText = UiText.res(R.string.notif_reconnecting, left),
                )
            }
            connected -> state(
                Phase.CONNECTED, Tone.OK, if (i.wireless) HeroIcon.CAR_WIFI else HeroIcon.CAR_USB, UiText.res(R.string.home_title_ok),
                UiText.res(if (i.wireless) R.string.home_sub_ok_wifi else R.string.home_sub_ok),
                listOf(
                    StageState.DONE to UiText.res(R.string.home_stage_working),
                    StageState.DONE to if (i.wireless) CARLIFE_WIFI else CARLIFE,
                    StageState.DONE to UiText.res(R.string.home_stage_connected),
                    StageState.DONE to UiText.raw(i.videoRes),
                    // Headswap (formerly USB Sound) is a CarLife channel sound; without cable, it runs on Wi-Fi
                    if (i.usbAudio) StageState.DONE to UiText.raw(if (i.wireless) "Wi-Fi" else "USB")
                    else StageState.PEND to UiText.res(R.string.home_stage_off),
                ),
                notifText = UiText.res(
                    when {
                        !i.usbAudio -> R.string.notif_video
                        i.wireless -> R.string.notif_video_wifi
                        else -> R.string.notif_video_usb
                    },
                ),
            )
            serverDown -> serverDownState()
            // “Start the server head unit” immediately, if the server did not work, the failure is not in the window after the closure of our session
            // or the sample said "failure". Short failure after ByeBye at the running server is the norm,
            // while AA is listening again; waiting as usual connection. Sample "started" - also just "Connect"
            refused && i.serverRunning != true && (!i.serverWorked || i.refusedFresh || i.serverRunning == false) -> state(
                Phase.OFF, Tone.PRI, HeroIcon.POWER, UiText.res(R.string.home_title_off),
                UiText.res(R.string.home_sub_refused, TEXT_START_CHAIN),
                listOf(
                    StageState.TODO to UiText.res(R.string.home_stage_not_started), StageState.DONE to CARLIFE, StageState.PEND to NONE,
                    StageState.PEND to NONE, StageState.PEND to NONE,
                ),
                primary = PrimaryAction.START_SERVER, showChain = true,
                notifText = UiText.res(R.string.notif_server_off),
            )
            i.bridgeRunning -> state(
                Phase.WAITING, Tone.PRI, HeroIcon.PLUG, UiText.res(R.string.home_title_connecting),
                UiText.res(if ("отключился" in st) R.string.home_sub_aa_dropped else R.string.home_sub_cable_ok),
                listOf(
                    StageState.ACTIVE to UiText.res(R.string.home_stage_connecting),
                    if (i.protocolKnown) StageState.DONE to CARLIFE else StageState.ACTIVE to UiText.res(R.string.home_stage_handshake),
                    StageState.PEND to NONE, StageState.PEND to NONE, StageState.PEND to NONE,
                ),
                notifText = UiText.res(R.string.notif_cable_waiting),
            )
            i.serverRunning == false -> offState()
            i.ended -> parkedState(i.serverRunning == true)
            i.serverRunning == true -> state(
                Phase.WAITING, Tone.PRI, HeroIcon.PLUG, UiText.res(R.string.home_title_ready),
                UiText.res(R.string.home_sub_ready),
                listOf(
                    StageState.DONE to UiText.res(R.string.home_stage_started),
                    StageState.ACTIVE to UiText.res(R.string.home_stage_wait_cable),
                    StageState.PEND to NONE, StageState.PEND to NONE, StageState.PEND to NONE,
                ),
                outline = OutlineAction.OPEN_AA,
                notifText = UiText.res(R.string.notif_server_started),
            )
            i.serverChecking -> state(
                Phase.WAITING, Tone.PRI, HeroIcon.POWER, UiText.res(R.string.home_title_checking), UiText.res(R.string.home_sub_checking),
                listOf(
                    StageState.ACTIVE to UiText.res(R.string.home_stage_checking), StageState.PEND to NONE, StageState.PEND to NONE,
                    StageState.PEND to NONE, StageState.PEND to NONE,
                ),
                notifText = UiText.res(R.string.home_title_checking),
            )
            // test did not give answer or has not yet been: whether the head unit server is running, do not know
            else -> unknownState()
        }
    }

    /** “Start the head unit server”: the trial failed or the server crashed. */
    private fun offState() = state(
        Phase.OFF, Tone.PRI, HeroIcon.POWER, UiText.res(R.string.home_title_off), TEXT_START_CHAIN,
        listOf(
            StageState.TODO to UiText.res(R.string.home_stage_not_started), StageState.PEND to NONE, StageState.PEND to NONE,
            StageState.PEND to NONE, StageState.PEND to NONE,
        ),
        primary = PrimaryAction.START_SERVER, showChain = true,
        notifText = UiText.res(R.string.notif_server_off),
    )

    /** “Check the head unit server”: the sample did not give an answer, neither “launched” nor “not running” is not approved. */
    private fun unknownState() = state(
        Phase.OFF, Tone.PRI, HeroIcon.POWER, UiText.res(R.string.home_title_unknown), UiText.res(R.string.home_sub_unknown),
        listOf(
            StageState.PEND to UiText.res(R.string.home_stage_unknown), StageState.PEND to NONE, StageState.PEND to NONE,
            StageState.PEND to NONE, StageState.PEND to NONE,
        ),
        primary = PrimaryAction.START_SERVER,
        notifText = UiText.res(R.string.notif_server_unknown),
    )

    /**
     * “The machine is off”: the bridge itself has closed the set session (ByeBye) on its own. Holds up to the new cable.
     * [serverKnownUp] - a sample after the trip confirmed that the server head unit remained; otherwise neutral text.
     */
    private fun parkedState(serverKnownUp: Boolean) = state(
        Phase.PARKED, Tone.PRI, HeroIcon.CAR, UiText.res(R.string.home_title_parked),
        UiText.res(if (serverKnownUp) R.string.home_sub_parked else R.string.home_sub_parked_unknown),
        listOf(
            StageState.DONE to UiText.res(R.string.home_stage_working),
            StageState.PEND to UiText.res(R.string.home_stage_hu_parked), StageState.PEND to NONE,
            StageState.PEND to NONE, StageState.PEND to NONE,
        ),
        notifText = UiText.res(if (serverKnownUp) R.string.notif_parked else R.string.notif_parked_unknown),
    )

    /** “head unit server does not respond”: real bridge connections do not receive a response (failure longer than REFUSED_DOWN_MS or HANGS_DOWN hangs), you need to turn on the server again. */
    private fun serverDownState() = state(
        Phase.ERROR, Tone.ERR, HeroIcon.ALERT, UiText.res(R.string.home_title_server_down), UiText.res(R.string.home_sub_server_down),
        listOf(
            StageState.ERR to UiText.res(R.string.home_stage_no_answer), StageState.PEND to NONE, StageState.PEND to NONE,
            StageState.PEND to NONE, StageState.PEND to NONE,
        ),
        primary = PrimaryAction.START_SERVER, logLink = true,
        notifText = UiText.res(R.string.notif_server_down),
    )

    private fun state(
        phase: Phase, tone: Tone, icon: HeroIcon, title: UiText, sub: UiText,
        stages: List<Pair<StageState, UiText>>,
        primary: PrimaryAction? = null, outline: OutlineAction? = null, logLink: Boolean = false,
        showChain: Boolean = false, graceLeft: Int = -1, graceTotal: Int = 0,
        notifTitle: UiText = title, notifText: UiText,
    ) = HomeState(
        phase, tone, icon, title, sub, LABELS.zip(stages), graceLeft, graceTotal,
        primary, outline, logLink, showChain, notifTitle, notifText,
    )
}

/**
 * Observed summary (only reading from CarLifeState/AaState + what UI-part knows itself):
 * collects Inputs and remembers the transitions (did the session break when they connected, to what point grace lasts).
 * Called with main thread: screen and ticker notification.
 */
object HeadswapStatus {
    private var prevRunning = false
    private var sawConnected = false
    private var ended = false
    private var graceDeadline = 0L
    private var graceTotalS = 0
    var connectedSince = 0L
        private set

    private val graceRe = Regex("""жива\s+(\d+)\s*с""")

    /** The user clicked "Open Android Auto" (to include the server head unit): remove "Machine is disabled". Whether the server is running, will say a sample when returning to the screen. */
    fun markServerRequested() {
        ended = false
        AaServerCheck.forgetAuto()
    }

    fun inputs(ctx: Context): Inputs {
        val now = SystemClock.elapsedRealtime()
        val running = CarLifeState.running
        val st = AaState.status
        val connectedNow = running && st.startsWith("мост: AA -> CarLife")
        val reconnectingNow = running && "USB потерян" in st

        if (running && !prevRunning) {
            ended = false
            sawConnected = false
            connectedSince = 0
            AaServerCheck.forgetAuto()      // after the trip, the server could fall: the result of the test before it is no longer true
        }
        if (connectedNow || reconnectingNow) sawConnected = true
        if (connectedNow) {
            if (connectedSince == 0L) connectedSince = System.currentTimeMillis()
        } else if (!reconnectingNow) {
            connectedSince = 0
        }
        if (reconnectingNow) {
            if (graceDeadline == 0L) {
                graceTotalS = graceRe.find(st)?.groupValues?.get(1)?.toIntOrNull() ?: 8
                graceDeadline = now + graceTotalS * 1000L
            }
        } else {
            graceDeadline = 0
        }
        if (!running && prevRunning) {
            if (sawConnected) ended = true
            sawConnected = false
            connectedSince = 0
        }
        prevRunning = running

        val aaPrefs = AaPrefs(ctx)
        // AaConnector knows about the installed sessions only in the process; the feature is memorized to survive the restart of the application
        if (AaState.serverFailures.worked && !aaPrefs.serverWorked) aaPrefs.serverWorked = true
        val serverWorked = aaPrefs.serverWorked
        val left = if (graceDeadline > 0) ((graceDeadline - now + 999) / 1000).toInt() else -1
        return Inputs(
            bridgeRunning = running,
            aaStatus = st,
            protocolKnown = CarLifeState.protocol != "-",
            serverRunning = AaServerCheck.autoRunning,
            serverChecking = AaServerCheck.autoBusy,
            ended = ended,
            graceLeftS = left,
            graceTotalS = graceTotalS,
            usbAudio = aaPrefs.usbAudio,
            // known text no-certificate replace resource, the rest of the errors show as is
            setupError = AaState.setupError?.let { if (it == AaCreds.MISSING_TEXT) UiText.res(R.string.home_err_no_cert) else UiText.raw(it) },
            videoRes = (CarLifeState.videoMode ?: aaPrefs.videoGeometry.effective(aaPrefs.transcode)).outLabel,
            wireless = CarLifeState.wirelessSession,
            refusedForMs = AaState.serverFailures.refusedForMs,
            versionHangs = AaState.serverFailures.versionHangs,
            serverWorked = serverWorked,
            refusedFresh = AaState.serverFailures.refusedFresh,
        )
    }

    fun current(ctx: Context): HomeState = HomeLogic.compute(inputs(ctx))

    /** Is USB-accessory connected (for screen prompts). */
    fun accessoryPresent(ctx: Context): Boolean =
        ctx.getSystemService(UsbManager::class.java).accessoryList?.isNotEmpty() == true
}
