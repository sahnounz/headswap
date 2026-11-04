package com.yegorkapomidorka.headswap.aa

import android.content.Context
import android.content.SharedPreferences
import com.yegorkapomidorka.headswap.AppLog
import java.io.FileNotFoundException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicLong

/** Settings AA-mode (separate file prefs). */
class AaPrefs(ctx: Context) {
    private val sp: SharedPreferences = ctx.getSharedPreferences("aa", Context.MODE_PRIVATE)

    /** jvc (default, adopted by AA 17.7 live) or hur. */
    var certSet: String
        get() = sp.getString("certSet", "jvc") ?: "jvc"
        set(v) = sp.edit().putString("certSet", v).apply()

    /** Sound AA -> CarLife by USB; default on. */
    var usbAudio: Boolean
        get() = sp.getBoolean("usbAudio", true)
        set(v) = sp.edit().putBoolean("usbAudio", v).apply()

    /** Music frequency in CarLife (name MusicRate, default Auto). Bridge reads on each MEDIA INIT. */
    var musicRate: MusicRate
        get() = MusicRate.parse(sp.getString("musicRate", MusicRate.DEFAULT.name))
        set(v) = sp.edit().putString("musicRate", v.name).apply()

    /** The size of the music package in CarLife (name MediaChunk, default Auto; experiment, not included in auto presets). The bridge reads on each buffer. */
    var mediaChunk: MediaChunk
        get() = MediaChunk.parse(sp.getString("mediaChunk", MediaChunk.DEFAULT.name))
        set(v) = sp.edit().putString("mediaChunk", v.name).apply()

    /** The size of the speech package in CarLife (name VoiceChunk, default Auto; experiment, not included in the auto presets). The bridge reads on each package. */
    var voiceChunk: VoiceChunk
        get() = VoiceChunk.parse(sp.getString("voiceChunk", VoiceChunk.DEFAULT.name))
        set(v) = sp.edit().putString("voiceChunk", v.name).apply()

    /** Pre-roll silence before the TTS prompt (name TtsPreroll, default Auto; experiment, not included in auto presets). Bridge reads on each TTS INIT. */
    var ttsPreroll: TtsPreroll
        get() = TtsPreroll.parse(sp.getString("ttsPreroll", TtsPreroll.DEFAULT.name))
        set(v) = sp.edit().putString("ttsPreroll", v.name).apply()

    /** The MOVE touch thinning (TouchDecimation name, default Off; experiment, not included in auto presets). The bridge reads for every event. */
    var touchDecimation: TouchDecimation
        get() = TouchDecimation.parse(sp.getString("touchDecimation", TouchDecimation.DEFAULT.name))
        set(v) = sp.edit().putString("touchDecimation", v.name).apply()

    /**
     * Bridge mode (BridgeMode key, default Android Auto): raw stored value, on Android below 13 it
     * can be DESKTOP - the mode used gives [BridgeMode.effective]. Reads once per connection.
     */
    var bridgeMode: BridgeMode
        get() = BridgeMode.parse(sp.getString("bridgeMode", BridgeMode.DEFAULT.key))
        set(v) = sp.edit().putString("bridgeMode", v.key).apply()

    /**
     * Its IDR times in 2 with direct passthrough (IdrSplice, ADR 0005), the section for developers. By default included;
     * turn off on head unit, which themselves return the picture after the camera (VW). The key is new: the old idrSplice was turned off by default.
     */
    var ownIdr: Boolean
        get() = sp.getBoolean("ownIdr", true)
        set(v) = sp.edit().putBoolean("ownIdr", v).apply()

    /** Video of the bridge: passthrough H.264 AA as is (default with 3.0) or transcoding. */
    var transcode: Boolean
        get() = sp.getBoolean("transcode", false)
        set(v) = sp.edit().putBoolean("transcode", v).apply()

    /**
     * One-time migration to 3.0: direct passthrough and Auto frequency for anyone who has updated.
     * Called MainActivity and BridgeService (the application could run from USB), it is idempotent.
     * Preset "My car" do not touch: it remains a signature, the user changes the settings himself.
     */
    fun migrateTo30() {
        if (sp.getBoolean("video30Migrated", false)) return
        sp.edit()
            .putBoolean("transcode", false)
            .putString("fpsSetting", FpsSetting.AUTO.name)
            .putBoolean("video30Migrated", true)
            .apply()
        AppLog.i("миграция 3.0: прямой проброс, частота кадров Авто")
    }

