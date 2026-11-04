package com.yegorkapomidorka.headswap.aa

import com.yegorkapomidorka.headswap.AnnexB
import com.yegorkapomidorka.headswap.Hex
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** Phone player in desktop mode (implements dex.DesktopMediaControl). */
interface DesktopMedia {
    fun isPlaying(): Boolean
    fun pause()
    fun resume()
}

/** What the bridge requires from the CarLife-side (implements the CarLifeSession). */
interface CarlifeLink {
    /** One video message CarLife (0x20001, Annex-B). false = not accepted (the queue is crowded, the session is closed). */
    fun sendVideo(payload: ByteArray): Boolean

    /**
     * The first IDR of AA instead of stub: sending and disabling frame stub atomically. Otherwise P-frame stub,
     * Leaked between sendVideo and hidePlaceholder, lies on AA-IDR and head unit draws strips to the next IDR.
     * stub stop anyway via hidePlaceholder.
     */
    fun sendVideoTakeover(payload: ByteArray): Boolean = sendVideo(payload)

    /** Communication by wireless connection (6 TCP-socket), not USB/tcp-debug. */
    val isAir: Boolean get() = false

    /** Current frame stub (null = none): with it transcoding begins transition to picture AA. */
    fun placeholderSnapshot(): android.graphics.Bitmap? = null

    /** Show on head unit stub with text (encodes VideoSource); you can call up to CarLife START - will remember. */
    fun showPlaceholder(text: String)

    /** Stop stub: further video comes from AA (sendVideo). */
    fun hidePlaceholder()

    /** Audio message CarLife: channel 3 MEDIA / 4 TTS, type (INIT/DATA/STOP), body. false = discarded. */
    fun sendAudio(channel: Int, type: Int, payload: ByteArray): Boolean

    /** MODULE_STATUS about one CarLife module (CarlifeAudio.MODULE_NAVI / MODULE_MUSIC / ...) as Baidu ModuleStatusManage. false = did not go away. */
    fun sendModuleStatus(module: Int, status: Int): Boolean

    /** Create and run a transcoder (AA H.264 -> decoder -> encoder). null = MediaCodec not up, work passthrough. */
    fun createTranscoder(out: TranscodeOutput): VideoTranscoder? = null

    /** Native desktop surface -> GL pacing -> the same CarLife encoder, without an input decoder. */
    fun createSurfaceEncoder(out: TranscodeOutput): VideoTranscoder? = null

    /**
     * Ask head unit to start (true) / finish (false) microphone recording as Baidu MD (VrManager): CMD
     * MIC_RECORD_RECOG_START / MIC_RECORD_END (+ MODULE_STATUS VR). Data will come to AaBridgeCore.onCarlifeMicData.
     * false = message not gone (session closed).
     */
    fun requestMicRecord(start: Boolean): Boolean = false

    /** key frame encoder (size in bytes) went to CarLife; called only on head unit with size limit IDR (VW ID, h196; Mobis CCNC, h242). */
    fun onEncodedKeyFrame(size: Int) {}

    /** Video queue status (plumbing, backlog, length) for bridge statistics; null = no data. */
    fun videoStats(): LinkVideoStats? = null

    /** CMD MEDIA_INFO (0x10035); the cover (PNG of AA) transcodes the implementation without delaying the caller. false = is not gone. */
    fun sendMediaInfo(info: CarlifeMediaInfo): Boolean = false

    /** CMD MEDIA_PROGRESS_BAR (0x10036). */
    fun sendMediaProgress(progressBar: Int): Boolean = false

    /** CMD NAVI_NEXT_TURN_INFO (0x10030); field 6 (icon / time) for configuration implementation. */
    fun sendNaviTurn(info: NaviTurnInfo): Boolean = false

    /** CMD GO_TO_DESKTOP (0x10021): head unit goes to your desktop (the "Get Out" tile in AA). */
    fun goToDesktop() {}

    /** Throw out of the transport queue not yet recorded MEDIA_DATA (INIT/PAUSE/STOP, video, TTS and command remain); how much is thrown out. */
    fun dropQueuedMedia(): Int = 0
}

/** Link to dead CarLife-session (USB aborted): throws everything away. */
private object NoLink : CarlifeLink {
    override fun sendVideo(payload: ByteArray) = false
    override fun showPlaceholder(text: String) {}
    override fun hidePlaceholder() {}
    override fun sendAudio(channel: Int, type: Int, payload: ByteArray) = false
    override fun sendModuleStatus(module: Int, status: Int) = false
    override fun goToDesktop() {}
}

/** Phone call: IDLE - no, RINGING - incoming calls, ACTIVE - conversation (AudioManager MODE_IN_CALL). */
enum class PhoneCall { IDLE, RINGING, ACTIVE }

/**
 * AA -> CarLife bridge logic without Android and socket: video reception from AA as AaVideoSink, first rules
 * messages for CarLife (SPS+PPS+IDR in one), switching stub/AA, mapping touch, request key frame.
 */
