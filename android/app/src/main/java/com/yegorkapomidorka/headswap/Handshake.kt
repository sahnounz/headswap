package com.yegorkapomidorka.headswap

import com.yegorkapomidorka.headswap.aa.FpsSetting

/** Outgoing CMD- message: type and body (null = empty). */
class OutCmd(val type: Int, val body: ByteArray?)

/**
 * Reply to HU_AUTHEN_REQUEST like PrivateKeyManager (Baidu CarLife 8.6.7): randomValue after versionVerify
 * signs (RSA/ECB/PKCS1Padding, ENCRYPT_MODE with private key), result in Base64 NO_WRAP.
 * Pure JVM-logic: java.util.Base64 and javax.crypto are both in tests and on Android (minSdk 29).
 */
object HuAuth {
    private const val ERROR_VERSION = "_00.00.00"
    private const val TRANSFORMATION = "RSA/ECB/PKCS1Padding"

    /** PRIVATE_KEY of PrivateKeyManager (PKCS8, Base64). */
    private const val PRIVATE_KEY =
        "MIICdwIBADANBgkqhkiG9w0BAQEFAASCAmEwggJdAgEAAoGBALzoBR3Io5wxQH+IMy0ekUE50s9ZOEPOI/y3bSwXSZEqU0EgDGVRnFBYhr8MSEkv2THTn5HKsUM3gHd3rpQhRG2JrcaFY+iIEJs2iueXvfKd8UqTUFQOZrc7xCz1npg4KResY4VAEN+W19ez/DsKiCS5LaxmwE844xWibTgjspY9AgMBAAECgYA7C/cVcTKaztZPRr5gWw6iKbXYNBYfM58SDi+kuX64TleJor/dJ55JivJLY0ZxAfDM304gXw/7Z6zTKui5ypA9duYu1Pohk/e5UUMFr0+cEyWzTjCJp/XaA/gEhH8g311qeWcALSo6q55vYtTJpc/OUn98nlsrOMv4XLgaBI2fwQJBAPBw7TkR4Xuvue2tfxh2Fn2x2ONJSRjckS7EipfdLSsgZCbwdQ2XL/k6VX0lMfQaLQQ6YCF/vrALlgkXFBuzFpECQQDJIWJGcoM95ms546hHBzu7DONmEpEh9b9nMEqRmOkzkitxvGApv1VjvZaX6gMrSANADmoz4lSTGXFea4nviJLtAkEA29v3H4NZMveJxWsrV5vLjx5MG/FMdP5jh2dS7/DgN5pD2lNwRYAk7vnHaErVtccluMMEWj1siZ/ejutaiMWm8QJBAIMM+4nHZ3hXwJoRmj3dmq/AMBL8GhC0nShRMOU5awmtPh13jnjlMHAywgLt+W6kF2oPemegG1dVhqbtDw1CQekCQH3xugE28LS6e29233PUBdipwoyRcbDeth70WDS3l4bl7XlWbTXOWF/D28ZZ49NSr1dRhAQwM8VVC/gHdvNpcqA="

    val privateKey: java.security.PrivateKey by lazy {
        java.security.KeyFactory.getInstance("RSA")
            .generatePrivate(java.security.spec.PKCS8EncodedKeySpec(java.util.Base64.getDecoder().decode(PRIVATE_KEY)))
    }

    /** MD5_HEAD salt from libencryption.so Baidu 8.6.7. */
    private const val V2_SALT = "CARLIFE"

    /** Branch "2.0;<rnd>" (EncryptionUtils.getVerifyCode, native lib Baidu). Condition as in Baidu. */
    fun isV2Branch(random: String): Boolean {
        val parts = javaSplit(random, ";")
        return parts.size >= 2 && parts[0] == "2.0" && parts[1].isNotEmpty()
    }

    /** String.split(regex) of Java: tail blanks are discarded. */
    private fun javaSplit(s: String, sep: String): List<String> {
        val p = s.split(sep)
        return if (p.size == 1) p else p.dropLastWhile { it.isEmpty() }
    }