    /** Video resolution (name VideoMode, default 1280×720). Read once per connection. */
    var videoMode: VideoMode
        get() = VideoMode.parse(sp.getString("videoMode", VideoMode.DEFAULT.name))
        set(v) = sp.edit().putString("videoMode", v.name).apply()

    /** Authorization (developer section): instead of [videoMode], only c transcoding. Read once for connection. */
    var customRes: Boolean
        get() = sp.getBoolean("customRes", false)
        set(v) = sp.edit().putBoolean("customRes", v).apply()

    var customW: Int
        get() = sp.getInt("customW", DEFAULT_CUSTOM_W)
        set(v) = sp.edit().putInt("customW", v).apply()

    var customH: Int
        get() = sp.getInt("customH", DEFAULT_CUSTOM_H)
        set(v) = sp.edit().putInt("customH", v).apply()

    /** The size that head unit requested in VIDEO_ENCODER_INIT in the last session; 0 = has not yet been. */
    var lastHuW: Int
        get() = sp.getInt("lastHuW", 0)
        set(v) = sp.edit().putInt("lastHuW", v).apply()

    var lastHuH: Int
        get() = sp.getInt("lastHuH", 0)
        set(v) = sp.edit().putInt("lastHuH", v).apply()

    /** Save the size requested by head unit (zeros and no trash). */
    fun saveHuSize(w: Int, h: Int) {
        if (w > 0 && h > 0) sp.edit().putInt("lastHuW", w).putInt("lastHuH", h).apply()
    }

    /** Total video geometry: its resolution (if included and valid) or [videoMode]. transcoding is taken into account through effective(). */
    val videoGeometry: VideoGeometry
        get() = CustomVideoMode.resolve(customRes, customW, customH, videoMode)

    /** Manual DPI interface AA (AaDensity), 0 = Auto (density mode). Read once per connection. */
    var aaDensity: Int
        get() = AaDensity.parse(sp.getInt("aaDensity", AaDensity.AUTO))
        set(v) = sp.edit().putInt("aaDensity", AaDensity.parse(v)).apply()

    /** Frequency frame (name FpsSetting, default Auto). Read once per connection. */
    var fpsSetting: FpsSetting
        get() = FpsSetting.parse(sp.getString("fpsSetting", FpsSetting.DEFAULT.name))
        set(v) = sp.edit().putString("fpsSetting", v.name).apply()

    /** Car brand for SDR instead of "Google" (CAR_MAKES); null = default. Readable for connection. */
    var carMake: String?
        get() = sp.getString("carMake", null)?.takeIf { it in CAR_MAKES }
        set(v) = sp.edit().apply { if (v == null) remove("carMake") else putString("carMake", v) }.apply()

    /** Machine from the section "My car" (name CarPreset); null = not selected. The choice is only a signature, settings write [applyCar]. */
    var carPreset: CarPreset?
        get() = CarPreset.parse(sp.getString("carPreset", null))
        set(v) = sp.edit().apply { if (v == null) remove("carPreset") else putString("carPreset", v.name) }.apply()

    /** The current values of the settings that the choice of machine sets. */
    val carSettings: CarSettings
        get() = CarSettings(transcode, videoMode, aaDensity, fpsSetting, musicRate)

    /** Select the car and write down its recommended settings. */
    fun applyCar(p: CarPreset) {
        val s = p.settings
        sp.edit()
            .putString("carPreset", p.name)
            .putBoolean("transcode", s.transcode)
            .putString("videoMode", s.videoMode.name)
            .putInt("aaDensity", s.aaDensity)
            .putString("fpsSetting", s.fps.name)
            .putString("musicRate", s.musicRate.name)
            .apply()
    }

    /** Sound source for microphone AA: machine/phone/car (head unit, with silence 2 s - phone). */
    var micMode: MicMode
        get() = MicMode.parse(sp.getString("micMode", MicMode.AUTO.key))
        set(v) = sp.edit().putString("micMode", v.key).apply()

