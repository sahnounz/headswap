package com.yegorkapomidorka.headswap.aa

/** Phone call status (on the device - PhoneCalls on top of TelephonyManager; in JVM-tests - stub). */
interface CallStateSource {
    /** Subscription: [onState] Get TelephonyManager.CALL_STATE_* (0 IDLE, 1 RINGING, 2 OFFHOOK). false = Failed (no permission). */
    fun start(onState: (Int) -> Unit): Boolean

    fun stop()

    object NONE : CallStateSource {
        override fun start(onState: (Int) -> Unit) = false
        override fun stop() = Unit
    }

    companion object {
        /** API, from which TelephonyCallback requires READ_PHONE_STATE; below PhoneStateListener works without permission. */
        const val PERMISSION_SDK = 31

        /** Listen to telephony: the check box "Report head unit calls" is enabled and (on API 31+) issued by READ_PHONE_STATE. */
        fun shouldListen(enabled: Boolean, sdk: Int, permissionGranted: Boolean) =
            enabled && (sdk < PERMISSION_SDK || permissionGranted)

        const val IDLE = 0
        const val RINGING = 1
        const val OFFHOOK = 2
    }
}