    /**
     * PrivateKeyManager.versionVerify at versionName = Handshake.MD_VERSION. Without "_" at Baidu drops
     * (substring(-1)), here the string remains as it is. Non-numerical parts are omitted (Baidu has an exception).
     */
    fun versionVerify(str: String, mdVersion: String = Handshake.MD_VERSION): String {
        val idx = str.indexOf('_')
        if (idx < 0) return str
        val suffix = str.substring(idx)
        val stripped = str.replace(suffix, "")
        val theirs = javaSplit(suffix.replace("_", ""), ".")
        val ours = javaSplit(mdVersion, ".")
        if (ours.size != theirs.size) return stripped + ERROR_VERSION
        for (i in ours.indices) {
            val a = theirs[i].toIntOrNull() ?: continue
            val b = ours[i].toIntOrNull() ?: continue
            if (a > b) return stripped + ERROR_VERSION
            if (a < b) return str
        }
        return str
    }

    /**
     * CONNECT_ENCRYPTION_MD_INFO string at Baidu: brand + model + sdk from sent to MD_INFO
     * (CarlifeDeviceInfoManager :174). head unit takes it from the same MD_INFO, so the fields must match bytes per byte.
     */
    fun mdInfoKey(info: DeviceInfo) = info.brand + info.model + info.sdk

    /**
     * getVerifyCode of libencryption.so: md5(MD5_HEAD + str) inline hex (MD5 class of md5.cpp, HEX_NUMBERS
     * "0123456789abcdef"). Checking the signature of APK (APP_SIGNATURE_CARLIFE) skip: at Baidu it is at
     * someone else's signature just gives null. head unit verifies getVerifyResult: same md5 and strcmp.
     */
    fun verifyCode(str: String): String {
        val d = java.security.MessageDigest.getInstance("MD5").digest((V2_SALT + str).toByteArray(Charsets.UTF_8))
        return d.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    /**
     * encryptValue for MD_AUTHEN_RESPONSE. Branch 2.0: verifyCode(mdInfo + part after ";"), such as
     * PrivateKeyManager.encryptContentProcess; otherwise RSA-signature.
     */
    fun encryptValue(random: String, mdInfo: String = ""): String {
        if (isV2Branch(random)) return verifyCode(mdInfo + javaSplit(random, ";")[1])
        val cipher = javax.crypto.Cipher.getInstance(TRANSFORMATION)
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, privateKey)
        val sig = cipher.doFinal(versionVerify(random).toByteArray(Charsets.UTF_8))
        return java.util.Base64.getEncoder().encodeToString(sig)
    }
}

/**
 * MD -> HU sequences for CarLife 2.0 repeating MD Baidu CarLife 8.6.7.
 * Pure logic without android.* to check order and bytes offline.
 */
object Handshake {
    /** MODULE_STATUS leaves via 500 ms after the starting pack (CarlifeConnectHsgHandler.boxDelayProtocolMatchStatus, :752-758). */
    const val MODULE_STATUS_DELAY_MS = 500L

    /**
     * No VIDEO_ENCODER_START after so many ms after INIT_DONE -> video starts itself. VW Tharu XR 2026 (CarLife 2.1) START
     * not send and tear USB through ~ 5.5 with no video; Baidu HU sends START immediately after initDecoder (CarlifeActivity :486-497).
     */
    const val START_FALLBACK_MS = 1500L

    const val MD_VERSION = "8.6.7"

    /** carlifeType: 1 = application CarLife (MixConfig.isCarLifeApp, CarlifeDeviceInfoManager :109-113). */
    const val MD_TYPE_CARLIFE_APP = 1

    /**
     * Start pack immediately after replying to HU_PROTOCOL_VERSION, without expecting anything from head unit:
     * MATCH_STATUS, FOREGROUND, SCREEN_ON (if screen is enabled), MD_INFO
     * (CarlifeConnectHsgHandler.boxDelayProtocolMatchStatus :743-751 + sendPhoneStatusMsg :995-1021).
     */
    fun startBatch(screenOn: Boolean, info: DeviceInfo): List<OutCmd> {
        val out = ArrayList<OutCmd>()
        out.add(OutCmd(Msg.PROTOCOL_VERSION_MATCH_STATUS, Msgs.matchStatus(1)))
        out.add(OutCmd(Msg.FOREGROUND, null))
        if (screenOn) out.add(OutCmd(Msg.SCREEN_ON, null))
        out.add(OutCmd(Msg.MD_INFO, Msgs.deviceInfo(info)))
        return out
    }

    fun moduleStatus(huMajor: Int) = OutCmd(Msg.MODULE_STATUS, Msgs.moduleStatusList(huMajor))