    /** Together with the recording request from head unit send MODULE_STATUS VR=1/0 (as Baidu). */
    var micVrStatus: Boolean
        get() = sp.getBoolean("micVrStatus", true)
        set(v) = sp.edit().putBoolean("micVrStatus", v).apply()

    /** Notify head unit of calls (TelephonyManager -> MODULE_STATUS PHONE + TELE_STATE); by default off. Read to connect. */
    var callsToHu: Boolean
        get() = sp.getBoolean("callsToHu", false)
        set(v) = sp.edit().putBoolean("callsToHu", v).apply()

    /** Track, cover and progress from AA -> MEDIA_INFO / MEDIA_PROGRESS_BAR. Read on connection. */
    var metaMusic: Boolean
        get() = sp.getBoolean("metaMusic", true)
        set(v) = sp.edit().putBoolean("metaMusic", v).apply()

    /** Turning tips from AA -> NAVI_NEXT_TURN_INFO (if head unit has subscribed). Readable for connection. */
    var metaNavi: Boolean
        get() = sp.getBoolean("metaNavi", true)
        set(v) = sp.edit().putBoolean("metaNavi", v).apply()

    /** Field 6 NAVI_NEXT_TURN_INFO: bytes-icon (as 8.6.7). */
    var naviIcon: Boolean
        get() = sp.getBoolean("naviIcon", true)
        set(v) = sp.edit().putBoolean("naviIcon", v).apply()

    /** Field 6 NAVI_NEXT_TURN_INFO: int32 time (as apollo-DuerOS). */
    var naviTime: Boolean
        get() = sp.getBoolean("naviTime", true)
        set(v) = sp.edit().putBoolean("naviTime", v).apply()

    val naviField6: NaviField6 get() = NaviField6.of(naviIcon, naviTime)

    /** MEDIA_PROGRESS_BAR in seconds instead of ms (if head unit shows progress in 1000 times more). */
    var progressSeconds: Boolean
        get() = sp.getBoolean("progressSeconds", false)
        set(v) = sp.edit().putBoolean("progressSeconds", v).apply()

    /** At least once installed a real AA-session: the server head unit user worked (for "Server head unit does not respond" and not "Start server head unit"). */
    var serverWorked: Boolean
        get() = sp.getBoolean("serverWorked", false)
        set(v) = sp.edit().putBoolean("serverWorked", v).apply()

    /** The video alert in motion in the "Magnetola Applications" section is already shown (once). */
    var appsWarningShown: Boolean
        get() = sp.getBoolean("appsWarningShown", false)
        set(v) = sp.edit().putBoolean("appsWarningShown", v).apply()

    /** The preset of the equalizer (EqPreset.key); strips and preamp are stored separately, the preset is only a signature. */
    var eqPreset: EqPreset
        get() = EqPreset.parse(sp.getString("eqPreset", EqPreset.FLAT.key))
        set(v) = sp.edit().putString("eqPreset", v.key).apply()

    /** Bands (CSV, dB) and preamp; recording immediately applies to bridge over EqLive. */
    var eq: EqSettings
        get() = EqSettings(EqSettings.parseBands(sp.getString("eqBands", null)), sp.getInt("eqPreamp", 0)).normalized()
        set(v) {
            val n = v.normalized()
            sp.edit().putString("eqBands", n.bandsCsv()).putInt("eqPreamp", n.preamp).apply()
            EqLive.settings = n
        }

    /** The last meaning of “My”: a return to it after testing presets. */
    var eqCustom: EqSettings
        get() = EqSettings(EqSettings.parseBands(sp.getString("eqCustomBands", null)), sp.getInt("eqCustomPreamp", 0)).normalized()
        set(v) {
            val n = v.normalized()
            sp.edit().putString("eqCustomBands", n.bandsCsv()).putInt("eqCustomPreamp", n.preamp).apply()
        }

    companion object {
        const val DEFAULT_CUSTOM_W = 1920
        const val DEFAULT_CUSTOM_H = 720
    }
}

