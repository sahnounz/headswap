package com.yegorkapomidorka.headswap.ui

import com.yegorkapomidorka.headswap.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeLogicTest {
    private fun inp(
        running: Boolean = false,
        status: String = "не запущено",
        protocol: Boolean = false,
        serverRunning: Boolean? = null,
        ended: Boolean = false,
        graceLeft: Int = -1,
        usbAudio: Boolean = true,
        setupError: UiText? = null,
        wireless: Boolean = false,
        refusedMs: Long = 0,
        hangs: Int = 0,
        worked: Boolean = false,
        refusedFresh: Boolean = false,
        checking: Boolean = false,
    ) = Inputs(
        running, status, protocol, serverRunning, ended, graceLeft, 8, usbAudio, setupError, wireless = wireless,
        refusedForMs = refusedMs, versionHangs = hangs, serverWorked = worked, refusedFresh = refusedFresh, serverChecking = checking,
    )

    @Test
    fun checkingWhileAutoProbeRuns() {
        // test at the opening is, the result is not yet: not "Check", but "Check", without buttons
        val s = HomeLogic.compute(inp(checking = true))
        assertEquals(UiText.res(R.string.home_title_checking), s.title)
        assertEquals(StageState.ACTIVE to UiText.res(R.string.home_stage_checking), s.stages[0].second)
        assertEquals(null, s.primary)
        // the last result of the test is more important: while it goes again, the screen does not blink
        assertEquals(UiText.res(R.string.home_title_ready), HomeLogic.compute(inp(checking = true, serverRunning = true)).title)
        assertEquals(Phase.OFF, HomeLogic.compute(inp(checking = true, serverRunning = false)).phase)
        // Bridge is more important than trial
        assertEquals(Phase.WAITING, HomeLogic.compute(inp(checking = true, running = true, status = "мост: жду кабель")).phase)
    }

    @Test
    fun errorWhenCertificateMissing() {
        val err = UiText.res(R.string.home_err_no_cert)
        val s = HomeLogic.compute(inp(setupError = err))
        assertEquals(Phase.ERROR, s.phase)
        assertEquals(err, s.sub)
        assertEquals(Phase.WAITING, HomeLogic.compute(inp(serverRunning = true)).phase)
    }

    @Test
    fun offWhenNothingStarted() {
        // test failed: exactly not started
        val s = HomeLogic.compute(inp(serverRunning = false))
        assertEquals(Phase.OFF, s.phase)
        assertEquals(PrimaryAction.START_SERVER, s.primary)
        assertEquals(StageState.TODO, s.stages[0].second.first)
        // head unit server includes user: amber tone (not warn), header-call and block of three points
        assertEquals(Tone.PRI, s.tone)
        assertEquals(UiText.res(R.string.home_title_off), s.title)
        assertEquals(UiText.res(R.string.home_sub_start_chain), s.sub)
        assertEquals(true, s.showChain)
    }

    @Test
    fun waitingWhenServerStarted() {
        val s = HomeLogic.compute(inp(serverRunning = true))
        assertEquals(Phase.WAITING, s.phase)
        assertEquals(UiText.res(R.string.home_title_ready), s.title)
        assertEquals(OutlineAction.OPEN_AA, s.outline)
    }

    @Test
    fun unknownWhenProbeGaveNoAnswer() {
        // test did not give an answer (failure or not yet): neither "launched" nor "not launched"
        val s = HomeLogic.compute(inp(serverRunning = null))
        assertEquals(Phase.OFF, s.phase)
        assertEquals(Tone.PRI, s.tone)
        assertEquals(HeroIcon.POWER, s.icon)
        assertEquals(UiText.res(R.string.home_title_unknown), s.title)
        assertEquals(UiText.res(R.string.home_sub_unknown), s.sub)
        assertEquals(StageState.PEND to UiText.res(R.string.home_stage_unknown), s.stages[0].second)
        assertEquals(PrimaryAction.START_SERVER, s.primary)
        assertNull(s.outline)
        assertEquals(false, s.showChain)
        assertEquals(UiText.res(R.string.notif_server_unknown), s.notifText)
    }

    @Test
    fun parkedTextDependsOnServerProbe() {
        // test after the trip confirms the server: previous text about background
        val up = HomeLogic.compute(inp(ended = true, serverRunning = true))
        assertEquals(Phase.PARKED, up.phase)
        assertEquals(UiText.res(R.string.home_sub_parked), up.sub)
        assertEquals(UiText.res(R.string.notif_parked), up.notifText)
        // No answer given: neutral text
        val unknown = HomeLogic.compute(inp(ended = true, serverRunning = null))
        assertEquals(Phase.PARKED, unknown.phase)
        assertEquals(UiText.res(R.string.home_sub_parked_unknown), unknown.sub)
        assertEquals(UiText.res(R.string.notif_parked_unknown), unknown.notifText)
        // server stopped: "Start the server head unit"
        val down = HomeLogic.compute(inp(ended = true, serverRunning = false))
        assertEquals(Phase.OFF, down.phase)
        assertEquals(UiText.res(R.string.home_title_off), down.title)
    }

    @Test
    fun shortRefusalIsConnectingWhenProbeSaysRunning() {
        val status = "мост: жду сервер ГУ AA"
        val up = HomeLogic.compute(inp(running = true, status = status, protocol = true, serverRunning = true))
        assertEquals(Phase.WAITING, up.phase)
        assertEquals(UiText.res(R.string.home_title_connecting), up.title)
        // without sample result former "Start the server head unit"
        val unknown = HomeLogic.compute(inp(running = true, status = status, protocol = true, serverRunning = null))
        assertEquals(Phase.OFF, unknown.phase)
        assertEquals(UiText.res(R.string.home_title_off), unknown.title)
    }

    @Test
    fun freshRefusalOnWorkedServerIsStartServerImmediately() {
        val status = "мост: жду сервер ГУ AA"
        // fresh start of the bridge: failure is not in the window after closing its session - the server is not running, there is nothing to wait for
        val fresh = HomeLogic.compute(inp(running = true, status = status, protocol = true, worked = true, refusedFresh = true, refusedMs = 500))
        assertEquals(Phase.OFF, fresh.phase)
        assertEquals(UiText.res(R.string.home_title_off), fresh.title)
        assertEquals(PrimaryAction.START_SERVER, fresh.primary)
        // Immediately after the closure of its session, the refusal norm: “I connect”
        val afterBye = HomeLogic.compute(inp(running = true, status = status, protocol = true, worked = true, refusedFresh = false, refusedMs = 500))
        assertEquals(Phase.WAITING, afterBye.phase)
        assertEquals(UiText.res(R.string.home_title_connecting), afterBye.title)
        // sample said "failure": please run, even inside the window
        val stopped = HomeLogic.compute(inp(running = true, status = status, protocol = true, worked = true, serverRunning = false, refusedFresh = false, refusedMs = 500))
        assertEquals(Phase.OFF, stopped.phase)
        assertEquals(UiText.res(R.string.home_title_off), stopped.title)
        // test said "launched": freshness of refusal is not important
        val running = HomeLogic.compute(inp(running = true, status = status, protocol = true, worked = true, serverRunning = true, refusedFresh = true))
        assertEquals(Phase.WAITING, running.phase)
    }

    @Test
    fun connectedFromBridgeStatus() {
        val s = HomeLogic.compute(inp(running = true, status = "мост: AA -> CarLife (перекодирование)"))
        assertEquals(Phase.CONNECTED, s.phase)
        assertNull(s.outline)
        assertEquals(UiText.raw("USB"), s.stages[4].second.second)
        assertEquals(UiText.res(R.string.notif_video_usb), s.notifText)
        val noAudio = HomeLogic.compute(inp(running = true, status = "мост: AA -> CarLife", usbAudio = false))
        assertEquals(StageState.PEND, noAudio.stages[4].second.first)
        assertEquals(UiText.res(R.string.notif_video), noAudio.notifText)
        assertEquals(HeroIcon.CAR_USB, s.icon)
    }

    @Test
    fun connectedOverWifiShowsWifi() {
        val s = HomeLogic.compute(inp(running = true, status = "мост: AA -> CarLife (перекодирование)", wireless = true))
        assertEquals(Phase.CONNECTED, s.phase)
        assertEquals(HeroIcon.CAR_WIFI, s.icon)
        assertEquals(UiText.res(R.string.home_sub_ok_wifi), s.sub)
        assertEquals(UiText.raw("CarLife 2.0 · Wi-Fi"), s.stages[1].second.second)
        assertEquals(UiText.raw("Wi-Fi"), s.stages[4].second.second)
        assertEquals(UiText.res(R.string.notif_video_wifi), s.notifText)
        val noAudio = HomeLogic.compute(inp(running = true, status = "мост: AA -> CarLife", usbAudio = false, wireless = true))
        assertEquals(StageState.PEND, noAudio.stages[4].second.first)
        assertEquals(UiText.res(R.string.notif_video), noAudio.notifText)
    }

    @Test
    fun reconnectingShowsCountdown() {
        val s = HomeLogic.compute(inp(running = true, status = "мост: USB потерян, AA жива 8 с", graceLeft = 5))
        assertEquals(Phase.RECONNECTING, s.phase)
        assertEquals(5, s.graceLeftS)
        assertEquals(UiText.res(R.string.notif_reconnecting, 5), s.notifText)
        assertEquals(StageState.PAUSE, s.stages[4].second.first)
    }

    @Test
    fun parkedAfterSessionEnded() {
        // The bridge itself closed the set session: “The machine is disconnected”, head unit server in the background “works”, without “checking” and buttons
        val s = HomeLogic.compute(inp(ended = true, serverRunning = true))
        assertEquals(Phase.PARKED, s.phase)
        assertEquals(Tone.PRI, s.tone)
        assertEquals(HeroIcon.CAR, s.icon)
        assertEquals(UiText.res(R.string.home_title_parked), s.title)
        assertEquals(UiText.res(R.string.home_sub_parked), s.sub)
        assertNull(s.primary)
        assertNull(s.outline)
        assertEquals(false, s.logLink)
        assertEquals(StageState.DONE to UiText.res(R.string.home_stage_working), s.stages[0].second)
        assertEquals(StageState.PEND, s.stages[1].second.first)
        assertEquals(UiText.res(R.string.home_stage_hu_parked), s.stages[1].second.second)
        assertEquals(UiText.res(R.string.notif_parked), s.notifText)
        // failures and hangs of past PARKED connections do not change: SERVER_DOWN only when the bridge is live
        val stale = HomeLogic.compute(inp(ended = true, serverRunning = true, refusedMs = 60_000, hangs = 3, worked = true))
        assertEquals(Phase.PARKED, stale.phase)
    }

    @Test
    fun serverDownWhenRefusedLongerThanFifteenSeconds() {
        val status = "мост: жду сервер ГУ AA"
        val s = HomeLogic.compute(inp(running = true, status = status, protocol = true, refusedMs = HomeLogic.REFUSED_DOWN_MS, worked = true))
        assertEquals(Phase.ERROR, s.phase)
        assertEquals(Tone.ERR, s.tone)
        assertEquals(HeroIcon.ALERT, s.icon)
        assertEquals(UiText.res(R.string.home_title_server_down), s.title)
        assertEquals(UiText.res(R.string.home_sub_server_down), s.sub)
        assertEquals(StageState.ERR to UiText.res(R.string.home_stage_no_answer), s.stages[0].second)
        assertEquals(PrimaryAction.START_SERVER, s.primary)
        assertEquals(true, s.logLink)
        assertEquals(UiText.res(R.string.home_title_server_down), s.notifTitle)
        assertEquals(UiText.res(R.string.notif_server_down), s.notifText)
        // A short rejection ( ~ 6 S after ByeBye) Normal: normal "Connecting", without red and without "Start server head unit»
        val short = HomeLogic.compute(inp(running = true, status = status, protocol = true, refusedMs = HomeLogic.REFUSED_DOWN_MS - 1, worked = true))
        assertEquals(Phase.WAITING, short.phase)
        assertEquals(false, short.showChain)
    }

    @Test
    fun serverDownWhenTwoAttemptsInARowHangWithoutVersionResponse() {
        val status = "мост: AA отключился"
        val s = HomeLogic.compute(inp(running = true, status = status, protocol = true, hangs = HomeLogic.HANGS_DOWN, worked = true))
        assertEquals(Phase.ERROR, s.phase)
        assertEquals(UiText.res(R.string.home_title_server_down), s.title)
        assertEquals(UiText.res(R.string.notif_server_down), s.notifText)
        // one hang not yet output: the usual "I connect"
        val one = HomeLogic.compute(inp(running = true, status = status, protocol = true, hangs = 1, worked = true))
        assertEquals(Phase.WAITING, one.phase)
        assertEquals(UiText.res(R.string.home_title_connecting), one.title)
    }

    @Test
    fun serverDownNeedsEvidenceThatItWasUp() {
        // Newcomer: the server has never worked, a long failure and hangs do not give red - the former "Start the server head unit"
        val fresh = HomeLogic.compute(inp(running = true, status = "мост: жду сервер ГУ AA", refusedMs = 60_000))
        assertEquals(Phase.OFF, fresh.phase)
        assertEquals(UiText.res(R.string.home_title_off), fresh.title)
        assertEquals(true, fresh.showChain)
        assertEquals(Phase.WAITING, HomeLogic.compute(inp(running = true, status = "мост: AA отключился", hangs = 5)).phase)
    }

    @Test
    fun serverDownOnlyWhileBridgeAttemptsAndNotOnceConnected() {
        // No bridge (no cable): past failures mean nothing
        assertEquals(Phase.OFF, HomeLogic.compute(inp(serverRunning = false, refusedMs = 60_000, hangs = 3, worked = true)).phase)
        // session is going: the usual "Everything works", even if the meters are not yet zeroed
        val connected = HomeLogic.compute(inp(running = true, status = "мост: AA -> CarLife", refusedMs = 60_000, hangs = 3, worked = true))
        assertEquals(Phase.CONNECTED, connected.phase)
        // break USB: grace hold session, no failures
        val grace = HomeLogic.compute(inp(running = true, status = "мост: USB потерян, AA жива 8 с", refusedMs = 60_000, worked = true))
        assertEquals(Phase.RECONNECTING, grace.phase)
    }

    @Test
    fun cableButServerRefusesIsOff() {
        val s = HomeLogic.compute(inp(running = true, status = "мост: жду сервер ГУ AA", protocol = true))
        assertEquals(Phase.OFF, s.phase)
        assertEquals(Tone.PRI, s.tone)
        assertEquals(true, s.showChain)
        assertEquals(StageState.DONE, s.stages[1].second.first)
    }

    @Test
    fun cableConnectingIsWaiting() {
        val s = HomeLogic.compute(inp(running = true, status = "мост: старт"))
        assertEquals(Phase.WAITING, s.phase)
        assertEquals(UiText.res(R.string.home_title_connecting), s.title)
        assertNull(s.primary)
    }
}
