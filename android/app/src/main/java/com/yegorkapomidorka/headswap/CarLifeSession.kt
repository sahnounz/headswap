package com.yegorkapomidorka.headswap

import android.os.Build
import android.os.SystemClock
import android.graphics.Rect
import com.yegorkapomidorka.headswap.aa.AaBridgeCore
import com.yegorkapomidorka.headswap.aa.CarlifeAudio
import com.yegorkapomidorka.headswap.aa.AaTranscoder
import com.yegorkapomidorka.headswap.aa.BitratePolicy
import com.yegorkapomidorka.headswap.aa.BitrateProfile
import com.yegorkapomidorka.headswap.aa.DesktopVideoQuality
import com.yegorkapomidorka.headswap.aa.DesktopVideoSelection
import com.yegorkapomidorka.headswap.aa.DesktopVideoGeometry
import com.yegorkapomidorka.headswap.aa.CallStateSource
import com.yegorkapomidorka.headswap.aa.CarlifeLink
import com.yegorkapomidorka.headswap.aa.FpsSetting
import com.yegorkapomidorka.headswap.aa.IdrBitrateCeiling
import com.yegorkapomidorka.headswap.aa.IdrThrottle
import com.yegorkapomidorka.headswap.aa.CarlifeMediaInfo
import com.yegorkapomidorka.headswap.aa.LinkVideoStats
import com.yegorkapomidorka.headswap.aa.NaviField6
import com.yegorkapomidorka.headswap.aa.NaviTurnInfo
import com.yegorkapomidorka.headswap.aa.ThermalLevel
import com.yegorkapomidorka.headswap.aa.ThermalSource
import com.yegorkapomidorka.headswap.aa.ThermalThrottle
import com.yegorkapomidorka.headswap.aa.TranscodeOutput
import com.yegorkapomidorka.headswap.aa.VideoGeometry
import com.yegorkapomidorka.headswap.aa.VideoQueueLimit
import com.yegorkapomidorka.headswap.aa.VideoTranscoder
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import com.yegorkapomidorka.headswap.dex.DesktopAudioOutputLease
import com.yegorkapomidorka.headswap.dex.DesktopConnectionState
import com.yegorkapomidorka.headswap.dex.DesktopVideoDelivery
import java.util.concurrent.atomic.AtomicBoolean
import com.yegorkapomidorka.headswap.link.CarTransport
import com.yegorkapomidorka.headswap.link.TransportCaps
import com.yegorkapomidorka.headswap.link.diag.WLog

/** Session settings taken at the time of start. */
class SessionConfig(
    val screenOn: Boolean = true,
    /** Together with MIC_RECORD_* send MODULE_STATUS VR 1/0 as Baidu (prepareShowVr/prepareCloseVr). */
    val micVrStatus: Boolean = true,
    /** frame: Auto frequency (as requested by head unit) or fixed. passthrough is not valid. */
    val fpsSetting: FpsSetting = FpsSetting.DEFAULT,
    /** Track, cover and progress from AA (MEDIA_INFO/MEDIA_PROGRESS_BAR). */
    val metaMusic: Boolean = true,
    /** Turning tips from AA (NAVI_NEXT_TURN_INFO by subscription head unit moduleID=0). */
    val metaNavi: Boolean = true,
    /** Field 6 NAVI_NEXT_TURN_INFO: Icon and/or time. */
    val naviField6: NaviField6 = NaviField6.BOTH,
    val desktopVideo: DesktopVideoSelection? = null,
)

/**
 * One session with head unit over transport (USB-stream or 6 socket): responds like MD, gives the bridge video/sound/touch, writes everything in log.
 * Streams: reading and recording in transport (sink and onWritten serialized), enc-drain/render (VideoSource stub),
 * scheduler (statistics, watchdog silence, delayed MODULE_STATUS).
 * CarLife 2.0 MD Baidu CarLife 8.6.7 behavior protocol, see docs/carlife-v2-handshake.md.
 */