/** Stamps for setting up "Car brand" (canonical English spellings). */
val CAR_MAKES = listOf(
    "Honda", "Mazda", "Toyota", "Nissan", "Hyundai", "Kia", "Volkswagen", "Skoda", "Ford",
    "Chevrolet", "Mitsubishi", "Subaru", "Lexus", "Changan", "Haval", "Chery", "Geely",
)

/** Status AA-screen mode; writes service, reads activity (one process). */
object AaState {
    @Volatile var status: String = "не запущено"
    @Volatile var running: Boolean = false
    @Volatile var cert: String = "-"
    val frames = AtomicLong(0)
    @Volatile var bytes: Long = 0
    @Volatile var codecConfigs: Long = 0
    @Volatile var selftest: String = "-"
    @Volatile var bridgeSent: Long = 0
    @Volatile var bridgeDropped: Long = 0
    @Volatile var audioBytes: Long = 0

    /** A configuration error that prevents the bridge from starting (e.g. no head unit certificate); null = all right. */
    @Volatile var setupError: String? = null

    /** head unit server failures with real bridge connections: status source "head Server unit does not respond" on the main screen. */
    val serverFailures = ServerFailures()

    fun reset() {
        frames.set(0)
        bytes = 0
        codecConfigs = 0
        bridgeSent = 0
        bridgeDropped = 0
        audioBytes = 0
    }

    fun summary(): String =
        "AA: $status\nAA cert: $cert  selftest: $selftest\nAA кадров: ${frames.get()} (config $codecConfigs, ${bytes / 1024} КиБ)\nМОСТ: в CarLife отправлено $bridgeSent, отброшено $bridgeDropped, звук ${audioBytes / 1024} КиБ"
}

/** No head unit certificate set found in assets. */
class AaCertsMissingException(message: String) : Exception(message)

/** Download certificates from assets with rollback to the second set and a large rollback log. */
object AaCreds {
    const val MISSING_TEXT = "Нет сертификата head unit: положите cert.pem и key.pem в app/src/main/assets/aa/jvc/ (см. README)"

    /** @throws AaCertsMissingException if the certificate files are not in any set */
    fun load(ctx: Context, preferred: String): AapCerts.Loaded {
        val opener: (String) -> InputStream = { ctx.assets.open(it) }
        val loaded = try {
            AapCerts.loadWithFallback(opener, preferred)
        } catch (e: FileNotFoundException) {
            AppLog.i("AA !!!!!! $MISSING_TEXT", true)
            throw AaCertsMissingException(MISSING_TEXT)
        }
        if (loaded.error != null) {
            AppLog.i("AA !!!!!! ${loaded.error}", true)
            AppLog.i("AA !!!!!! ОТКАТ НА СЕРТИФИКАТ '${loaded.creds.certSet}' (запрошен '$preferred') !!!!!!", true)
        } else {
            AppLog.i("AA сертификат '${loaded.creds.certSet}' загружен: ${loaded.creds.chain[0].subjectX500Principal}", true)
        }
        return loaded
    }

    /** notBefore/notAfter separately: JVC format UTCTime with zone, parsed not everywhere. Only for log. */
    fun describeValidity(creds: AapCredentials): String = try {
        val c = creds.chain[0]
        "notBefore=${c.notBefore} notAfter=${c.notAfter}"
    } catch (e: Throwable) {
        "notBefore/notAfter не читаются: ${e.javaClass.simpleName}: ${e.message}"
    }

    /** Self-checking without network: in-memory TLS-handshake both sets, result in log. */
    fun selfTest(ctx: Context) {
        val opener: (String) -> InputStream = { ctx.assets.open(it) }
        val parts = ArrayList<String>()
        for (set in AapCerts.SETS) {
            try {
                AppLog.i("AA selftest [$set]: ${describeValidity(AapCerts.load(opener, set))}", true)
            } catch (e: Throwable) {
                AppLog.i("AA selftest [$set]: сертификат/ключ не загрузились: ${e.javaClass.simpleName}: ${e.message}", true)
            }
            val r = AapTlsSelfTest.run(opener, set)
            AppLog.i("AA selftest [$set]: TLS in-memory ${if (r.ok) "OK" else "FAIL"} ${r.detail}", true)
            parts.add("$set=${if (r.ok) "OK" else "FAIL"}")
        }
        AaState.selftest = parts.joinToString(" ")
    }
}