class AaBridgeCore(
    private val log: (String, Boolean) -> Unit,
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val clockUs: () -> Long = { System.nanoTime() / 1_000 },
    micSchedule: ((Long, Runnable) -> MicTimerHandle)? = null,
    audioSchedule: ((Long, Runnable) -> MicTimerHandle)? = null,
    private val connectingText: String = TEXT_CONNECTING,
    private val waitingText: String = TEXT_WAIT_FRAME,
    private val disconnectedText: String = TEXT_AA_LOST,
) : AaVideoSink, AaAudioSink, AaMicSink, AaMetaSink, TranscodeOutput, RefPictureSink {
    enum class Source { PLACEHOLDER, AA }

    @Volatile var peer: AaPeer? = null
        set(v) {
            synchronized(touchLock) {
                field = v
                fingers.clear()
            }
            v?.sendNightMode(huTheme == THEME_DARK)
        }
    @Volatile private var link: CarlifeLink = NoLink

    /** The latest CHANGE_MD_THEME from head unit (0 light, 1 dark, 2 cars); before it - light. */
    @Volatile var huTheme: Int = THEME_LIGHT
        private set

    @Volatile private var carlifeStarted = false
    private var aaConfig: ByteArray? = null
    private var aaVideoSeen = false
    private var gotIdr = false
    private var active = Source.PLACEHOLDER
    private var placeholderText = connectingText
    private var waitSince = 0L
    private var lastKeyframeRequest = NEVER
    private var lastIdrSentMs = NEVER              // the CarLife the last IDR passthrough

    var dropped = 0L
        private set
    var sent = 0L
        private set
    var idrSent = 0L
        private set
    var keyframeRequests = 0
        private set
    private var dropLogged = 0L
    private var touchDown = false

    // --- Video: transcoding (default in app) or passthrough ---
    /** Video bridge mode: true = AA -> decoder -> encoder (frame repeat on static), false = H.264 as is. */
    @Volatile var transcodeEnabled = false

    /**
     * Video resolution, shot once when creating a host (already taking into account passthrough): it built AA (SDR),
     * It is taken by CarLife-session (INIT_DONE, encoder) and mapping touch. Prefs from here are not read.
     */
    @Volatile var videoMode: VideoGeometry = VideoMode.DEFAULT

    /** Manual DPI interface AA, shot with videoMode (AaDensity.AUTO = density mode). Only SDR, on crop and touch does not affect. */
    @Volatile var densityOverride: Int = AaDensity.AUTO

    /** Desktop sources handle CarLife HOME themselves; AA leaves that key to the head unit. */
    @Volatile var mapHomeKey: Boolean = false

    /** Desktop input accepts explicit CANCEL and releases contacts when CarLife disconnects. */
    @Volatile var desktopInputEnabled: Boolean = false

    /** Desktop source bitrate exceeds CarLife's limit, so encoder failure must never bypass it. */
    @Volatile var desktopVideoError: String? = null
        private set

    /** Desktop: music is played by the phone player, not AA - MUSIC=0/1 by head unit pause and return it. */
    @Volatile var desktopMedia: DesktopMedia? = null

    @Volatile var desktopSurfaceEnabled: Boolean = false
        private set
    private var desktopSurfaceListener: DesktopSurfaceListener? = null
    private var desktopSurfaceExecutor: ExecutorService? = null
    private var desktopSurfaceSequence = 0L
    private var desktopSurfaceGeneration = 0L
    private var desktopSurfaceReady = false
    private var desktopSurfaceFirstFrame = false
    private var desktopSurfaceCsdVerified = false
    private var desktopSurfaceCreating = false
    private var desktopSurfaceStopped = false
    private var desktopSurfaceStopComplete = false
    private val desktopSurfaceStopCallbacks = ArrayList<() -> Unit>()
    private data class RetiredSurfaceEncoder(val encoder: VideoTranscoder, val listener: DesktopSurfaceListener?)
    private val retiredSurfaceEncoders = LinkedHashMap<Long, RetiredSurfaceEncoder>()
    private val closedSurfaceProducers = HashSet<Long>()

    /** Register before CarLife START. The source receives a new lease after every USB reconnect. */
    @Synchronized
    fun enableDesktopSurface(listener: DesktopSurfaceListener) {
        check(!desktopSurfaceStopped) { "Desktop surface source was already stopped" }
        check(desktopSurfaceListener == null || desktopSurfaceListener === listener) { "Desktop surface source is already registered" }
        check(transcoder == null || desktopSurfaceEnabled) { "Compressed source encoder is already active" }
        desktopSurfaceListener = listener
        desktopSurfaceEnabled = true
        desktopInputEnabled = true
        transcodeEnabled = true
        if (carlifeStarted) startTranscoder()
    }

    /** Call only after the source has created and verified its own native desktop display. */
    @Synchronized
    fun desktopSurfaceReady(generation: Long): Boolean {
        if (!desktopSurfaceEnabled || desktopSurfaceStopped || generation != desktopSurfaceGeneration ||
            generation == 0L || transcoder == null || !carlifeStarted || desktopVideoError != null) return false
        desktopSurfaceReady = true
        aaVideoSeen = true
        gotIdr = true // No compressed source IDR exists in this mode.
        outputEnabled = !carlifePaused
        outGotIdr = false
        waitSince = 0L
        transcoder?.requestSyncFrame()
        return true
    }

    @Synchronized
    fun desktopSurfaceFailed(generation: Long, message: String) {
        if (generation != 0L && generation == desktopSurfaceGeneration && !desktopSurfaceStopped) stopDesktopVideo(message)
    }

    /** Callback runs after source closure and encoder disposal, outside the bridge monitor. */
    @Synchronized
    fun stopDesktopSurface(onDone: () -> Unit) {
        if (desktopSurfaceStopComplete) {
            Thread(onDone, "dex-surface-stopped").start()
            return
        }
        desktopSurfaceStopCallbacks.add(onDone)
        if (desktopSurfaceStopped) return
        desktopSurfaceStopped = true
        stopTranscoderAsync()
        desktopSurfaceWorker().execute { finishDesktopSurfaceStop() }
    }

    /** Keep the current CarLife handshake, but never reuse a producer/encoder generation.
     * The owner may call this only after its source stop callback has completed. */
    @Synchronized
    fun rearmDesktopSurfaceAfterStop(): Boolean {
        if (!desktopSurfaceEnabled || !desktopSurfaceStopped || !desktopSurfaceStopComplete ||
            desktopSurfaceCreating || transcoder != null || desktopSurfaceGeneration != 0L ||
            retiredSurfaceEncoders.isNotEmpty() || desktopSurfaceStopCallbacks.isNotEmpty() || link === NoLink) return false
        desktopSurfaceEnabled = false
        desktopSurfaceStopped = false
        desktopSurfaceStopComplete = false
        desktopSurfaceListener = null
        desktopSurfaceExecutor = null
        desktopVideoError = null
        closedSurfaceProducers.clear()
        // desktopSurfaceSequence deliberately survives: old codec/death callbacks stay stale.
        return true
    }

    /** Proof from the producer's Binder death recipient. Safe before, during, or after a failed close. */
    @Synchronized
    fun desktopSurfaceProducerClosed(generation: Long) {
        if (generation <= 0L || generation > desktopSurfaceSequence || desktopSurfaceStopComplete) return
        closedSurfaceProducers.add(generation)
        if (generation in retiredSurfaceEncoders) {
            desktopSurfaceWorker().execute { disposeRetiredSurfaceEncoder(generation) }
        }
    }

    /** Lifecycle-worker only. A failed source close never authorizes releasing its consumer surface. */
    private fun disposeRetiredSurfaceEncoder(generation: Long) {
        val retired = synchronized(this) {
            if (generation in closedSurfaceProducers) retiredSurfaceEncoders[generation] else null
        } ?: return
        try { retired.encoder.stop() } finally {
            synchronized(this) { retiredSurfaceEncoders.remove(generation); closedSurfaceProducers.remove(generation) }
            finishDesktopSurfaceStop()
        }
    }

    private fun finishDesktopSurfaceStop() {
        val (callbacks, worker) = synchronized(this) {
            if (!desktopSurfaceStopped || desktopSurfaceStopComplete || retiredSurfaceEncoders.isNotEmpty() || desktopSurfaceCreating) return
            desktopSurfaceStopComplete = true
            desktopSurfaceStopCallbacks.toList().also { desktopSurfaceStopCallbacks.clear() } to desktopSurfaceExecutor
        }
        // Capture/retire the old worker before callbacks can rearm the same live CarLife link.
        worker?.shutdown()
        callbacks.forEach { runCatching { it() } }
    }

    private fun desktopSurfaceWorker(): ExecutorService {
        return desktopSurfaceExecutor ?: Executors.newSingleThreadExecutor { task ->
            Thread(task, "dex-surface-lifecycle").also { it.isDaemon = true }
        }.also { desktopSurfaceExecutor = it }
    }

    /** SDR went with fields entirely right and bottom (h529, ui_config): SPS-crop cuts only right and bottom. It is removed together with videoMode. */
    @Volatile var marginsAtBottom = false

    /**
     * Yours IDR in passthrough ([IdrSplice]Once in so many ms head unit Get key frame from decoded on the phone
     * pictures AA, without transcoding and without cycle VideoFocus. 0 - off. Puts BridgeService at the start of CarLife-session.
     */
    @Volatile var spliceMs = 0L

    /**
     * How many frame AA before the picture of his IDR does not go to head unit (in motion): IDR stands instead of drop + 1 frame AA,
     * head unit with constant display frequency is shortened to drop frame. P-frame after IDR refers only to its picture.
     * For tests only: 0 - IDR stands instead of one frame AA, without emission.
     */
    internal var spliceDrop = 1

    /** decoder reference images for its IDR (MediaCodec puts BridgeService); null - no inserts. */
    @Volatile var refDecoderFactory: ((RefPictureSink) -> RefPictureDecoder?)? = null
    private var transcoder: VideoTranscoder? = null
    private var encCsd: ByteArray? = null
    private var outGotIdr = false
    private val idrThrottle = IdrThrottle()            // requests IDR Overloading the queue CarLife
    private var overflowIdrRequests = 0L
    private var lastStatsIdrRequests = 0L
    private var outputEnabled = false
    private var lastAaFrameAt = 0L
    private var lastStatsAt = 0L
    private var lastStatsSent = 0L
    private var lastStatsAaFrames = 0L
    private var aaFrames = 0L
    private var encDropped = 0L
    val isTranscoding: Boolean @Synchronized get() = transcoder != null

    // --- sound AA -> CarLife ---
    /** UI Switch "Sound by USB". */
    @Volatile var usbAudioEnabled = true

    /** Whether to wait for the FEATURE_CONFIG head unit before connecting to the AA (to decide whether to announce audio synches). */
    @Volatile var expectFeatureConfig = false
    @Volatile var featureConfigSeen = false
    @Volatile private var btAudio = false
    @Volatile var huDesayG6S = false           // head unit Desay G6S (Mazda CX-5): NAVI=1 jams the regular radio, the route NAVI does not send
        private set
    @Volatile var huHondaVezel = false         // Honda Vezel (channel 20832125): sound button on head unit relay MUSIC=0/1, on MUSIC=1 return play
        private set
    @Volatile var huHonda20772104 = false      // Honda Civic 2021 (channel 20772104, h629): after the rear AA pauses from the outside, head unit immediately asks MUSIC=1
        private set
    @Volatile var huHonda = false              // Honda (STATISTIC_INFO channel 2077xxxx/2083xxxx, HU_INFO empty): roadName on instrument cluster cut by bytes and cells
        private set
    private var naviRaisedAt = NEVER          // Honda: when was the last time MODULE_STATUS NAVI=1 left
    private var naviShownSinceRaise = false   // Honda: after the last NAVI=1 was already SHOW (0->1 transition instrument cluster "wasted")
    @Volatile var huVwBt1420 = false           // VW BT 1420 (Pavel, channel) 20082138) by wireless connection without heartbeat on PAUSE vomit CarLife through ~ 1 min
    @Volatile var huVwCns2Delphi = false       // VW CNS2 Delphi 20082114 (h425): AA speech in voice session is silent in TTS, shot in VR
    @Volatile var huIdleVideoHeartbeat = false // 20082114 (h425), Skoda CNS3 21290002 (h625): without video 5-6 with rip USB and out PAUSE
    private var vrReply = false                // Assistant response thread open in VR channel (VR_INIT gone)
    @Volatile var huVw20082120 = false        // VW (STATISTIC_INFO channel 20082120 h93, 20082122 h222): without heartbeat rips USB through 3,7-5,6 with PAUSE
        private set
    @Volatile var huVw20082138 = false         // VW ID (channel 20082138 " Exception "VW BT 1420"; ID.6 Crozz: 20082128 + "VW BT 9491"; preset ID. UNYX): IDR > ~600 KiB USB EIO
        private set
    @Volatile var huKia20032131 = false        // Kia Seltos sm6150 (channel 20032131, h672): button tube on the steering wheel = LAUNCH_MODE_PHONE
        private set
    private var lastLaunchPhoneMs = NEVER       // h672: head unit sends LAUNCH_MODE_PHONE a couple for ~ 200 ms for one click
    private var huGear = -1                     // h672: latest transmission from HU_GEAR_INFO, -1 = did not arrive
    @Volatile var huHyundaiCn7 = false         // head unit Hyundai CN7 (product wp_cn7cpe_cn, h218in the "Auto" music mode 32 kHz with resampling 48→32
        private set
    @Volatile var huPioneerRm56 = false        // head unit Pioneer RM56E8B (Dongfeng DF6, h277): 4-5 Mbit/s USB EIO after 25-30 c, lives on ~ 1 Mbit/s fps ≤ 15
        private set
    @Volatile var huMazda20622111 = false      // Mazda Connect 2 the Chinese Empirechannel 20622111, h238/h359): VIDEO_DATA > ~16 KiB Hangs Reading USB
        private set
    @Volatile var huMobisNx4c = false          // Hyundai Tucson NX4 (Mobis CCNC, channel 20022188, h242): all like Baidu (record AOA, keepalive, MIC, encoder 20 fps)
        private set
    @Volatile var musicRateProvider: () -> MusicRate = { MusicRate.DEFAULT }
    private var resampler: MusicResampler? = null    // null at 48000: PCM goes as is
    private var mediaRateLogged = MEDIA_RATE
    @Volatile var mediaChunkProvider: () -> MediaChunk = { MediaChunk.DEFAULT }
    private var mediaChunkLogged = CarlifeAudio.MAX_CHUNK
    @Volatile var voiceChunkProvider: () -> VoiceChunk = { VoiceChunk.DEFAULT }
    private var voiceChunkLogged = CarlifeAudio.MAX_CHUNK
    @Volatile var ttsPrerollProvider: () -> TtsPreroll = { TtsPreroll.DEFAULT }
    private val audioInited = HashMap<Int, Boolean>()
    private val audioLogged = HashMap<Int, Long>()

    /** Music equalizer (CarLife MEDIA only); settings are read on each buffer, change from UI on the fly. */
    private val equalizer = Equalizer(48000, 2)
    var eqProvider: () -> EqSettings = { EqLive.settings }
    private var eqLogged: EqSettings? = null
    var audioBytes = 0L
        private set
    var audioDropped = 0L
        private set

    // Seriality audio, MD Baidu: NAVI before TTS, MEDIA released PAUSE, TTS_STOP delayed.
    private val audioTimer: ScheduledExecutorService by lazy {
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "aa-audio-timer").also { it.isDaemon = true } }
    }
    private val audioSched: (Long, Runnable) -> MicTimerHandle = audioSchedule ?: { delayMs, task ->
        val f = audioTimer.schedule(task, delayMs, TimeUnit.MILLISECONDS)
        MicTimerHandle { f.cancel(false) }
    }

    /** One-time canceled timer; the task goes under the lock of the kernel, canceled (even already queued) is not performed. */
    private inner class AudioTimer {
        private var gen = 0
        private var handle: MicTimerHandle? = null
        val pending: Boolean get() = handle != null

        fun start(delayMs: Long, task: () -> Unit) {
            cancel()
            val g = gen
            handle = audioSched(delayMs, Runnable {
                synchronized(this@AaBridgeCore) {
                    if (g == gen && handle != null) {
                        handle = null
                        task()
                    }
                }
            })
        }

        fun cancel(): Boolean {
            val h = handle ?: return false
            handle = null
            gen++
            h.cancel()
            return true
        }
    }

    private val ttsStopTimer = AudioTimer()

    /** h425: Delphi swallows TTS_INIT in one millisecond with NAVI=1; speech piles up, INIT goes through [TTS_INIT_AFTER_NAVI_MS]. */
    private val ttsInitTimer = AudioTimer()
    private val ttsPending = ArrayList<ByteArray>()
    private var ttsPendingChan = 0
    private var ttsPendingStop = false          // AA STOP came while the speech was waiting for INIT: to repeat after issue

    /** h425: VW CNS2 Delphi Sucks MOVE ~190 ms and send a pack; distribute the pack evenly. Only when [huVwCns2Delphi]. */
    private val touchPacer = TouchBurstPacer(clockMs, audioSched) { a, x, y, px, py -> dispatchTouch(a, x, y, px, py) }
    @Volatile private var touchPacerLogged = false

    /** Leakage MOVE ([TouchDecimation]) stands before pacer and direct sending. */
    @Volatile var touchDecimationProvider: () -> TouchDecimation = { TouchDecimation.DEFAULT }
    private val touchDecimator = TouchDecimator { a, x, y, px, py -> routeTouch(a, x, y, px, py) }
    @Volatile private var touchDecimationLogged = 1
    private val mediaReleaseTimer = AudioTimer()
    private val voiceExitTimer = AudioTimer()
    private var carlifePaused = false          // VIDEO_ENCODER_PAUSE after last START
    private var exitPending = false            // "Exit" in AA: GO_TO_DESKTOP left, waiting for PAUSE from head unit to give AA NATIVE
    private var huPausedAa = false             // AA player paused when head unit went to its source; MEDIA do not send to play
    private var callPausedAa = false           // huPausedAa put the call (muteMediaOnCall), and not the departure of head unit on the radio: at the end of the call we remove ourselves
    private var resumeOnHuMusic = false        // We paused (127 with AA playing): MUSIC=1 from head unit can be returned to play
    private var lastHuResumeMs = NEVER         // when MUSIC=1 from head unit sent AA KEYCODE 126
    @Volatile private var lastUserInputMs = NEVER   // Latest touch DOWN / Key / Washer / Voice with head unit
    @Volatile private var lastVoiceKeyMs = NEVER    // latest KEYCODE_SEARCH in AA (h425 anti-drebezhg)
    private var lastHfpConnectMs = NEVER       // head unit sent BT_HFP_CONNECTION=connected (hereinafter AA itself launches autoplay according to BT)
    private var lastPauseKeyMs = NEVER         // when was last sent AA KEYCODE 127 (desktop: pause phone player)
    private var lastDesktopPlayCheckMs = NEVER // desktop: when asked "does the player play" when closed MEDIA
    private var lastExternalStopMs = NEVER     // AA player is not from our 127 and not from user input (Honda 20772104)
    private var naviSent = false               // MODULE_STATUS NAVI=1 gone (holds TTS and/or route)
    private var naviEndPending = false           // route ended while NAVI=1 held TTS: NAVI=0 will leave after TTS STOP
    private var ttsNaviHeld = false            // NAVI=1 is needed for TTS (up to stopAllAudio)
    private var routeNaviHeld = false          // NAVI=1 is needed for the route (MetaRelay)
    private var mediaPaused = false            // MEDIA on head unit is on PAUSE (STOP has not been sent yet)
    private var mediaGuard = false             // TTS_STOP is already gone, wait +300 ms to MEDIA control packages
    private var pendingMediaPause = false      // desired state MEDIA = pause, applicable after TTS
    private var voice = false
    private var mediaGateDrops = 0L
    private var ttsPackets = 0

    /** MEDIA is initialized on head unit and plays (for response to MODULE_CONTROL MUSIC). */
    val isMediaInited: Boolean @Synchronized get() = audioInited[CarlifeAudio.CH_MEDIA] == true

    /** The answer to MODULE_CONTROL MUSIC: sound on USB - on MEDIA INIT, otherwise according to the state of the player AA (ch9). */
    val musicActive: Boolean @Synchronized get() = isMediaInited || (!audioViaUsb && meta.playing)

    // --- metadata AA (ch9/ch10) -> CarLife MEDIA_INFO / PROGRESS / NAVI_NEXT_TURN_INFO ---
    private val meta = MetaRelay(
        log = log,
        clockMs = clockMs,
        link = { link },
        ready = { carlifeStarted && link !== NoLink },
        navHold = { on -> routeNavi(on) },
        musicState = { on -> musicByState(on) },
        navRearm = { rearmNavi() },
        emptyRoadText = { huHonda },
    )

    val metaRelay: MetaRelay get() = meta

    /** The sound is on USB: included in UI and head unit does not ask Bluetooth. Reads AapOptions.sdProvider (which blues to announce). */
    val audioViaUsb: Boolean get() = usbAudioEnabled && !btAudio

    val activeSource: Source @Synchronized get() = active
    val currentPlaceholderText: String @Synchronized get() = placeholderText
    val isCarlifeStarted: Boolean @Synchronized get() = carlifeStarted

    /** There is a live (not closed) CarLife-session: USB is connected. Readable without blocking. */
    val hasCarlifeLink: Boolean get() = link !== NoLink

    /**
     * Change the text of stub to head unit ("Starting the server...", "Unlock the phone"); when the video of AA is already there, do not touch.
     * It is also remembered for the future CarLife-session.
     */
    @Synchronized
    fun showStatus(text: String) {
        if (active == Source.AA || text == placeholderText) return
        placeholderText = text
        link.showPlaceholder(text)
        log("BRIDGE заглушка: '$text'", true)
    }

    // --- microphone AA: request opened the phone, sound take from head unit (CarLife VR) or from the phone microphone ---
    /** The microphone mode is read at the moment of each open: switch in UI operates immediately. */
    @Volatile var micModeProvider: () -> MicMode = { MicMode.AUTO }
    /** Phone Call Status (AudioManager.mode): On [huVwCns2Delphi] Rewind the steering wheel for the time of call = take/put. */
    @Volatile var phoneCallProvider: () -> PhoneCall = { PhoneCall.IDLE }

    /** Call status from TelephonyManager (CarLifeSession.onCallState); null = telephony not reported (no READ_PHONE_STATE) - then [phoneCallProvider]. */
    @Volatile private var telephonyCall: PhoneCall? = null

    /** Calling: telephony is priority AudioManager.mode (RINGING comes before mode becomes RINGTONE). */
    private fun currentCall(): PhoneCall = telephonyCall ?: phoneCallProvider()

    /** Phone microphone (AudioRecord); null = no. One instance for all time: router calls start/stop in turn. */
    @Volatile var phoneMic: PhoneMic? = null

    private val micTimer: ScheduledExecutorService by lazy {
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "aa-mic-timer").also { it.isDaemon = true } }
    }

    private val mic = AaMicRouter(
        log = log,
        mode = { micModeProvider() },
        huLinkUp = { carlifeStarted && link !== NoLink },
        huRecord = { start -> link.requestMicRecord(start) },
        phone = { phoneMic },
        toAa = { pcm, ts -> peer?.sendMicPcm(pcm, ts) },
        schedule = micSchedule ?: { delayMs, task ->
            val f = micTimer.schedule(task, delayMs, TimeUnit.MILLISECONDS)
            MicTimerHandle { f.cancel(false) }
        },
        nowMs = clockMs,
        nowUs = clockUs,
    )

    override fun onMicOpen(): Boolean = mic.open()

    override fun onMicClose(why: String) = mic.close(why)

    /** PCM microphone head unit (VR 0x00058001, message body) -> AA. */
    fun onCarlifeMicData(pcm: ByteArray) = mic.onHuData(pcm)

    /**
     * Call at start CarLife-session: stub. If AA is already alive and playing (USB is back in grace- window),
     * text "waiting for frame" instead of "start the server", and key frame will go to CarLife START.
     */
    @Synchronized
    fun attach(link: CarlifeLink) {
        if (desktopSurfaceEnabled && this.link !== link) stopTranscoderAsync()
        this.link = link
        active = Source.PLACEHOLDER
        carlifeStarted = false
        carlifePaused = false
        exitPending = false
        huPausedAa = false
        callPausedAa = false
        telephonyCall = null                   // CarLifeSession.calls will send the current state again
        resumeOnHuMusic = false
        lastHuResumeMs = NEVER
        lastExternalStopMs = NEVER
        gotIdr = false
        huDesayG6S = false                    // HU_INFO / STATISTIC_INFO will come again in this session
        huHondaVezel = false                   // STATISTIC_INFO will come again in this session
        huHonda20772104 = false                // STATISTIC_INFO will come again in this session
        huVw20082120 = false                   // HU_INFO / STATISTIC_INFO will come again in this session
        huHonda = false                        // STATISTIC_INFO will come again in this session
        huVwBt1420 = false                     // HU_INFO will come again in this session
        huVw20082138 = false                   // HU_INFO / STATISTIC_INFO will come again in this session
        huKia20032131 = false                  // STATISTIC_INFO will come again in this session
        lastLaunchPhoneMs = NEVER
        huGear = -1
        vwIdName = null
        vwIdChannel = null
        huHyundaiCn7 = false                   // HU_INFO will come again in this session
        huPioneerRm56 = false                  // HU_INFO will come again in this session
        huMazda20622111 = false                // STATISTIC_INFO will come again in this session
        huMobisNx4c = false                    // HU_INFO / STATISTIC_INFO will come again in this session
        huVwCns2Delphi = false                 // STATISTIC_INFO will come again in this session
        huIdleVideoHeartbeat = false           // STATISTIC_INFO will come again in this session
        vrReply = false
        touchDecimator.reset()
        touchDecimationLogged = 1
        touchPacer.reset()
        touchPacerLogged = false
        sessionKickDone = false
        spliceCancel("новая CarLife-сессия")
        latStats.resetSession()
        mediaRateLogged = MEDIA_RATE
        mediaChunkLogged = CarlifeAudio.MAX_CHUNK
        voiceChunkLogged = CarlifeAudio.MAX_CHUNK
        resampler?.reset()
        if (aaVideoSeen) placeholderText = waitingText
        meta.onAttach()
        link.showPlaceholder(placeholderText)
        log("BRIDGE CarLife-сессия подключена: источник = заглушка '$placeholderText' (AA ${if (aaVideoSeen) "жива" else "нет видео"})", true)
    }

    // --- CarLife events ---
    @Synchronized
    fun onCarlifeStart() {
        // Desay G6S (Mazda) sends START without PAUSE at every pressing of NAVI/MEDIA: sound is already coming, repeated INIT
        // in the middle of the track rips it on head unit (2026-10-11 log). The sound is reset only on the first START and after PAUSE.
        val warm = carlifeStarted && !carlifePaused
        // wireless connection, return after PAUSE: decoder whole pause ate frame AA (gate only threw out encoder), its
        // status is live. Do not wait for IDR from AA (VideoFocus cycle, 0.2-2 from silence), and immediately take IDR from encoder.
        // VW BT 1420 (Paul 2026-10-16 log) closed socket via 0.16-0.42 with after such START. Only for him:
        // the rest of head unit (Mazda G6S by wireless connection, reverse) - as was, via IDR from AA.
        val resume = AIR_WARM_RESUME_ENABLED && huVwBt1420 && link.isAir && carlifeStarted && carlifePaused &&
            transcodeEnabled && transcoder != null && gotIdr && active == Source.AA
        // START after PAUSE is the same live session: the delayed TTS STOP sends immediately, otherwise resetAudio will eat it,
        // head unit keeps the hint channel open and jams the music until the next clue (h482, ix35 HSAE).
        if (carlifeStarted && carlifePaused && ttsStopTimer.pending) {
            log("BRIDGE CarLife START после PAUSE: отложенный TTS STOP шлю сразу", true)
            stopAudio(CarlifeAudio.CH_TTS)
        }
        carlifeStarted = true
        carlifePaused = false
        if (warm) log("BRIDGE CarLife START без PAUSE: звук не трогаю, INIT не повторяю", true)
        else resetAudio()                      // new START: MEDIA/TTS init again on the first package
        if (desktopSurfaceEnabled) {
            startTranscoder()
            if (!warm) meta.onCarlifeStart()
            return
        }
        if (resume) {
            lastKeyframeRequest = NEVER
            log("BRIDGE CarLife START после PAUSE (воздух): декодер жив, сразу IDR энкодера, AA не жду", true)
            startTranscoder()
            outputEnabled = true
            if (!warm) meta.onCarlifeStart()
            if (huPausedAa) log("BRIDGE CarLife START: AA на паузе после ухода ГУ на свой источник, музыку не запускаю", true)
            return
        }
        gotIdr = false
        spsCropKickAt = NEVER
        waitSince = if (aaVideoSeen) clockMs() - KEYFRAME_WAIT_MS else 0L
        lastKeyframeRequest = NEVER
        log("BRIDGE CarLife START: источник=$active, AA видео ${if (aaVideoSeen) "идёт, запрашиваю ключевой кадр" else "ещё нет"}", true)
        if (transcodeEnabled) startTranscoder()
        else log("BRIDGE видео: режим passthrough (H.264 AA как есть)", true)
        if (aaVideoSeen) {
            // AA is already playing (warm session): do not wait for the tick, immediately cycle NATIVE -> PROJECTED (decoder needed IDR from AA)
            lastKeyframeRequest = clockMs()
            keyframeRequests++
            peer?.requestKeyFrame()
        }
        if (!warm) meta.onCarlifeStart()
        // play on the START is not a shot: the sound of CarLife immediately selects from head unit radio, and listen to the radio for navigation - script (h33).
        // Return music - only on the explicit MUSIC=1 from head unit (onCarlifeMusicControl).
        if (huPausedAa) log("BRIDGE CarLife START: AA на паузе после ухода ГУ на свой источник, музыку не запускаю", true)
    }

    /**
     * MODULE_CONTROL MUSIC from head unit; answer - MODULE_STATUS MUSIC. status=0 - head unit went to its source (radio): Desay G6S
     * (Mazda) sends it after PAUSE, others head unit - without PAUSE. Player AA on pause, its buffer tail is not sent to
     * Next play, answer MUSIC=0 - otherwise head unit sees CarLife- music active and argues with the radio.
     * The tail is also held when the AA is on pause (Honda: MUSIC=0 after a pause by the assistant, the tail of the buffer gave MEDIA INIT
     * through 40 ms), and MUSIC=0 respond even if MEDIA PAUSE is delayed to the end of TTS (log of Yegor 2026-10-13).
     *
     * status=1 - Desay G6S is back on CarLife-music (pack 10-20 MUSIC=1 next to START). After our pause it is a choice
     * user on head unit: AA KEYCODE 126 and MUSIC=1 for the whole pack (log h214 2026-10-17). Easy return to the card
     * (NAVI) comes without MUSIC=1 - there is no radio touch. Fresh session also sends MUSIC=1, but our pause is not there.
     */
    @Synchronized
    fun onCarlifeMusicControl(status: Int?): Boolean {
        if (hondaBtMusic) {
            // Civic 2021 (h629): sound by BT, "its source" head unit - BT-audio of the same phone. A pause of AA would jam it,
            // and MUSIC=1 in return leads head unit to CarLife-source: it puts BT on pause (2026-10-31 20:58-21:07 log).
            log("BRIDGE Honda 20772104: MODULE_CONTROL MUSIC=$status, звук по BT - AA не трогаю, CarLife-музыки нет", true)
            return false
        }
        desktopMedia?.let { return onDesktopMusicControl(it, status) }
        if (HU_MUSIC_FOLLOW_ENABLED && status == 0 && carlifeStarted) {
            val p = peer
            if (meta.playing && !huPausedAa && p != null) {
                log("BRIDGE ГУ ушло на свой источник (MUSIC=0${if (carlifePaused) " после PAUSE" else ""}): AA KEYCODE $KEYCODE_MEDIA_PAUSE, MEDIA на паузу", true)
                sendPauseKey(p)
                resumeOnHuMusic = true
            } else if (!huPausedAa) {
                log("BRIDGE ГУ ушло на свой источник (MUSIC=0), AA не играет: хвост MEDIA не пересылаю до play", true)
                resumeOnHuMusic = false
            }
            huPausedAa = true
            requestMediaPause("ГУ MODULE_CONTROL MUSIC=0")
            return false
        }
        if (HU_MUSIC_RESUME_ENABLED && status == 1 && (huDesayG6S || huHondaVezel)) {
            val now = clockMs()
            val p = peer
            if (huPausedAa && resumeOnHuMusic && p != null) {
                huPausedAa = false
                callPausedAa = false
                resumeOnHuMusic = false
                lastHuResumeMs = now
                lastUserInputMs = now              // source selection on head unit - user input, BT-gard does not interrupt it
                log("BRIDGE ГУ вернулось на CarLife-музыку (MUSIC=1) после нашей паузы: AA KEYCODE $KEYCODE_MEDIA_PLAY", true)
                p.sendKey(KEYCODE_MEDIA_PLAY)
                return true
            }
            if (now - lastHuResumeMs < HU_RESUME_ANSWER_MS) return true   // pack MUSIC=1 while AA swings
        }
        // Honda Civic 2021 (h629, sound on BT): after the rear player AA pauses from the outside, through 50-100 ms head unit relay MUSIC=1.
        // The answer MUSIC=0 head unit repeats, then goes to its source and does not return (2026-10-31 log).
        if (HU_MUSIC_RESUME_ENABLED && status == 1 && huHonda20772104) {
            val now = clockMs()
            val p = peer
            if (!meta.playing && p != null && now - lastExternalStopMs < HONDA_RESUME_AFTER_STOP_MS &&
                now - lastHuResumeMs >= HONDA_RESUME_MIN_INTERVAL_MS) {
                log("BRIDGE Honda 20772104: ГУ просит музыку (MUSIC=1) через ${now - lastExternalStopMs} мс после паузы AA извне: AA KEYCODE $KEYCODE_MEDIA_PLAY", true)
                lastExternalStopMs = NEVER
                huPausedAa = false
                callPausedAa = false
                resumeOnHuMusic = false
                lastHuResumeMs = now
                p.sendKey(KEYCODE_MEDIA_PLAY)
                return true
            }
            if (now - lastHuResumeMs < HU_RESUME_ANSWER_MS) return true   // repeat MUSIC=1 while AA swings
        }
        return musicActive
    }

    /**
     * MODULE_CONTROL MUSIC in desktop mode: AA no, music is played by the phone player. MUSIC=0 - head unit went to your
     * source changes or powers down (Vezel sends it about one second before USB disconnect when switching off the car, log 2026-11-03): pause the player.
     * MUSIC=1 - head unit is back on CarLife-music: MEDIA again send, and Desay G6S/Vezel after our pause - play.
     */
    private fun onDesktopMusicControl(media: DesktopMedia, status: Int?): Boolean {
        val now = clockMs()
        if (HU_MUSIC_FOLLOW_ENABLED && status == 0 && carlifeStarted) {
            if (!huPausedAa) {
                if (media.isPlaying()) {
                    log("BRIDGE ГУ ушло на свой источник (MUSIC=0): плеер телефона на паузу", true)
                    lastPauseKeyMs = now
                    media.pause()
                    resumeOnHuMusic = true
                } else {
                    log("BRIDGE ГУ ушло на свой источник (MUSIC=0), плеер телефона не играет: хвост MEDIA не пересылаю до play", true)
                    resumeOnHuMusic = false
                }
            }
            huPausedAa = true
            requestMediaPause("ГУ MODULE_CONTROL MUSIC=0")
            return false
        }
        if (status == 1 && huPausedAa) {
            huPausedAa = false
            callPausedAa = false
            if (HU_MUSIC_RESUME_ENABLED && resumeOnHuMusic && (huDesayG6S || huHondaVezel)) {
                resumeOnHuMusic = false
                lastHuResumeMs = now
                lastUserInputMs = now
                log("BRIDGE ГУ вернулось на CarLife-музыку (MUSIC=1) после нашей паузы: play плееру телефона", true)
                media.resume()
                return true
            }
            resumeOnHuMusic = false
            log("BRIDGE ГУ вернулось на CarLife-музыку (MUSIC=1): MEDIA снова пересылаю", true)
            return musicActive
        }
        if (status == 1 && now - lastHuResumeMs < HU_RESUME_ANSWER_MS) return true   // pack MUSIC=1 while the player swings
        return musicActive
    }

    /** The Desktop: player of the phone plays again after the MUSIC=0 (play on the phone or desktop) - the sound is sent again. */
    private fun releaseDesktopPauseIfPlaying() {
        val media = desktopMedia ?: return
        val now = clockMs()
        if (now - lastPauseKeyMs < DESKTOP_RESUME_GUARD_MS || now - lastDesktopPlayCheckMs < DESKTOP_PLAY_CHECK_MS) return
        lastDesktopPlayCheckMs = now
        if (!media.isPlaying()) return
        huPausedAa = false
        callPausedAa = false
        resumeOnHuMusic = false
        log("BRIDGE плеер телефона снова играет после MUSIC=0: MEDIA снова пересылаю", true)
    }

    private fun sendPauseKey(p: AaPeer) {
        lastPauseKeyMs = clockMs()
        p.sendKey(KEYCODE_MEDIA_PAUSE)
    }

    /** BT_HFP_CONNECTION from head unit: state=2 - the phone is connected to head unit by Bluetooth (0 - disconnected). */
    @Synchronized
    fun onHuBtHfpConnection(state: Int?) {
        if (state == 2) {
            lastHfpConnectMs = clockMs()
            log("BRIDGE ГУ: BT HFP подключён (автоплей AA в ближайшие $BT_AUTOPLAY_WINDOW_MS мс не снимет паузу)", true)
        } else {
            log("BRIDGE ГУ: BT HFP состояние=$state", true)
        }
    }

    /** AA again played itself (play user): sound again send. After BT HFP without input with head unit this autoplay - pause hold. */
    private fun releaseHuPause(why: String) {
        if (!huPausedAa) return
        val now = clockMs()
        val p = peer
        if (BT_AUTOPLAY_GUARD_ENABLED && p != null && now - lastHfpConnectMs < BT_AUTOPLAY_WINDOW_MS &&
            now - lastUserInputMs > USER_INPUT_WINDOW_MS) {
            if (now - lastPauseKeyMs >= REPAUSE_MIN_MS) {
                log("BRIDGE $why через ${now - lastHfpConnectMs} мс после BT HFP без ввода пользователя: автоплей, снова KEYCODE $KEYCODE_MEDIA_PAUSE", true)
                sendPauseKey(p)
            }
            return
        }
        huPausedAa = false
        callPausedAa = false
        resumeOnHuMusic = false
        log("BRIDGE $why: MEDIA снова пересылаю", true)
    }

    private fun ago(t: Long): String = if (t == NEVER) "не было" else "${clockMs() - t} мс назад"

    /** CARLIFE_DATA_SUBSCRIBE_START (on=true) / STOP by head unit; moduleID subscriptions: 0 navigation, 2 music. */
    @Synchronized
    fun onCarlifeSubscribe(moduleIds: List<Int>, on: Boolean) {
        for (id in moduleIds) meta.onSubscribe(id, on)
    }

    /** CHANGE_MD_THEME from head unit (h294): dark -> night mode AA, light -> day; auto (2) and other - day. */
    fun onCarlifeTheme(mode: Int?) {
        val m = mode ?: return log("BRIDGE CHANGE_MD_THEME без themeModes, тему не меняю", true)
        val prev = huTheme
        huTheme = m
        val night = m == THEME_DARK
        log("BRIDGE тема ГУ $prev -> $m (${themeName(m)}): AA night=$night${if (peer == null) ", AA не подключён, отдам при подключении" else ""}", true)
        peer?.sendNightMode(night)
    }

    /** VIDEO_ENCODER_PAUSE from head unit: next START - full, with reset sound. */
    @Synchronized
    fun onCarlifePause() {
        carlifePaused = true
        if (desktopSurfaceEnabled) outputEnabled = false
        if (exitPending) {
            // head unit left with CarLife after our GO_TO_DESKTOP: tell AA that there is no projection (NATIVE), return - via START
            exitPending = false
            log("BRIDGE ГУ вышел из CarLife (PAUSE) после «Выйти»: AA VideoFocus NATIVE", true)
            peer?.sendVideoFocusNative()
        }
    }

    /** Tile "Get Out" in AA: VIDEO_FOCUS_REQUEST NATIVE. We ask head unit on your desktop; NATIVE for AA - when head unit will send PAUSE. */
    @Synchronized
    override fun onAaNativeFocusRequest() {
        if (link === NoLink || !carlifeStarted) {
            log("BRIDGE AA: «Выйти» (VIDEO_FOCUS NATIVE) проигнорирован: CarLife ${if (link === NoLink) "не подключён" else "ещё не START"}", true)
            return
        }
        log("BRIDGE AA: «Выйти» (VIDEO_FOCUS NATIVE) -> CarLife GO_TO_DESKTOP", true)
        exitPending = true
        link.goToDesktop()
        // AA is waiting for a response to NATIVE with black area launcher. Desay G6S (Mazda) does not send out PAUSE or START
        // on the return (2026-10-12 log), so the answer is direct: the NATIVE -> PROJECTED cycle returns AA to the main screen.
        lastKeyframeRequest = clockMs()
        keyframeRequests++
        peer?.requestKeyFrame()
    }

    /** FEATURE_CONFIG head unit disassembled; mode = value of AUDIO_TRANSMISSION_MODE (null - no key). */
    fun onHuFeatureConfig(audioTransmissionMode: Int?) {
        featureConfigSeen = true
        if (audioTransmissionMode == CarlifeAudio.AUDIO_MODE_BT) {
            btAudio = true
            log("BRIDGE !!!!!! ГУ: AUDIO_TRANSMISSION_MODE=1 (звук по Bluetooth). Звук по USB НЕ шлю, AA-звук остаётся на телефоне (синки MEDIA/SPEECH не объявляю)", true)
        } else {
            log("BRIDGE AUDIO_TRANSMISSION_MODE=${audioTransmissionMode ?: "нет в списке"}: звук по USB ${if (usbAudioEnabled) "включён" else "выключен в UI"}", true)
        }
    }

    /**
     * HU_INFO head unit disassembled: Desay G6S identified by manufacturer and model (other head unit Desay SV are not prohibited),
     * Hyundai CN7 by product.
     */
    fun onHuInfo(manufacturer: String?, model: String?, product: String? = null, board: String? = null) {
        if (board == MOBIS_BOARD || model == MOBIS_BOARD) enableMobisNx4c()
        if (manufacturer == DESAY_MANUFACTURER && model == DESAY_G6S_MODEL) huDesayG6S = true
        vwIdName = manufacturer
        if (manufacturer == VW_BT_1420_MANUFACTURER) huVwBt1420 = true
        updateVwId()
        if (product == HYUNDAI_CN7_PRODUCT) {
            huHyundaiCn7 = true
            // passthrough flickers and goes blank; throughput is about 250 KiB/s (h638). BridgeService restores the configured value next session.
            if (!transcodeEnabled) {
                transcodeEnabled = true
                log("BRIDGE ГУ Hyundai CN7: passthrough отключаю, перекодирование принудительно", true)
            }
        }
        if (manufacturer == PIONEER_MANUFACTURER && model == PIONEER_RM56_MODEL) {
            if (!huPioneerRm56) log("BRIDGE ГУ Pioneer RM56E8B: потолок ${PIONEER_RM56_CEILING_BPS / 1000} кбит/с, fps ≤ $PIONEER_RM56_MAX_FPS", true)
            huPioneerRm56 = true
            // passthrough ceiling and fps do not work (h277 test1); BridgeService will return the field to setup at the next session
            if (!transcodeEnabled) {
                transcodeEnabled = true
                log("BRIDGE ГУ Pioneer RM56E8B: passthrough отключаю, перекодирование принудительно", true)
            }
        }
    }

    /**
     * Hyundai Tucson NX4 (Mobis CCNC, h242). The flag is activated as soon as possible (HU_INFO, spare sign - channel
     * STATISTIC_INFO), because some of the reactions (recording AOA, auth, BT) are needed up to STATISTIC_INFO. Direct passthrough under
     * flag do not use: encoder Baidu (20 fps, VBR, 3,84 Mbit/s) is only available in transcoding.
     */
    private fun enableMobisNx4c() {
        if (huMobisNx4c) return
        huMobisNx4c = true
        log("MOBIS CCNC: режим Baidu включён", true)
        if (!transcodeEnabled) {
            transcodeEnabled = true
            log("BRIDGE ГУ Mobis CCNC: passthrough отключаю, перекодирование принудительно", true)
        }
    }

    private var vwIdName: String? = null       // HU_INFO manufacturer = BT-Name head unit("VW BT NNNN", the digits are their own in each machine)
    private var vwIdChannel: String? = null    // STATISTIC_INFO channel = firmware head unit

    /** "My car" VW ID. UNYX: Ceiling by size IDR and without identification head unit. BridgeService He puts on every session. */
    @Volatile var carVwId = false

    /**
     * VW ID - firmware 20082138 with any BT-name, except VW BT 1420 (Pavel: 8 Mbit/s by wireless connection and USB without breaks,
     * log 2026-10-16); name waiting to distinguish Paul. Meet "VW BT 1538" (h196) and "VW BT 4370" (ID. UNYX, 26.10.2026).
     * Other firmware - strictly paired with [VW_ID_IDR_LIMIT_HUS]: 20082128 + "VW BT 9491" (ID.6 Crozz, 24.10.2026).
     * The [carVwId] preset includes the ceiling itself.
     */
    private fun updateVwId() {
        val was = huVw20082138
        val name = vwIdName
        val channel = vwIdChannel
        huVw20082138 = carVwId || (name != null && channel != null && name != VW_BT_1420_MANUFACTURER &&
            (channel == VW_20082138_CHANNEL || channel == VW_ID_IDR_LIMIT_HUS[name]))
        if (huVw20082138 && !was) {
            val why = if (carVwId) "пресет VW ID. UNYX" else "$channel, $name"
            log("BRIDGE ГУ VW ID ($why): потолок битрейта по размеру IDR", true)
        }
    }

    /** STATISTIC_INFO head unit disassembled: Desay G6S spare feature and VW 20082120 - firmware assembly channel. */
    fun onHuStatistic(channel: String?) {
        if (channel == MOBIS_NX4C_CHANNEL) enableMobisNx4c()
        if (channel == DESAY_G6S_CHANNEL) huDesayG6S = true
        if (channel == HONDA_20832125_CHANNEL) huHondaVezel = true
        if (channel == HONDA_20772104_CHANNEL) huHonda20772104 = true
        if (channel == KIA_20032131_CHANNEL && !huKia20032131) {
            huKia20032131 = true
            log("BRIDGE ГУ Kia 20032131: LAUNCH_MODE_PHONE (кнопка трубки на руле) -> AA KEYCODE_CALL", true)
        }
        if (channel == MAZDA_20622111_CHANNEL && !huMazda20622111) {
            huMazda20622111 = true
            log("BRIDGE ГУ Mazda 20622111: VIDEO_DATA режу на куски ≤ $MAZDA_20622111_MAX_VIDEO_PACKET Б, потолок ${MAZDA_20622111_CEILING_BPS / 1000} кбит/с", true)
        }
        if (channel == VW_20082120_CHANNEL || channel == VW_CNS3_20082122_CHANNEL || channel == VW_CNS3_20082134_CHANNEL ||
            channel == VW_CNS2_20082114_CHANNEL || channel == SKODA_CNS3_21290002_CHANNEL) huVw20082120 = true
        if (channel == VW_CNS2_20082114_CHANNEL || channel == SKODA_CNS3_21290002_CHANNEL) huIdleVideoHeartbeat = true
        if (channel == VW_CNS2_20082114_CHANNEL && !huVwCns2Delphi) {
            huVwCns2Delphi = true
            log("BRIDGE ГУ VW CNS2 Delphi 20082114: ответ ассистента в канал VR, антидребезг кнопки голоса", true)
        }
        if (isHondaChannel(channel) && !huHonda) {
            huHonda = true
            log("BRIDGE ГУ Honda (channel $channel): roadName под приборку, стрелки после SUBSCRIBE_START, перевзвод NAVI 0->1", true)
        }
        vwIdChannel = channel
        updateVwId()
    }

    /** MODULE_STATUS NAVI=1 is gone (route / TTS / rewind). Honda: head unit will answer SUBSCRIBE_START - arrows are waiting for him. */
    private fun naviRaised() {
        naviRaisedAt = clockMs()
        naviShownSinceRaise = false
        if (huHonda) meta.awaitSubscribe()
    }

    /**
     * Honda: before the first SHOW (new route, rebuild) and repeated after silence instrument cluster need transition MODULE_STATUS
     * NAVI 0->1 (h137, 2026-10-16 20:43:45: NAVI=1 held TTS, SHOW new route left without crossing - the shooter was not up to
     * We do not touch the fresh NAVI=1 (younger than NAVI_REARM_FRESH_MS, there was no shooter after it): the transition was already there.
     */
    @Synchronized
    private fun rearmNavi() {
        if (!huHonda || huDesayG6S || !ROUTE_NAVI_STATUS_ENABLED) return
        if (naviSent && !naviShownSinceRaise && clockMs() - naviRaisedAt < NAVI_REARM_FRESH_MS) {
            naviShownSinceRaise = true
            return
        }
        if (naviSent) link.sendModuleStatus(CarlifeAudio.MODULE_NAVI, 0)
        if (link.sendModuleStatus(CarlifeAudio.MODULE_NAVI, 1)) {
            naviSent = true
            naviRaised()
            log("BRIDGE CarLife MODULE_STATUS NAVI 0->1 (Honda: перевзвод приборки перед SHOW)", true)
        }
    }

    /** CarLife-session is closed (USB EIO / detach / Stop). AA do not touch; from - closed link, someone else's do not unplug. */
    fun onCarlifeClosed(from: CarlifeLink? = null) {
        if (closeCarlife(from)) mic.onCarlifeLost()      // out of core lock: microphone router takes its
    }

    @Synchronized
    private fun closeCarlife(from: CarlifeLink?): Boolean {
        if (from != null && from !== link) {
            log("BRIDGE закрытие старой CarLife-сессии, текущая уже другая: игнор", false)
            return false
        }
        carlifeStarted = false
        exitPending = false
        gotIdr = false
        telephonyCall = null                   // Telephony subscription closed with session
        callPausedAa = false
        spliceCancel("CarLife закрыт")
        resetAudio()
        stopTranscoderAsync()
        active = Source.PLACEHOLDER
        link = NoLink
        if (desktopInputEnabled) {
            touchDecimator.reset()
            touchPacer.reset()
        }
        synchronized(touchLock) {
            if (desktopInputEnabled && fingers.isNotEmpty()) peer?.let { sendFingers(it, 3, 0, "CANCEL") }
            fingers.clear()
        }
        log("BRIDGE CarLife закрыт (AA ${if (aaVideoSeen) "жива" else "без видео"}, держим)", true)
        return true
    }

    /** CarLife-touch: id finger for AA and last coordinates of frame head unit. */
    private class Finger(val id: Int, var x: Int, var y: Int) {
        fun set(nx: Int, ny: Int) {
            x = nx
            y = ny
        }
    }

    private val touchLock = Any()
    private var lastDownMs = NEVER          // under touchLock: time of last DOWN, for take Vezel
    private var dupDowns = 0

    /** MotionEvent head unit: x,y TOUCH_ACTION - 0, pointerx/pointery - 1 index. */
    private val fingers = ArrayList<Finger>(2)

    /**
     * TOUCH_ACTION head unit -> AA. action U - = s MotionEvent: action&255 = 0 DOWN, 1 UP, 2 MOVE, 5 POINTER_DOWN, 6 POINTER_UP,
     * action>>8 = finger index. The second finger is in (px, py); 0,0 = coordinates are missing (then last known).
     * Up to two fingers; id remaining finger after POINTER_UP does not change. Coordinates of frame head unit -> frame AA via
     * [VideoMode.mapTouch] (without margin-ov 1:1). One finger goes to AA as well as to multi-touch (id 0, action_index 0).
     */
    fun onCarlifeTouch(action: Int, x: Int, y: Int, px: Int = 0, py: Int = 0) {
        if (desktopInputEnabled && action and 0xFF == 3) {
            // Discard delayed MOVE events instead of replaying them into an already cancelled gesture.
            touchDecimator.reset()
            touchPacer.reset()
            dispatchTouch(action, x, y, px, py)
            return
        }
        val every = touchDecimationProvider().every
        if (every != touchDecimationLogged) {
            touchDecimationLogged = every
            log("BRIDGE тач: прореживание MOVE ${if (every > 1) "×$every" else "выкл"} (настройка)", true)
        }
        touchDecimator.onTouch(action, x, y, px, py, every)
    }

    /** Output: Delphi through pacer packs, on the other head unit directly in AA. */
    private fun routeTouch(action: Int, x: Int, y: Int, px: Int, py: Int) {
        if (huVwCns2Delphi) {
            touchPacer.onTouch(action, x, y, px, py)
            if (touchPacer.bursts == 1 && !touchPacerLogged) {
                touchPacerLogged = true
                log("BRIDGE тач: ГУ шлёт MOVE пачками, раздаю равномерно между пачками (h425)", true)
            }
        } else {
            dispatchTouch(action, x, y, px, py)
        }
    }

    private var spsCropKickAt = NEVER              // h529: when to request a repeat IDR after the first in CarLife-session
    private var spsCropKickBase = NEVER            // when that first IDR: of his IDR left after it replaces the repeat
    private var spsCropKickDeferred = false        // Repeat has already been postponed once in anticipation of its IDR
    private var sessionKickDone = false            // repeated IDR is already assigned in this CarLife-session; after PAUSE/START bridge and so send cycle
    private var spsCropSrc: ByteArray? = null      // last original SPS AA and its rewritten version (null = failed)
    private var spsCropDst: ByteArray? = null

    /**
     * Passthrough with fields (WIDE*, R1024 with included SPS-crop, h529) to crop each SPS Fields are added AA,
     * They are cut off by decoder head unit. Undecided SPS - leaves the original (head unit will show frame with fields), the reason is in the log once.
     * It is called only from a branch without a transcoder, so it also works when rolled off an uncreated transcoder.
     */
    private fun spsCrop(au: ByteArray): ByteArray {
        if (videoMode.crop.isNone) return au
        return SpsCrop.mapSps(au) { sps ->
            if (!sps.contentEquals(spsCropSrc)) {
                spsCropSrc = sps
                spsCropDst = cropSps(sps, "")
            }
            spsCropDst ?: sps
        }
    }

    /** SPS from AU to log: POC, reference frame, VUI (bitstream_restriction decides whether decoder head holds unit frame in DPB). */
    private fun logSps(who: String, au: ByteArray) {
        for (nal in IdrSplicer.nals(au)) {
            if (nal[0].toInt() and 0x1F != 7) continue
            val d = try {
                SpsVui.parse(nal).toString()
            } catch (e: RuntimeException) {
                "не разобрался: ${e.message}"
            }
            log("BRIDGE SPS $who: $d (${Hex.compact(nal, 48)})", true)
        }
    }

    private fun cropSps(sps: ByteArray, who: String): ByteArray? {
        val g = videoMode
        return try {
            val r = if (marginsAtBottom) SpsCrop.rewrite(sps, 0, g.marginW, 0, g.marginH)
            else SpsCrop.rewrite(sps, g.marginW / 2, g.marginW / 2, g.marginH / 2, g.marginH / 2)
            if (r.after.visibleW != g.outW || r.after.visibleH != g.outH) {
                throw SpsCrop.SpsException("видимая часть ${r.after.visibleW}x${r.after.visibleH}, ГУ ждёт ${g.outW}x${g.outH}")
            }
            log("BRIDGE SPS-кроп$who: ${r.before} -> ${r.after}, ${sps.size} -> ${r.sps.size} Б", true)
            r.sps
        } catch (e: SpsCrop.SpsException) {
            log("BRIDGE SPS-кроп$who не применён (${e.message}), SPS ${sps.joinToString("") { "%02x".format(it) }} уходит как есть", true)
            null
        }
    }

    /**
     * stub in passthrough C SPS-crop Encoded in frame AA (aaW×aaH) and cut the same crop (h529, Mazda 20622116):
     * head unit adjusts decoder to the first SPS after START and the frame size change in the middle of the stream does not tolerate,
     * after stub 1728x648 stream AA 1920x1088 went porridge from points to the next PAUSE/START.
     */
    val stubInAaGeometry: Boolean get() = !transcodeEnabled && !videoMode.crop.isNone

    /** The upper left corner of the visible part of frame AA at SPS-crop: there stub draws an inscription. */
    val spsCropOrigin: Pair<Int, Int> get() = if (marginsAtBottom) 0 to 0 else videoMode.marginW / 2 to videoMode.marginH / 2

    private var stubCropSrc: ByteArray? = null     // same as spsCropSrc/Dst, for SPS encoder stub (thread drain encoder)
    private var stubCropDst: ByteArray? = null

    /** SPS encoder stub with the same fields as AA ([stubInAaGeometry]). */
    fun stubSpsCrop(au: ByteArray): ByteArray = SpsCrop.mapSps(au) { sps ->
        if (!sps.contentEquals(stubCropSrc)) {
            stubCropSrc = sps
            stubCropDst = cropSps(sps, " заглушки")
        }
        stubCropDst ?: sps
    }

    private fun dispatchTouch(action: Int, x: Int, y: Int, px: Int, py: Int) = synchronized(touchLock) {
        val act = action and 0xFF
        val idx = action ushr 8
        if (act == AapMessages.TOUCH_DOWN || act == AapMessages.TOUCH_POINTER_DOWN) lastUserInputMs = clockMs()
        val name = if (desktopInputEnabled && act == 3) "CANCEL" else TOUCH_NAMES[act]
        if (name == null) {
            log("BRIDGE тач action=$action x=$x y=$y не передаю (нет аналога в AA)", false)
            return@synchronized
        }
        val p = peer
        if (p == null) {
            log("BRIDGE тач $name $x,$y проигнорирован: AA не подключён", false)
            fingers.clear()
            return@synchronized
        }
        val second = px != 0 || py != 0
        when (act) {
            3 -> {
                if (fingers.isNotEmpty()) sendFingers(p, act, 0, name)
                fingers.clear()
            }
            AapMessages.TOUCH_DOWN -> {
                // Vezel sends each DOWN twice through 1-25 ms; in Desktop the take becomes CANCEL+DOWN = double tap
                val now = clockMs()
                if (huHondaVezel && desktopInputEnabled && isDuplicateDown(fingers.singleOrNull()?.let { it.x to it.y },
                        now - lastDownMs, x, y)) {
                    dupDowns++
                    log("BRIDGE тач DOWN $x,$y: дубль через ${now - lastDownMs} мс, подавлен (всего $dupDowns)", false)
                    return@synchronized
                }
                lastDownMs = now
                fingers.clear()
                fingers.add(Finger(0, x, y))
                sendFingers(p, act, 0, name)
            }
            AapMessages.TOUCH_MOVE -> {
                if (fingers.isEmpty()) fingers.add(Finger(0, x, y))   // DOWN lost: as before, one finger id 0
                fingers[0].set(x, y)
                if (fingers.size > 1 && second) fingers[1].set(px, py)
                sendFingers(p, act, 0, name)
            }
            AapMessages.TOUCH_POINTER_DOWN -> {
                if (fingers.size >= 2 || idx > 1) {
                    log("BRIDGE тач $name action=$action: больше двух пальцев не передаю", false)
                    return@synchronized
                }
                if (fingers.isEmpty()) fingers.add(Finger(0, x, y))
                val pos = idx.coerceAtMost(fingers.size)
                val fresh = Finger(if (fingers[0].id == 0) 1 else 0, x, y)
                fingers.add(pos, fresh)
                fingers[0].set(x, y)
                if (second) fingers[1].set(px, py) else if (fingers[1] === fresh) fresh.set(x, y)
                sendFingers(p, act, pos, name)
            }
            AapMessages.TOUCH_POINTER_UP -> {
                if (fingers.size < 2 || idx > 1) {
                    log("BRIDGE тач $name action=$action: пальцев ${fingers.size}, не передаю", false)
                    return@synchronized
                }
                fingers[0].set(x, y)
                if (second) fingers[1].set(px, py)
                sendFingers(p, act, idx, name)
                fingers.removeAt(idx)
            }
            else -> {                                              // UP Last Finger
                if (fingers.size > 1) {                            // POINTER_UP lost: first take off the second finger
                    sendFingers(p, AapMessages.TOUCH_POINTER_UP, 1, "POINTER_UP")
                    fingers.removeAt(1)
                }
                if (fingers.isEmpty()) fingers.add(Finger(0, x, y))
                fingers[0].set(x, y)
                sendFingers(p, act, 0, name)
                fingers.clear()
            }
        }
    }

    /** All active fingers -> AA one event; log as up to multi-touch for one finger. */
    private fun sendFingers(p: AaPeer, act: Int, index: Int, name: String) {
        val mode = videoMode
        val points = fingers.map { f ->
            val (ax, ay) = mode.mapTouch(f.x, f.y)
            TouchPoint(f.id, ax, ay)
        }
        val ui = act != AapMessages.TOUCH_MOVE
        if (points.size == 1) {
            val f = fingers[0]
            val a = points[0]
            if (a.x == f.x && a.y == f.y) log("BRIDGE тач $name ${f.x},${f.y} -> AA", ui)
            else log("BRIDGE тач $name ГУ ${f.x},${f.y} -> AA ${a.x},${a.y} (${mode.label})", ui)
        } else {
            val gu = fingers.joinToString(" ") { "#${it.id} ${it.x},${it.y}" }
            val aa = points.joinToString(" ") { "#${it.id} ${it.x},${it.y}" }
            log("BRIDGE тач $name[$index] пальцев ${points.size}: ГУ $gu -> AA $aa (${mode.label})", ui)
        }
        p.sendTouch(act, index, points)
    }

    /**
     * CMD LAUNCH_MODE_PHONE from head unit. Kia 20032131 (h672) sends it with a tube button on the steering wheel instead of hard key PHONE_CALL,
     * twice for ~ 200 ms: second CALL in AA I would call the number, so repeat [LAUNCH_PHONE_DEBOUNCE_MS] Swallow.
     * On the other head unit meaning command not checked - only log.
     */
    fun onCarlifeLaunchPhone() {
        if (!huKia20032131) {
            log("BRIDGE LAUNCH_MODE_PHONE: не Kia 20032131, игнор", false)
            return
        }
        val now = clockMs()
        val since = now - lastLaunchPhoneMs
        lastLaunchPhoneMs = now
        if (since < LAUNCH_PHONE_DEBOUNCE_MS) {
            log("BRIDGE LAUNCH_MODE_PHONE повтор через $since мс, игнор", true)
            return
        }
        onCarlifeHardKey(HARD_KEY_PHONE_CALL)
    }

    /**
     * HU_GEAR_INFO from head unit. Kia 20032131 (h672) on the rear camera extinguishes CarLife and after R ~3 with holds the screensaver
     * BaiduUntil he comes. IDR (their IDR on passthrough once in ~ 2,3 (c) true = out of R, caller Requests key frame.
     */
    @Synchronized
    fun onHuGear(gear: Int?): Boolean {
        val prev = huGear
        huGear = gear ?: -1
        if (!huKia20032131 || prev != GEAR_REVERSE || gear == GEAR_REVERSE) return false
        log("BRIDGE передача R -> $gear: ГУ вернётся с камеры, прошу ключевой кадр", true)
        return true
    }

    /** TOUCH_CAR_HARD_KEY_CODE head unit (drive buttons) -> AA: pressing Android KeyEvent by [HARD_KEY_TO_ANDROID]. */
    fun onCarlifeHardKey(code: Int) {
        lastUserInputMs = clockMs()
        HARD_KEY_TO_ROTARY[code]?.let { delta ->
            val p = peer
            if (p == null) {
                log("BRIDGE шайба keycode=$code проигнорирована: AA не подключён", true)
                return
            }
            log("BRIDGE шайба keycode=$code -> AA rotary $delta", true)
            p.sendRotary(delta)
            return
        }
        val call = if (huVwCns2Delphi) currentCall() else PhoneCall.IDLE
        if (code == HARD_KEY_MEDIA_STOP) muteMediaOnCall("ГУ прислало keycode=$code")
        val android = if (mapHomeKey && code == 0x01) 3 else delphiKey(code, call) ?: HARD_KEY_TO_ANDROID[code]
        if (call == PhoneCall.ACTIVE && code == HARD_KEY_SEEK_SUB) {
            log("BRIDGE руль keycode=$code: идёт звонок, назад не шлю", true)
            return
        }
        if (android == null) {
            log("BRIDGE руль keycode=$code: нет аналога в AA, игнор", true)
            return
        }
        val p = peer
        if (p == null) {
            log("BRIDGE руль keycode=$code проигнорирован: AA не подключён", true)
            return
        }
        if (android == KEYCODE_SEARCH && voiceKeyBounce("руль keycode=$code")) return
        val note = if (call != PhoneCall.IDLE) " (звонок ${call.name})" else ""
        log("BRIDGE руль keycode=$code -> AA KEYCODE $android$note", true)
        p.sendKey(android)
    }

    /**
     * There is no tube button on the new VW wheels: while the call is ringing or going, the rewinder is responsible for the call.
     * As on the phone: green on the left, red on the right. Calls: back = accept (CALL), forward = reject (ENDCALL).
     * Talk: go ahead = put the phone down.
     * There is no pause button either: holding the rewind of the head unit sends as the keycode 0, outside the call is a pause/play.
     * Only VW CNS2 Delphi 20082114 ([huVwCns2Delphi]Dmitry; for others head unit It's the way it was.
     */
    private fun delphiKey(code: Int, call: PhoneCall): Int? = when {
        !huVwCns2Delphi -> null
        call == PhoneCall.IDLE && code == HARD_KEY_SEEK_HOLD -> KEYCODE_MEDIA_PLAY_PAUSE
        call == PhoneCall.RINGING && code == HARD_KEY_SEEK_SUB -> KEYCODE_CALL
        call != PhoneCall.IDLE && code == HARD_KEY_SEEK_ADD -> KEYCODE_ENDCALL
        else -> null
    }

    /**
     * VW CNS2 Delphi (h425) sends VR_START twice for 1-2 ms: second KEYCODE_SEARCH closes just opened
     * assistant. Repeat the voice button more often [VOICE_KEY_DEBOUNCE_MS] swallow.
     */
    private fun voiceKeyBounce(what: String): Boolean {
        val now = clockMs()
        val dup = now - lastVoiceKeyMs < VOICE_KEY_DEBOUNCE_MS
        if (dup) log("BRIDGE $what: повтор кнопки голоса через ${now - lastVoiceKeyMs} мс, глотаю", true)
        else lastVoiceKeyMs = now
        return dup
    }

    /** MODULE_CONTROL VR=1 from head unit (voice button) -> AA KEYCODE_SEARCH: AA opens the assistant and asks for the microphone himself. */
    fun onCarlifeVoiceKey() {
        lastUserInputMs = clockMs()
        val p = peer
        if (p == null) {
            log("BRIDGE кнопка голоса проигнорирована: AA не подключён", true)
            return
        }
        if (voiceKeyBounce("кнопка голоса (MODULE_CONTROL VR=1)")) return
        log("BRIDGE кнопка голоса (MODULE_CONTROL VR=1) -> AA KEYCODE $KEYCODE_SEARCH", true)
        p.sendKey(KEYCODE_SEARCH)
    }

    // --- AA events ---
    @Synchronized
    fun onAaConnecting() {
        resetTranscodeForAa()
        spliceReset()
        gotIdr = false
        aaConfig = null
        aaVideoSeen = false
    }

    @Synchronized
    fun onAaEnded(established: Boolean) {
        meta.onAaEnded()
        stopAllAudio("AA завершился")
        resetTranscodeForAa()
        spliceReset()
        val wasAa = active == Source.AA
        gotIdr = false
        aaConfig = null
        aaVideoSeen = false
        if (established || wasAa) placeholderText = disconnectedText
        if (wasAa || established) {
            active = Source.PLACEHOLDER
            log("BRIDGE AA завершился: источник -> заглушка '$placeholderText'", true)
            link.showPlaceholder(placeholderText)
        }
    }

    // --- AA sound ---
    private fun carlifeChannel(aaChan: Int): Int? = when (aaChan) {
        AapMessages.CH_AUDIO_MEDIA -> CarlifeAudio.CH_MEDIA
        AapMessages.CH_AUDIO_SPEECH -> CarlifeAudio.CH_TTS      // navigational tips / voice
        else -> null                                              // SYSTEM (5): AA signals on head unit do not send
    }

    private fun chName(ch: Int) = if (ch == CarlifeAudio.CH_MEDIA) "MEDIA" else "TTS"

    @Synchronized
    override fun onAudioStart(chan: Int) {
        log("BRIDGE AA audio START ch$chan (${AapMessages.channelName(chan)}) -> CarLife ${carlifeChannel(chan)?.let { chName(it) } ?: "не пересылается"}", true)
        if (carlifeChannel(chan) == CarlifeAudio.CH_MEDIA) releaseHuPause("AA MEDIA START")
        if (carlifeChannel(chan) == CarlifeAudio.CH_TTS) ttsPendingStop = false
        if (carlifeChannel(chan) == CarlifeAudio.CH_TTS && ttsStopTimer.cancel()) {
            log("BRIDGE TTS STOP отменён: SPEECH START до истечения ${TTS_STOP_DELAY_MS} мс, INIT не повторяю", true)
        }
    }

    @Synchronized
    override fun onAudioData(chan: Int, pcm: ByteArray, ptsUs: Long) {
        val ch = carlifeChannel(chan)
        if (ch == null || !audioViaUsb || !carlifeStarted) {
            audioDropped++
            if (audioDropped <= 2 || audioDropped % 500 == 0L) {
                val why = when {
                    ch == null -> "SYSTEM-канал"
                    !audioViaUsb -> "звук по USB выключен/BT"
                    else -> "CarLife ещё не START"
                }
                log("BRIDGE AA audio ch$chan ${pcm.size} Б не пересылаю ($why), всего отброшено $audioDropped", false)
            }
            return
        }
        if (ch == CarlifeAudio.CH_TTS && (vrReply || (huVwCns2Delphi && voice && micModeProvider() != MicMode.PHONE))) {
            sendVrReply(chan, pcm)
            return
        }
        if (ch == CarlifeAudio.CH_TTS) {
            if (ttsStopTimer.cancel()) log("BRIDGE TTS STOP отменён: данные ch$chan до истечения ${TTS_STOP_DELAY_MS} мс, INIT не повторяю", true)
        } else if (!admitMedia()) {
            return
        }
        if (audioInited[ch] != true) {
            val setting = if (ch == CarlifeAudio.CH_MEDIA) musicRateProvider() else MusicRate.AUTO
            val rate = when {
                ch != CarlifeAudio.CH_MEDIA -> 16000
                huPioneerRm56 -> MEDIA_RATE_PIONEER_RM56    // Whatever the setting: USB head unit does not pull video + music
                else -> setting.hz ?: if (huHyundaiCn7) MEDIA_RATE_CN7 else MEDIA_RATE
            }
            val channels = if (ch == CarlifeAudio.CH_MEDIA) 2 else 1
            if (ch == CarlifeAudio.CH_TTS && NAVI_STATUS_ENABLED) {
                ttsNaviHeld = true
                // as MD: before TTS send MODULE_STATUS NAVI=1 and hold it
                if (!naviSent && link.sendModuleStatus(CarlifeAudio.MODULE_NAVI, 1)) {
                    naviSent = true
                    naviRaised()
                    log("BRIDGE CarLife MODULE_STATUS NAVI=1 (перед TTS INIT)", true)
                }
            }
            if (ch == CarlifeAudio.CH_TTS && huVwCns2Delphi && naviSent) {
                val wait = TTS_INIT_AFTER_NAVI_MS - (clockMs() - naviRaisedAt)
                if (ttsInitTimer.pending || wait > 0) {
                    if (!ttsInitTimer.pending) {
                        log("BRIDGE TTS INIT через $wait мс после NAVI=1 (h425), речь коплю", true)
                        ttsInitTimer.start(wait) { flushTtsPending() }
                    }
                    ttsPendingChan = chan
                    ttsPending.add(pcm.copyOf())
                    return
                }
            }
            if (!link.sendAudio(ch, CarlifeAudio.initType(ch), CarlifeAudio.initBody(rate, channels, 16))) {
                audioDropped++
                return
            }
            audioInited[ch] = true
            log("BRIDGE CarLife ${chName(ch)} INIT ${rate}Гц ${channels}к 16бит (0x%08x)".format(CarlifeAudio.initType(ch)), true)
            if (ch == CarlifeAudio.CH_TTS) {
                val prerollMs = ttsPrerollProvider().ms ?: if (huVw20082120) TTS_PREROLL_MS else 0
                if (prerollMs > 0) sendTtsPreroll(prerollMs)
                ttsPackets = 0
                mediaGuard = false
                mediaReleaseTimer.cancel()
            } else {
                mediaPaused = false
                mediaGateDrops = 0
                // frequency is fixed on MEDIA INIT; each new INIT is a clean sheet flow
                resampler = if (rate == MEDIA_RATE) null else resampler?.takeIf { it.outRate == rate }?.also { it.reset() } ?: MusicResampler(rate)
                if (rate != MEDIA_RATE && rate != mediaRateLogged) {
                    val why = when {
                        huPioneerRm56 -> "авто: Pioneer RM56E8B"
                        setting.hz != null -> "настройка"
                        else -> "авто: Hyundai CN7"
                    }
                    log("BRIDGE музыка $rate Гц, ресэмпл $MEDIA_RATE→$rate ($why)", true)
                }
                mediaRateLogged = rate
                musicStatus(1)
            }
        }
        var out = pcm
        if (ch == CarlifeAudio.CH_MEDIA) {
            val eq = eqProvider()
            if (eq != eqLogged) {
                eqLogged = eq
                log("BRIDGE EQ: ${if (eq.isFlat) "без обработки" else "полосы ${eq.bandsCsv()} дБ, preamp ${eq.preamp} дБ"}", false)
            }
            equalizer.update(eq)
            equalizer.process(pcm)
            val rs = resampler
            if (rs != null) {
                out = rs.process(pcm)
                if (out.isEmpty()) return
            }
        }
        val chunkMax = when (ch) {
            CarlifeAudio.CH_MEDIA -> mediaChunkProvider().bytes ?: CarlifeAudio.MAX_CHUNK
            CarlifeAudio.CH_TTS -> voiceChunkMax()
            else -> CarlifeAudio.MAX_CHUNK
        }
        if (ch == CarlifeAudio.CH_MEDIA && chunkMax != mediaChunkLogged) {
            mediaChunkLogged = chunkMax
            log("BRIDGE музыка: пакеты по $chunkMax Б (настройка)", true)
        }
        var off = 0
        while (off < out.size) {
            val n = minOf(chunkMax, out.size - off)
            val chunk = if (off == 0 && n == out.size) out else out.copyOfRange(off, off + n)
            if (!link.sendAudio(ch, CarlifeAudio.dataType(ch), chunk)) {
                audioDropped++
            } else {
                audioBytes += n
                AaState.audioBytes = audioBytes
            }
            off += n
        }
        val c = (audioLogged[ch] ?: 0L) + 1
        audioLogged[ch] = c
        if (c <= 2 || c % 200 == 0L) log("BRIDGE AA audio ch$chan ${pcm.size} Б -> CarLife ${chName(ch)} (#$c, всего ${audioBytes / 1024} КиБ)", false)
        if (ch == CarlifeAudio.CH_TTS && ++ttsPackets <= TTS_DIAG_PACKETS) {
            val (peak, rms) = CarlifeAudio.pcm16Stats(pcm)
            log("BRIDGE TTS пакет #$ttsPackets: ${pcm.size} Б, peak=$peak rms=%.1f${if (peak == 0) " (ВСЕ НУЛИ)" else ""}".format(rms), false)
        }
    }

    @Synchronized
    override fun onAudioStop(chan: Int) {
        val ch = carlifeChannel(chan) ?: return
        log("BRIDGE AA audio STOP ch$chan", true)
        if (ch == CarlifeAudio.CH_TTS && ttsInitTimer.pending) {
            ttsPendingStop = true
            log("BRIDGE TTS STOP после выдачи накопленной речи (${ttsPending.sumOf { it.size }} Б)", true)
            return
        }
        if (vrReply && ch == CarlifeAudio.CH_TTS) {
            vrReply = false
            link.sendAudio(CarlifeAudio.CH_VR, CarlifeAudio.VR_STOP, ByteArray(0))
            log("BRIDGE CarLife VR STOP (0x%08x)".format(CarlifeAudio.VR_STOP), true)
            vrModuleOff("последний VR STOP после голосовой сессии")
            return
        }
        if (ch == CarlifeAudio.CH_MEDIA) {
            requestMediaPause("AA STOP ch$chan")
        } else if (TTS_DEBOUNCE_ENABLED) {
            if (audioInited[ch] == true && !ttsStopTimer.pending) {
                log("BRIDGE CarLife TTS STOP отложен на ${TTS_STOP_DELAY_MS} мс", true)
                ttsStopTimer.start(TTS_STOP_DELAY_MS) { ttsStopNow() }
            }
        } else {
            stopAudio(ch)
        }
    }

    /**
     * Speech AA during voice session -> VR channel (VR_INIT 16 kHz mono, VR_DATA) as answer assistant Baidu.
     * VW CNS2 Delphi (h425) assistant speech, which came to TTS immediately after recording the microphone, does not play.
     */
    private fun sendVrReply(chan: Int, pcm: ByteArray) {
        if (!vrReply) {
            if (audioInited[CarlifeAudio.CH_TTS] == true) stopAudio(CarlifeAudio.CH_TTS)
            if (!link.sendAudio(CarlifeAudio.CH_VR, CarlifeAudio.VR_INIT, CarlifeAudio.initBody(16000, 1, 16))) {
                audioDropped++
                return
            }
            vrReply = true
            ttsPackets = 0
            log("BRIDGE CarLife VR INIT 16000Гц 1к 16бит (0x%08x): речь AA ch$chan в голосовой сессии".format(CarlifeAudio.VR_INIT), true)
        }
        val chunkMax = voiceChunkMax()
        var off = 0
        while (off < pcm.size) {
            val n = minOf(chunkMax, pcm.size - off)
            val chunk = if (off == 0 && n == pcm.size) pcm else pcm.copyOfRange(off, off + n)
            if (!link.sendAudio(CarlifeAudio.CH_VR, CarlifeAudio.VR_DATA, chunk)) audioDropped++ else audioBytes += n
            off += n
        }
        AaState.audioBytes = audioBytes
        if (++ttsPackets <= TTS_DIAG_PACKETS) log("BRIDGE VR пакет #$ttsPackets: ${pcm.size} Б", false)
    }

    /**
     * h425: VW CNS2 Delphi after reply in VR channel leaves volume buttons on the voice channel until crossed
     * cable. MODULE_STATUS VR=0 leaves when closing the microphone, but the answer in VR goes after it: repeat VR=0,
     * when both the AA voice session ended and the VR stream closed.
     */
    private fun vrModuleOff(why: String) {
        if (!huVwCns2Delphi || voice || vrReply) return
        if (link.sendModuleStatus(CarlifeAudio.MODULE_VR, 0)) log("BRIDGE CarLife MODULE_STATUS VR=0 ($why)", true)
    }

    // --- Assistant voice session: MEDIA paused, no data sent; output with 2/2/1/2/2 pack smoothing ---
    @Synchronized
    override fun onVoiceSession(status: Int) {
        if (!VOICE_SESSION_ENABLED) return
        when (status) {
            1 -> {
                val cancelled = voiceExitTimer.cancel()
                if (!voice) {
                    voice = true
                    log("BRIDGE голосовая сессия: старт, MEDIA на паузу, данные MEDIA не пересылаю", true)
                    requestMediaPause("голосовая сессия")
                } else if (cancelled) {
                    log("BRIDGE голосовая сессия: выход отменён (снова status=1)", true)
                }
            }
            2 -> if (voice && !voiceExitTimer.pending) {
                voiceExitTimer.start(VOICE_EXIT_DELAY_MS) {
                    voice = false
                    log("BRIDGE голосовая сессия: выход, MEDIA поднимется через INIT на первых данных", true)
                    vrModuleOff("выход из голосовой сессии")
                }
            }
            else -> log("BRIDGE голосовая сессия: status=$status не знаю, игнор", false)
        }
    }

    // --- MEDIA control packets (PAUSE) go in turn: not yet TTS initialized or up to TTS_STOP+300 ms ---
    private fun mediaBlocked() = TTS_DEBOUNCE_ENABLED && (audioInited[CarlifeAudio.CH_TTS] == true || mediaGuard)

    /** Desired state MEDIA = pause: immediately or while busy TTS, after TTS_STOP+300 ms. */
    private fun requestMediaPause(why: String) {
        if (audioInited[CarlifeAudio.CH_MEDIA] != true) {
            pendingMediaPause = false
            return
        }
        if (mediaBlocked()) {
            pendingMediaPause = true
            log("BRIDGE MEDIA пауза отложена ($why): TTS занят, применю после TTS_STOP+${MEDIA_AFTER_TTS_MS} мс", true)
            return
        }
        pendingMediaPause = false
        pauseMedia(why)
    }

    private fun pauseMedia(why: String) {
        if (!MEDIA_PAUSE_ENABLED) {
            stopAudio(CarlifeAudio.CH_MEDIA)
            return
        }
        audioInited[CarlifeAudio.CH_MEDIA] = false
        mediaPaused = true
        link.sendAudio(CarlifeAudio.CH_MEDIA, CarlifeAudio.MEDIA_PAUSE, ByteArray(0))
        log("BRIDGE CarLife MEDIA PAUSE (0x%08x), $why".format(CarlifeAudio.MEDIA_PAUSE), true)
        musicStatus(0)
    }

    /** The size of the speech package that is explicitly specified or selected by head unit; null = as given by AA (Auto on the rest of head unit). */
    private fun voiceChunkOverride(): Int? = voiceChunkProvider().bytes ?: if (huVwCns2Delphi) VOICE_CHUNK_DELPHI else null

    /** The size of the speech packet (VR-answer and TTS) for the current sending; change the value writes to the log once. */
    private fun voiceChunkMax(): Int {
        val size = voiceChunkOverride() ?: CarlifeAudio.MAX_CHUNK
        if (size != voiceChunkLogged) {
            voiceChunkLogged = size
            log("BRIDGE голос: пакеты по $size Б (${if (voiceChunkProvider().bytes != null) "настройка" else "авто ГУ"})", true)
        }
        return size
    }

    /**
     * Silence immediately after TTS_INIT (VW CNS3 20082122, h222: hissing at the beginning of the prompt). Baidu hoofs phrase
     * and sends her a pack of 4096 B faster than real time (NaviTTSController.splitAndSendTTSData), head unit
     * have stock; AA gives real-time speech from the first package, no stock. Zeros give head unit open
     * path in silence and keep buffer on [ms] ahead to the end of phrase. Pieces of zeros are cut by package size
     * voice if it is set (setting or auto-1024 on Delphi), otherwise TTS_PREROLL_CHUNK.
     */
    private fun sendTtsPreroll(ms: Int) {
        val zeros = ByteArray((ms * TTS_BYTES_PER_SEC / 1000) and 1.inv())
        val chunkMax = voiceChunkOverride() ?: TTS_PREROLL_CHUNK
        var off = 0
        while (off < zeros.size) {
            val n = minOf(chunkMax, zeros.size - off)
            if (!link.sendAudio(CarlifeAudio.CH_TTS, CarlifeAudio.TTS_DATA, zeros.copyOfRange(off, off + n))) {
                log("BRIDGE TTS пре-ролл: очередь полна, отправлено ${off * 1000 / TTS_BYTES_PER_SEC} из $ms мс", true)
                return
            }
            off += n
        }
        log("BRIDGE TTS пре-ролл $ms мс тишины (${zeros.size} Б) после INIT", true)
    }

    /** [ttsInitTimer] timer expired: accumulated speech is in the usual way (INIT, pre-roll, data), then delayed STOP. */
    private fun flushTtsPending() {
        val chunks = ttsPending.toList()
        ttsPending.clear()
        val stop = ttsPendingStop
        ttsPendingStop = false
        log("BRIDGE TTS: выдаю накопленную речь ${chunks.sumOf { it.size }} Б (${chunks.size} пакетов)", true)
        for (c in chunks) onAudioData(ttsPendingChan, c, 0)
        if (stop) onAudioStop(ttsPendingChan)
    }

    private fun dropTtsPending() {
        ttsInitTimer.cancel()
        ttsPending.clear()
        ttsPendingStop = false
    }

    /** Actual TTS_STOP (debounce timer); after it MEDIA-packages are waiting for more MEDIA_AFTER_TTS_MS. */
    private fun ttsStopNow() {
        stopAudio(CarlifeAudio.CH_TTS)
        mediaGuard = true
        mediaReleaseTimer.start(MEDIA_AFTER_TTS_MS) {
            mediaGuard = false
            if (pendingMediaPause) {
                pendingMediaPause = false
                if (audioInited[CarlifeAudio.CH_MEDIA] == true) pauseMedia("отложенная пауза после TTS")
            }
        }
    }

    /**
     * VW CNS2 Delphi (h425) in the incoming head unit He goes to his phone and stops taking MEDIA. Tail of Music AA (~3 from to
     * MEDIA_STOP) scored a general USB-stream: video and sound stood ~ 20 c, sometimes EIO. If the music is on in advance,
     * no problem (Dmitry, log 24.10 22:13). Therefore, when calling immediately MEDIA PAUSE and the tail does not send to play AA.
     */
    @Synchronized
    private fun muteMediaOnCall(why: String) {
        if (!huVwCns2Delphi || huPausedAa || !carlifeStarted) return
        val call = currentCall()
        if (call == PhoneCall.IDLE) return
        muteMedia(call, why)
    }

    /** [muteMediaOnCall] core: call already recognized; tail MEDIA_DATA out of queue USB throw away until it scored head unit. */
    private fun muteMedia(call: PhoneCall, why: String) {
        huPausedAa = true
        callPausedAa = true
        resumeOnHuMusic = false
        val dropped = link.dropQueuedMedia()
        log("BRIDGE звонок ${call.name} ($why): MEDIA на паузу, хвост музыки AA не пересылаю, из очереди USB выброшено MEDIA_DATA: $dropped (h425)", true)
        requestMediaPause("звонок на телефоне")
    }

    /**
     * Call status from TelephonyManager (CALL_STATE_*: 0 IDLE, 1 RINGING, 2 OFFHOOK), calls CarLifeSession earlier than
     * head unit. call to Delphi RINGING/OFFHOOK immediately jams MEDIA, without waiting for the change of the player AA and AudioManager.mode.
     * IDLE after our pause on the call: if the player AA plays, we take a pause ourselves - the events of play may not be,
     * when the call reset before the player got up.
     */
    @Synchronized
    fun onPhoneCallState(state: Int) {
        val call = when (state) {
            CallStateSource.RINGING -> PhoneCall.RINGING
            CallStateSource.OFFHOOK -> PhoneCall.ACTIVE
            else -> PhoneCall.IDLE
        }
        val prev = telephonyCall
        telephonyCall = call
        if (prev == call) return
        if (call != PhoneCall.IDLE) {
            when {
                !huVwCns2Delphi -> log("BRIDGE звонок ${call.name} (телефония): не VW CNS2 Delphi, MEDIA не трогаю", true)
                !carlifeStarted -> log("BRIDGE звонок ${call.name} (телефония): CarLife ещё не START, гасить нечего", true)
                huPausedAa -> log("BRIDGE звонок ${call.name} (телефония): MEDIA уже на паузе", true)
                else -> muteMedia(call, "телефония")
            }
        } else if (callPausedAa) {
            if (meta.playing) releaseHuPause("звонок закончен, плеер AA играет")
            else log("BRIDGE звонок закончен: плеер AA стоит, MEDIA держу на паузе до play", true)
        } else if (prev != null) {
            log("BRIDGE звонок закончен (телефония): пауза MEDIA была не от звонка, не трогаю", false)
        }
    }

    /** Whether to run MEDIA_DATA further; no = data discarded (voice / wait for INIT after TTS). */
    private fun admitMedia(): Boolean {
        if (huPausedAa && !callPausedAa) releaseDesktopPauseIfPlaying()
        val why = when {
            VOICE_SESSION_ENABLED && voice -> "идёт голосовая сессия"
            huPausedAa -> "ГУ на своём источнике, AA на паузе (хвост буфера)"
            audioInited[CarlifeAudio.CH_MEDIA] != true && mediaBlocked() -> "TTS занят, MEDIA ждёт INIT"
            else -> null
        }
        if (why != null) {
            mediaGateDrops++
            if (mediaGateDrops == 1L || mediaGateDrops % 500 == 0L) log("BRIDGE AA audio MEDIA данные отброшены: $why (#$mediaGateDrops)", false)
            return false
        }
        if (pendingMediaPause) {
            pendingMediaPause = false
            log("BRIDGE отложенная пауза MEDIA отменена: данные снова идут", true)
        }
        return true
    }

    /** Civic 2021 (h629) with sound according to BT: music is played by BT-source head unit, CarLife- music is not declare him. */
    private val hondaBtMusic get() = huHonda20772104 && !audioViaUsb

    private fun musicStatus(status: Int) {
        if (MODULE_MUSIC_ENABLED) link.sendModuleStatus(CarlifeAudio.MODULE_MUSIC, status)
    }

    /** AA player changed its state (ch9). MUSIC on it - only when the sound is not USB: otherwise it owns MEDIA INIT/PAUSE. */
    private fun musicByState(playing: Boolean) {
        if (playing) releaseHuPause("AA заиграл")
        else {
            val now = clockMs()
            lastExternalStopMs = if (now - lastPauseKeyMs > USER_INPUT_WINDOW_MS && now - lastUserInputMs > USER_INPUT_WINDOW_MS) now else NEVER
            log("BRIDGE AA плеер остановился: наш KEYCODE $KEYCODE_MEDIA_PAUSE ${ago(lastPauseKeyMs)}, ввод пользователя ${ago(lastUserInputMs)}, BT HFP ${ago(lastHfpConnectMs)}", true)
            muteMediaOnCall("плеер AA остановился")
        }
        if (audioViaUsb || !MODULE_MUSIC_ENABLED || !carlifeStarted) return
        if (hondaBtMusic) {
            log("BRIDGE Honda 20772104: MODULE_STATUS MUSIC не шлю, звук по BT - ГУ держит свой BT-источник", false)
            return
        }
        if (link.sendModuleStatus(CarlifeAudio.MODULE_MUSIC, if (playing) 1 else 0)) {
            log("BRIDGE CarLife MODULE_STATUS MUSIC=${if (playing) 1 else 0} (плеер AA, звук не по USB)", true)
        }
    }

    /** The route keeps / lets go NAVI. NAVI=0 leaves only when it does not keep and TTS. */
    private fun routeNavi(on: Boolean) {
        if (!ROUTE_NAVI_STATUS_ENABLED) {
            routeNaviHeld = false
            if (on) log("BRIDGE маршрут: MODULE_STATUS NAVI=1 не шлю (только вокруг TTS)", false)
            return
        }
        if (huDesayG6S) {
            routeNaviHeld = false
            if (on) log("BRIDGE маршрут: MODULE_STATUS NAVI=1 не шлю (Desay G6S: глушит радио)", false)
            return
        }
        routeNaviHeld = on
        if (on) naviEndPending = false
        if (on && !naviSent) {
            if (link.sendModuleStatus(CarlifeAudio.MODULE_NAVI, 1)) {
                naviSent = true
                naviRaised()
                log("BRIDGE CarLife MODULE_STATUS NAVI=1 (маршрут)", true)
            }
        } else if (!on && naviSent && !ttsNaviHeld) {
            naviSent = false
            link.sendModuleStatus(CarlifeAudio.MODULE_NAVI, 0)
            log("BRIDGE CarLife MODULE_STATUS NAVI=0 (маршрут закончен)", true)
        } else if (!on && naviSent) {
            naviEndPending = true
            log("BRIDGE маршрут закончен, NAVI=1 держит TTS", false)
        }
    }

    private fun stopAudio(ch: Int) {
        if (ch == CarlifeAudio.CH_TTS) {
            ttsStopTimer.cancel()
            dropTtsPending()
        }
        val wasInited = audioInited[ch] == true
        if (wasInited || (ch == CarlifeAudio.CH_MEDIA && mediaPaused)) {
            audioInited[ch] = false
            if (ch == CarlifeAudio.CH_MEDIA) {
                mediaPaused = false
                if (wasInited) musicStatus(0)
            }
            link.sendAudio(ch, CarlifeAudio.stopType(ch), ByteArray(0))
            log("BRIDGE CarLife ${chName(ch)} STOP (0x%08x)".format(CarlifeAudio.stopType(ch)), true)
        }
        if (ch == CarlifeAudio.CH_TTS) releaseTtsNavi()
    }

    /**
     * TTS is over: NAVI is no longer needed for its sake. If the route has already ended (the last "You arrived" played
     * after INACTIVE), NAVI=0 leaves here, otherwise instrument cluster remains in navigation until the end of the session.
     */
    private fun releaseTtsNavi() {
        ttsNaviHeld = false
        if (naviEndPending && naviSent && !routeNaviHeld) {
            naviEndPending = false
            naviSent = false
            link.sendModuleStatus(CarlifeAudio.MODULE_NAVI, 0)
            log("BRIDGE CarLife MODULE_STATUS NAVI=0 (TTS закончился, маршрута нет)", true)
        }
    }

    /** reset audio state of CarLife-session (START/closing): the link is already new or dead, nothing is sent. */
    private fun resetAudio() {
        audioInited.clear()
        ttsStopTimer.cancel()
        dropTtsPending()
        mediaReleaseTimer.cancel()
        naviSent = false
        naviEndPending = false
        ttsNaviHeld = false
        mediaPaused = false
        mediaGuard = false
        pendingMediaPause = false
        mediaGateDrops = 0
    }

    private fun stopAllAudio(why: String) {
        ttsStopTimer.cancel()
        mediaReleaseTimer.cancel()
        voiceExitTimer.cancel()
        voice = false
        mediaGuard = false
        pendingMediaPause = false
        if (audioInited.values.any { it } || mediaPaused) log("BRIDGE останавливаю звук CarLife: $why", true)
        stopAudio(CarlifeAudio.CH_MEDIA)
        stopAudio(CarlifeAudio.CH_TTS)
        ttsNaviHeld = false
        if (naviSent && !routeNaviHeld) {
            naviSent = false
            link.sendModuleStatus(CarlifeAudio.MODULE_NAVI, 0)
            log("BRIDGE CarLife MODULE_STATUS NAVI=0", true)
        }
    }

    // --- metadata AA ---
    @Synchronized
    override fun onMediaStatus(s: AaMediaStatus) = meta.onMediaStatus(s)

    @Synchronized
    override fun onMediaMetadata(m: AaMediaMetadata) = meta.onMediaMetadata(m)

    @Synchronized
    override fun onNavCluster(start: Boolean) = meta.onNavCluster(start)

    @Synchronized
    override fun onNavStatus(status: Int) = meta.onNavStatus(status)

    @Synchronized
    override fun onNavTurn(t: AaNavTurn) = meta.onNavTurn(t)

    @Synchronized
    override fun onNavDistance(d: AaNavDistance) = meta.onNavDistance(d)

    // --- video AA ---
    @Synchronized
    override fun onCodecConfig(annexB: ByteArray) {
        if (desktopSurfaceEnabled) return
        aaVideoSeen = true
        aaConfig = AnnexB.normalize(annexB)
        log("BRIDGE AA CODEC_CONFIG ${annexB.size} Б nal=${AnnexB.nalTypes(aaConfig!!)}", true)
        logSps("AA", aaConfig!!)
        if (carlifeStarted) transcoder?.feedConfig(aaConfig!!)
        splicer?.onConfig(aaConfig!!)
        refDecoder?.feedConfig(aaConfig!!)
    }

    @Synchronized
    override fun onFrame(annexB: ByteArray, ptsUs: Long) {
        if (desktopSurfaceEnabled) return
        aaVideoSeen = true
        AaState.frames.incrementAndGet()
        AaState.bytes += annexB.size
        if (splicePendingSeq != 0L && spliceHold) {
            spliceHeld.add(annexB)              // IDR is going from the last picture: new frame waiting for him
            return
        }
        aaFrame(annexB, ptsUs)
    }

    private fun aaFrame(annexB: ByteArray, ptsUs: Long) {
        if (desktopInputEnabled && desktopVideoError != null) {
            drop("desktop video stopped after encoder failure")
            return
        }
        // IDR: decoder reference images and parsing of header see each frame AA, and up to START too
        val spliced = if (spliceOn) spliceTrack(AnnexB.normalize(annexB)) else null
        if (!carlifeStarted) {
            drop("CarLife ещё не прислал START")
            return
        }
        val b = spliced ?: AnnexB.normalize(annexB)
        val types = AnnexB.nalTypes(b)
        val frameAt = clockMs()
        aaFrameGapMs = if (lastAaFrameAt > 0) frameAt - lastAaFrameAt else Long.MAX_VALUE
        lastAaFrameAt = frameAt
        aaFrames++
        val t = transcoder
        if (t != null) {
            feedTranscoder(t, b, types, ptsUs)
            return
        }
        if (spliceOn && spliceReplaceNow(b, types)) return
        passthroughFrame(b, types)
        if (spliceOn) spliceRequest(fromFrame = true)
    }

    /**
     * It's time for his IDR and AA shot frame - this frame does not go, his IDR from his picture will go instead.
     * Without this, IDR goes over frame after its picture: head unit, which shows frame with a constant frequency, saves it.
     * In the movement still [spliceDrop] frame before the picture IDR Do not leave ([spliceDropped]), IDR Stands up instead
     * All of them were gone before they were gone, and they were gone.
     */
    private fun spliceReplaceNow(b: ByteArray, types: List<Int>): Boolean {
        if (!gotIdr || 5 in types || splicePendingSeq != 0L) {
            spliceFlushDropped()
            return false
        }
        if (spliceDropped.size < spliceDrop) {
            if (spliceDropped.isEmpty()) {
                if (aaFrameGapMs >= SPLICE_QUIET_MS || !spliceDue(clockMs())) return false
                spliceDroppedAt = clockMs()
            } else if (aaFrameGapMs >= SPLICE_QUIET_MS) {
                spliceFlushDropped()
                return false
            }
            spliceDropped.add(b)
            return true
        }
        spliceRequest(fromFrame = true)
        if (splicePendingSeq == 0L || !spliceHold || splicePendingSeq != spliceLastSeq) {
            spliceFlushDropped()
            return false
        }
        spliceReplaced = b
        return true
    }

    /** Thrown under its IDR frame AA still go to head unit (IDR will not): in order, they have already passed splicer. */
    private fun spliceFlushDropped() {
        if (spliceDropped.isEmpty()) return
        val frames = ArrayList(spliceDropped)
        spliceDropped.clear()
        latStats.dropUndone++
        if (carlifeStarted) for (f in frames) passthroughFrame(f, AnnexB.nalTypes(f))
    }

    private fun passthroughFrame(b: ByteArray, types: List<Int>) {
        if (!gotIdr) {
            if (5 !in types) {
                drop("P-кадр до IDR nal=$types")
                return
            }
            val payload = spsCrop(if (AnnexB.startsWithSps(b) && 8 in types) b else {
                val cfg = aaConfig
                if (cfg == null) {
                    drop("IDR без SPS/PPS и без CODEC_CONFIG")
                    return
                }
                AnnexB.glue(cfg, b)
            })
            val takeover = active != Source.AA
            if (!(if (takeover) link.sendVideoTakeover(payload) else link.sendVideo(payload))) {
                drop("первый IDR не принят CarLife (очередь/закрыто)")
                return
            }
            gotIdr = true
            waitSince = 0
            sent++
            idrSent++
            lastIdrSentMs = clockMs()
            latStats.sent(lastIdrSentMs, own = false)
            // h529 (Vezel) the first IDR AA immediately after IDR stub head unit sometimes loses, and the screen is black until the next IDR AA (~60 (c)
            // 211335 log: yes and without crop (R1080), so repeat IDR in all passthrough modes and on all head unit,
            // but once per CarLife-session: after PAUSE/START it would give extra shake (0,9 with stop-frame)
            // With your IDR, you do not need to repeat, but this is solved in tick: here decoder has not yet issued a single picture
            if (!sessionKickDone) {
                sessionKickDone = true
                spsCropKickAt = lastIdrSentMs + SPS_CROP_KICK_MS
                spsCropKickBase = lastIdrSentMs
                spsCropKickDeferred = false
            }
            if (active != Source.AA) {
                active = Source.AA
                log("BRIDGE источник -> AA (первый IDR ${payload.size} Б, nal=${AnnexB.nalTypes(payload)})", true)
                link.hidePlaceholder()
            } else {
                log("BRIDGE IDR -> CarLife ${payload.size} Б nal=${AnnexB.nalTypes(payload)} (SPS/PPS ${if (payload.size != b.size) "склеены" else "в кадре"})", true)
            }
            AaState.status = "мост: AA -> CarLife"
            return
        }
        val out = if (5 in types) spsCrop(b) else b
        if (!link.sendVideo(out)) {
            // frame is lost: decoder head unit will float, waiting for the new IDR
            gotIdr = false
            waitSince = clockMs()
            drop("кадр не принят CarLife, жду новый IDR")
            return
        }
        sent++
        latStats.sent(clockMs(), own = false)
        if (5 in types) {
            idrSent++
            lastIdrSentMs = clockMs()
        }
        AaState.bridgeSent = sent
    }

    private fun drop(why: String) {
        dropped++
        AaState.bridgeDropped = dropped
        dropLogged++
        if (dropLogged <= 3 || dropLogged % 100 == 0L) log("BRIDGE отброшен AA-кадр (#$dropped): $why", false)
    }

    /** Once in 0.5 with: key frame, if after START there is no IDR for a long time; its IDR on the static screen. */
    fun tick() {
        tickLocked()
        spliceRun()                                    // IDR assembly outside core lock
    }

    @Synchronized
    private fun tickLocked() {
        logStats()
        if (!carlifeStarted) return
        if (desktopSurfaceEnabled) return // Direct input has no upstream compressed stream to recover.
        val now = clockMs()
        if (spliceOn) spliceTick(now)
        latStats.logIfDue(now)
        if (spsCropKickAt != NEVER && now >= spsCropKickAt && spliceKickNeeded(now)) {
            spsCropKickAt = NEVER
            lastKeyframeRequest = now
            keyframeRequests++
            log("BRIDGE повторный ключевой кадр через ${SPS_CROP_KICK_MS} мс после первого (ГУ могла потерять его на смене заглушка -> AA)", true)
            peer?.requestKeyFrame()
        }
        if (gotIdr || !aaVideoSeen) return
        if (waitSince == 0L) waitSince = now
        if (now - waitSince >= KEYFRAME_WAIT_MS && now - lastKeyframeRequest >= KEYFRAME_RETRY_MS) {
            lastKeyframeRequest = now
            keyframeRequests++
            log("BRIDGE жду IDR от AA ${(now - waitSince) / 1000.0} с: запрашиваю ключевой кадр (VideoFocus NATIVE -> PROJECTED) #$keyframeRequests", true)
            peer?.requestKeyFrame()
        }
    }

    /**
     * h529-Repeat IDR from AA or your IDR. has already left after the first IDR session - no repeat is needed. Insert works
     * (decoder gives pictures), but his not gone yet - we are waiting for him more [SPLICE_KICK_GRACE_MS] once. Otherwise - repeat as in h529.
     */
    private fun spliceKickNeeded(now: Long): Boolean {
        if (spliceLastSentAt != NEVER && spliceLastSentAt > spsCropKickBase) {
            log("BRIDGE повторный ключевой кадр от AA не нужен: свой IDR уже ушёл", true)
            spsCropKickAt = NEVER
            return false
        }
        if (spliceWorks && !spsCropKickDeferred) {
            spsCropKickDeferred = true
            spsCropKickAt = now + SPLICE_KICK_GRACE_MS
            return false
        }
        return true
    }

    // --- your IDR in passthrough (IdrSplice) ---
    private var splicer: IdrSplicer? = null
    private var refDecoder: RefPictureDecoder? = null
    private var refDecoderFailed = false
    @Volatile private var spliceLastSeq = 0L           // the number of the last picture AA, passed through splicer
    @Volatile private var refOutSeq = 0L               // last picture on exit decoder (copied or not)
    @Volatile private var refPic: IdrSplice.Picture? = null
    @Volatile private var refPicSeq = 0L
    @Volatile private var splicePendingSeq = 0L        // picture for your IDR: waiting for it from decoder or collect; 0 - no
    private var splicePendingAt = 0L
    private var spliceHold = false                     // frame AA are waiting for IDR (AA is frame); otherwise, the assembly in the background
    private var spliceBuilding = false
    private var spliceNotBefore = NEVER                // next attempt not earlier (after failure, skip, timeout)
    private var spliceBlockLogged: String? = null
    private val spliceHeld = ArrayList<ByteArray>()     // frame AA, coming while going to his IDR (spliceHold)
    private var spliceReplaced: ByteArray? = null       // frame AA, which will be replaced by IDR, through splicer
    private val spliceDropped = ArrayList<ByteArray>()  // frame AA in front of the picture of his IDR, which he will replace (spliceDrop), already through splicer
    private var spliceDroppedAt = 0L
    private val guModel = GuQueueModel()
    private val latStats = LatencyStats()
    private var spliceLastSentAt = NEVER               // when his IDR last went to head unit
    private var spliceMisses = 0                       // picture did not come from decoder, consecutive
    private var spliceBackoffUntil = NEVER             // Pause after [SPLICE_MAX_MISSES] Passes: up to this point IDR do not try
    private var spliceBackoffs = 0                     // how many such pauses per AA-session
    private var refLagSince = NEVER                    // when does decoder lag behind frame AA more than on 2 and does not move
    private var refLagOutSeq = 0L                      // refOutSeq at the beginning of this lag: moved - decoder is alive
    private var spliceDisabled: String? = null          // inserting is not possible until the end of AA-session (cause)
    private var aaFrameGapMs = Long.MAX_VALUE          // pause before last frame AA
    private var builder: IdrBuilder? = null
    private val spliceStats = SpliceStats()

    /** thread builds its IDR; null - its own, created at the first assembly. Tests put the performer in the same thread. */
    var spliceExecutor: Executor? = null

    /** Slide pool IDR; null - yours on [SPLICE_SLICES] thread. */
    var spliceSlicePool: ExecutorService? = null

    private val spliceOn: Boolean get() = spliceMs > 0 && !transcodeEnabled

    /**
     * Your IDR is really possible: decoder has already given away the picture, the stream fits, the pause after the passes is over. Then
     * repeated IDR from AA (shake-up) is not needed. While pauses or standing up decoder - false: h529 works as insurance.
     */
    internal val spliceWorks: Boolean
        get() = spliceOn && spliceDisabled == null && refDecoder != null && refPicSeq > 0 &&
            clockMs() >= spliceBackoffUntil && splicer?.blocker() == null

    /** frame AA via splicer (frame_num/POC after its IDR) and in decoder reference images. */
    private fun spliceTrack(b: ByteArray): ByteArray {
        val sp = splicer ?: IdrSplicer().also { s ->
            splicer = s
            aaConfig?.let { s.onConfig(it) }
        }
        val out = sp.process(b)
        spliceLastSeq = sp.seq
        var d = refDecoder
        if (d == null && !refDecoderFailed) {
            d = refDecoderFactory?.invoke(this)
            if (d == null) {
                refDecoderFailed = true
                log("BRIDGE свой IDR: декодера опорных картинок нет, вставка выключена", true)
            } else {
                refDecoder = d
                aaConfig?.let { d.feedConfig(it) }
                log("BRIDGE свой IDR: включён, раз в ${spliceMs} мс (passthrough)", true)
            }
        }
        d?.feed(b, sp.seq)
        return out
    }

    /**
     * Is it time to replace the last picture of AA with your IDR: for the period, and without IDR on head unit - immediately (with a pause between attempts).
     * IDR should follow immediately after its picture: otherwise head unit will continue P-frame AA from another frame and the picture will go in motion.
     * Therefore, if AA sends frame (pause before frame less [SPLICE_QUIET_MS]), frame Waiting for assembly (no longer)
     * [SPLICE_HOLD_MAX_MS]); on the static IDR is collected in the background, and the frame that came during this time makes it obsolete.
     */
    private fun spliceRequest(fromFrame: Boolean) {
        if (spliceDropped.isNotEmpty() && !fromFrame) return     // emissions started: IDR will collect from the next frame AA
        val now = clockMs()
        if (!spliceDue(now)) return
        val sp = splicer ?: return
        splicePendingSeq = sp.seq
        splicePendingAt = now
        spliceHold = if (fromFrame) aaFrameGapMs < SPLICE_QUIET_MS else now - lastAaFrameAt < SPLICE_QUIET_MS
    }

    /** It's time to build your IDR: period has passed, decoder is not behind, the flow is coming. */
    private fun spliceDue(now: Long): Boolean {
        if (splicePendingSeq != 0L || spliceDisabled != null || !carlifeStarted || active != Source.AA) return false
        val sp = splicer ?: return false
        if (refDecoder == null || sp.seq == 0L || sp.seq - refOutSeq > 2) return false     // decoder is behind or waiting for IDR
        if (now < spliceNotBefore || now < spliceBackoffUntil) return false
        if (gotIdr && now - lastIdrSentMs < spliceMs) return false
        val why = sp.blocker()
        if (why != null) {
            if (why != spliceBlockLogged) log("BRIDGE свой IDR невозможен: $why", true)
            spliceBlockLogged = why
            spliceNotBefore = now + SPLICE_RETRY_MS
            return false
        }
        return true
    }

    /**
     * Once in 0.5 s. Passes pictures from decoder ([SPLICE_MAX_MISSES] in a row) insert do not turn off: pause [SPLICE_BACKOFF_MS]
     * and a new try (the phone could just slow down) while the pause - [spliceWorks] false. The standing up decoder restarts.
     */
    private fun spliceTick(now: Long) {
        spliceCheckStall(now)
        // the emission started, and the next frame AA is not (the screen stood up): discarded go, on static turn head unit goes and so
        if (spliceDropped.isNotEmpty() && now - spliceDroppedAt > SPLICE_QUIET_MS) spliceFlushDropped()
        val seq = splicePendingSeq
        if (seq != 0L && !spliceBuilding && refPicSeq != seq && now - splicePendingAt > SPLICE_WAIT_MS) {
            log("BRIDGE свой IDR: картинка #$seq не пришла из декодера за $SPLICE_WAIT_MS мс, пропускаю", true)
            spliceStats.noPicture++
            spliceNotBefore = now + SPLICE_RETRY_MS
            spliceCancel(null)
            if (++spliceMisses >= SPLICE_MAX_MISSES) {
                spliceBackoffs++
                log("BRIDGE свой IDR: картинка из декодера не пришла $spliceMisses раз подряд, пауза $SPLICE_BACKOFF_MS мс, потом снова " +
                    "(пауз за сессию $spliceBackoffs)", true)
                spliceMisses = 0
                spliceBackoffUntil = now + SPLICE_BACKOFF_MS
                spliceNotBefore = spliceBackoffUntil
            }
        } else if (seq != 0L && spliceHold && now - splicePendingAt > SPLICE_HOLD_MAX_MS) {
            // assembly is not in time: frame AA leave, IDR is thrown out on readiness (splicePendingSeq is dropped)
            spliceStats.holdTimeout++
            spliceNotBefore = now + SPLICE_RETRY_MS
            spliceCancel(null)
        } else {
            spliceRequest(fromFrame = false)
        }
        spliceStats.logIfDue(now, builder?.qp)
    }

    /**
     * decoder Stand up: frame AA go, and its output does not move and lags behind more than on 2 Longer [SPLICE_STALL_MS]. Without it
     *  - IDR No, and [spliceWorks] (The picture was once) would jam a repeat IDR from AA (h529). decoder It stops, new creates.
     * [spliceTrack] on the following frame AA; he needs IDR from AAThere are no pictures of him.)[refPicSeq] 0, [spliceWorks] false).
     * While the conclusion of new decoder There was no ([refOutSeq] 0He's just waiting IDR - not up, do not restart.
     * AA Silenced longer [SPLICE_STALL_IDLE_MS] (Statistics, decoder Holds the last frameThe countdown goes on again.
     */
    private fun spliceCheckStall(now: Long) {
        val sp = splicer
        if (sp == null || refDecoder == null || spliceDisabled != null || refOutSeq == 0L || sp.seq - refOutSeq <= 2) {
            refLagSince = NEVER
            return
        }
        if (refLagSince == NEVER || refOutSeq != refLagOutSeq || now - lastAaFrameAt > SPLICE_STALL_IDLE_MS) {
            refLagSince = now
            refLagOutSeq = refOutSeq
            return
        }
        if (now - refLagSince < SPLICE_STALL_MS) return
        log("BRIDGE свой IDR: декодер встал ${now - refLagSince} мс, перезапускаю", true)
        refDecoder?.stop()
        refDecoder = null                       // refDecoderFailed not touch: the factory will return null - then it will become true, as at the start
        refPicSeq = 0
        refOutSeq = 0
        refPic = null
        refLagSince = NEVER
        spliceCancel(null)
    }

    override fun wantsRefPicture(seq: Long): Boolean {
        refOutSeq = seq
        return seq == spliceLastSeq
    }

    override fun onRefUnavailable(why: String) = synchronized(this) { spliceDisable(why) }

    /**
     * Insertion is not possible until the end of AA-session: decoder stop, frame AA no longer adhere, repeat IDR from AA (h529)
     * frame_num shift after already gone its IDR holds up to IDR from AA.
     */
    private fun spliceDisable(why: String) {
        if (spliceDisabled != null) return
        spliceDisabled = why
        log("BRIDGE свой IDR: !!!!!! выключен до конца AA-сессии: $why", true)
        refDecoder?.stop()
        refDecoder = null
        refDecoderFailed = true
        spliceCancel(null)
    }

    override fun onRefPicture(seq: Long, pic: IdrSplice.Picture) {
        refPic = pic
        refPicSeq = seq
        if (seq == splicePendingSeq) spliceRun()
    }

    /** Give the assembly of your IDR to its thread, if the picture already exists. It is called out of the kernel lock. */
    private fun spliceRun() {
        val task = synchronized(this) {
            val seq = splicePendingSeq
            val ready = refPicSeq == seq                // first number, then picture: it is no older than the number
            val pic = refPic
            val sp = splicer
            if (seq == 0L || spliceBuilding || !ready || pic == null || sp == null) return
            val job = sp.prepare()
            if (job == null || job.seq != seq) {
                // in the background: AA sent frame before decoder gave picture
                spliceStats.stale++
                spliceNotBefore = clockMs() + SPLICE_RETRY_MS
                spliceCancel(null)
                return
            }
            spliceBuilding = true
            val b = builder ?: IdrBuilder(spliceSlicePool ?: newSlicePool(), SPLICE_SLICES).also { builder = it }
            val exec = spliceExecutor ?: newBuildThread()
            BuildTask(job, b, exec, pic)
        }
        val job = task.job
        val b = task.builder
        // copy: in the background, decoder can write the following picture in the same buffer (then this IDR will become obsolete)
        val pic = task.pic.copy()
        val exec = task.exec
        exec.execute {
            val t0 = clockMs()
            val r = try {
                b.build(job, pic)
            } catch (e: RuntimeException) {
                log("BRIDGE свой IDR: !!!!!! сборка упала: ${e.javaClass.simpleName}: ${e.message}", true)
                null
            }
            spliceComplete(job, r, clockMs() - t0)
        }
    }

    private class BuildTask(val job: IdrSplicer.Job, val builder: IdrBuilder, val exec: Executor, val pic: IdrSplice.Picture)

    private fun newBuildThread(): Executor =
        Executors.newSingleThreadExecutor { r -> Thread(r, "aa-idr-build").also { it.isDaemon = true } }.also { spliceExecutor = it }

    private fun newSlicePool(): ExecutorService =
        Executors.newFixedThreadPool(SPLICE_SLICES) { r -> Thread(r, "aa-idr-slice").also { it.isDaemon = true } }.also { spliceSlicePool = it }

    @Synchronized
    private fun spliceComplete(job: IdrSplicer.Job, r: IdrSplice.Result?, buildMs: Long) {
        spliceBuilding = false
        if (splicePendingSeq != job.seq) return        // Cancelled (held longer than the ceiling, new session): frame already released
        val now = clockMs()
        spliceNotBefore = now + SPLICE_RETRY_MS
        spliceMisses = 0
        spliceStats.build(buildMs)
        val sp = splicer
        when {
            r == null || sp == null || !carlifeStarted || active != Source.AA -> {}
            sp.seq != job.seq -> spliceStats.stale++    // in the background: while collecting, AA sent frame
            r.nal.size > SPLICE_MAX_BYTES -> {
                spliceStats.big++
                if (gotIdr) spliceNotBefore = now + spliceMs      // picture is the same or similar: do not collect every second
            }
            else -> spliceSend(sp, job, r, buildMs, now)
        }
        spliceCancel(null)
    }

    private fun spliceSend(sp: IdrSplicer, job: IdrSplicer.Job, r: IdrSplice.Result, buildMs: Long, now: Long) {
        // SPS/PPS through time: Honda after reset waiting for them in frame (h295), Java-head unit CarLife in reset takes only naked IDR
        val cfg = aaConfig
        val recovered = !gotIdr
        val glued = cfg != null && sp.spliced % 2 == 0L
        val payload = if (glued) spsCrop(AnnexB.glue(cfg!!, r.nal)) else r.nal
        if (!link.sendVideo(payload)) {
            log("BRIDGE свой IDR ${payload.size} Б не принят CarLife (очередь/закрыто)", true)
            return
        }
        sp.commit(job)
        spliceStats.sent++
        spliceLastSentAt = now
        val replaced = spliceReplaced != null
        spliceReplaced = null                           // frame AA this picture head unit will not get: his place was taken by IDR
        val dropped = spliceDropped.size
        spliceDropped.clear()                           // and frame in front of her (spliceDrop)
        latStats.sent(now, own = true, replaced = replaced, holdMs = if (spliceHold) now - splicePendingAt else -1, held = spliceHeld.size, dropped = dropped)
        gotIdr = true
        waitSince = 0
        sent++
        idrSent++
        lastIdrSentMs = now
        if (recovered || sp.spliced <= 5 || sp.spliced % 30 == 0L) {
            log("BRIDGE свой IDR #${sp.spliced}${if (recovered) " (ГУ была без IDR)" else ""}: ${payload.size} Б, " +
                (if (r.qp < 0) "без потерь (PCM ${r.pcm})" else "QP ${r.qp}") + ", сборка $buildMs мс, " +
                (if (spliceHold) "кадры AA ждали ${now - splicePendingAt} мс" else "в фоне") + ", ${if (glued) "с SPS/PPS" else "голый"}" +
                (if (!replaced) "" else if (dropped == 0) ", вместо кадра AA" else ", вместо ${dropped + 1} кадров AA"), true)
        }
    }

    /** No longer waiting for the picture: held frame AA go their own way (can again stand in line for the new IDR). */
    private fun spliceCancel(why: String?) {
        if (splicePendingSeq == 0L) {
            spliceFlushDropped()
            return
        }
        if (why != null) log("BRIDGE свой IDR отменён: $why", false)
        splicePendingSeq = 0
        spliceHold = false
        spliceFlushDropped()
        // frame, instead of which IDR was waiting, has already passed through splicer: in head unit as it is, previously held
        spliceReplaced?.let { b ->
            spliceReplaced = null
            if (carlifeStarted) passthroughFrame(b, AnnexB.nalTypes(b))
        }
        while (spliceHeld.isNotEmpty() && splicePendingSeq == 0L) aaFrame(spliceHeld.removeAt(0), 0)
    }

    /** New AA-session: flow from scratch, decoder is re-created. The old stream's stored frame is discarded. */
    private fun spliceReset() {
        splicePendingSeq = 0
        spliceHold = false
        spliceHeld.clear()
        spliceReplaced = null
        spliceDropped.clear()
        splicer = null
        refDecoder?.stop()
        refDecoder = null
        refDecoderFailed = false
        spliceDisabled = null
        spliceMisses = 0
        spliceBackoffUntil = NEVER
        spliceBackoffs = 0
        refLagSince = NEVER
        spliceLastSentAt = NEVER
        spliceLastSeq = 0
        refOutSeq = 0
        refPic = null
        refPicSeq = 0
        spliceBlockLogged = null
    }

    /**
     * Flow in head unit times in [ SPLICE_STATS_MS] (passthrough): frame AA and its IDR, how many of them are redundant (above frame AA),
     * Retreat frame AA Delayed model head unit with constant display frequency ([GuQueueModel]To look for accumulation of delay.
     */
    private inner class LatencyStats {
        private var aa = 0
        private var own = 0
        private var replaced = 0
        private var holds = 0
        private var holdSum = 0L
        private var holdMax = 0L
        private var heldMax = 0
        private var extraTotal = 0L
        private var dropped = 0
        private var droppedTotal = 0L
        var dropUndone = 0                          // emissions started, but IDR did not go away: frame sent as were
        private var since = NEVER

        fun sent(now: Long, own: Boolean, replaced: Boolean = false, holdMs: Long = -1, held: Int = 0, dropped: Int = 0) {
            if (own) {
                this.own++
                if (replaced) this.replaced++ else extraTotal++
                this.dropped += dropped
                droppedTotal += dropped
                if (holdMs >= 0) {
                    holds++
                    holdSum += holdMs
                    holdMax = maxOf(holdMax, holdMs)
                    heldMax = maxOf(heldMax, held)
                }
            } else aa++
            guModel.onFrame(now)                    // IDR instead of frame AA takes its place, extra - adds frame
        }

        fun logIfDue(now: Long) {
            if (since == NEVER) since = now
            if (now - since < SPLICE_STATS_MS) return
            val sec = (now - since) / 1000.0
            if (aa + own > 0 && transcoder == null) {
                log("BRIDGE поток в ГУ за ${sec.toInt()} с: кадров AA $aa (${"%.1f".format(aa / sec)} fps), своих IDR $own " +
                    "(вместо кадра AA $replaced, лишних ${own - replaced}, лишних за сессию $extraTotal)" +
                    (if (spliceDrop > 0) "; выброшено кадров AA $dropped (за сессию $droppedTotal), выброс отменён $dropUndone раз" else "") +
                    (if (holds > 0) "; кадры AA ждали IDR ср ${holdSum / holds} мс, макс $holdMax мс, в пачке до $heldMax" else "") +
                    "; модель ГУ ${guModel.fps} fps без пропусков: задержка сейчас ${guModel.delayMs(now)} мс, макс ${guModel.maxMs} мс", true)
            }
            aa = 0; own = 0; replaced = 0; holds = 0; holdSum = 0; holdMax = 0; heldMax = 0; dropped = 0; dropUndone = 0
            guModel.resetMax()
            since = now
        }

        fun resetSession() {
            extraTotal = 0
            droppedTotal = 0
            guModel.reset()
        }
    }

    /** Summary of your IDR times in [SPLICE_STATS_MS]: log gets everything, not every N-th line. */
    private inner class SpliceStats {
        var sent = 0
        var stale = 0
        var big = 0
        var holdTimeout = 0
        var noPicture = 0
        private var builds = 0
        private var buildSum = 0L
        private var buildMax = 0L
        private var since = NEVER

        fun build(ms: Long) {
            builds++
            buildSum += ms
            buildMax = maxOf(buildMax, ms)
        }

        fun logIfDue(now: Long, qp: Int?) {
            if (since == NEVER) since = now
            if (now - since < SPLICE_STATS_MS) return
            if (sent + stale + big + holdTimeout + noPicture > 0) {
                log("BRIDGE свой IDR за ${(now - since) / 1000} с: ушло $sent, устарел (AA прислал кадр) $stale, больше $SPLICE_MAX_BYTES Б $big, " +
                    "держали кадры дольше $SPLICE_HOLD_MAX_MS мс $holdTimeout, без картинки $noPicture; сборка " +
                    (if (builds > 0) "ср ${buildSum / builds} мс, макс $buildMax мс" else "-") + (if (qp != null) ", QP $qp" else ""), true)
            }
            sent = 0; stale = 0; big = 0; holdTimeout = 0; noPicture = 0
            builds = 0; buildSum = 0; buildMax = 0
            since = now
        }
    }

    // --- transcoding ---
    private fun startTranscoder() {
        if (desktopSurfaceEnabled) {
            startDesktopSurfaceEncoder()
            return
        }
        if (desktopInputEnabled && desktopVideoError != null) return
        outGotIdr = false
        outputEnabled = false
        var t = transcoder
        if (t == null) {
            t = link.createTranscoder(this)
            transcoder = t
            if (t == null) {
                if (desktopInputEnabled) {
                    stopDesktopVideo("Не удалось создать энкодер CarLife")
                    return
                }
                log("BRIDGE !!!!!! транскодер не поднялся (MediaCodec), откат на passthrough", true)
                val g = videoMode
                if (g is CustomVideoMode) {
                    // TODO: rollback to R720 by recreating the host; while head unit is waiting for frame out, and will receive frame substrate AA with fields
                    log("BRIDGE !!!!!! своё разрешение ${g.outW}x${g.outH} без транскодера не получится: ГУ ждёт ${g.outW}x${g.outH}, " +
                        "а уйдёт кадр AA ${g.aaResolution} с полями ${g.marginW}x${g.marginH}. Выключите «Своё разрешение» или задайте другой размер", true)
                }
                return
            }
        }
        log("BRIDGE видео: режим перекодирование (AA -> декодер -> энкодер, повтор кадра 100 мс)", true)
        aaConfig?.let { t.feedConfig(it) }
        t.requestSyncFrame()        // encoder: key frame on START as soon as the frame goes
    }

    private fun stopTranscoderAsync() {
        val t = transcoder
        if (t == null && !desktopSurfaceCreating) return
        transcoder = null
        encCsd = null
        outGotIdr = false
        outputEnabled = false
        if (desktopSurfaceEnabled) {
            val generation = desktopSurfaceGeneration
            val listener = desktopSurfaceListener
            desktopSurfaceGeneration = 0L // Invalidate late codec callbacks before publishing a new encoder.
            desktopSurfaceReady = false
            desktopSurfaceFirstFrame = false
            desktopSurfaceCsdVerified = false
            desktopSurfaceCreating = false
            if (t != null && generation != 0L) {
                retiredSurfaceEncoders[generation] = RetiredSurfaceEncoder(t, listener)
                desktopSurfaceWorker().execute {
                    try {
                        listener?.onSurfaceClosing(generation)
                        synchronized(this) { closedSurfaceProducers.add(generation) }
                    } catch (e: Exception) {
                        val confirmed = synchronized(this) { generation in closedSurfaceProducers }
                        if (!confirmed) synchronized(this) {
                            stopDesktopVideo("Не подтверждено закрытие экрана DeX; Surface сохранён до завершения службы")
                        }
                        log("DEX surface close failed: ${e.javaClass.simpleName}; consumer retained until producer death is confirmed", true)
                    }
                    disposeRetiredSurfaceEncoder(generation)
                }
            }
        } else Thread({ t?.stop() }, "aa-transcoder-stop").start()
    }

    /** Called once before START after the previous transport's encoder has been retired. */
    @Synchronized fun configureDesktopVideo(owner: CarlifeLink, mode: VideoGeometry, density: Int) {
        check(link === owner && !carlifeStarted && transcoder == null && !desktopSurfaceCreating) { "Desktop video already started or session retired" }
        require(DesktopVideoGeometry.matchesNative(mode))
        DesktopGeometryPolicy.requireDensity(density)
        videoMode = mode
        densityOverride = density
    }

    /** Caller holds the core monitor; source work is deferred to the ordered lifecycle worker. */
    private fun startDesktopSurfaceEncoder() {
        if (desktopSurfaceStopped || desktopVideoError != null) return
        if (!DesktopVideoGeometry.matchesNative(videoMode)) {
            stopDesktopVideo("Прямой экран DeX требует ${DesktopVideoGeometry.outLabel} без кропа и масштаба")
            return
        }
        val listener = desktopSurfaceListener ?: return
        outGotIdr = false
        outputEnabled = desktopSurfaceReady && !carlifePaused
        transcoder?.let { it.requestSyncFrame(); return }
        if (desktopSurfaceCreating) return
        val generation = ++desktopSurfaceSequence
        val sourceLink = link
        val sourceMode = videoMode
        val sourceDensity = sourceMode.densityFor(densityOverride)
        desktopSurfaceGeneration = generation
        desktopSurfaceCreating = true
        desktopSurfaceReady = false
        desktopSurfaceFirstFrame = false
        desktopSurfaceCsdVerified = false
        outputEnabled = false
        encCsd = null
        desktopSurfaceWorker().execute {
            val valid = synchronized(this) {
                val current = !desktopSurfaceStopped && desktopSurfaceGeneration == generation && link === sourceLink &&
                    desktopVideoError == null && retiredSurfaceEncoders.isEmpty()
                if (!current && desktopSurfaceGeneration == generation) desktopSurfaceCreating = false
                current
            }
            if (!valid) return@execute
            // This runs after the previous source and encoder have both closed. Creating
            // codecs under the core monitor would also block USB while GL initializes.
            val t = try { sourceLink.createSurfaceEncoder(DesktopEncoderOutput(generation)) } catch (e: Exception) {
                log("DEX surface encoder creation failed: ${e.javaClass.simpleName}", true)
                null
            }
            val input = runCatching { t?.surfaceInput }.getOrNull()
            var disposalQueued = false
            val adopted = synchronized(this) {
                if (desktopSurfaceStopped || desktopSurfaceGeneration != generation || link !== sourceLink || desktopVideoError != null) false
                else {
                    desktopSurfaceCreating = false
                    transcoder = t
                    if (input == null) {
                        disposalQueued = t != null
                        stopDesktopVideo("Не удалось создать Surface энкодера CarLife")
                    }
                    input != null
                }
            }
            if (!adopted) {
                // A failed/null-input codec already queued by stopDesktopVideo is disposed
                // there; a codec created after cancellation has never been published.
                if (!disposalQueued) t?.stop()
                return@execute
            }
            log("DEX video: native ${videoMode.outW}x${videoMode.outH} Surface -> GL -> H.264 CarLife; one encode, generation=$generation", true)
            try {
                listener.onSurfaceAvailable(generation, object : SurfaceVideoInput {
                    override val surface get() = input!!.surface
                    override val width = sourceMode.outW
                    override val height = sourceMode.outH
                    override val density = sourceDensity
                })
            } catch (e: Exception) {
                log("DEX surface source failed: ${e.javaClass.simpleName}", true)
                desktopSurfaceFailed(generation, "Не удалось подключить экран DeX к энкодеру CarLife")
            }
        }
    }

    /** A retired codec may emit a final callback while the next USB session is starting. */
    private inner class DesktopEncoderOutput(private val generation: Long) : TranscodeOutput {
        private inline fun current(block: () -> Unit) = synchronized(this@AaBridgeCore) {
            if (generation == desktopSurfaceGeneration && !desktopSurfaceStopped && desktopVideoError == null) block()
        }
        override fun onEncoderConfig(csd: ByteArray) = current { this@AaBridgeCore.onEncoderConfig(csd) }
        override fun onEncoderFrame(data: ByteArray, key: Boolean) = current { this@AaBridgeCore.onEncoderFrame(data, key) }
        override fun onTranscodeError(msg: String) = current { this@AaBridgeCore.onTranscodeError(msg) }
        override fun onNeedAaKeyFrame() {} // There is no decoder in direct-surface mode.
    }

    private fun resetTranscodeForAa() {
        outputEnabled = false
        outGotIdr = false
        transcoder?.resetDecoder()
    }

    /** AA-frame -> decoder. Before the first IDR after START/ re-creation, frame is discarded (decoder is needed by IDR). */
    private fun feedTranscoder(t: VideoTranscoder, b: ByteArray, types: List<Int>, ptsUs: Long) {
        if (!gotIdr) {
            if (5 !in types) {
                drop("P-кадр до IDR (декодер ждёт) nal=$types")
                return
            }
            val payload = if (AnnexB.startsWithSps(b) && 8 in types) b else {
                val cfg = aaConfig
                if (cfg == null) {
                    drop("IDR без SPS/PPS и без CODEC_CONFIG")
                    return
                }
                AnnexB.glue(cfg, b)
            }
            gotIdr = true
            waitSince = 0
            outputEnabled = true
            log("BRIDGE AA IDR -> декодер (${payload.size} Б, nal=${AnnexB.nalTypes(payload)})", true)
            // transition with stub: encoder will start with its frame and dissolve it in AA (up to feedFrame, so that GL-thread brought it before the first frame)
            if (active != Source.AA) link.placeholderSnapshot()?.let { t.startIntro(it) }
            t.feedFrame(payload, ptsUs)
            t.requestSyncFrame()    // First frame encoder after the output is enabled immediately IDRNo waiting until ~ 1 C
            return
        }
        t.feedFrame(b, ptsUs)
    }

    @Synchronized
    override fun onEncoderConfig(csd: ByteArray) {
        if (desktopSurfaceEnabled && (desktopSurfaceStopped || desktopVideoError != null)) return
        encCsd = csd
        log("BRIDGE энкодер: SPS+PPS ${csd.size} Б nal=${AnnexB.nalTypes(csd)}", false)
        if (desktopSurfaceEnabled && !AnnexB.nalTypes(csd).containsAll(listOf(7, 8))) {
            stopDesktopVideo("Энкодер CarLife не выдал SPS/PPS")
            return
        }
        if (desktopInputEnabled) for (nal in IdrSplicer.nals(csd)) {
            if (nal[0].toInt() and 0x1f != 7) continue
            try {
                val info = SpsCrop.parse(nal)
                val matches = info.visibleW == videoMode.outW && info.visibleH == videoMode.outH
                log("DEX geometry stage=carlife-encoder-sps coded=${info.codedW}x${info.codedH} visible=${info.visibleW}x${info.visibleH} " +
                    "expected=${videoMode.outW}x${videoMode.outH} crop=${info.left},${info.right},${info.top},${info.bottom} matches=$matches", true)
                if (!matches) {
                    stopDesktopVideo("Энкодер CarLife выдал ${info.visibleW}×${info.visibleH} вместо ${videoMode.outW}×${videoMode.outH}")
                    return
                }
                if (desktopSurfaceEnabled) desktopSurfaceCsdVerified = true
            } catch (e: Exception) {
                log("DEX geometry stage=carlife-encoder-sps unverified=${e.javaClass.simpleName}", true)
                stopDesktopVideo("Не удалось проверить размер H.264 CarLife")
                return
            }
        }
    }

    /** frame encoder -> CarLife. First Source Message = SPS+PPS+IDR (SPS/PPS glued), P to IDR discard. */
    @Synchronized
    override fun onEncoderFrame(data: ByteArray, key: Boolean) {
        if (desktopSurfaceEnabled && (!desktopSurfaceReady || !desktopSurfaceCsdVerified || desktopVideoError != null)) {
            encDropped++
            return
        }
        if (!outputEnabled || !carlifeStarted) {
            encDropped++
            return
        }
        if (desktopSurfaceEnabled) {
            lastAaFrameAt = clockMs()
            aaFrames++
        }
        val types = AnnexB.nalTypes(data)
        val idr = 5 in types
        if (!outGotIdr) {
            if (!idr) {
                encDropped++
                return
            }
            val payload = if (AnnexB.startsWithSps(data) && 8 in types) data else {
                val cfg = encCsd
                if (cfg == null) {
                    encDropped++
                    return
                }
                AnnexB.glue(cfg, data)
            }
            val takeover = active != Source.AA
            if (!(if (takeover) link.sendVideoTakeover(payload) else link.sendVideo(payload))) {
                encDropped++
                requestIdrOnOverflow()
                return
            }
            outGotIdr = true
            sent++
            idrSent++
            if (active != Source.AA) {
                active = Source.AA
                log("BRIDGE источник -> ${if (desktopSurfaceEnabled) "DeX Surface" else "AA"} (первый IDR ${payload.size} Б, nal=${AnnexB.nalTypes(payload)})", true)
                link.hidePlaceholder()
            } else {
                log("BRIDGE IDR энкодера -> CarLife ${payload.size} Б nal=${AnnexB.nalTypes(payload)}", true)
            }
            AaState.status = if (desktopSurfaceEnabled) "мост: DeX -> CarLife (${videoMode.outLabel}, одно кодирование)" else "мост: AA -> CarLife (перекодирование)"
            if (desktopSurfaceEnabled && !desktopSurfaceFirstFrame) {
                desktopSurfaceFirstFrame = true
                val generation = desktopSurfaceGeneration
                val listener = desktopSurfaceListener
                desktopSurfaceWorker().execute {
                    val valid = synchronized(this) {
                        generation != 0L && generation == desktopSurfaceGeneration && !desktopSurfaceStopped
                    }
                    if (valid) listener?.onFirstFrame(generation)
                }
            }
            if (huVw20082138 || huMobisNx4c) link.onEncodedKeyFrame(payload.size)
            return
        }
        if (!link.sendVideo(data)) {
            // frame is not accepted (the queue of CarLife is overcrowded): you can not go on P, until the next IDR everything is thrown away above
            outGotIdr = false
            encDropped++
            requestIdrOnOverflow()
            return
        }
        sent++
        if (idr) idrSent++
        AaState.bridgeSent = sent
        if (idr && (huVw20082138 || huMobisNx4c)) link.onEncodedKeyFrame(data.size)
    }

    /**
     * IDR at encoder after the failure of the queue CarLife - no more than once in 1.5 with (IdrThrottle). Otherwise, large IDR (30-160 KB)
     * themselves score a slow channel. Refusal - waiting for the already requested or the nearest natural IDR (interval 1 with).
     */
    private fun requestIdrOnOverflow() {
        val t = transcoder ?: return
        if (!idrThrottle.tryRequest(clockMs())) return
        overflowIdrRequests++
        t.requestSyncFrame()
    }

    /** bitrate encoder on the fly (adapted to drain head unit). false = no transcoder (passthrough or not yet created). */
    @Synchronized
    fun setEncoderBitrate(bps: Int): Boolean {
        val t = transcoder ?: return false
        t.setBitrate(bps)
        return true
    }

    /** fps target transcoder on the fly (FRAME_RATE_CHANGE from head unit). false = no transcoder (passthrough or not yet created). */
    @Synchronized
    fun setTargetFps(fps: Int): Boolean {
        val t = transcoder ?: return false
        t.setTargetFps(fps)
        return true
    }

    @Synchronized
    override fun onNeedAaKeyFrame() {
        gotIdr = false
        waitSince = clockMs()
        log("BRIDGE декодер потерял кадр: жду новый IDR от AA", true)
    }

    @Synchronized
    override fun onTranscodeError(msg: String) {
        if (desktopInputEnabled) {
            stopDesktopVideo("Ошибка энкодера CarLife: $msg")
            return
        }
        log("BRIDGE !!!!!! ошибка транскодера: $msg. Откат на passthrough", true)
        stopTranscoderAsync()
        gotIdr = false
        waitSince = clockMs()
    }

    /** Called under the core monitor; block frames before the connector observes the failure. */
    private fun stopDesktopVideo(message: String) {
        if (desktopVideoError != null) return
        desktopVideoError = message
        outputEnabled = false
        gotIdr = false
        stopTranscoderAsync()
        active = Source.PLACEHOLDER
        placeholderText = "DeX: $message"
        link.showPlaceholder(placeholderText)
        log("DEX video stopped: $message; passthrough disabled", true)
    }

    /** head unit asks for key frame (VIDEO_ENCODER_RESET, etc.). */
    @Synchronized
    fun onCarlifeKeyFrameRequest() {
        val t = transcoder
        if (t != null) {
            t.requestSyncFrame()
        } else if (carlifeStarted) {
            gotIdr = false
            waitSince = clockMs() - KEYFRAME_WAIT_MS
        }
        log("BRIDGE ГУ просит ключевой кадр (${if (t != null) "sync frame энкодера" else "ждём IDR от AA"})", true)
    }

    /** Once in 5 with: mode, fps output, transcoder statistics; in passthrough - duration of downtime AA (keepalive is not a slug). */
    private fun logStats() {
        if (!carlifeStarted) return
        val now = clockMs()
        if (lastStatsAt == 0L) {
            lastStatsAt = now
            lastStatsSent = sent
            lastStatsAaFrames = aaFrames
            return
        }
        if (now - lastStatsAt < 5000) return
        val sec = (now - lastStatsAt) / 1000.0
        val fps = (sent - lastStatsSent) / sec
        val aaFps = (aaFrames - lastStatsAaFrames) / sec
        val t = transcoder
        val idle = if (lastAaFrameAt > 0 && now - lastAaFrameAt > 1000) " простой AA ${(now - lastAaFrameAt) / 1000.0} с" else ""
        val target = if (t != null) "цель ${t.targetFps} fps" else "fps не регулируется"
        val idrReq = overflowIdrRequests - lastStatsIdrRequests
        val ls = link.videoStats()
        val channel = if (ls == null) "" else
            "; канал: ${if (t != null) "энкодер ${t.currentBitrate / 1000} кбит/с, " else ""}слив %.0f КиБ/с, бэклог ${ls.backlogMs} мс, очередь ${ls.queueFrames} кадр/${ls.queueBytes / 1024} КБ, запросов IDR из-за переполнения $idrReq за окно"
                .format(ls.drainBytesPerSec / 1024)
        val summary = if (desktopSurfaceEnabled)
            "BRIDGE видео[Surface, одно кодирование]: выход в CarLife %.1f fps ($target), отброшено энкодер/гейт $encDropped/$dropped".format(fps)
        else
            "BRIDGE видео[${if (t != null) "перекодирование" else "passthrough"}]: AA вход %.1f fps, выход в CarLife %.1f fps ($target), отброшено энкодер/гейт $encDropped/$dropped$idle".format(aaFps, fps)
        log(summary + channel + (if (t != null) "; ${t.statsLine()}" else ""), true)
        lastStatsAt = now
        lastStatsSent = sent
        lastStatsAaFrames = aaFrames
        lastStatsIdrRequests = overflowIdrRequests
    }

    companion object {
        const val DUP_DOWN_MS = 30L

        /** DOWN is a take, if the previous DOWN of the same point has not yet been released and came no earlier than [DUP_DOWN_MS] ago. */
        fun isDuplicateDown(held: Pair<Int, Int>?, sinceDownMs: Long, x: Int, y: Int): Boolean =
            held != null && held.first == x && held.second == y && sinceDownMs in 0..DUP_DOWN_MS

        /** CarlifeThemeState.themeModes (SkinDisplayModeManager in CarLife 8.6.7). */
        const val THEME_LIGHT = 0
        const val THEME_DARK = 1
        const val THEME_AUTO = 2

        fun themeName(mode: Int) = when (mode) {
            THEME_LIGHT -> "светлая"; THEME_DARK -> "тёмная"; THEME_AUTO -> "авто"; else -> "?"
        }

        /** action&255 TOUCH_ACTION, which go to AA (AA has the same PointerAction codes). */
        private val TOUCH_NAMES = mapOf(
            AapMessages.TOUCH_DOWN to "DOWN", AapMessages.TOUCH_UP to "UP", AapMessages.TOUCH_MOVE to "MOVE",
            AapMessages.TOUCH_POINTER_DOWN to "POINTER_DOWN", AapMessages.TOUCH_POINTER_UP to "POINTER_UP",
        )

        // New sound behavior flags (default enabled): the machine is disabled one at a time.
        /** MODULE_STATUS NAVI=1 before the first TTS_INIT, NAVI=0 when the sound stops. */
        @Volatile @JvmStatic var NAVI_STATUS_ENABLED = true

        /** Debounce TTS_STOP (1500 ms) and MEDIA control packet queue to TTS_STOP+300 ms. */
        @Volatile @JvmStatic var TTS_DEBOUNCE_ENABLED = true

        /** Voice session Assistant: MEDIA pause, MEDIA_DATA not forward, output via 500 ms. */
        @Volatile @JvmStatic var VOICE_SESSION_ENABLED = true

        /** MODULE_STATUS MUSIC(3) along with MEDIA INIT/PAUSE/STOP and response to MODULE_CONTROL (CarLifeSession). */
        @Volatile @JvmStatic var MODULE_MUSIC_ENABLED = true

        /** onAudioStop(MEDIA) sends MEDIA_PAUSE (as MD) instead of MEDIA_STOP; disabled = old behavior. */
        @Volatile @JvmStatic var MEDIA_PAUSE_ENABLED = true

        /**
         * MODULE_STATUS NAVI=1 the entire route (MetaRelay) without him instrument cluster Honda Vezel " The Book "Turn by turn waiting
         * for your smartphone". Desay G6S (Mazda CX-5) with NAVI=1 jams regular radio at the entrance to CarLife (Ivan's log)
         * 2026-10-12), there NAVI=1 only around TTS (huDesayG6S). Off = NAVI=1 only around TTS at all head unit.
         */
        @Volatile @JvmStatic var ROUTE_NAVI_STATUS_ENABLED = true

        /** head unit went to its source (MODULE_CONTROL MUSIC=0): AA on pause; START play is not sent. */
        @Volatile @JvmStatic var HU_MUSIC_FOLLOW_ENABLED = true

        /** Desay G6S: MUSIC=1 after our pause by MUSIC=0 - AA play (KEYCODE 126) and reply by MUSIC=1. */
        @Volatile @JvmStatic var HU_MUSIC_RESUME_ENABLED = true

        /** How much to answer MUSIC=1 on a pack of MUSIC=1 after our play, before MEDIA INIT has arrived. */
        const val HU_RESUME_ANSWER_MS = 3_000L

        /** Desktop: after our pause, the player still plays its tail - "plays again" no earlier than this. */
        const val DESKTOP_RESUME_GUARD_MS = 2_000L

        /** Desktop: "does the player" - binder-call, with closed MEDIA ask no more than once a second. */
        const val DESKTOP_PLAY_CHECK_MS = 1_000L

        /**
         * Mazda CX-5: AA itself runs the music through 5-9 with after BT_HFP_CONNECTION. While head unit on its source, such as play
         * (in the window after HFP, without input from head unit) pause does not remove, but again gets KEYCODE 127.
         */
        @Volatile @JvmStatic var BT_AUTOPLAY_GUARD_ENABLED = true

        /** wireless connection, VW BT 1420: START after PAUSE directly with IDR encoder (decoder alive), no waiting IDR from AA. */
        @Volatile @JvmStatic var AIR_WARM_RESUME_ENABLED = true

        const val BT_AUTOPLAY_WINDOW_MS = 20_000L
        const val USER_INPUT_WINDOW_MS = 3_000L
        const val HONDA_RESUME_AFTER_STOP_MS = 3_000L      // MUSIC=1 from Honda 20772104 after the pause of AA outside - no longer about it
        const val HONDA_RESUME_MIN_INTERVAL_MS = 5_000L    // no more often: if AA gets up again, do not fight it every 100 ms
        private const val REPAUSE_MIN_MS = 1_000L

        const val TTS_STOP_DELAY_MS = 1500L
        const val MEDIA_AFTER_TTS_MS = 300L
        const val VOICE_EXIT_DELAY_MS = 500L
        private const val TTS_DIAG_PACKETS = 3
        /**
         * TTS default silence pre-roll (Auto) on VW 20082120/20082122/20082134 (h222); 16 kHz mono 16 bit, chunks like Baidu.
         * 400 ms taken without measurements (the spike of the start of the hint on CNS3), the duration is selected by setting [TtsPreroll].
         */
        const val TTS_PREROLL_MS = 400
        /** h425 (24.10 18:34:09 log): NAVI=1 and TTS_INIT in one millisecond - the first session prompt is silent. 300 ms without measurements. */
        const val TTS_INIT_AFTER_NAVI_MS = 300L
        private const val TTS_BYTES_PER_SEC = 16000 * 2
        private const val TTS_PREROLL_CHUNK = 4096
        /** Auto-size speech packet on VW CNS2 Delphi (h425): assistant's stuttering response went away with MAX_CHUNK/10; AA gives speech on 2048 B. */
        private const val VOICE_CHUNK_DELPHI = 1024

        /** Android KEYCODE_SEARCH: in AA launches voice assistant. */
        const val KEYCODE_SEARCH = 84
        const val VOICE_KEY_DEBOUNCE_MS = 500L
        const val KEYCODE_MEDIA_PLAY = 126
        const val KEYCODE_MEDIA_PAUSE = 127
        const val KEYCODE_MEDIA_PLAY_PAUSE = 85
        const val KEYCODE_CALL = 5
        const val KEYCODE_ENDCALL = 6
        const val HARD_KEY_SEEK_SUB = 0x0F
        const val HARD_KEY_SEEK_ADD = 0x10
        /** VW CNS2 Delphi 20082114: forward/backward hold comes as keycode 0 (Dmitry, 24.10). */
        const val HARD_KEY_SEEK_HOLD = 0
        /** MEDIA_STOP with head unit; Delphi sends it itself as it leaves for its call. */
        const val HARD_KEY_MEDIA_STOP = 0x20
        const val HARD_KEY_PHONE_CALL = 0x02
        const val LAUNCH_PHONE_DEBOUNCE_MS = 1000L
        const val GEAR_REVERSE = 5
        /** h672: after exiting R key frame immediately and repeatedly - when head unit will lift CarLife is unknown. */
        val GEAR_IDR_DELAYS_MS = longArrayOf(0, 500, 1200)

        /**
         * CarLife hard key (HUJ/CommonParams) -> Android KeyEvent; all values are in AapMessages.KEYCODES_SUPPORTED.
         * Volume, HOME, MEDIA/RADIO/NAVI and numbers are not mapped: they are processed by head unit itself or there is no analogue.
         */
        val HARD_KEY_TO_ANDROID: Map<Int, Int> = mapOf(
            0x10 to 87,     // SEEK_ADD -> MEDIA_NEXT
            0x0F to 88,     // SEEK_SUB -> MEDIA_PREVIOUS
            0x1F to 126,    // MEDIA_START -> MEDIA_PLAY
            0x20 to 127,    // MEDIA_STOP -> MEDIA_PAUSE
            0x02 to 5,      // PHONE_CALL -> CALL
            0x03 to 6,      // PHONE_END -> ENDCALL
            0x0E to 4,      // BACK
            0x14 to 23,     // OK -> DPAD_CENTER
            0x15 to 21,     // MOVE_LEFT -> DPAD_LEFT
            0x16 to 22,     // MOVE_RIGHT -> DPAD_RIGHT
            0x17 to 19,     // MOVE_UP -> DPAD_UP
            0x18 to 20,     // MOVE_DOWN -> DPAD_DOWN
            0x21 to KEYCODE_SEARCH, // VR_START -> voice assistant
            0x1E to 3,      // MAIN -> HOME (application bar)
            0x09 to 209,    // MEDIA -> MUSIC (media player)
            0x0B to 65538,  // NAVI -> AA NAVIGATION (code from KEY_BINDING_REQUEST AA)
        )

        /** Turn the puck (Mazda Commander etc.) -> rotary AA: focus by lists, choice is OK (DPAD_CENTER). */
        val HARD_KEY_TO_ROTARY: Map<Int, Int> = mapOf(
            0x06 to 1,      // SELECTOR_NEXT -> Hourly
            0x07 to -1,     // SELECTOR_PREVIOUS -> anti-clockwise
        )

        const val TEXT_CONNECTING = "Подключаю Android Auto..."
        const val TEXT_START_SERVER = "Запустите сервер ГУ в Android Auto"
        const val TEXT_WAIT_FRAME = "Android Auto: жду кадр..."
        const val TEXT_AA_LOST = "Android Auto отключился, переподключаю..."
        const val DESAY_MANUFACTURER = "Desay SV Automotive"
        const val DESAY_G6S_MODEL = "G6S"
        const val DESAY_G6S_CHANNEL = "20622116"          // STATISTIC_INFO.channel to Mazda CX-5; Vezel to 20832125, other Mazda to 21520001
        /** STATISTIC_INFO.channel prefixes at Honda (CarLife 1.1.0, HU_INFO empty, BT "Honda HFT"): 2077xxxx (Civic 11, UR-V), 2083xxxx (Vezel etc.). */
        val HONDA_CHANNEL_PREFIXES = listOf("2077", "2083")
        /** Honda: NAVI=1 is younger than this and without arrows after it - the transition of 0->1 is still fresh, before the first SHOW do not rewind. */
        const val NAVI_REARM_FRESH_MS = 5000L    // First TURN_EVENT from AA After starting the route comes through ~ 2 C

        fun isHondaChannel(channel: String?) =
            channel != null && channel.length == 8 && HONDA_CHANNEL_PREFIXES.any { channel.startsWith(it) }
        const val HONDA_20772121_CHANNEL = "20772121"     // Honda Civic 11 2023 (China, h295): no bypasses, in tests - "another Honda without a flag"
        const val HONDA_20832125_CHANNEL = "20832125"     // Honda Vezel (head unit Egor)
        const val HONDA_20772104_CHANNEL = "20772104"     // Honda Civic 2021 (China, h588/h629): After rear asks MUSIC=1
        /** Mazda Connect 2 (China CarLife 1.0, HU_INFO from the covers, h238/h359): VIDEO_DATA 16163 B has passed, 16518 and 19104 B - B head unit She stopped reading USB. */
        const val MAZDA_20622111_CHANNEL = "20622111"
        const val MAZDA_20622111_MAX_VIDEO_PACKET = 15_000
        const val MAZDA_20622111_ART_BYTES = 12_000          // cover in MEDIA_INFO: the whole package should stay < 15000 B
        const val MAZDA_20622111_CEILING_BPS = 1_500_000  // IDR smaller in case head unit does not glue pieces
        const val KIA_20032131_CHANNEL = "20032131"       // Kia Seltos 2026 China, QTI sm6150_au madison (h672)
        const val VW_20082120_CHANNEL = "20082120"        // VW, protocol 1.0, versionName="RADIO" (h93)
        const val VW_CNS3_20082122_CHANNEL = "20082122"   // VW CNS3 (Tharu and others): vomits USB through ~ 5,6 C PAUSE without heartbeat (h222)
        const val VW_CNS3_20082134_CHANNEL = "20082134"   // VW CNS3 Tiguan L Pro: vomit CarLife using Wi-Fi through ~ 5,1-5,7 C PAUSE (h224)
        const val VW_CNS2_20082114_CHANNEL = "20082114"   // VW CNS2 Delphi (Tharu 2020): rips USB via 6-8 with PAUSE (h425)
        const val SKODA_CNS3_21290002_CHANNEL = "21290002" // Skoda Octavia Pro CNS3: rips USB through 5-6 with PAUSE and static AA (h625)
        const val VW_20082138_CHANNEL = "20082138"        // VW ID: HU_INFO manufacturer=model="VW BT 1538" (h196), "VW BT 4370" (ID. UNYX)
        const val VW_BT_1420_MANUFACTURER = "VW BT 1420"  // VW (Paul): HU_INFO manufacturer=model, channel 20082138
        const val VW_20082128_CHANNEL = "20082128"        // VW ID.6 Crozz (24.10.2026): HU_INFO manufacturer=model="VW BT 9491", same limit IDR
        const val VW_20082128_MANUFACTURER = "VW BT 9491"
        /** head unit VW ID with size limit IDR: BT-name (HU_INFO manufacturer) -> firmware channel (STATISTIC_INFO); pair required. */
        val VW_ID_IDR_LIMIT_HUS = mapOf(VW_20082128_MANUFACTURER to VW_20082128_CHANNEL)
        const val HYUNDAI_CN7_PRODUCT = "wp_cn7cpe_cn"    // HU_INFO.product Hyundai Elantra CN7 (h218): manufacturer QTI, model "sm6150_au for arm64"
        /** Hyundai Tucson NX4 (Mobis CCNC, h242): HU_INFO board or model = "CCNC" , a secondary sign - STATISTIC_INFO channel 20022188; by one manufacturer Mobis not include (others head unit Mobis). */
        const val MOBIS_BOARD = "CCNC"
        const val MOBIS_NX4C_CHANNEL = "20022188"
        const val MOBIS_FPS = 20                          // Baidu MediaFormatUtils: frame-rate encoder 20 for any INIT_DONE
        const val MOBIS_BITRATE = 3_840_000               // Baidu Recorder.getBitRate: Width > 1280
        const val PIONEER_MANUFACTURER = "Pioneer"        // HU_INFO Pioneer RM56E8B (Dongfeng DF6, h277): manufacturer="Pioneer" model="RM56E8B"
        const val PIONEER_RM56_MODEL = "RM56E8B"
        const val PIONEER_RM56_CEILING_BPS = 800_000      // 4-5 Mbit/s USB after 25-30 c, 2 Mbit / s - 195 c, 800 kbit / s - 1301 c; test1: 1 Mbit/s + music 32 kHz ( ~ 1 Mbit/s PCM) vulture for 23-140 C
        const val PIONEER_RM56_MAX_FPS = 10
        const val MEDIA_RATE = 48000
        const val MEDIA_RATE_CN7 = 32000
        const val MEDIA_RATE_PIONEER_RM56 = 22050         // h277: Music 32 kHz added to USB ~1 Mbit/s, 22,05 kHz - half as much
        const val KEYFRAME_WAIT_MS = 1500L
        const val SPS_CROP_KICK_MS = 2000L
        const val KEYFRAME_RETRY_MS = 4000L

        /** Yours IDR: No more than that (the reception buffer) C++-head unit CarLife 600 KiB, docs/carlife-protocol.md §6). */
        const val SPLICE_MAX_BYTES = 560_000
        const val SPLICE_WAIT_MS = 300L          // picture from decoder no longer
        const val SPLICE_QUIET_MS = 200L         // pause in frame AA, after which IDR is assembled in the background, without holding frame
        const val SPLICE_HOLD_MAX_MS = 250L      // longer than frame AA do not hold: let go, IDR is thrown away on readiness
        const val SPLICE_SLICES = 4              // Slices in compressed IDR: are assembled in parallel
        const val SPLICE_STATS_MS = 30_000L
        const val SPLICE_MAX_MISSES = 3          // timeouts of pictures in a row - pause [SPLICE_BACKOFF_MS] and a new attempt
        const val SPLICE_BACKOFF_MS = 5000L      // IDR after [SPLICE_MAX_MISSES] passes (spliceWorks in it false)
        const val SPLICE_STALL_MS = 3000L        // decoder reference images do not move when running frame AA longer - restart
        const val SPLICE_STALL_IDLE_MS = 1000L   // AA stays silent longer - frame no, decoder is not up
        const val SPLICE_KICK_GRACE_MS = 1500L   // h529-Repeat IDR from AA is postponed for so long if your IDR is about to go away
        const val SPLICE_RETRY_MS = 1000L        // pause between attempts (without IDR on head unit, after failure, skip, timeout)

        private const val NEVER = Long.MIN_VALUE / 4
    }
}