    /**
     * Response to STATISTIC_INFO (CarlifeConnectHsgHandler :434-499): MD_AUTHEN_RESULT{true } , then request
     * FEATURE_CONFIG (0x10051, empty), then CAR_DATA_SUBSCRIBE (0x10031, empty).
     */
    fun onStatisticInfo(sendFeatureConfigRequest: Boolean): List<OutCmd> {
        val out = ArrayList<OutCmd>()
        out.add(OutCmd(Msg.MD_AUTHEN_RESULT, Msgs.authenResult(true)))
        if (sendFeatureConfigRequest) out.add(OutCmd(Msg.MD_FEATURE_CONFIG_REQUEST, null))
        out.add(OutCmd(Msg.CAR_DATA_SUBSCRIBE, null))
        return out
    }

    /** That Baidu sends head unit at voice initialization after connection. */
    enum class MicStart { NONE, WAKEUP_START, END }

    /** Delayed voice initialization after STATISTIC_INFO (CarlifeConnectHsgHandler :490-495, postDelayed 1000). */
    const val MIC_INIT_DELAY_MS = 1000L

    /**
     * What Baidu 8.6.7 Immanuel head unit via [MIC_INIT_DELAY_MS] after STATISTIC_INFO (VrManager.onUsbConnectedImpl :582-675,
     * prepareWakeUp :400-439) for FEATURE_CONFIG head unit [items] (in order of arrival, FeatureConfigManager.excute :213-290):
     * - VOICE_MIC sets micStatus (default 0 = microphone head unit), 2 = "head unit does not support voice" -> MIC_RECORD_END;
     * - ALL_VOICE_WAKEUP_DISABLE=1 and VOICE_WAKEUP=0 jam wake-up head unit (disableWakeUp); at micStatus=0 this is MIC_RECORD_END,
     *   prepareWakeUp then is not called (wake-up flag is reset);
     * - otherwise (wake-up is allowed, microphone head unit) -> MIC_RECORD_WAKEUP_START (empty body).
     * The channels from the Baidu lists (DisableWakeUpChannelManager, etc.) come from the Baidu server and are not counted here.
     */
    fun baiduMicStart(items: List<Pair<String, Int>>): MicStart {
        var micStatus = 0
        var vehicleDisable = false   // VrManager.isVehicleDisableWakeUp
        for ((key, value) in items) {
            when (key) {
                "VOICE_MIC" -> micStatus = value
                "ALL_VOICE_WAKEUP_DISABLE" -> if (value == 1) vehicleDisable = true
                "VOICE_WAKEUP" -> vehicleDisable = value != 1
            }
        }
        if (micStatus == 2) return MicStart.END                 // closeWakeUp -> sendRecordEndMsgToVehicle
        // prepareWakeUp: with the wake-up head unit switched off does not send anything (phone microphone starts), micStatus 1 = phone microphone
        if (!vehicleDisable && micStatus != 1) return MicStart.WAKEUP_START
        return if (micStatus == 0 && vehicleDisable) MicStart.END else MicStart.NONE
    }

    /** randomValue of HU_AUTHEN_REQUEST type "2.0;<rnd>" (v2- query option). */
    fun isAuthV2(random: String?) = random != null && random.startsWith("2.0;")

    /** Valid response to VIDEO_ENCODER_FRAME_RATE_CHANGE: 3..30 (VideoMsgHandler.changeFrameRate). */
    fun frameRateChangeAllowed(fps: Int) = fps in 3..30

    const val DEFAULT_FPS = 30

    /** The lower frequency limit, which is used for FRAME_RATE_CHANGE (below encoder/head unit no longer have time to update statics). */
    const val MIN_APPLIED_FPS = 10

    /**
     * fps for INIT_DONE. Auto: as in Baidu (fps of INIT, if in 1..30, otherwise 30); 15 and 30 - fixed.
     * Honda weld fps=0, Kia 15, Mazda 30. [huMaxFps] - ceiling under weak head unit (Pioneer RM56E8B, h277), null
     */
    fun chooseFps(setting: FpsSetting, requested: Int, huMaxFps: Int? = null): Int =
        capFps(setting.fixed ?: if (requested in 1..30) requested else DEFAULT_FPS, huMaxFps)

    /** fps ceiling for specific head unit (null = no limit). */
    fun capFps(fps: Int, huMaxFps: Int?): Int = huMaxFps?.let { minOf(fps, it) } ?: fps
}
