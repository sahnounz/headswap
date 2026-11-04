package com.yegorkapomidorka.headswap.aa

/**
 * Bridge mode: what head unit gets by CarLife. ANDROID_AUTO - bridge to server head unit Android Auto (default),
 * DESKTOP - your desktop on the virtual screen of the phone (you need to activate with a PC, Android 13+).
 * The mode is removed for connection (host and session), and not read on the fly.
 */
enum class BridgeMode(val key: String) {
    ANDROID_AUTO("aa"),
    DESKTOP("desktop");

    companion object {
        val DEFAULT = ANDROID_AUTO

        /** Trusted-displays and activation rights are with Android 13; below mode DESKTOP is not available. */
        const val DESKTOP_MIN_SDK = 33

        /** By key from prefs; unknown/empty = default. */
        fun parse(key: String?): BridgeMode = values().firstOrNull { it.key == key } ?: DEFAULT

        fun desktopSupported(sdk: Int) = sdk >= DESKTOP_MIN_SDK

        /** Mode that is actually applied: DESKTOP on Android below 13 is treated as Android Auto. */
        fun effective(stored: BridgeMode, sdk: Int): BridgeMode =
            if (stored == DESKTOP && !desktopSupported(sdk)) ANDROID_AUTO else stored

        /** A live host (grace after the USB break) is only suitable for a new session of the same mode. */
        fun hostReusable(hostMode: BridgeMode, wanted: BridgeMode) = hostMode == wanted
    }
}
