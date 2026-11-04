package com.yegorkapomidorka.headswap

import com.yegorkapomidorka.headswap.aa.AaDensity
import com.yegorkapomidorka.headswap.aa.VideoGeometry

/** Status for screen; write service and session, read activity (one process). */
object CarLifeState {
    @Volatile var status: String = "не запущено"
    @Volatile var accessory: String = "-"
    @Volatile var protocol: String = "-"
    @Volatile var auth: String = "-"
    @Volatile var video: String = "-"
    @Volatile var touch: String = "-"

    /** Video mode of the current/last session (actual, taking into account passthrough); null - there was no session. */
    @Volatile var videoMode: VideoGeometry? = null

    /** Manual DPI AA of the same session (AaDensity.AUTO = density mode). */
    @Volatile var densityOverride: Int = AaDensity.AUTO
    @Volatile var running: Boolean = false

    /** Current session is on Wi-Fi (TCP), not cable; false - no session or it is on USB. */
    @Volatile var wirelessSession: Boolean = false

    /** head unit from HU_INFO: "manufacturer model" and carlifeversion (the "System" section of launcher); null has not yet arrived. */
    @Volatile var huName: String? = null
    @Volatile var huCarlifeVersion: String? = null

    /** STATISTIC_INFO: channel and versionName are the head unit when HU_INFO is empty (Honda Vezel). */
    @Volatile var huChannel: String? = null
    @Volatile var huStatVersion: String? = null

    /** Last themeModes of CHANGE_MD_THEME (0 day, 1 night, 2 car); null - head unit did not send. The theme "Auto" launcher. */
    @Volatile var huTheme: Int? = null

    /** Wireless (beta, branch B): what is happening now, for the screen; null - mode not included. */
    @Volatile var wireless: String? = null

    /** Bluetooth- test branch A: that sent head unit to RFCOMM `carlife`; null - the test was not started. */
    @Volatile var btProbe: String? = null

    /** Wireless mode: selected machine next to Bluetooth (writes BridgeService). */
    @Volatile var carAuto: Boolean = false

    /** Wi-Fi machine network request: idle / requesting / available / unavailable (writes BridgeService). */
    @Volatile var carNet: String = "idle"

    fun resetSession() {
        protocol = "-"
        auth = "-"
        video = "-"
        touch = "-"
    }

    fun summary(): String =
        "статус: $status\nаксессуар: $accessory\nпротокол: $protocol\nauth: $auth\nвидео: $video\nтач: $touch" +
            (wireless?.let { "\nбеспровод: $it" } ?: "")
}