class CarLifeSession(
    private val transport: CarTransport,
    private val cfg: SessionConfig,
    private val bridge: AaBridgeCore,
    /** Phone heating: transcoding reduces fps and bitrate. */
    private val thermal: ThermalSource = ThermalSource.NONE,
    /** Phone Call Status: It is reported by head unit as Baidu (h428). */
    private val calls: CallStateSource = CallStateSource.NONE,
    /** Size frame from VIDEO_ENCODER_INIT head unit (for the hint "As requested by head unit" in the section for developers). */
    private val onHuSize: (Int, Int) -> Unit = { _, _ -> },
    private val onClosed: (String) -> Unit,
) : VideoSource.Listener, CarlifeLink {

    /** The video mode of the host is the same for INIT_DONE, encoder and touch throughout the session. */
    @Volatile private var mode: VideoGeometry = bridge.videoMode

    /** Transport Sessions: usb-aoa, tcp-debug or tcp6. */
    val transportKind: String get() = transport.caps.kind

    /** wireless connection (6 socket): own ladder bitrate, PAUSE-keepalive more often, WL-line video. USB and tcp-debug - as it was. */
    private val air = transport.caps == TransportCaps.TCP6
    private val bitrateProfile = if (air) BitrateProfile.WIFI else BitrateProfile.USB
    private val legacyBitratePolicy = BitratePolicy(mode.bitrate, profile = bitrateProfile)
    private val surfaceBitratePolicy: BitratePolicy by lazy { DesktopVideoQuality.bitratePolicy(bitrateProfile) }
    @Volatile private var encodingSurface = false
    // The source starts after this session is constructed. Select its independent policy
    // only when the direct Surface encoder is requested, never from an early source flag.
    private val bitratePolicy: BitratePolicy get() = if (encodingSurface) surfaceBitratePolicy else legacyBitratePolicy
    @Volatile private var vwIdrCutMs = NEVER_MS
    private val thermalThrottle = ThermalThrottle()
    private var thermalStatus = 0                       // only thread cl-sched
    private var thermalHeadroom = Float.NaN
    private var headroomAtMs = NEVER_MS
    private var thermalPassthroughLogged = false
    private val pauseKeepaliveMs = if (air) PAUSE_KEEPALIVE_AIR_MS else PAUSE_KEEPALIVE_MS
    private var overflows = 0
    private var maxQueueFrames = 0
    private val placeholderIdr = IdrThrottle()
    @Volatile private var lastOverflowMs = NEVER_MS
    private var passthroughBitrateLogged = false
    private var spsFixLogged = false
    private var splitLogged = false
    private val closed = AtomicBoolean(false)
    private val sched: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "cl-sched") }
    private val metaExec = Executors.newSingleThreadExecutor { r -> Thread(r, "cl-meta").also { it.isDaemon = true } }
    private var lastArtSrc: ByteArray? = null          // only thread cl-meta
    private var lastArtJpeg = ByteArray(0)
    private val turnIcons = HashMap<Int, ByteArray>()
    @Volatile private var metaMusicOffLogged = false

    private val t0 = SystemClock.elapsedRealtime()
    private val desktopAudioOutputLease = DesktopAudioOutputLease(t0)
    private val desktopVideoDelivery = DesktopVideoDelivery()

    /** No enqueue/capture counter can substitute for successful transport writes. */
    fun canCaptureDesktopAudio(): Boolean = !closed.get() &&
        desktopAudioOutputLease.canCapture(SystemClock.elapsedRealtime())
    fun healthyDesktopForIdleTimer(): Boolean = !closed.get() && desktopVideoDelivery.hasPicture() &&
        canCaptureDesktopAudio()
    fun hasFreshDesktopVideo(): Boolean = !closed.get() &&
        desktopVideoDelivery.isFlowing(SystemClock.elapsedRealtime())

    // Protocol
    @Volatile private var matchSent = false
    private val protocolHandshake = ProtocolHandshake(cfg.screenOn, ::deviceInfo)
    private var pendingW = 0
    private var pendingH = 0
    private var pendingFps = FPS

    /** The target fps transcoder: from INIT (on setup), in Auto changes according to FRAME_RATE_CHANGE; the new transcoder starts with it. */
    @Volatile private var targetFps = FPS
    private var videoInited = false
    private var source: VideoSource? = null
    @Volatile private var csd: ByteArray? = null
    @Volatile private var stubCropped = false      // stub in frame AA, its SPS cut like the AA (h529)
    @Volatile private var csdPending = true
    @Volatile private var paused = false

    // Mobis CCNC (h242)
    private var mobisApplied = false                    // thread read: flag reactions already included
    private var mobisMicScheduled = false
    @Volatile private var huFeatureItems: List<Pair<String, Int>>? = null
    private var mobisBeatLogged = false
    private var mobisAdaptLogged = false
    // IDR (h242) size ceiling: Transcoder and stub own, each leading bitrate its encoder
    private val mobisAaCeiling = IdrBitrateCeiling(AaBridgeCore.MOBIS_BITRATE)
    private val mobisStubCeiling = IdrBitrateCeiling(AaBridgeCore.MOBIS_BITRATE)

    // Findings for the final summary
    private var huVersion = "-"
    private var sawAuthRequest = false
    private var authRandom: String? = null
    /** brand + model + sdk from the sent MD_INFO: authorization branch key 2.0. */
    private var mdInfoKey = ""
    private var huAuthResult: String? = null
    private var touchMode = "не было тачей"
    private var touchCount = 0
    private var maxFingers = 0
    private var lastMoveFingers = 0
    private val rxByType = java.util.TreeMap<Int, Int>()
    private var huInfo: String? = null
    private var huStatistic: String? = null
    private var huFeature: String? = null
    private var encryptionNeeded: Boolean? = null
    private var requested = "-"
    private var chosen = "-"

    // video statistics
    @Volatile private var lastVideoTx = 0L
    private var idleBeatLogged = false
    private var vFrames = 0L
    private var vBytes = 0L
    private var vKeys = 0L
    private var vBeats = 0L
    private var vDropped = 0L
    private var audioTx = 0L
    private var ttsTxSinceInit = 0       // TTS_DATA after last TTS_INIT: first TTS_TX_LOGGED with sending time
    @Volatile private var micRxPackets = 0L
    @Volatile private var micRxBytes = 0L
    /** When we sent the MODULE_STATUS VR=1:, we consider the MODULE_CONTROL VR=1 to be an echo immediately after it. */
    @Volatile private var lastOwnVrOnMs = 0L

    // stub (VideoSource with text) or AA-video; switches bridge core
    private val videoLock = Any()
    private val srcLock = Any()
    @Volatile private var bannerText: String? = null
    @Volatile private var acceptEncoder = true
    @Volatile private var carlifeStarted = false
    private val vrReassembler = DataReassembler()  // h425: Delphi cuts VR MIC_DATA to frame by 256 B

    fun start() {
        DesktopConnectionState.attach(desktopVideoDelivery)
        bridge.attach(this)
        AppLog.i("сессия: старт")
        CarLifeState.resetSession()
        CarLifeState.videoMode = mode
        CarLifeState.densityOverride = bridge.densityOverride
        AppLog.i(mode.describe(bridge.densityOverride))
        AppLog.i("сессия: транспорт=${transport.caps.kind} (${transport.label})")
        WLog.ev(WLog.LINK, "session", "transport" to transport.caps.kind)
        if (air) {
            WLog.ev(WLog.VIDEO, "policy", "profile" to bitratePolicy.profile.name, "startKbps" to bitratePolicy.startBps / 1000,
                "ceilingKbps" to bitratePolicy.ceilingBps / 1000, "mode" to mode.label, "transcode" to bridge.transcodeEnabled)
        }
        CarLifeState.status = "соединено, жду HU_PROTOCOL_VERSION"
        transport.start(::dispatch, ::logTx, ::close)
        sched.scheduleWithFixedDelay({ tick() }, 1, 1, TimeUnit.SECONDS)
        sched.scheduleWithFixedDelay({ pauseHeartbeat() }, PAUSE_HEARTBEAT_MS, PAUSE_HEARTBEAT_MS, TimeUnit.MILLISECONDS)
        sched.scheduleWithFixedDelay({ adaptBitrate() }, 1000, BITRATE_POLL_MS, TimeUnit.MILLISECONDS)
        sched.scheduleWithFixedDelay({ mobisKeepalive() }, MOBIS_KEEPALIVE_POLL_MS, MOBIS_KEEPALIVE_POLL_MS, TimeUnit.MILLISECONDS)
        val callsOn = calls.start { state ->
            try {
                sched.execute { onCallState(state) }
            } catch (_: java.util.concurrent.RejectedExecutionException) {   // session is already closed
            }
        }
        AppLog.i("звонки: ${if (callsOn) "слежу (VW CNS2 Delphi: сообщаю ГУ MODULE_STATUS PHONE + TELE_STATE)" else "не слежу (флажок выкл или нет READ_PHONE_STATE)"}")
    }

    /** thread cl-sched. Last state of call sent by head unit. */
    private var callState = CallStateSource.IDLE

    /**
     * As Baidu PhoneManager.onCallStateChanged: RINGING - MODULE_STATUS PHONE=1 + TELE_STATE_CHANGE_INCOMING,
     * OFFHOOK - PHONE=2 + OUTGOING, IDLE - PHONE=0 + IDLE + FOREGROUND. No. in INCOMING do not put (no READ_CALL_LOG).
     * The first IDLE when you subscribe is not sent: PHONE=0 has already left the starting list of modules.
     */
    private fun onCallState(state: Int) {
        if (closed.get()) return
        // h425: bridge learns about the call first and independently of handshake and grandfather below: on VW CNS2 Delphi tail music
        // must be extinguished before the head unit goes into its call window; the starting state of the new session should also reach the bridge
        bridge.onPhoneCallState(state)
        if (state == callState) return
        callState = state
        if (!matchSent) {
            AppLog.i("звонки: state=$state до рукопожатия, ГУ не сообщаю")
            return
        }
        if (!bridge.huVwCns2Delphi) {       // h428- way checked only under the task h425; others head unit call not reported
            AppLog.i("звонки: state=$state, ГУ не VW CNS2 Delphi, не сообщаю")
            return
        }
        when (state) {
            CallStateSource.RINGING -> {
                AppLog.i("-> ГУ: звонок входящий: MODULE_STATUS PHONE=1 + TELE_STATE_CHANGE_INCOMING", true)
                sendCmd(Msg.MODULE_STATUS, Msgs.moduleStatusSingle(MODULE_PHONE, 1))
                sendCmd(Msg.TELE_STATE_CHANGE_INCOMING, null)
            }
            CallStateSource.OFFHOOK -> {
                AppLog.i("-> ГУ: звонок разговор/исходящий: MODULE_STATUS PHONE=2 + TELE_STATE_CHANGE_OUTGOING", true)
                sendCmd(Msg.MODULE_STATUS, Msgs.moduleStatusSingle(MODULE_PHONE, 2))
                sendCmd(Msg.TELE_STATE_CHANGE_OUTGOING, null)
            }
            else -> {
                AppLog.i("-> ГУ: звонок закончен: MODULE_STATUS PHONE=0 + TELE_STATE_CHANGE_IDLE + FOREGROUND", true)
                sendCmd(Msg.MODULE_STATUS, Msgs.moduleStatusSingle(MODULE_PHONE, 0))
                sendCmd(Msg.TELE_STATE_CHANGE_IDLE, null)
                sendCmd(Msg.FOREGROUND, null)
            }
        }
    }

    fun close(reason: String) {
        if (!closed.compareAndSet(false, true)) return
        desktopVideoDelivery.close()
        DesktopConnectionState.detach(desktopVideoDelivery)
        desktopAudioOutputLease.close()
        AppLog.i("сессия: закрытие, причина: $reason")
        bridge.onCarlifeClosed(this)
        if (air) {
            WLog.ev(WLog.VIDEO, "summary", "frames" to vFrames, "dropped" to vDropped, "overflows" to overflows, "maxQueue" to maxQueueFrames,
                "downs" to bitratePolicy.downs, "ups" to bitratePolicy.ups, "minKbps" to bitratePolicy.minBps / 1000,
                "lastKbps" to bitratePolicy.current / 1000)
        }
        WLog.ev(WLog.LINK, "session-end", "transport" to transport.caps.kind, "aliveS" to (SystemClock.elapsedRealtime() - t0) / 1000, "why" to reason)
        logSummary(reason)
        calls.stop()
        CarLifeState.status = "закрыто: $reason"
        try {
            sched.shutdownNow()
            metaExec.shutdownNow()
        } catch (_: Exception) {
        }
        try {
            source?.stop()
        } catch (e: Exception) {
            AppLog.e("остановка видео", e)
        }
        source = null
        transport.close()
        AppLog.i("сессия: закрыта")
        onClosed(reason)
    }

    // --------------- reading ----------

    private fun dispatch(chan: Int, inner: ByteArray) {
        if (Ch.isCmdStyle(chan)) {
            val m = Wire.parseCmd(inner)
            if (m == null) {
                AppLog.i("RX ${Ch.name(chan)} слишком короткое inner (${inner.size} байт): ${Hex.compact(inner, 32)}")
                AppLog.detail(Hex.dump(inner))
                return
            }
            rxByType.merge(m.type, 1, Int::plus)
            val decoded = if (m.body.isEmpty()) null else PbText.render(m.body, Schemas.byType[m.type])
            val note = (if (m.truncated) " [TRUNCATED declared=${m.declaredLen}]" else "") +
                (if (m.resv != 0) " [resv=${m.resv}]" else "") +
                (if (Schemas.byType[m.type] == null && m.body.isNotEmpty()) " [схемы нет]" else "")
            val quiet = m.type == Msg.TOUCH_ACTION_MOVE || (m.type == Msg.TOUCH_ACTION && touchCount > 20 && isMove(m.body))
            AppLog.msg("RX", chan, inner.size, m.type, m.declaredLen, decoded, inner, ui = !quiet, note = note)
            if (chan == Ch.CMD) onCmd(m) else onTouch(m)
        } else {
            val whole = if (chan == Ch.VR) vrReassembler.feed(inner) ?: return else inner
            if (chan == Ch.VR && vrReassembler.assembled == 1 && whole !== inner) {
                AppLog.i("RX VR: ГУ режет сообщение на кадры по ${vrReassembler.firstFragment} Б, собираю (${whole.size} Б)")
            }
            val m = Wire.parseData(whole)
            if (m == null) {
                AppLog.i("RX ${Ch.name(chan)} слишком короткое inner (${inner.size} байт): ${Hex.compact(inner, 32)}")
                return
            }
            rxByType.merge(m.type, 1, Int::plus)
            if (chan == Ch.VR && m.type == Msg.VR_MIC_DATA) {
                onMicData(m)
                return
            }
            val note = " time=${m.time}" + (if (m.truncated) " [TRUNCATED declared=${m.declaredLen}]" else "")
            AppLog.msg("RX", chan, inner.size, m.type, m.declaredLen, null, inner, note = note)
        }
    }

    /** head unit microphone (VR 0x58001): body = PCM16 mono 16 kHz, no encryption. In log: first package and each 200th. */
    private fun onMicData(m: DataMsg) {
        micRxPackets++
        micRxBytes += m.data.size
        if (micRxPackets == 1L || micRxPackets % 200 == 0L) {
            AppLog.i("RX VR MIC_DATA len=${m.data.size} time=${m.time}${if (m.truncated) " [TRUNCATED declared=${m.declaredLen}]" else ""} #$micRxPackets, всего $micRxBytes Б", ui = micRxPackets == 1L)
        }
        if (m.truncated || m.data.isEmpty()) return
        bridge.onCarlifeMicData(m.data)
    }

    private fun isMove(body: ByteArray): Boolean {
        val f = PbReader.parse(body) ?: return false
        return ((PbReader.int(f, 1) ?: -1) and 0xFF) == 2
    }

    // ---------------- CMD ----------------

    private fun onCmd(m: CmdMsg) {
        when (m.type) {
            Msg.HU_PROTOCOL_VERSION -> {
                val reply = protocolHandshake.accept(m)
                if (reply == null) {
                    AppLog.i("== HU_PROTOCOL_VERSION некорректен или отличается от принятой версии $huVersion; игнор")
                    return
                }
                if (reply.repeated) {
                    if (cfg.desktopVideo == null) {
                        // Baidu: ConnectClient.geHasMatch() -> second MATCH do not span (CarlifeConnectHsgHandler :308-310)
                        AppLog.i("== ПОВТОРНЫЙ HU_PROTOCOL_VERSION $huVersion, второй MATCH не шлю (как Baidu)")
                        return
                    }
                    // Software reconnect can leave AOA open. Replay our greeting, preserving
                    // the active display/encoder; the following START requests a fresh IDR.
                    AppLog.i("== ПОВТОРНЫЙ HU_PROTOCOL_VERSION $huVersion: повторяю стартовую пачку без перезапуска рабочего стола")
                    for (c in reply.commands) sendCmd(c.type, c.body)
                    return
                }
                huVersion = "${reply.major}.${reply.minor}"
                CarLifeState.protocol = "ГУ шлёт версию $huVersion"
                AppLog.i("== ВЕРСИЯ ПРОТОКОЛА ГУ: major=${reply.major} minor=${reply.minor}")
                WLog.ev(WLog.HS, "HU_PROTOCOL_VERSION", "version" to huVersion)
                matchSent = true
                // starting pack at once, nothing to expect from head unit: MATCH, FOREGROUND, SCREEN_ON, MD_INFO
                mdInfoKey = reply.mdInfoKey
                for (c in reply.commands) sendCmd(c.type, c.body)
                val mod = Handshake.moduleStatus(reply.major)
                try {
                    sched.schedule({ sendCmd(mod.type, mod.body) }, Handshake.MODULE_STATUS_DELAY_MS, TimeUnit.MILLISECONDS)
                } catch (_: java.util.concurrent.RejectedExecutionException) {
                    // session is already closed
                }
                CarLifeState.status = "handshake: стартовая пачка отправлена"
            }
            Msg.HU_INFO -> {
                huInfo = PbText.render(m.body, Schemas.deviceInfo)
                AppLog.i("== HU_INFO: $huInfo")
                PbReader.parse(m.body)?.let {
                    WLog.ev(WLog.HS, "HU_INFO", "manufacturer" to PbReader.str(it, 13), "model" to PbReader.str(it, 14))
                    bridge.onHuInfo(PbReader.str(it, 13), PbReader.str(it, 14), PbReader.str(it, 15), PbReader.str(it, 2))
                    CarLifeState.huName = listOfNotNull(PbReader.str(it, 13), PbReader.str(it, 14))
                        .filter { s -> s.isNotBlank() }.joinToString(" ").ifEmpty { null }
                    CarLifeState.huCarlifeVersion = PbReader.str(it, 24)?.takeIf { s -> s.isNotBlank() }
                }
                applyMobis()
                logDesay()
                // No Response: Baidu only saves (CarlifeDeviceInfoManager.setCarDeviceInfo)
            }
            Msg.STATISTIC_INFO -> {
                huStatistic = PbText.render(m.body, Schemas.statisticsInfo)
                AppLog.i("== STATISTIC_INFO: $huStatistic")
                PbReader.parse(m.body)?.let {
                    WLog.ev(WLog.HS, "STATISTIC_INFO", "channel" to PbReader.str(it, 4))
                    bridge.onHuStatistic(PbReader.str(it, 4))
                    CarLifeState.huChannel = PbReader.str(it, 4)?.takeIf { s -> s.isNotBlank() }
                    CarLifeState.huStatVersion = PbReader.str(it, 2)?.takeIf { s -> s.isNotBlank() }
                }
                applyMobis()
                scheduleMobisMic()
                logDesay()
                for (c in Handshake.onStatisticInfo(sendFeatureConfigRequest = true)) sendCmd(c.type, c.body)
            }
            Msg.HU_AUTHEN_REQUEST -> {
                sawAuthRequest = true
                val f = PbReader.parse(m.body)
                val rnd = f?.firstOrNull { it.number == 1 && it.wire == 2 }?.data
                val rndStr = rnd?.toString(Charsets.UTF_8)
                authRandom = rnd?.let { PbText.quote(it) + " hex=" + Hex.compact(it, 64) }
                val v2 = Handshake.isAuthV2(rndStr)
                AppLog.i("== ГУ ШЛЁТ HU_AUTHEN_REQUEST randomValue=$authRandom")
                AppLog.i("== AUTHEN randomValue ${if (v2) "начинается с \"2.0;\"" else "не начинается с \"2.0;\""}")
                CarLifeState.auth = "HU_AUTHEN_REQUEST пришёл, ${if (v2) "2.0;" else "не 2.0;"}"
                // как Baidu 8.6.7 (PrivateKeyManager.encryptContentProcess): сразу по запросу подпись приватным ключом Baidu,
                // для "2.0;" md5-код от MD_INFO; MD_AUTHEN_RESULT{true} по-прежнему уходит после STATISTIC_INFO
                // Mobis CCNC (h242) шлёт "1": у Baidu versionVerify падает на substring(-1), ответа нет вовсе - делаем так же
                val baiduSilent = bridge.huMobisNx4c && rndStr != null && !HuAuth.isV2Branch(rndStr) && !rndStr.contains('_')
                val enc = if (rndStr == null || baiduSilent) null else try {
                    HuAuth.encryptValue(rndStr, mdInfoKey)
                } catch (e: Exception) {
                    AppLog.e("подпись HU_AUTHEN_REQUEST", e)
                    null
                }
                if (enc == null) {
                    AppLog.i("на HU_AUTHEN_REQUEST не отвечаю (${when {
                        rndStr == null -> "нет randomValue"
                        baiduSilent -> "Mobis CCNC: randomValue без \"2.0;\" и \"_\", Baidu тут молчит"
                        else -> "подпись не вышла"
                    }})")
                } else {
                    if (rndStr != null && HuAuth.isV2Branch(rndStr)) AppLog.i("== AUTHEN 2.0: md5 от \"CARLIFE\" + \"$mdInfoKey\" + дата")
                    AppLog.i("== отвечаю MD_AUTHEN_RESPONSE encryptValue=$enc")
                    CarLifeState.auth += ", MD_AUTHEN_RESPONSE отправлен"
                    sendCmd(Msg.MD_AUTHEN_RESPONSE, Msgs.authenResponse(enc))
                }
            }
            Msg.HU_FEATURE_CONFIG_RESPONSE -> onFeatureConfig(m)
            Msg.BT_START_IDENTIFY_REQ -> {
                // Baidu BtDeviceManager :436-457: without HFP with head unit and BLUETOOTH_AUTO_CONNECT - status=0 with address head unit.
                // The rest of the head unit do not respond as before (live and unanswered)
                val addr = PbReader.parse(m.body)?.let { PbReader.str(it, 1) }
                if (bridge.huMobisNx4c && !addr.isNullOrEmpty()) {
                    AppLog.i("== BT_START_IDENTIFY_REQ $addr: Mobis CCNC, отвечаю BT_IDENTIFY_RESULT_IND status=0")
                    sendCmd(Msg.BT_IDENTIFY_RESULT_IND, Msgs.btIdentifyResult(0, addr))
                }
            }
            Msg.CARLIFE_DATA_SUBSCRIBE -> {
                val done = Msgs.subscribeDone(m.body)
                if (done == null) {
                    AppLog.i("== CARLIFE_DATA_SUBSCRIBE не разобрался, не отвечаю (как Baidu)")
                } else {
                    AppLog.i("== CARLIFE_DATA_SUBSCRIBE moduleID=${Msgs.subscribeIds(m.body)}, отвечаю DONE")
                    sendCmd(Msg.CARLIFE_DATA_SUBSCRIBE_DONE, done)
                }
            }
            Msg.CARLIFE_DATA_SUBSCRIBE_START, Msg.CARLIFE_DATA_SUBSCRIBE_STOP -> {
                val on = m.type == Msg.CARLIFE_DATA_SUBSCRIBE_START
                val ids = Msgs.subscribeIds(m.body)
                // navigation disabled in settings: subscription head unit do not transfer, NAVI_NEXT_TURN_INFO will not go away
                val pass = ids.filter { it != SUB_NAVI || cfg.metaNavi }
                AppLog.i("== SUBSCRIBE_${if (on) "START" else "STOP"} moduleID=$ids" +
                    (if (pass.size != ids.size) ", навигация выключена в настройках" else ""))
                bridge.onCarlifeSubscribe(pass, on)
            }
            Msg.VIDEO_ENCODER_JPEG -> {
                AppLog.i("== ГУ просит JPEG-режим видео: отвечаю ACK, но шлю только H.264")
                sendCmd(Msg.VIDEO_ENCODER_JPEG_ACK, null)
            }
            Msg.REQUEST_MD_THEME_STATE -> {
                // our theme = latest that sent head unit (it also shows AA), to CHANGE_MD_THEME - light
                val mode = bridge.huTheme
                AppLog.i("== REQUEST_MD_THEME_STATE: отвечаю themeModes=$mode (${AaBridgeCore.themeName(mode)})")
                sendCmd(Msg.RESPONSE_MD_THEME_STATE, Msgs.themeState(mode))
            }
            Msg.CHANGE_MD_THEME -> {
                AppLog.i("== CHANGE_MD_THEME ${PbText.render(m.body, Schemas.themeState)}")
                val mode = PbReader.parse(m.body)?.let { PbReader.int(it, 1) }
                if (mode != null) CarLifeState.huTheme = mode
                bridge.onCarlifeTheme(mode)
            }
            Msg.HU_AUTHEN_RESULT -> {
                val f = PbReader.parse(m.body)
                val ok = f?.let { PbReader.int(it, 1) }
                huAuthResult = "authenResult=$ok"
                AppLog.i("== HU_AUTHEN_RESULT: $ok")
                CarLifeState.auth += " -> HU_AUTHEN_RESULT=$ok"
            }
            Msg.VIDEO_ENCODER_INIT -> onVideoInit(m)
            Msg.VIDEO_ENCODER_START -> onVideoStart()
            Msg.VIDEO_ENCODER_PAUSE -> {
                paused = true
                desktopVideoDelivery.pause()
                if (pauseHeartbeatHu) { // first heartbeat directly; Desay by USB - as before, via 2 with
                    lastVideoTx = 0L
                    pauseHeartbeat()
                }
                bridge.onCarlifePause()
                AppLog.i("видео: PAUSE, кадры не шлю")
            }
            Msg.VIDEO_ENCODER_RESET -> {
                AppLog.i("видео: RESET, прошу ключевой кадр")
                source?.requestKeyFrame()
                bridge.onCarlifeKeyFrameRequest()
            }
            Msg.VIDEO_ENCODER_FRAME_RATE_CHANGE -> {
                // Legacy AA accepts 3..30; the direct desktop also supports requests through 60 fps.
                val fps = PbReader.parse(m.body)?.let { PbReader.int(it, 1) } ?: -1
                val allowed = if (cfg.desktopVideo != null) DesktopVideoQuality.frameRateChangeAllowed(fps)
                    else Handshake.frameRateChangeAllowed(fps)
                if (allowed) {
                    onFrameRateChange(fps)
                    sendCmd(Msg.VIDEO_ENCODER_FRAME_RATE_CHANGE_DONE, Msgs.frameRate(fps))
                } else {
                    AppLog.i("видео: FRAME_RATE_CHANGE fps=$fps вне 3..${if (cfg.desktopVideo != null) 60 else 30}, игнор")
                }
            }
            Msg.MODULE_CONTROL -> {
                // head unit asks the status of the module (moduleID=3 MUSIC; Desay G6S relay pack status=1 after handshake and
                // when returning to CarLife-music): we answer on the fact whether MEDIA is playing, or on the decision of the bridge
                val f = PbReader.parse(m.body)
                val module = f?.let { PbReader.int(it, 1) }
                val status = f?.let { PbReader.int(it, 2) }
                if (module == CarlifeAudio.MODULE_MUSIC && AaBridgeCore.MODULE_MUSIC_ENABLED) {
                    val on = bridge.onCarlifeMusicControl(status)
                    AppLog.i("== MODULE_CONTROL MUSIC status=$status, отвечаю MODULE_STATUS MUSIC=${if (on) 1 else 0}", ui = false)
                    sendModuleStatus(CarlifeAudio.MODULE_MUSIC, if (on) 1 else 0)
                } else if (module == MODULE_VR) {
                    // Voice button on head unit/Role: Baidu on VR=1 opens voice input (openVrAndDialog), 0/3 - closes
                    val sinceOwn = SystemClock.elapsedRealtime() - lastOwnVrOnMs
                    when {
                        status != 1 -> AppLog.i("== MODULE_CONTROL VR status=$status: игнор")
                        sinceOwn < VR_ECHO_MS -> AppLog.i("== MODULE_CONTROL VR=1 через $sinceOwn мс после нашего VR=1: эхо, игнор")
                        else -> bridge.onCarlifeVoiceKey()
                    }
                }
            }
            Msg.BT_HFP_CONNECTION -> {
                // field 1: 2 = phone connected to head unit by BT, 0 = disconnected; after connecting, AA starts autoplay itself
                val state = PbReader.parse(m.body)?.let { PbReader.int(it, 1) }
                bridge.onHuBtHfpConnection(state)
            }
            Msg.LAUNCH_MODE_PHONE -> bridge.onCarlifeLaunchPhone()
            Msg.HU_GEAR_INFO -> {
                val gear = PbReader.parse(m.body)?.let { PbReader.int(it, 1) }
                if (bridge.onHuGear(gear)) {
                    for (d in AaBridgeCore.GEAR_IDR_DELAYS_MS) sched.schedule({
                        source?.requestKeyFrame()
                        bridge.onCarlifeKeyFrameRequest()
                    }, d, TimeUnit.MILLISECONDS)
                }
            }
            Msg.MD_EXIT -> close("ГУ прислало MD_EXIT")
            else -> Unit // The rest is in the log.
        }
    }

    /** Auto: fps target transcoder = max(fps head unit, 10) on the fly; fixed setting: only answer DONE. Passthrough do not touch (emission P-frame breaks the flow). */
    private fun onFrameRateChange(fps: Int) {
        if (bridge.huMobisNx4c) {
            AppLog.i("видео: FRAME_RATE_CHANGE fps=$fps, Mobis CCNC: отвечаю DONE, энкодер держит ${AaBridgeCore.MOBIS_FPS} fps, как Baidu")
            return
        }
        if (!bridge.transcodeEnabled) {
            AppLog.i("видео: FRAME_RATE_CHANGE fps=$fps, passthrough: частота не регулируется, энкодер AA не трогаю")
            return
        }
        val fixed = cfg.fpsSetting.fixed
        if (fixed != null) {
            AppLog.i("видео: FRAME_RATE_CHANGE fps=$fps, настройка фиксирована на $fixed: отвечаю DONE, частоту не меняю")
            return
        }
        val wanted = maxOf(fps, Handshake.MIN_APPLIED_FPS)
        val applied = Handshake.capFps(wanted, huMaxFps())
        val old = targetFps
        targetFps = applied
        val capped = thermalThrottle.capFps(applied)
        val live = bridge.setTargetFps(capped)
        val hu = if (applied != wanted) ", потолок ГУ $applied" else ""
        val heat = if (capped != applied) ", из-за нагрева $capped" else ""
        AppLog.i("видео: FRAME_RATE_CHANGE fps=$fps, целевой fps $old -> $applied$hu$heat${if (live) "" else " (транскодер ещё не создан, применится при старте)"}")
    }

    private fun resolveDesktopGeometry(width: Int = 0, height: Int = 0) {
        val selection = cfg.desktopVideo ?: return
        mode = selection.resolve(width, height)
        bridge.configureDesktopVideo(this, mode, selection.densityFor(mode))
        CarLifeState.videoMode = mode
        CarLifeState.densityOverride = selection.densityFor(mode)
    }

    private fun chooseVideoFps(requested: Int): Int = if (cfg.desktopVideo != null)
        DesktopVideoQuality.chooseFps(cfg.fpsSetting, requested, huMaxFps())
        else Handshake.chooseFps(cfg.fpsSetting, requested, huMaxFps())

    @Synchronized private fun onVideoInit(m: CmdMsg) {
        val f = PbReader.parse(m.body)
        val rw = f?.let { PbReader.int(it, 1) } ?: 0
        val rh = f?.let { PbReader.int(it, 2) } ?: 0
        val rf = f?.let { PbReader.int(it, 3) } ?: 0
        requested = "${rw}x$rh fps=$rf"
        if (rw > 0 && rh > 0) onHuSize(rw, rh)
        AppLog.i("== ГУ ПРОСИТ РАЗРЕШЕНИЕ: ${rw}x$rh fps=$rf")
        WLog.ev(WLog.HS, "VIDEO_ENCODER_INIT", "size" to "${rw}x$rh", "fps" to rf)
        if (videoInited && pendingW > 0) {
            // as VideoMsgHandler (:84-88): repeated INIT -> only INIT_DONE with the same size
            AppLog.i("== повторный INIT, отвечаю INIT_DONE ${pendingW}x$pendingH fps=$pendingFps")
            sendCmd(Msg.VIDEO_ENCODER_INIT_DONE, Msgs.videoEncoderInfo(pendingW, pendingH, pendingFps))
            return
        }
        resolveDesktopGeometry(rw, rh)
        // size sets video mode (default 1280x720 fps 30: head unit Honda takes and shows whole, checked in machine)
        val (w, h, why) = Triple(mode.outW, mode.outH, "мост: режим ${mode.label}")
        // Mobis CCNC (h242): INIT_DONE with the fps requested by head unit and encoder Baidu while going to 20 fps
        val mobis = bridge.huMobisNx4c
        val fps = if (mobis) (if (rf in 1..30) rf else Handshake.DEFAULT_FPS) else chooseVideoFps(rf)
        pendingW = w
        pendingH = h
        pendingFps = fps
        targetFps = if (mobis) AaBridgeCore.MOBIS_FPS else fps
        if (mobis) AppLog.i("видео: Mobis CCNC, INIT_DONE fps=$fps, энкодер ${AaBridgeCore.MOBIS_FPS} fps ${AaBridgeCore.MOBIS_BITRATE / 1000} кбит/с VBR")
        videoInited = true
        chosen = "${w}x$h ($why)"
        AppLog.i("== ОТВЕЧАЮ INIT_DONE ${w}x$h fps=$fps ($why; ГУ просит fps=$rf, настройка ${cfg.fpsSetting.name}${if (bridge.transcodeEnabled) "" else ", passthrough: частота не регулируется"})")
        CarLifeState.video = "ГУ просит $requested, кодирую ${w}x$h"
        sendCmd(Msg.VIDEO_ENCODER_INIT_DONE, Msgs.videoEncoderInfo(w, h, fps))
        sendCmd(Msg.FOREGROUND, null)
        // VW Tharu XR 2026 (CarLife 2.1) after INIT_DONE START not send, waiting for the video and through ~ 5.5 Rivut USB
        try {
            sched.schedule({
                if (!closed.get() && !carlifeStarted) {
                    AppLog.i("== START не пришёл за ${Handshake.START_FALLBACK_MS} мс после INIT_DONE: стартую видео сам")
                    onVideoStart()
                }
            }, Handshake.START_FALLBACK_MS, TimeUnit.MILLISECONDS)
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // session is already closed
        }
    }

    private var desayLogged = false

    private fun logDesay() {
        if (!bridge.huDesayG6S || desayLogged) return
        desayLogged = true
        AppLog.i("== ГУ Desay G6S: маршрутный NAVI=1 выключен")
    }

    /**
     * Mobis CCNC (h242): Baidu mode reactions that need a flag before STATISTIC_INFO. Flag comes on HU_INFO
     * (a spare feature is a channel in STATISTIC_INFO), so it is called after both. Once per session.
     */
    private fun applyMobis() {
        if (!bridge.huMobisNx4c || mobisApplied) return
        mobisApplied = true
        transport.setWholeWrites(true)
        AppLog.i("== Mobis CCNC: запись в AOA как у Baidu (8 байт заголовка и весь inner по одному write), пустой VIDEO_DATA после ${MOBIS_KEEPALIVE_MS} мс тишины")
    }

    /** Through [Handshake.MIC_INIT_DELAY_MS] after STATISTIC_INFO Baidu initializes the voice (CarlifeConnectHsgHandler :490-495). */
    private fun scheduleMobisMic() {
        if (!bridge.huMobisNx4c || mobisMicScheduled) return
        mobisMicScheduled = true
        try {
            sched.schedule({ sendMobisMic() }, Handshake.MIC_INIT_DELAY_MS, TimeUnit.MILLISECONDS)
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // session is already closed
        }
    }

    private fun sendMobisMic() {
        if (closed.get()) return
        val items = huFeatureItems
        val what = Handshake.baiduMicStart(items ?: emptyList())
        val src = if (items == null) "FEATURE_CONFIG ещё не пришёл, решаю по умолчаниям Baidu" else "FEATURE_CONFIG: ${huFeature ?: "пусто"}"
        when (what) {
            Handshake.MicStart.END -> {
                AppLog.i("MIC -> ГУ: Mobis CCNC, MIC_RECORD_END как Baidu ($src)")
                sendCmd(Msg.MIC_RECORD_END, null)
            }
            Handshake.MicStart.WAKEUP_START -> {
                AppLog.i("MIC -> ГУ: Mobis CCNC, MIC_RECORD_WAKEUP_START как Baidu ($src)")
                sendCmd(Msg.MIC_RECORD_WAKEUP_START, null)
            }
            Handshake.MicStart.NONE -> AppLog.i("MIC: Mobis CCNC, Baidu тут ГУ ничего не шлёт ($src)")
        }
    }

    private fun onFeatureConfig(m: CmdMsg) {
        val fc = FeatureConfig.parse(m.body)
        if (fc == null) {
            AppLog.i("== FEATURE_CONFIG не разобрался: ${Hex.compact(m.body, 64)}")
            return
        }
        huFeature = fc.items.joinToString(" ") { "${it.first}=${it.second}" }
        huFeatureItems = fc.items
        bridge.onHuFeatureConfig(fc.value("AUDIO_TRANSMISSION_MODE"))
        // Baidu FeatureConfigManager.excute :241-243: VOICE_MIC -> MODULE_STATUS{moduleID=6, statusID=meaning } immediately
        val voiceMic = fc.value("VOICE_MIC")
        if (bridge.huMobisNx4c && voiceMic != null) {
            AppLog.i("== VOICE_MIC=$voiceMic: Mobis CCNC, шлю MODULE_STATUS moduleID=$MODULE_MIC statusID=$voiceMic, как Baidu")
            sendCmd(Msg.MODULE_STATUS, Msgs.moduleStatusSingle(MODULE_MIC, voiceMic))
        }
        AppLog.i("== HU FEATURE_CONFIG (${fc.items.size} ключей), btAudio=${fc.huBtAudioSupport} btName=${fc.huBtName} btMAC=${fc.huBtMac}")
        for ((k, v) in fc.items) AppLog.i("   feature $k = $v")
        val enc = fc.value(FEATURE_CONTENT_ENCRYPTION)
        encryptionNeeded = enc?.let { it == 1 }
        AppLog.i("== CONTENT_ENCRYPTION=${enc ?: "нет в списке"}, CONNECT_TYPE=${fc.value("CONNECT_TYPE") ?: "нет"}")
        WLog.ev(WLog.HS, "FEATURE_CONFIG", "keys" to fc.items.size, "CONNECT_TYPE" to fc.value("CONNECT_TYPE"), "CONTENT_ENCRYPTION" to enc)
        if (enc == 1) {
            AppLog.i("== !!! ГУ ТРЕБУЕТ ШИФРОВАНИЕ КАНАЛА (CONTENT_ENCRYPTION=1): нужна цепочка RSA/AES (0x1006A..0x1006E), пока не реализована")
        }
    }

    /** START: gate AA resets, stub (if active) starts with SPS+PPS+IDR. */
    @Synchronized private fun onVideoStart() {
        paused = false
        desktopVideoDelivery.start(SystemClock.elapsedRealtime())
        if (pendingW <= 0) {
            resolveDesktopGeometry()
            if (cfg.desktopVideo != null) {
                pendingFps = chooseVideoFps(0)
                targetFps = pendingFps
                videoInited = true // A late INIT must not resize a running Surface.
            }
            pendingW = mode.outW
            pendingH = mode.outH
            chosen = "${mode.outW}x${mode.outH} (START без INIT, мост, режим ${mode.label})"
        }
        carlifeStarted = true
        synchronized(videoLock) {
            csdPending = true
            lastVideoTx = SystemClock.elapsedRealtime()
        }
        CarLifeState.status = "видео: мост"
        bridge.onCarlifeStart()
        if (bannerText != null) startOrUpdatePlaceholder()
    }

    private fun startOrUpdatePlaceholder() {
        synchronized(srcLock) {
            val text = bannerText ?: return
            val cur = source
            if (cur != null) {
                cur.banner = text
                cur.requestKeyFrame()
                return
            }
            // Pioneer RM56E8B (h277), Mazda 20622111 (h359): and stub on USB no higher than the ceiling of head unit (without setEncoderBitrate: here keep srcLock)
            val stubBps = when {
                bridge.huPioneerRm56 -> minOf(mode.bitrate, AaBridgeCore.PIONEER_RM56_CEILING_BPS)
                bridge.huMazda20622111 -> minOf(mode.bitrate, AaBridgeCore.MAZDA_20622111_CEILING_BPS)
                else -> mode.bitrate
            }
            // h529: in passthrough with SPS-crop stub in frame AA with the same fields, head unit does not see a size change on AA.
            // On wireless connection too: with the tick "And Wi-Fi" there passthrough (stubInAaGeometry itself checks transcoding)
            val mobis = bridge.huMobisNx4c
            stubCropped = !mobis && bridge.stubInAaGeometry
            val s = if (mobis) {
                VideoSource(pendingW, pendingH, AaBridgeCore.MOBIS_FPS, mobisStubCeiling.current, this, baidu = true)
            } else if (stubCropped) {
                val (x, y) = bridge.spsCropOrigin
                VideoSource(mode.aaW, mode.aaH, pendingFps, stubBps, this, Rect(x, y, x + mode.outW, y + mode.outH))
            } else {
                VideoSource(pendingW, pendingH, pendingFps, if (air) bitratePolicy.current else stubBps, this)
            }
            s.banner = text
            try {
                s.start()
                source = s
                AppLog.i("BRIDGE заглушка: энкодер ${s.encoderName} ${s.width}x${s.height}" +
                    (if (stubCropped) " (кадр AA, SPS-кроп до ${mode.outW}x${mode.outH})" else "") + ", текст '$text'")
            } catch (e: Exception) {
                AppLog.e("не удалось запустить энкодер заглушки", e)
                s.stop()
            }
        }
    }

    override fun sendVideo(payload: ByteArray): Boolean {
        if (closed.get() || paused) return false
        synchronized(videoLock) {
            if (videoOverflow()) return false
            lastVideoTx = SystemClock.elapsedRealtime()
            sendVideoData(withFixedSps(payload))
            return true
        }
    }

    override val isAir: Boolean get() = air

    override fun sendVideoTakeover(payload: ByteArray): Boolean {
        if (closed.get() || paused) return false
        synchronized(videoLock) {
            if (videoOverflow()) return false
            // under the same lock as onFrame stub: after AA-IDR none of its frame will not leave
            acceptEncoder = false
            lastVideoTx = SystemClock.elapsedRealtime()
            sendVideoData(withFixedSps(payload))
            return true
        }
    }

    /**
     * Mazda 20622111 (h238/h359) on VIDEO_DATA more ~ 16 KiB head unit She stops reading USB Totally. frame Cut to pieces
     * by [AaBridgeCore.MAZDA_20622111_MAX_VIDEO_PACKET] bytes, each individual VIDEO_DATA in a row (calculation that head unit
     * glues the flow before decoder). The rest of head unit get frame one package as before.
     */
    private fun sendVideoData(data: ByteArray) {
        val max = AaBridgeCore.MAZDA_20622111_MAX_VIDEO_PACKET
        if (!bridge.huMazda20622111 || data.size <= max) {
            send(Ch.VIDEO, Wire.dataInner(Msg.VIDEO_DATA, unixSeconds(), data))
            return
        }
        if (!splitLogged) {
            splitLogged = true
            AppLog.i("видео: Mazda 20622111, кадр ${data.size} Б режу на ${(data.size + max - 1) / max} VIDEO_DATA по ≤ $max Б")
        }
        val ts = unixSeconds()
        var off = 0
        while (off < data.size) {
            val end = minOf(off + max, data.size)
            send(Ch.VIDEO, Wire.dataInner(Msg.VIDEO_DATA, ts, data.copyOfRange(off, end)))
            off = end
        }
    }

    /** All H.264 in head unit (bridge, interception, stub) goes through AnnexB.fixSps: SPS signature Exynos head unit does not accept. */
    private fun withFixedSps(payload: ByteArray): ByteArray {
        val out = AnnexB.fixSps(payload, pendingW, pendingH)
        if (out !== payload && !spsFixLogged) {
            spsFixLogged = true
            val i = payload.indices.firstOrNull { payload[it] != out[it] } ?: return out
            val from = maxOf(0, i - 4)
            AppLog.i("видео: SPS в ГУ исправлен под ${pendingW}x$pendingH: ${Hex.compact(payload.copyOfRange(from, i + 4), 8)}" +
                " -> ${Hex.compact(out.copyOfRange(from, i + 4), 8)}")
        }
        return out
    }

    /**
     * Video queue overflow: more than 30 queued frames or more bytes than can drain in about 500 ms; use 512 KB until drain speed is measured.
     * Checked until a new frame: is added to the empty queue IDR is accepted always. Failure = caller discards frame
     * and waiting for IDR (bridge: outGotIdr=false; stub: csdPending=true), P after the lost frame can not be sent.
     */
    private fun videoOverflow(): Boolean {
        val over = VideoQueueLimit.overflow(transport.queuedVideoFrames, transport.queuedVideoBytes, transport.drainBytesPerSec)
        if (over) {
            lastOverflowMs = SystemClock.elapsedRealtime()
            overflows++
        }
        return over
    }

    override fun videoStats(): LinkVideoStats {
        val bytes = transport.queuedVideoBytes
        val rate = transport.drainBytesPerSec
        return LinkVideoStats(rate, VideoQueueLimit.backlogMs(bytes, rate), transport.queuedVideoFrames, bytes)
    }

    /**
     * Adaptation of bitrate encoder (once in 100 ms): backlog and drain -> BitratePolicy -> MediaCodec.setParameters on the fly.
     * Only transcoding: in passthrough H.264 AA goes as is, bitrate would not change.
     */
    private fun adaptBitrate() {
        try {
            if (closed.get() || !carlifeStarted) return
            if (bridge.huMobisNx4c) {
                if (!mobisAdaptLogged) {
                    mobisAdaptLogged = true
                    AppLog.i("видео: Mobis CCNC, адаптация по бэклогу выключена: энкодер ${AaBridgeCore.MOBIS_BITRATE / 1000} кбит/с VBR, как Baidu, вниз только по размеру IDR > ${IdrBitrateCeiling.LIMIT_BYTES / 1024} КБ")
                }
                return
            }
            if (!bridge.transcodeEnabled) {
                if (!passthroughBitrateLogged) {
                    passthroughBitrateLogged = true
                    AppLog.i("видео: passthrough, адаптация битрейта недоступна (H.264 AA идёт как есть)")
                }
                return
            }
            // on wireless connection policy leads and stub (CBR on the ceiling while AA starts); on USB stub do not touch
            val placeholder = if (air && !bridge.isTranscoding) source else null
            if (!bridge.isTranscoding && placeholder == null) return
            val now = SystemClock.elapsedRealtime()
            val rate = transport.drainBytesPerSec
            val qf = transport.queuedVideoFrames
            if (qf > maxQueueFrames) maxQueueFrames = qf
            val real = VideoQueueLimit.backlogMs(transport.queuedVideoBytes, rate)
            val sinceOverflow = lastOverflowMs.let { if (it == NEVER_MS) -1L else now - it }
            applyHuCeilings()
            val old = bitratePolicy.current
            val bps = bitratePolicy.update(now, VideoQueueLimit.policyBacklogMs(real, sinceOverflow), rate) ?: return
            if (placeholder?.setBitrate(bps) ?: bridge.setEncoderBitrate(bps)) {
                val who = if (placeholder != null) "заглушки" else "энкодера"
                AppLog.i("видео: битрейт $who ${old / 1000} -> ${bps / 1000} кбит/с (${bitratePolicy.lastReason}; потолок ${bitratePolicy.ceilingBps / 1000})")
                if (air) WLog.ev(WLog.VIDEO, "bitrate", "fromKbps" to old / 1000, "toKbps" to bps / 1000,
                    "src" to if (placeholder != null) "placeholder" else "aa", "why" to bitratePolicy.lastReason)
            }
        } catch (e: Exception) {
            AppLog.e("адаптация битрейта", e)
        }
    }

    /**
     * VW ID (h196): head unit disconnects USB EIO on IDR > ~600 KiB. Ceiling transcoding 2 Mbit/s under flag head unit The flag could come.
     * after the start of encoder - bitrate decreases on the fly). With 4 Mbit/s session was torn in the first 10-15 s, while insurance
     * the ceiling was lowered; for the trip he himself converged to 1,7 Mbit / s. The remaining head unit is not affected.
     */
    private fun applyVwCeiling() {
        if (!bridge.huVw20082138) return
        val old = bitratePolicy.current
        if (!bitratePolicy.lowerCeiling(VW_20082138_CEILING_BPS)) return
        AppLog.i("VW ID: потолок битрейта ${bitratePolicy.ceilingBps / 1000} кбит/с (лимит размера IDR)")
        if (bitratePolicy.current != old) bridge.setEncoderBitrate(bitratePolicy.current)
    }

    /**
     * Pioneer RM56E8B (h277): head unit disconnects USB EIO after 25-30 from the 4-5 Mbit/s, on ~ 2 Mbit/s 195 c, stable only on 800 kbit/s
     * 1 Mbit/s ceiling under the flag of head unit (HU_INFO comes to INIT, but the ceiling also applies on the fly).
     * ([BitratePolicy.thermalCapBps]) lives separately and is taken via min in update(), so it does not conflict with it.
     */
    private fun applyPioneerCeiling() {
        if (!bridge.huPioneerRm56) return
        val old = bitratePolicy.current
        if (!bitratePolicy.lowerCeiling(AaBridgeCore.PIONEER_RM56_CEILING_BPS)) return
        AppLog.i("Pioneer RM56E8B: потолок битрейта ${bitratePolicy.ceilingBps / 1000} кбит/с")
        if (bitratePolicy.current != old) bridge.setEncoderBitrate(bitratePolicy.current)
    }

    /** Mazda 20622111 (h238/h359): 1,5 Mbit/s ceiling to make IDR less likely to climb beyond the head package limit unit. */
    private fun applyMazda20622111Ceiling() {
        if (!bridge.huMazda20622111) return
        val old = bitratePolicy.current
        if (!bitratePolicy.lowerCeiling(AaBridgeCore.MAZDA_20622111_CEILING_BPS)) return
        AppLog.i("Mazda 20622111: потолок битрейта ${bitratePolicy.ceilingBps / 1000} кбит/с")
        if (bitratePolicy.current != old) bridge.setEncoderBitrate(bitratePolicy.current)
    }

    private fun applyHuCeilings() {
        applyVwCeiling()
        applyPioneerCeiling()
        applyMazda20622111Ceiling()
    }

    /** fps ceiling under head unit (Pioneer RM56E8B: 15), null = no limit. */
    private fun huMaxFps(): Int? = if (bridge.huPioneerRm56) AaBridgeCore.PIONEER_RM56_MAX_FPS else null

    /**
     * Mobis CCNC (h242): head unit Goes to Cycle START -> EIO on IDR > ~600 KB, and IDR qti VBR Each movement grows
     * second (200 -> 712 KB at 3,84 Mbit/s). Each IDR > 300 KB cuts bitrate encoder ×0,7 (1 Mbit/s floor), several
     * The little ones bring him back. ×1,15 ([IdrBitrateCeiling]). [apply] apply new bitrate C encoder-source.
     */
    private fun mobisKeyFrame(size: Int, ceiling: IdrBitrateCeiling, who: String, apply: (Int) -> Unit) {
        val old = ceiling.current
        val bps = ceiling.onKeyFrame(size)
        if (bps == null) {
            if (ceiling.stuckAtFloor == 1) {
                AppLog.i("Mobis: IDR ${size / 1024} КБ > ${IdrBitrateCeiling.LIMIT_BYTES / 1024}, битрейт $who ${old / 1000} кбит/с уже на полу")
            }
            return
        }
        try {
            apply(bps)
        } catch (e: Exception) {
            AppLog.e("Mobis: битрейт $who", e)
        }
        if (bps < old) {
            AppLog.i("Mobis: IDR ${size / 1024} КБ > ${IdrBitrateCeiling.LIMIT_BYTES / 1024}, битрейт $who ${old / 1000} -> ${bps / 1000} кбит/с")
        } else {
            AppLog.i("Mobis: ${IdrBitrateCeiling.CALM_COUNT} IDR подряд < ${IdrBitrateCeiling.CALM_BYTES / 1024} КБ, битрейт $who ${old / 1000} -> ${bps / 1000} кбит/с")
        }
    }

    /** Insurance VW ID: IDR > 450 KB - ceiling ×0,75 (not lower than 1,5 Mbps), no more than once a second; frame has already left. */
    override fun onEncodedKeyFrame(size: Int) {
        if (bridge.huMobisNx4c) {
            mobisKeyFrame(size, mobisAaCeiling, "энкодера") { bridge.setEncoderBitrate(it) }
            return
        }
        if (size <= VW_20082138_IDR_LIMIT) return
        val now = SystemClock.elapsedRealtime()
        if (vwIdrCutMs != NEVER_MS && now - vwIdrCutMs < VW_20082138_CUT_INTERVAL_MS) return
        applyVwCeiling()
        val old = bitratePolicy.current
        if (!bitratePolicy.lowerCeiling(maxOf(VW_20082138_MIN_BPS, (bitratePolicy.ceilingBps * 0.75).toInt()))) return
        vwIdrCutMs = now
        AppLog.i("VW ID: IDR $size Б > $VW_20082138_IDR_LIMIT, потолок битрейта -> ${bitratePolicy.ceilingBps}")
        if (bitratePolicy.current != old) bridge.setEncoderBitrate(bitratePolicy.current)
    }

    /** Audio of the bridge: MEDIA/TTS channel, header 12 bytes; time = millis & 0xFFFFFFFF in DATA, 0 in INIT/STOP (as Baidu). */
    override fun sendAudio(channel: Int, type: Int, payload: ByteArray): Boolean {
        if (closed.get()) return false
        if (payload.isNotEmpty() && transport.queueSize(channel) > MAX_AUDIO_QUEUE) return false   // The sound should not choke the video in line
        val isData = type == CarlifeAudio.MEDIA_DATA || type == CarlifeAudio.TTS_DATA || type == CarlifeAudio.VR_DATA
        val time = if (isData) (System.currentTimeMillis() and 0xFFFFFFFFL).toInt() else 0
        send(channel, Wire.dataInner(type, time, payload))
        return true
    }

    /** h425: tail MEDIA_DATA, not yet recorded in USB, discard (head unit went into the call and does not read). The queue is general: do not touch other channels. */
    override fun dropQueuedMedia(): Int {
        if (closed.get()) return 0
        return transport.dropQueued(Ch.MEDIA) { it.size >= 12 && Wire.u32(it, 8) == CarlifeAudio.MEDIA_DATA }
    }

    /** MODULE_STATUS about one module (NAVI before TTS, MUSIC at MEDIA INIT/PAUSE) as Baidu ModuleStatusManage. */
    override fun sendModuleStatus(module: Int, status: Int): Boolean {
        if (closed.get()) return false
        sendCmd(Msg.MODULE_STATUS, Msgs.moduleStatusSingle(module, status))
        return true
    }

    /** CMD GO_TO_DESKTOP (MD -> head unit, empty body): head unit shows your desktop. */
    override fun goToDesktop() {
        if (closed.get()) return
        AppLog.i("-> ГУ: GO_TO_DESKTOP")
        sendCmd(Msg.GO_TO_DESKTOP, null)
    }

    /**
     * Record microphone on head unit as in Baidu MD: start = MIC_RECORD_RECOG_START (VrManager.sendStartRecogMsgToVehicle)
     * and MODULE_STATUS VR=1 (prepareShowVr); stop = MIC_RECORD_END (sendRecordEndMsgToVehicle) and VR=0 (prepareCloseVr).
     */
    override fun requestMicRecord(start: Boolean): Boolean {
        if (closed.get()) return false
        if (start) {
            micRxPackets = 0
            micRxBytes = 0
            AppLog.i("MIC -> ГУ: MIC_RECORD_RECOG_START${if (cfg.micVrStatus) " + MODULE_STATUS VR=1" else ""}")
            sendCmd(Msg.MIC_RECORD_RECOG_START, null)
            if (cfg.micVrStatus) {
                lastOwnVrOnMs = SystemClock.elapsedRealtime()
                sendCmd(Msg.MODULE_STATUS, Msgs.moduleStatusSingle(MODULE_VR, 1))
            }
        } else {
            AppLog.i("MIC -> ГУ: MIC_RECORD_END${if (cfg.micVrStatus) " + MODULE_STATUS VR=0" else ""}; с ГУ принято $micRxPackets пакетов, $micRxBytes Б")
            sendCmd(Msg.MIC_RECORD_END, null)
            if (cfg.micVrStatus) sendCmd(Msg.MODULE_STATUS, Msgs.moduleStatusSingle(MODULE_VR, 0))
        }
        return true
    }

    // -------------- metadata AA -> CarLife -------------
    // All three messages go through one thread metaExec: order MEDIA_INFO -> PROGRESS is saved, and transcoding
    // cover and draw arrows do not hold the bridge monitor (call from thread AA-session below it).

    private fun metaSubmit(what: String, task: () -> Unit): Boolean {
        if (closed.get()) return false
        return try {
            metaExec.execute {
                try {
                    if (!closed.get()) task()
                } catch (e: Throwable) {
                    AppLog.e("метаданные: $what", e)
                }
            }
            true
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            false
        }
    }

    override fun sendMediaInfo(info: CarlifeMediaInfo): Boolean {
        if (!cfg.metaMusic) {
            if (!metaMusicOffLogged) AppLog.i("метаданные: музыка выключена в настройках, MEDIA_INFO не шлю")
            metaMusicOffLogged = true
            return false
        }
        return metaSubmit("MEDIA_INFO") {
            val art = info.art
            val jpeg = when {
                art == null || art.isEmpty() -> ByteArray(0)
                art.contentEquals(lastArtSrc) -> lastArtJpeg
                else -> {
                    val t = SystemClock.elapsedRealtime()
                    // Mazda 20622111 (h359Package > > 16 KiB Hangs USB, cover 24 KB killed the session because 3 with after picture
                    val limit = if (bridge.huMazda20622111) AaBridgeCore.MAZDA_20622111_ART_BYTES else MetaImages.MAX_BYTES
                    val j = MetaImages.albumJpeg(art, limit) ?: ByteArray(0)
                    AppLog.i("метаданные: обложка ${PbText.blob(art)} -> ${PbText.blob(j)} за ${SystemClock.elapsedRealtime() - t} мс", ui = false)
                    lastArtSrc = art
                    lastArtJpeg = j
                    j
                }
            }
            sendCmd(Msg.MEDIA_INFO, Msgs.mediaInfo(info, jpeg))
        }
    }

    override fun sendMediaProgress(progressBar: Int): Boolean {
        if (!cfg.metaMusic) return false
        return metaSubmit("MEDIA_PROGRESS_BAR") { sendCmd(Msg.MEDIA_PROGRESS_BAR, Msgs.mediaProgress(progressBar)) }
    }

    private var hondaRoadLogged = false

    /** Honda (channel 2077xxxx/2083xxxx): roadName under ≤24 cells / ≤31 B, otherwise instrument cluster discards the string. The rest of head unit - unchanged. */
    private fun fitRoad(info: NaviTurnInfo): NaviTurnInfo {
        if (!bridge.huHonda) return info
        if (!hondaRoadLogged) {
            hondaRoadLogged = true
            AppLog.i("== Honda приборка: roadName подгоняю под ≤24 клетки / ≤31 Б")
        }
        val road = HondaClusterRoad.fit(info.roadName)
        if (road == info.roadName) return info
        AppLog.i("метаданные: road→\"$road\" (${road.toByteArray(Charsets.UTF_8).size} Б)", ui = false)
        return NaviTurnInfo(info.action, info.nextTurn, road, info.totalDistance, info.remainDistance, info.timeSeconds)
    }

    override fun sendNaviTurn(info: NaviTurnInfo): Boolean {
        if (!cfg.metaNavi) return false
        val sent = fitRoad(info)
        return metaSubmit("NAVI_NEXT_TURN_INFO") {
            // end of route (action 3) - icon empty as Baidu onNaviStateChange(false)
            val icon = if (!cfg.naviField6.icon || info.action == 3) ByteArray(0) else turnIcons.getOrPut(info.nextTurn) {
                MetaImages.turnIcon(info.nextTurn).also {
                    AppLog.i("метаданные: стрелка nextTurn=${info.nextTurn} ${PbText.blob(it)}", ui = false)
                }
            }
            sendCmd(Msg.NAVI_NEXT_TURN_INFO, Msgs.naviNextTurn(sent, cfg.naviField6, icon))
        }
    }

    /** Bridge Transcoder: decoder AA (frame mode) -> crop -> Surface encoder (mode output). null if MediaCodec did not rise. */
    override fun createTranscoder(out: TranscodeOutput): VideoTranscoder? = createBridgeEncoder(out, false)

    override fun createSurfaceEncoder(out: TranscodeOutput): VideoTranscoder? = createBridgeEncoder(out, true)

    private fun createBridgeEncoder(out: TranscodeOutput, directSurface: Boolean): VideoTranscoder? = try {
        if (directSurface) {
            require(DesktopVideoGeometry.matchesNative(mode)) { "Desktop encoder requires native ${DesktopVideoGeometry.outLabel} geometry" }
            encodingSurface = true
        }
        applyHuCeilings()
        val mobis = bridge.huMobisNx4c   // Baidu: 20 fps, VBR to 3,84 Mbit/s, bitrate leads only ceiling IDR (mobisKeyFrame)
        if (directSurface) {
            // A thermal transition may have happened while the lower-bitrate placeholder
            // was active. Apply that same level to the newly selected Surface policy.
            bitratePolicy.setThermalCap(if (thermalThrottle.level == ThermalLevel.NORMAL) Int.MAX_VALUE
                else thermalThrottle.capBitrate(bitratePolicy.ceilingBps))
            val actualBps = if (mobis) mobisAaCeiling.current else bitratePolicy.current
            AppLog.i("DEX video quality native=${mode.outW}x${mode.outH} singleEncode=true " +
                "targetKbps=${actualBps / 1000} ceilingKbps=${bitratePolicy.ceilingBps / 1000} " +
                "profile=${bitratePolicy.profile.name} adaptive=true", false)
        }
        AaTranscoder(
            mode.outW, mode.outH, thermalThrottle.capFps(if (mobis) AaBridgeCore.MOBIS_FPS else targetFps),
            if (mobis) mobisAaCeiling.current else bitratePolicy.current, out, { AppLog.i(it, false) },
            srcWidth = if (directSurface) mode.outW else mode.aaW,
            srcHeight = if (directSurface) mode.outH else mode.aaH,
            crop = if (directSurface) com.yegorkapomidorka.headswap.aa.VideoCrop.NONE else mode.cropFor(bridge.marginsAtBottom),
            baidu = mobis, directSurface = directSurface,
            capabilityFps = if (mobis) AaBridgeCore.MOBIS_FPS else targetFps,
        ).also { it.start() }
    } catch (e: Exception) {
        AppLog.e("BRIDGE транскодер не создан", e)
        null
    }

    override fun showPlaceholder(text: String) {
        bannerText = text
        synchronized(videoLock) {
            if (!acceptEncoder) csdPending = true
            acceptEncoder = true
        }
        if (carlifeStarted && !closed.get()) {
            synchronized(videoLock) { csdPending = true }
            startOrUpdatePlaceholder()
        }
    }

    override fun placeholderSnapshot(): android.graphics.Bitmap? = try {
        synchronized(srcLock) { source }?.snapshot()
    } catch (e: Exception) {
        AppLog.e("снимок заглушки для перехода", e)
        null
    }

    override fun hidePlaceholder() {
        synchronized(videoLock) { acceptEncoder = false }
        bannerText = null
        val old = synchronized(srcLock) {
            val o = source
            source = null
            o
        }
        if (old != null) Thread({ old.stop() }, "cl-placeholder-stop").start()
    }

    // ---------------- TOUCH ----------------

    private fun onTouch(m: CmdMsg) {
        if (m.type == Msg.TOUCH_CAR_HARD_KEY_CODE) {
            val code = PbReader.parse(m.body)?.let { PbReader.int(it, 1) }
            AppLog.i("== HARD KEY keycode=$code")
            if (code != null) bridge.onCarlifeHardKey(code)
            return
        }
        val t = CarlifeTouch.parse(m.type, m.body) ?: return
        setTouchMode(
            if (m.type == Msg.TOUCH_ACTION) "TOUCH_ACTION (прозрачный, TRANSPARENT_SEND_TOUCH_EVENT=true)"
            else "жестовый (отдельные коды 0x68002..0x6800D)"
        )
        touchCount++
        val n = t.fingers
        if (n > 1 && maxFingers < 2) AppLog.i("== МУЛЬТИТАЧ: ГУ шлёт второй палец (action=${t.action} тело=${m.body.size} байт)")
        maxFingers = maxOf(maxFingers, n)
        // for checklist pinch: raw action, fingers, body length; MOVE only when changing the number of fingers, otherwise the log will blow
        if (t.masked != 2 || n != lastMoveFingers) {
            AppLog.i("TOUCH action=${t.action} пальцев=$n тело=${m.body.size} байт", false)
            lastMoveFingers = if (t.masked == 2) n else 0
        }
        // Gesture opcodes also normalize to action 3; only raw TOUCH_ACTION means CANCEL.
        val desktopCancel = bridge.desktopInputEnabled && m.type == Msg.TOUCH_ACTION && t.masked == 3
        if (t.masked in TOUCH_TO_AA || desktopCancel) bridge.onCarlifeTouch(t.action, t.x, t.y, t.px, t.py)
        CarLifeState.touch = "$touchMode; событий=$touchCount; пальцев макс=$maxFingers; последний act=${t.action} x=${t.x} y=${t.y}" +
            (if (n > 1) " px=${t.px} py=${t.py}" else "")
    }

    private fun setTouchMode(mode: String) {
        if (touchMode != mode) {
            touchMode = mode
            AppLog.i("== РЕЖИМ ТАЧА: $mode")
        }
    }

    // -------------- record ---------

    private fun sendCmd(type: Int, body: ByteArray?) = send(Ch.CMD, Wire.cmdInner(type, body))

    private fun send(chan: Int, inner: ByteArray) {
        if (closed.get()) return
        if (bridge.huMazda20622111 && inner.size > AaBridgeCore.MAZDA_20622111_MAX_VIDEO_PACKET + 12) {
            // insurance h359: VIDEO_DATA already cut, everything else is more than the limit head unit do not send - otherwise head unit ceases to read USB
            AppLog.i("Mazda 20622111: пакет ch$chan ${inner.size} Б больше лимита ГУ, не отправляю")
            return
        }
        transport.send(chan, inner)
    }

    /** After recording in transport (thread writer). */
    private fun logTx(chan: Int, inner: ByteArray) {
        desktopAudioOutputLease.writeCompleted(SystemClock.elapsedRealtime())
        if (Ch.isCmdStyle(chan)) {
            val m = Wire.parseCmd(inner) ?: return
            val decoded = if (m.body.isEmpty()) null else PbText.render(m.body, Schemas.byType[m.type])
            // track progress - once per second, update the distance (action 2) - too: in a file, not in UI
            val quiet = m.type == Msg.MEDIA_PROGRESS_BAR ||
                (m.type == Msg.NAVI_NEXT_TURN_INFO && PbReader.parse(m.body)?.let { PbReader.int(it, 1) } == 2)
            AppLog.msg("TX", chan, inner.size, m.type, m.declaredLen, decoded, inner, ui = !quiet)
        } else {
            val m = Wire.parseData(inner) ?: return
            desktopVideoDelivery.writeCompleted(chan, m.type, m.data.size, SystemClock.elapsedRealtime())
            if (chan != Ch.VIDEO) { // audio bridge: INIT/STOP always, DATA - first and every 200
                audioTx++
                if (m.type == CarlifeAudio.TTS_INIT) ttsTxSinceInit = 0
                val ttsHead = m.type == CarlifeAudio.TTS_DATA && ++ttsTxSinceInit <= TTS_TX_LOGGED
                if (m.data.size <= 16 || audioTx <= 3 || audioTx % 200 == 0L || ttsHead) {
                    AppLog.i(
                        "TX ${Ch.name(chan)} ${Msg.hex(m.type)} ${Msg.name(m.type)} len=${m.data.size} time=${m.time} #$audioTx" +
                            (if (m.data.size <= 16) " ${Hex.compact(m.data, 16)}" else ""), ui = false
                    )
                }
                return
            }
            if (m.data.isEmpty()) { // keepalive on PAUSE: empty VIDEO_DATA (Desay) or VIDEO_HEARTBEAT (VW 20082120)
                vBeats++
                if (vBeats <= 3) AppLog.msg("TX", chan, inner.size, m.type, m.declaredLen, null, inner)
                return
            }
            vFrames++
            vBytes += m.data.size
            if (vFrames == 1L) WLog.ev(WLog.HS, "first-video", "bytes" to m.data.size)
            val key = AnnexB.nalTypes(m.data).contains(5)
            if (key) vKeys++
            val nals = AnnexB.nalTypes(m.data)
            if (vFrames <= 5 || key) {
                AppLog.msg("TX", chan, inner.size, m.type, m.declaredLen, "nal=$nals", inner, ui = vFrames <= 5)
            }
        }
    }

    // --------------- video from encoder -----------

    override fun onConfig(csd: ByteArray) {
        this.csd = csd
        val types = AnnexB.nalTypes(csd)
        var prof = ""
        if (csd.size > 8 && types.firstOrNull() == 7) {
            // [00 00 00 01][SPS header][profile_idc][constraints][level_idc]
            prof = " profile_idc=${csd[5].toInt() and 0xFF} constraints=0x%02X level_idc=${csd[7].toInt() and 0xFF}".format(csd[6].toInt() and 0xFF)
        }
        AppLog.i("энкодер: codec config ${csd.size} байт nal=$types$prof")
        AppLog.detail(Hex.dump(csd, 128))
    }

    override fun onFrame(data: ByteArray, key: Boolean) {
        if (closed.get() || paused) return
        synchronized(videoLock) {
            if (!acceptEncoder) return   // stub hidden (AA-video is available): the remainder of the frame encoder is not needed
            onEncoderFrame(data, key)
        }
    }

    private fun onEncoderFrame(data: ByteArray, key: Boolean) {
        if (key && bridge.huMobisNx4c) mobisKeyFrame(data.size, mobisStubCeiling, "заглушки") { source?.setBitrate(it) }
        if (!key && csdPending) {
            vDropped++
            return // up to the first IDR do not send anything (the first package must start with SPS)
        }
        if (videoOverflow()) {
            // frame is lost: P after it can not be sent, again waiting for IDR (csdPending), request IDR no more than once in 1.5 with
            vDropped++
            csdPending = true
            if (placeholderIdr.tryRequest(SystemClock.elapsedRealtime())) source?.requestKeyFrame()
            return
        }
        var payload = data
        if (key) {
            val c = csd
            if (csdPending && c != null && !AnnexB.startsWithSps(data)) payload = AnnexB.glue(c, data)
            csdPending = false
            if (stubCropped) payload = bridge.stubSpsCrop(payload)
        }
        lastVideoTx = SystemClock.elapsedRealtime()
        sendVideoData(withFixedSps(payload))
    }

    override fun onError(msg: String, t: Throwable?) {
        AppLog.e(msg, t)
        CarLifeState.status = "ОШИБКА: $msg: ${t?.message}"
    }

    // --------------timer -----------

    private var lastStatsAt = 0L
    private var lastStatsFrames = 0L

    // Empty keepalive when video is going NO: frame provides encoder (repeat-previous-frame-after 100 ms).
    // On PAUSE (head unit in its menu / on its source), the channel is otherwise silent:
    // - Desay G6S (Mazda CX-5) through22 from Silence USB (EIO, Ivan's log 2026-10-12): empty VIDEO_DATA 0x20001,
    //   Match Baidu sendEmptyPacketBackground: every two seconds over USB and about every second wirelessly; Wi-Fi head units close the video socket sooner.
    // - VW channel 20082120 rips the channel through 3,6-4 with after PAUSE even at 0x20001 times in 1-2 with (h93, logs 2026-10-15 USB
    //   and 2026-10-16 Wi-Fi), reconnect with FOREGROUND pulled the user from the menu head unit in CarLife: VIDEO_HEARTBEAT 0x20002 directly
    //   and once in 100 ms, how Baidu Recorder.sendEmptyPacketBackground (~50 ms). VW CNS3 channel 20082122 Also,
    //   after about 5.6 seconds (h222, 2026-10-17, USB).
    // - Desay G6S on wireless connection (h211, reverse): empty VIDEO_DATA times in 900 ms does not hold, via 24-28 with head unit removes
    //   Wi-Fi Direct; experienced only pauses up to 25 s. Baidu on the pause of the flight exactly VIDEO_HEARTBEAT (Recorder:906-926),
    //   put it once in 100 ms, USB Desay remains VIDEO_DATA.
    /**
     * head unit, which on PAUSE need VIDEO_HEARTBEAT times in 100 ms: VW 20082120/20082122 (h93, h222), VW BT 1420 by wireless connection
     * (Pavel, 2026-10-18: without keepalive on PAUSE, the head unit closed the socket after about one minute; empty VIDEO_DATA caused a disconnect on START.)
     * and Desay G6S by wireless connection (h211).
     */
    private val pauseHeartbeatHu: Boolean
        get() = bridge.huVw20082120 || (air && (bridge.huVwBt1420 || bridge.huDesayG6S))

    private fun pauseHeartbeat() {
        if (closed.get() || !PAUSE_KEEPALIVE_ENABLED) return
        if (!paused) {
            idleHeartbeat()
            return
        }
        val (type, period) =
            if (pauseHeartbeatHu) Msg.VIDEO_HEARTBEAT to PAUSE_HEARTBEAT_MS else Msg.VIDEO_DATA to pauseKeepaliveMs
        val now = SystemClock.elapsedRealtime()
        if (now - lastVideoTx < period) return
        if (vBeats == 0L) AppLog.i("видео: PAUSE, шлю пустой ${Msg.name(type)} раз в $period мс, чтобы ГУ не рвало USB")
        lastVideoTx = now
        send(Ch.VIDEO, Wire.dataInner(type, unixSeconds(), ByteArray(0)))
    }

    /**
     * VW CNS2 Delphi 20082114 (h425, log 2026-10-24): AA on the static screen does not send frame, via 6,4 with no video head unit
     * broke USB (EIO), pauses to 5,1 passed. Outside PAUSE after [IDLE_HEARTBEAT_MS] without frame by VIDEO_HEARTBEAT,
     * as on PAUSE, and further with the same step while AA is silent. Skoda CNS3 21290002 is also (h625, 2026-10-30: EIO via
     * 5,7 with downtime of AA).
     */
    private fun idleHeartbeat() {
        if (!bridge.huIdleVideoHeartbeat || !carlifeStarted || vFrames == 0L) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastVideoTx < IDLE_HEARTBEAT_MS) return
        if (!idleBeatLogged) {
            idleBeatLogged = true
            AppLog.i("видео: AA молчит ${now - lastVideoTx} мс, шлю пустой VIDEO_HEARTBEAT раз в $IDLE_HEARTBEAT_MS мс, чтобы ГУ не рвало USB")
        }
        lastVideoTx = now
        send(Ch.VIDEO, Wire.dataInner(Msg.VIDEO_HEARTBEAT, unixSeconds(), ByteArray(0)))
    }

    /**
     * Mobis CCNC (h242): as Baidu (Recorder.sendEmptyPacketForeground, VideoOutputThread :404-410, :545-581) -
     * after START and yet no PAUSE if VIDEO_DATA did not leave >= 200 ms, by the empty VIDEO_DATA (length 0, time = unix-seconds)
     * and repeat every 200 ms of silence. Including the gap between START and the first IDR (lastVideoTx puts onVideoStart).
     * Not VIDEO_HEARTBEAT 0x20002. PAUSE is powered by the former [pauseHeartbeat].
     */
    private fun mobisKeepalive() {
        if (closed.get() || !bridge.huMobisNx4c || !carlifeStarted || paused) return
        synchronized(videoLock) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastVideoTx < MOBIS_KEEPALIVE_MS) return
            if (!mobisBeatLogged) {
                mobisBeatLogged = true
                AppLog.i("видео: Mobis CCNC, ${now - lastVideoTx} мс без VIDEO_DATA, шлю пустой VIDEO_DATA 0x20001 раз в $MOBIS_KEEPALIVE_MS мс тишины, как Baidu")
            }
            lastVideoTx = now
            send(Ch.VIDEO, Wire.dataInner(Msg.VIDEO_DATA, unixSeconds(), ByteArray(0)))
        }
    }

    private fun tick() {
        if (closed.get()) return
        val now = SystemClock.elapsedRealtime()
        checkThermal(now)
        if (transport.rxBytes == 0L && now - t0 > SILENCE_MS && (now - t0) % 10_000 < 1000) {
            AppLog.i("ГУ не прислало ни одного байта за ${(now - t0) / 1000} с (ждём HU_PROTOCOL_VERSION)")
            CarLifeState.status = "ГУ молчит, байт от него нет"
        }
        if (now - lastStatsAt >= 5000 && (vFrames > 0 || vBeats > 0)) {
            AppLog.i("видео: отправлено кадров=$vFrames (ключевых=$vKeys) байт=$vBytes heartbeat=$vBeats сброшено=$vDropped очередь=${transport.queueSize(Ch.VIDEO)} (+${vFrames - lastStatsFrames} за ${(now - lastStatsAt) / 1000}с)")
            lastStatsAt = now
            lastStatsFrames = vFrames
            CarLifeState.video = "$chosen; кадров=$vFrames hb=$vBeats"
        }
    }

    /**
     * Once per second: heating status (headroom forecast - once in 10 s, more often PowerManager gives NaN) -> ThermalThrottle.
     * Changed the stage - the ceiling of fps and bitrate transcoding on the fly. In passthrough there is nothing to unload, only log.
     */
    private fun checkThermal(now: Long) {
        try {
            val status = thermal.status()
            if (headroomAtMs == NEVER_MS || now - headroomAtMs >= HEADROOM_POLL_MS) {
                headroomAtMs = now
                thermalHeadroom = thermal.headroom()
            }
            if (status != thermalStatus) {
                AppLog.i("нагрев: статус телефона ${thermalName(thermalStatus)} -> ${thermalName(status)} (headroom ${headroomText()})")
                thermalStatus = status
            }
            val level = thermalThrottle.update(now, status, thermalHeadroom) ?: return
            applyThermal(level)
        } catch (e: Exception) {
            AppLog.e("нагрев", e)
        }
    }

    private fun applyThermal(level: ThermalLevel) {
        val why = "статус ${thermalName(thermalStatus)}, headroom ${headroomText()}"
        if (!bridge.transcodeEnabled) {
            if (level != ThermalLevel.NORMAL && !thermalPassthroughLogged) {
                thermalPassthroughLogged = true
                AppLog.i("нагрев: ступень ${level.name} ($why), но видео passthrough: fps и битрейт не регулируются")
            }
            return
        }
        val fps = thermalThrottle.capFps(targetFps)
        val live = bridge.setTargetFps(fps)
        if (bridge.huMobisNx4c) {
            // Mobis CCNC: bitrate encoder do not touch (Baidu keeps fixed), from heating only the ceiling of fps
            AppLog.i("нагрев: ступень ${level.name} ($why): Mobis CCNC, fps $fps (цель $targetFps), битрейт не меняю")
            return
        }
        val old = bitratePolicy.current
        val cap = if (level == ThermalLevel.NORMAL) Int.MAX_VALUE else thermalThrottle.capBitrate(bitratePolicy.ceilingBps)
        if (bitratePolicy.setThermalCap(cap)) bridge.setEncoderBitrate(bitratePolicy.current)
        val capText = if (cap == Int.MAX_VALUE) "снят" else "${bitratePolicy.thermalCapBps / 1000} кбит/с"
        AppLog.i("нагрев: ступень ${level.name} ($why): fps $fps (ГУ/настройка $targetFps), потолок битрейта $capText, " +
            "битрейт ${old / 1000} -> ${bitratePolicy.current / 1000} кбит/с${if (live) "" else " (транскодера нет, применится при старте)"}")
        if (air) WLog.ev(WLog.VIDEO, "thermal", "level" to level.name, "status" to thermalStatus, "fps" to fps,
            "capKbps" to if (cap == Int.MAX_VALUE) 0 else bitratePolicy.thermalCapBps / 1000)
    }

    private fun headroomText(): String = if (thermalHeadroom.isNaN()) "н/д" else "%.2f".format(thermalHeadroom)

    private fun thermalName(status: Int): String = THERMAL_NAMES.getOrElse(status) { "$status" }

    private fun logSummary(reason: String) {
        val sb = StringBuilder("== РЕЗЮМЕ СЕССИИ (${(SystemClock.elapsedRealtime() - t0) / 1000} с, причина: $reason)\n")
        sb.append("  принято байт: ${transport.rxBytes}\n")
        sb.append("  версия протокола ГУ: $huVersion\n")
        sb.append("  HU_AUTHEN_REQUEST: ${if (sawAuthRequest) "ПРИШЁЛ randomValue=$authRandom" else "не приходил"}\n")
        sb.append("  HU_AUTHEN_RESULT: ${huAuthResult ?: "не приходил"}\n")
        sb.append("  запрошенное разрешение: $requested; ответ: $chosen\n")
        sb.append("  тач: $touchMode, событий=$touchCount\n")
        sb.append("  видео: кадров=$vFrames ключевых=$vKeys heartbeat=$vBeats сброшено=$vDropped\n")
        sb.append("  нагрев: статус ${thermalName(thermalStatus)}, макс. ступень ${thermalThrottle.maxLevel.name}, разгрузка ${thermalThrottle.throttledMs / 1000} с\n")
        sb.append("  HU_INFO: ${huInfo ?: "не приходил"}\n")
        sb.append("  принятые типы: ")
        for ((t, c) in rxByType) sb.append("${Msg.hex(t)} ${Msg.name(t)} x$c; ")
        AppLog.i(sb.toString().trimEnd(), ui = false)
        AppLog.i("резюме записано в лог (сессия ${reason})")
    }

    private fun deviceInfo(): DeviceInfo {
        // For SDK_INT >= 33, report "29"/29 to match Baidu 8.6.7.
        val (sdk, sdkInt) = DeviceInfo.reportedSdk(Build.VERSION.SDK_INT)
        return DeviceInfo(
            os = "Android",
            board = Build.BOARD,
            bootloader = Build.BOOTLOADER,
            brand = Build.BRAND,
            cpuAbi = Build.SUPPORTED_ABIS.getOrElse(0) { "" },
            cpuAbi2 = Build.SUPPORTED_ABIS.getOrElse(1) { "" },
            device = Build.DEVICE,
            display = Build.DISPLAY,
            fingerprint = Build.FINGERPRINT,
            hardware = Build.HARDWARE,
            host = Build.HOST,
            cid = Build.ID,
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            product = Build.PRODUCT,
            serial = "unknown",
            codename = Build.VERSION.CODENAME,
            incremental = Build.VERSION.INCREMENTAL,
            release = Build.VERSION.RELEASE,
            sdk = sdk,
            sdkInt = sdkInt,
            btaddress = "unknown", // BT-address is not available to us; Baidu send "unknown" if not received
            carlifeVersion = Handshake.MD_VERSION,
            carlifeType = Handshake.MD_TYPE_CARLIFE_APP,
        )
    }

    companion object {
        const val FPS = Handshake.DEFAULT_FPS

        /** action&255 touch, which the bridge translates into AA: DOWN, UP, MOVE, POINTER_DOWN, POINTER_UP. */
        private val TOUCH_TO_AA = setOf(0, 1, 2, 5, 6)
        private const val TTS_TX_LOGGED = 8           // TTS_DATA after INIT with time recording in transport (h222)
        private const val MAX_AUDIO_QUEUE = 180       // no sound is received if there are more packets in the general queue
        private const val BITRATE_POLL_MS = 100L
        private const val VW_20082138_CEILING_BPS = 2_000_000
        private const val VW_20082138_MIN_BPS = 1_500_000
        private const val VW_20082138_IDR_LIMIT = 450_000
        private const val VW_20082138_CUT_INTERVAL_MS = 1000L
        private const val NEVER_MS = Long.MIN_VALUE

        /** getThermalHeadroom gives NaN more than once a second, the forecast for 10 s - enough times in 10 s. */
        private const val HEADROOM_POLL_MS = 10_000L

        /** PowerManager.THERMAL_STATUS_* in order. */
        private val THERMAL_NAMES = listOf("NONE", "LIGHT", "MODERATE", "SEVERE", "CRITICAL", "EMERGENCY", "SHUTDOWN")
        private const val SILENCE_MS = 10_000L
        private const val PAUSE_KEEPALIVE_MS = 2_000L  // Desay G6S: empty VIDEO_DATA
        private const val PAUSE_HEARTBEAT_MS = 100L    // VW 20082120: empty VIDEO_HEARTBEAT
        private const val IDLE_HEARTBEAT_MS = 1_000L   // Delphi 20082114: AA without frame longer than a second

        /** Wirelessly, send about once per second: Wi-Fi head units close the video socket sooner (plan 5.2, item 4). */
        private const val PAUSE_KEEPALIVE_AIR_MS = 900L

        /** Keepalive on PAUSE: empty VIDEO_DATA, in VW 20082120 VIDEO_HEARTBEAT. */
        @Volatile @JvmStatic var PAUSE_KEEPALIVE_ENABLED = true

        private const val FEATURE_CONTENT_ENCRYPTION = "CONTENT_ENCRYPTION"

        /** moduleID CARLIFE_DATA_SUBSCRIBE subscription to navigation (CarlifeDataSubscribe: 0 navigation, 2 music). */
        private const val SUB_NAVI = 0

        /** moduleID voice assistant in MODULE_STATUS (ModuleStatusManage: 4 = VR). */
        private const val MODULE_VR = 4

        /** moduleID phone in MODULE_STATUS (ModuleStatusManage: 1 = PHONE; 0 idle, 1 incoming, 2 outgoing/talk). */
        private const val MODULE_PHONE = 1
        private const val VR_ECHO_MS = 1500L

        /** moduleID microphone head unit in MODULE_STATUS (ModuleStatusManage.setCarlifeModuleStatus(6, VOICE_MIC)). */
        private const val MODULE_MIC = 6

        /** Mobis CCNC: empty VIDEO_DATA after >= 200 ms without video (Baidu Recorder/VideoOutputThread), survey more often step. */
        private const val MOBIS_KEEPALIVE_MS = 200L
        private const val MOBIS_KEEPALIVE_POLL_MS = 50L

        /** The field of time video package: unix-seconds (Recorder.sendVideoData :930-962). */
        private fun unixSeconds() = (System.currentTimeMillis() / 1000).toInt()
    }
}