/**
 * Holds the AA-bridge session: connects to the head unit server, when failed (connection refused) repeats times in
 * 3 c, at the end of the session, reports to the kernel and reconnects. The head unit server turns on and restarts the user.
 */
class AaConnector(
    private val core: AaBridgeCore,
    private val creds: AapCredentials,
    private val log: (String, Boolean) -> Unit,
    private val options: () -> AapOptions = { AapOptions() },
    private val retryMs: Long = 3000,
    private val failures: ServerFailures = AaState.serverFailures,
    private val sessionFactory: (AaVideoSink, AapOptions, (String, Boolean) -> Unit, (String, Boolean) -> Unit) -> AapSession =
        { sink, o, lg, end -> AapSession(creds, sink, o, lg, onEnd = end) },
) : AaLifecycle {
    @Volatile private var stopping = false
    @Volatile private var current: AapSession? = null
    private var thread: Thread? = null
    @Volatile var attempts = 0
        private set

    override fun start() {
        val t = Thread({ loop() }, "aa-connector")
        t.isDaemon = true
        thread = t
        t.start()
    }

    /** Correctly close AA (ByeBye, EOF, close) and complete thread; onDone is called from separate thread. */
    fun stop() = stop {}

    override fun stop(onDone: () -> Unit) {
        stopping = true
        current?.requestStop("мост остановлен")
        val t = thread
        Thread({
            try {
                t?.join(10_000)
            } catch (_: InterruptedException) {
            }
            onDone()
        }, "aa-connector-stop").start()
    }

    /** Contract connections closed by AA before the session is set (connect+EOF). */
    @Volatile var bareEofs = 0
        private set

    private fun bareEofBackoffMs(n: Int): Long =
        if (n <= 0) retryMs else minOf(retryMs shl minOf(n - 1, 4), BARE_EOF_BACKOFF_MAX_MS).coerceAtLeast(retryMs)

    private fun loop() {
        failures.reset()      // The refusals of the previous trip are not carried forward
        try {
            connectLoop()
        } finally {
            failures.reset()
        }
    }

    private fun connectLoop() {
        var unavailableLogged = false
        // sound solution depends on FEATURE_CONFIG head unit (BT-mode): wait for it to 2.5 with before the first connection
        if (core.usbAudioEnabled && core.expectFeatureConfig) {
            var waited = 0
            while (!core.featureConfigSeen && waited < 2500 && !stopping) {
                Thread.sleep(50)
                waited += 50
            }
            log("BRIDGE FEATURE_CONFIG ${if (core.featureConfigSeen) "получен" else "не пришёл за $waited мс"}, звук по USB: ${core.audioViaUsb}", true)
        }
        while (!stopping) {
            // manual server trial (AaServerCheck, button in "For Developers") should not hold the 5277 server when we connect
            AaServerCheck.beforeBridgeConnect()
            if (stopping) break      // Stop could come during the waiting time: you can no longer connect, the session on handshake will not be interrupted on a regular basis
            val ended = CountDownLatch(1)
            var endReason = ""
            core.onAaConnecting()
            attempts++
            val s = sessionFactory(core, options(), log) { reason, _ ->
                endReason = reason
                ended.countDown()
            }
            current = s
            core.peer = s
            s.start()
            var establishedNoted = false
            while (!ended.await(500, TimeUnit.MILLISECONDS)) {
                if (stopping) s.requestStop("мост остановлен")
                core.tick()
                // passive diagnostics for the main screen: the session is established - the server is alive, previous failures do not count
                if (s.established) {
                    if (!establishedNoted) failures.onEstablished()
                    establishedNoted = true
                } else if (s.connected) {
                    failures.onTcpAccepted()
                }
            }
            core.peer = null
            current = null
            when {
                s.established -> {
                    if (!establishedNoted) failures.onEstablished()
                    // session ended: next seconds port failure is the norm, AA raises it again
                    failures.onSessionEnded()
                }
                !s.connected && endReason.startsWith(REFUSED_REASON) -> failures.onRefused()
                s.connected && endReason.startsWith(AapSession.NO_VERSION_RESPONSE_REASON) -> failures.onNoVersionResponse()
                else -> failures.reset()
            }
            // on head unit "Start the server head unit" only when it does not really respond; short failure after ByeBye - "Connect"
            if (failures.needsStart()) core.showStatus(AaBridgeCore.TEXT_START_SERVER)
            if (!s.connected) {
                if (!unavailableLogged) {
                    log("BRIDGE сервер ГУ Android Auto недоступен (connection refused), повторяю каждые ${retryMs / 1000.0} с", true)
                    unavailableLogged = true
                }
                AaState.status = "мост: жду сервер ГУ AA"
            } else {
                unavailableLogged = false
                log("BRIDGE AA-сессия завершена (established=${s.established}, video=${s.videoStarted})", true)
                AaState.status = "мост: AA отключился"
                core.onAaEnded(s.established)
                if (s.established) {
                    bareEofs = 0
                } else {
                    // The server took TCP and immediately closed (h224): it is alive, frequent repetitions only throw AA on the screen. We wait longer.
                    bareEofs++
                }
            }
            var left = if (s.connected && !s.established) bareEofBackoffMs(bareEofs) else retryMs
            while (left > 0 && !stopping) {
                Thread.sleep(minOf(left, 100))
                left -= 100
            }
        }
    }

    companion object {
        /** Pause ceiling between connections after bare EOF: 3, 6, 12, 24, 30 s. */
        const val BARE_EOF_BACKOFF_MAX_MS = 30_000L

        /** The reason for the end of the session at ECONNREFUSED (other failures of connect - timeout, EPERM - server failure is not considered). */
        private const val REFUSED_REASON = AapSession.UNAVAILABLE_REASON + "ConnectException"
    }
}
