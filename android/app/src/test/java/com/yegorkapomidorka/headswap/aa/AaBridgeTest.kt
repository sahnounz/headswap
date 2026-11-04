package com.yegorkapomidorka.headswap.aa

import com.yegorkapomidorka.headswap.AnnexB
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import com.yegorkapomidorka.headswap.aa.AapSessionTest.Phone as FakePhone

class FakeLink : CarlifeLink {
    val sent = CopyOnWriteArrayList<ByteArray>()
    val texts = CopyOnWriteArrayList<String>()
    @Volatile var hides = 0
    @Volatile var accept = true
    override fun sendVideo(payload: ByteArray): Boolean {
        if (!accept) return false
        sent.add(payload)
        return true
    }

    override fun showPlaceholder(text: String) {
        texts.add(text)
    }

    override fun hidePlaceholder() {
        hides++
    }

    @Volatile var takeovers = 0
    override fun sendVideoTakeover(payload: ByteArray): Boolean {
        val ok = sendVideo(payload)
        if (ok) takeovers++
        return ok
    }

    class Audio(val chan: Int, val type: Int, val payload: ByteArray)

    val audio = CopyOnWriteArrayList<Audio>()
    override fun sendAudio(channel: Int, type: Int, payload: ByteArray): Boolean {
        if (!accept) return false
        audio.add(Audio(channel, type, payload))
        events.add("A${channel}:0x%x".format(type))
        return true
    }

    val modules = CopyOnWriteArrayList<Pair<Int, Int>>()
    /** All audio-control events in send order: "A<channel>:0x<type>" and "M<module>=<status>". */
    val events = CopyOnWriteArrayList<String>()
    override fun sendModuleStatus(module: Int, status: Int): Boolean {
        if (!accept) return false
        modules.add(module to status)
        events.add("M$module=$status")
        return true
    }

    @Volatile var desktops = 0
    override fun goToDesktop() {
        desktops++
    }

    @Volatile var mediaDrops = 0
    override fun dropQueuedMedia(): Int {
        mediaDrops++
        return 0
    }
}

class FakePeer : AaPeer {
    /** One-finger projection (action, x, y) of the first pointer - for old checks. */
    val touches = CopyOnWriteArrayList<Triple<Int, Int, Int>>()

    /** Full form: (action, action_index, fingers). */
    val events = CopyOnWriteArrayList<Triple<Int, Int, List<TouchPoint>>>()
    @Volatile var keyframes = 0
    override fun sendTouch(action: Int, actionIndex: Int, points: List<TouchPoint>) {
        events.add(Triple(action, actionIndex, points))
        touches.add(Triple(action, points[0].x, points[0].y))
    }

    override fun requestKeyFrame() {
        keyframes++
    }

    val keys = CopyOnWriteArrayList<Int>()
    override fun sendKey(keycode: Int) {
        keys.add(keycode)
    }

    val rotary = CopyOnWriteArrayList<Int>()
    override fun sendRotary(delta: Int) {
        rotary.add(delta)
    }

    @Volatile var natives = 0
    override fun sendVideoFocusNative() {
        natives++
    }

    val nights = CopyOnWriteArrayList<Boolean>()
    override fun sendNightMode(night: Boolean) {
        nights.add(night)
    }
}

class AaBridgeCoreTest {
    private val sps = Golden.hex("00000001 67 42c01f da0140")
    private val pps = Golden.hex("00000001 68 ce3c80")
    private val cfg = sps + pps
    private val idr = Golden.hex("00000001 65") + ByteArray(100) { (it + 1).toByte() }
    private val p1 = Golden.hex("00000001 41") + ByteArray(30) { 0x22 }
    private var now = 0L
    private val link = FakeLink()
    private val logs = CopyOnWriteArrayList<String>()
    private val core = AaBridgeCore({ l, _ -> logs.add(l) }, { now }).also { it.attach(link) }

    private fun types(b: ByteArray) = AnnexB.nalTypes(b)

    @Test
    fun hyundaiCn7ForcesTranscode() {
        // h638: on CN7 straight passthrough blinks and goes out
        core.transcodeEnabled = false
        core.onHuInfo("QTI", "sm6150_au for arm64", "other")
        assertFalse(core.huHyundaiCn7)
        assertFalse(core.transcodeEnabled)
        core.onHuInfo("QTI", "sm6150_au for arm64", AaBridgeCore.HYUNDAI_CN7_PRODUCT)
        assertTrue(core.huHyundaiCn7)
        assertTrue(core.transcodeEnabled)
        assertEquals(1, logs.count { it.contains("Hyundai CN7: passthrough отключаю") })
    }

    @Test
    fun mobisFlagFromHuInfoOrChannelForcesTranscode() {
        // h242: Tucson (Mobis CCNC) is recognized by HU_INFO, a secondary feature is the 20022188 channel
        assertFalse(core.huMobisNx4c)
        core.onHuInfo("Samsung", "SM-A", "a", "exynos")
        core.onHuStatistic("20082122")
        assertFalse(core.huMobisNx4c)
        core.onHuInfo("Mobis", "AVN5", "other", "OTHER")      // other head unit Mobis by manufacturer does not include flag
        assertFalse(core.huMobisNx4c)
        core.transcodeEnabled = false
        core.onHuInfo("Mobis", "CCNC", "NX4C", "CCNC")
        assertTrue(core.huMobisNx4c)
        assertTrue(core.transcodeEnabled)
        assertEquals(1, logs.count { it == "MOBIS CCNC: режим Baidu включён" })
        core.onHuStatistic("20022188")                        // does not write the line again
        assertEquals(1, logs.count { it == "MOBIS CCNC: режим Baidu включён" })
        core.attach(link)
        assertFalse(core.huMobisNx4c)
        core.onHuStatistic("20022188")
        assertTrue(core.huMobisNx4c)
        core.attach(link)
        core.onHuInfo(null, null, null, "CCNC")               // only board
        assertTrue(core.huMobisNx4c)
    }

    @Test
    fun huThemeDrivesAaNightModeAndSurvivesPeerReconnect() {
        val p = FakePeer()
        core.peer = p
        assertEquals(listOf(false), p.nights.toList())        // up to CHANGE_MD_THEME - day
        core.onCarlifeTheme(AaBridgeCore.THEME_DARK)
        core.onCarlifeTheme(AaBridgeCore.THEME_AUTO)
        core.onCarlifeTheme(null)                              // without field - do not touch the topic
        core.onCarlifeTheme(AaBridgeCore.THEME_DARK)
        assertEquals(listOf(false, true, false, true), p.nights.toList())
        assertEquals(AaBridgeCore.THEME_DARK, core.huTheme)
        val p2 = FakePeer()
        core.peer = p2                                         // new AA-session gets current theme directly
        assertEquals(listOf(true), p2.nights.toList())
    }

    @Test
    fun startsWithPlaceholderAndHidesItOnFirstIdrGluedWithConfig() {
        assertEquals(listOf(AaBridgeCore.TEXT_CONNECTING), link.texts.toList())
        assertEquals(AaBridgeCore.Source.PLACEHOLDER, core.activeSource)
        core.onCarlifeStart()
        core.onCodecConfig(cfg)
        core.onFrame(idr, 1)
        assertEquals(1, link.sent.size)
        assertArrayEquals(cfg + idr, link.sent[0])            // SPS+PPS+IDR in one message
        assertEquals(listOf(7, 8, 5), types(link.sent[0]))
        assertEquals(1, link.hides)
        assertEquals(1, link.takeovers)                       // AA-IDR frame stub
        assertEquals(AaBridgeCore.Source.AA, core.activeSource)
    }

    @Test
    fun dropsPFramesUntilIdrThenPassesThem() {
        core.onCarlifeStart()
        core.onCodecConfig(cfg)
        core.onFrame(p1, 1)
        core.onFrame(p1, 2)
        assertEquals(0, link.sent.size)
        assertEquals(2L, core.dropped)
        assertEquals(0, link.hides)                           // while IDR was not, stub remains
        core.onFrame(idr, 3)
        core.onFrame(p1, 4)
        assertEquals(2, link.sent.size)
        assertArrayEquals(p1, link.sent[1])
        assertEquals(2L, core.dropped)
    }

    @Test
    fun idrWithInlineSpsPpsIsNotGluedTwice() {
        core.onCarlifeStart()
        core.onCodecConfig(cfg)
        core.onFrame(cfg + idr, 1)
        assertArrayEquals(cfg + idr, link.sent[0])
        assertEquals(listOf(7, 8, 5), types(link.sent[0]))
    }

    @Test
    fun threeByteStartCodesAreNormalizedToFour() {
        val cfg3 = Golden.hex("000001 67 42c01f da0140 000001 68 ce3c80")
        val idr3 = Golden.hex("000001 65 aabbcc")
        val p3 = Golden.hex("000001 41 11")
        core.onCarlifeStart()
        core.onCodecConfig(cfg3)
        core.onFrame(idr3, 1)
        core.onFrame(p3, 2)
        assertArrayEquals(cfg + Golden.hex("00000001 65 aabbcc"), link.sent[0])
        assertArrayEquals(Golden.hex("00000001 41 11"), link.sent[1])
    }

    @Test
    fun idrWithoutAnyConfigIsDropped() {
        core.onCarlifeStart()
        core.onFrame(idr, 1)
        assertEquals(0, link.sent.size)
        assertEquals(AaBridgeCore.Source.PLACEHOLDER, core.activeSource)
    }

    @Test
    fun nothingGoesBeforeCarlifeStart() {
        core.onCodecConfig(cfg)
        core.onFrame(idr, 1)
        assertEquals(0, link.sent.size)
        assertEquals(1L, core.dropped)
    }

    @Test
    fun carlifeStartResetsGateAndRequestsKeyframeWhenAaIsAlreadyStreaming() {
        val peer = FakePeer()
        core.peer = peer
        core.onCarlifeStart()
        core.onCodecConfig(cfg)
        core.onFrame(idr, 1)                                  // gate
        core.onFrame(p1, 2)
        assertEquals(2, link.sent.size)
        now = 10_000
        core.onCarlifeStart()                                 // head unit relaunched video: need a new IDR
        core.onFrame(p1, 3)                                   // P to IDR
        assertEquals(2, link.sent.size)
        core.tick()
        assertEquals("ключевой кадр сразу: AA уже играет", 1, peer.keyframes)
        core.tick()
        assertEquals("не чаще раза в 4 с", 1, peer.keyframes)
        now += 4000
        core.tick()
        assertEquals(2, peer.keyframes)
        core.onFrame(idr, 4)                                  // after IDR requests are terminated
        assertArrayEquals(cfg + idr, link.sent[2])
        now += AaBridgeCore.SPS_CROP_KICK_MS + 10_000
        core.tick()
        assertEquals("повторный кадр h529 был на первом старте сессии, после второго START его нет", 2, peer.keyframes)
        assertEquals(0, logs.count { it.contains("повторный ключевой кадр") })
    }

    @Test
    fun noRepeatedKeyframeAfterPauseStartAndNewFirstIdr() {
        val peer = FakePeer()
        core.peer = peer
        core.onCarlifeStart()
        core.onCodecConfig(cfg)
        core.onFrame(idr, 1)                                  // First IDR Session in now=0
        now = AaBridgeCore.SPS_CROP_KICK_MS
        core.tick()
        assertEquals("повторный кадр на первом старте", 1, peer.keyframes)
        now = 10_000
        core.onCarlifePause()
        core.onCarlifeStart()                                 // NATIVE -> PROJECTED cycle from START itself
        assertEquals("START после PAUSE: цикл от START", 2, peer.keyframes)
        core.onFrame(idr, 2)                                  // new first IDR after START
        now += AaBridgeCore.SPS_CROP_KICK_MS + 10_000
        core.tick()
        assertEquals("повторного кадра после PAUSE/START нет", 2, peer.keyframes)
        assertEquals(1, logs.count { it.contains("повторный ключевой кадр") })
    }

    @Test
    fun repeatedKeyframeAgainOnNewCarlifeSession() {
        val peer = FakePeer()
        core.peer = peer
        core.onCarlifeStart()
        core.onCodecConfig(cfg)
        core.onFrame(idr, 1)
        now = AaBridgeCore.SPS_CROP_KICK_MS
        core.tick()
        assertEquals(1, peer.keyframes)
        now = 20_000
        core.attach(FakeLink())                               // USB is back with a live AA: new CarLife-session
        core.onCarlifeStart()
        assertEquals("START новой сессии: цикл", 2, peer.keyframes)
        core.onFrame(idr, 2)
        now += AaBridgeCore.SPS_CROP_KICK_MS - 1
        core.tick()
        assertEquals("раньше 2000 мс повторного кадра нет", 2, peer.keyframes)
        now += 1
        core.tick()
        assertEquals("на новой сессии повторный кадр снова есть", 3, peer.keyframes)
        now += 10_000
        core.tick()
        assertEquals("ровно один", 3, peer.keyframes)
        assertEquals(2, logs.count { it.contains("повторный ключевой кадр") })
    }

    @Test
    fun repeatedKeyframe2sAfterFirstIdrWithoutCrop() {
        val peer = FakePeer()
        core.peer = peer
        core.onCarlifeStart()
        core.onCodecConfig(cfg)
        core.onFrame(idr, 1)                                  // passthrough without SPS-crop (R1080), IDR in now=0
        now = AaBridgeCore.SPS_CROP_KICK_MS - 1
        core.tick()
        assertEquals("раньше 2000 мс повторного кадра нет", 0, peer.keyframes)
        assertTrue(logs.none { it.contains("повторный ключевой кадр") })
        now = AaBridgeCore.SPS_CROP_KICK_MS
        core.tick()
        assertEquals(1, peer.keyframes)
        assertEquals(1, logs.count { it.contains("повторный ключевой кадр") })
        now += 10_000
        core.tick()
        assertEquals("одноразовый", 1, peer.keyframes)
        assertEquals(1, logs.count { it.contains("повторный ключевой кадр") })
    }

    @Test
    fun keyframeRequestWaitsForDelayWhenAaVideoAppearsAfterStart() {
        val peer = FakePeer()
        core.peer = peer
        core.onCarlifeStart()
        now = 1000
        core.onCodecConfig(cfg)                               // AA video started, but IDR no
        core.tick()                                           // Countdown to Expectations
        now = 2000
        core.tick()
        assertEquals(0, peer.keyframes)                       // 1 with < 1.5 with
        now = 2600
        core.tick()
        assertEquals(1, peer.keyframes)
    }

    @Test
    fun touchMapping() {
        val peer = FakePeer()
        core.peer = peer
        core.onCarlifeTouch(0, 40, 675)
        core.onCarlifeTouch(2, 41, 676)
        core.onCarlifeTouch(1, 41, 676)
        core.onCarlifeTouch(3, 1, 1)                          // no analogue
        assertEquals(listOf(Triple(0, 40, 675), Triple(2, 41, 676), Triple(1, 41, 676)), peer.touches.toList())
        core.peer = null
        core.onCarlifeTouch(0, 5, 5)                          // AA not connected: Ignoring without exception
        assertEquals(3, peer.touches.size)
        assertTrue(logs.any { it.contains("DOWN 40,675") })
    }

    @Test
    fun desktopCancelReleasesBothPointersAndStartsFreshGesture() {
        val peer = FakePeer()
        core.peer = peer
        core.desktopInputEnabled = true
        core.onCarlifeTouch(0, 100, 100)
        core.onCarlifeTouch(261, 100, 100, 300, 300)
        core.onCarlifeTouch(3, 0, 0)
        assertEquals(Triple(3, 0, pts(0, 100, 100, 1, 300, 300)), peer.events.last())
        core.onCarlifeTouch(3, 0, 0) // A second cancellation must not synthesize a contact.
        assertEquals(3, peer.events.size)
        core.onCarlifeTouch(0, 50, 60)
        core.onCarlifeTouch(1, 50, 60)
        assertEquals(Triple(0, 0, pts(0, 50, 60)), peer.events[3])
        assertEquals(Triple(1, 0, pts(0, 50, 60)), peer.events[4])
    }

    @Test
    fun desktopDetachCancelsContactsWithoutReplayingBufferedMoves() {
        val peer = FakePeer()
        core.peer = peer
        core.desktopInputEnabled = true
        core.touchDecimationProvider = { TouchDecimation.X2 }
        core.onCarlifeTouch(0, 10, 10)
        core.onCarlifeTouch(2, 11, 11)
        core.onCarlifeTouch(2, 12, 12) // Buffered, not sent to the desktop yet.
        core.onCarlifeClosed(FakeLink()) // Stale session must not cancel the current gesture.
        assertEquals(2, peer.events.size)
        core.onCarlifeClosed(link)
        assertEquals(Triple(3, 0, pts(0, 11, 11)), peer.events.last())
        core.attach(FakeLink())
        core.onCarlifeTouch(0, 20, 20)
        assertEquals(listOf(0, 2, 3, 0), peer.events.map { it.first })
        assertEquals(Triple(0, 0, pts(0, 20, 20)), peer.events.last())
    }

    @Test
    fun desktopCancelDropsPendingMovePacerTimer() {
        val timers = FakeTimers()
        val c = AaBridgeCore({ _, _ -> }, { timers.now }, audioSchedule = timers::schedule).also { it.attach(link) }
        val peer = FakePeer()
        c.peer = peer
        c.desktopInputEnabled = true
        c.onHuStatistic("20082114") // Delphi smoothing queues the second move.
        c.onCarlifeTouch(0, 10, 10)
        c.onCarlifeTouch(2, 11, 11)
        c.onCarlifeTouch(2, 12, 12)
        c.onCarlifeTouch(3, 0, 0)
        timers.advance(TouchBurstPacer.DEFAULT_INTERVAL_MS * 2)
        assertEquals(listOf(0, 2, 3), peer.events.map { it.first })
    }

    @Test
    fun vwCns2DelphiSpreadsMoveBurst() {
        val timers = FakeTimers()
        val c = AaBridgeCore({ l, _ -> logs.add(l) }, { timers.now }, audioSchedule = timers::schedule).also { it.attach(link) }
        val peer = FakePeer()
        c.peer = peer
        c.onHuStatistic("20082114")                           // h425: head unit Sucks MOVE ~190 ms and sends a pack
        c.onCarlifeTouch(0, 10, 10)
        c.onCarlifeTouch(2, 11, 11)                           // the first MOVE pack leaves immediately
        c.onCarlifeTouch(2, 12, 12)
        c.onCarlifeTouch(2, 13, 13)
        c.onCarlifeTouch(2, 14, 14)
        assertEquals(listOf(Triple(0, 10, 10), Triple(2, 11, 11)), peer.touches.toList())
        timers.advance(TouchBurstPacer.DEFAULT_INTERVAL_MS / 4)  // pack of 4: step = interval / 4
        assertEquals(Triple(2, 12, 12), peer.touches.last())
        assertEquals(3, peer.touches.size)                    // Others are still in line.
        c.onCarlifeTouch(1, 14, 14)                           // UP: first turn, then UP
        assertEquals(
            listOf(Triple(0, 10, 10), Triple(2, 11, 11), Triple(2, 12, 12), Triple(2, 13, 13), Triple(2, 14, 14), Triple(1, 14, 14)),
            peer.touches.toList(),
        )
    }

    @Test
    fun touchOnOtherHuDispatchesMovesImmediately() {
        val peer = FakePeer()
        core.peer = peer
        core.onCarlifeTouch(0, 10, 10)
        core.onCarlifeTouch(2, 11, 11)
        core.onCarlifeTouch(2, 12, 12)
        assertEquals(3, peer.touches.size)
    }

    @Test
    fun touchDecimationX2SkipsEverySecondMove() {
        val peer = FakePeer()
        core.peer = peer
        core.touchDecimationProvider = { TouchDecimation.X2 }
        core.onCarlifeTouch(0, 10, 10)
        for (y in 11..14) core.onCarlifeTouch(2, y, y)
        core.onCarlifeTouch(1, 14, 14)
        assertEquals(
            listOf(Triple(0, 10, 10), Triple(2, 11, 11), Triple(2, 13, 13), Triple(2, 14, 14), Triple(1, 14, 14)),
            peer.touches.toList(),
        )
        assertTrue(logs.any { it.contains("прореживание MOVE ×2") })
    }

    private fun pts(vararg p: Int) = p.toList().chunked(3).map { TouchPoint(it[0], it[1], it[2]) }

    @Test
    fun pinchSecondFingerUpFirst() {
        val peer = FakePeer()
        core.peer = peer
        core.onCarlifeTouch(0, 100, 100)
        core.onCarlifeTouch(5 or (1 shl 8), 100, 100, 300, 300)        // 261
        core.onCarlifeTouch(2, 90, 90, 310, 310)
        core.onCarlifeTouch(2, 85, 85)                                 // px/py = 0: the second finger in the same place
        core.onCarlifeTouch(2, 80, 80, 320, 320)
        core.onCarlifeTouch(6 or (1 shl 8), 80, 80, 320, 320)          // 262
        core.onCarlifeTouch(2, 81, 81)
        core.onCarlifeTouch(1, 81, 81)
        assertEquals(
            listOf(
                Triple(0, 0, pts(0, 100, 100)),
                Triple(5, 1, pts(0, 100, 100, 1, 300, 300)),
                Triple(2, 0, pts(0, 90, 90, 1, 310, 310)),
                Triple(2, 0, pts(0, 85, 85, 1, 310, 310)),
                Triple(2, 0, pts(0, 80, 80, 1, 320, 320)),
                Triple(6, 1, pts(0, 80, 80, 1, 320, 320)),
                Triple(2, 0, pts(0, 81, 81)),
                Triple(1, 0, pts(0, 81, 81)),
            ),
            peer.events.toList(),
        )
        assertTrue(logs.any { it.contains("POINTER_DOWN[1] пальцев 2") })
    }

    @Test
    fun pinchFirstFingerUpKeepsId() {
        val peer = FakePeer()
        core.peer = peer
        core.onCarlifeTouch(0, 10, 10)
        core.onCarlifeTouch(261, 10, 10, 50, 50)
        core.onCarlifeTouch(6, 12, 12, 52, 52)                         // raised index finger 0
        core.onCarlifeTouch(2, 55, 55)                                 // The remaining finger is now in x, y, id 1
        core.onCarlifeTouch(1, 56, 56)
        core.onCarlifeTouch(0, 7, 7)                                   // new gesture: again id 0
        core.onCarlifeTouch(1, 7, 7)
        assertEquals(
            listOf(
                Triple(0, 0, pts(0, 10, 10)),
                Triple(5, 1, pts(0, 10, 10, 1, 50, 50)),
                Triple(6, 0, pts(0, 12, 12, 1, 52, 52)),
                Triple(2, 0, pts(1, 55, 55)),
                Triple(1, 0, pts(1, 56, 56)),
                Triple(0, 0, pts(0, 7, 7)),
                Triple(1, 0, pts(0, 7, 7)),
            ),
            peer.events.toList(),
        )
    }

    @Test
    fun pinchLostPointerUpAndThirdFinger() {
        val peer = FakePeer()
        core.peer = peer
        core.onCarlifeTouch(0, 10, 10)
        core.onCarlifeTouch(261, 10, 10)                               // without pointer-fields: second finger in x,y (as Baidu)
        core.onCarlifeTouch(5 or (2 shl 8), 10, 10, 20, 20)            // Third finger: do not pass
        core.onCarlifeTouch(1, 11, 11)                                 // POINTER_UP lost: first shoot the second
        assertEquals(
            listOf(
                Triple(0, 0, pts(0, 10, 10)),
                Triple(5, 1, pts(0, 10, 10, 1, 10, 10)),
                Triple(6, 1, pts(0, 10, 10, 1, 10, 10)),
                Triple(1, 0, pts(0, 11, 11)),
            ),
            peer.events.toList(),
        )
    }

    @Test
    fun hardKeyMapping() {
        val peer = FakePeer()
        core.peer = peer
        core.onCarlifeHardKey(16)                             // SEEK_ADD
        core.onCarlifeHardKey(15)                             // SEEK_SUB
        core.onCarlifeHardKey(0x1F)                           // MEDIA_START
        core.onCarlifeHardKey(0x2F)                           // DEL: has no analogue
        assertEquals(listOf(87, 88, 126), peer.keys.toList())
        assertTrue(logs.any { it.contains("руль keycode=16 -> AA KEYCODE 87") })
        core.peer = null
        core.onCarlifeHardKey(16)                             // AA not connected: Ignoring without exception
        assertEquals(3, peer.keys.size)
        assertTrue(AaBridgeCore.HARD_KEY_TO_ANDROID.values.all { it in AapMessages.KEYCODES_SUPPORTED })
    }

    @Test
    fun desktopHomeMappingIsOptInAndKeepsMainAndBackKeys() {
        val peer = FakePeer()
        core.peer = peer
        core.onCarlifeHardKey(0x01) // CarLife HOME belongs to the HU in AA mode.
        assertTrue(peer.keys.isEmpty())
        core.onCarlifeHardKey(0x1E) // MAIN already maps to Android HOME.
        assertEquals(listOf(3), peer.keys.toList())

        core.mapHomeKey = true
        core.onCarlifeHardKey(0x01)
        core.onCarlifeHardKey(0x0E)
        assertEquals(listOf(3, 3, 4), peer.keys.toList())

        core.mapHomeKey = false
        core.onCarlifeHardKey(0x01)
        assertEquals(listOf(3, 3, 4), peer.keys.toList())
        core.mapHomeKey = true
        core.peer = null
        core.onCarlifeHardKey(0x01) // Disconnected desktop must not crash or queue a stale key.
        assertEquals(listOf(3, 3, 4), peer.keys.toList())
    }

    @Test
    fun launchPhoneOnlyOnKia20032131() {
        val peer = FakePeer()
        core.peer = peer
        core.onCarlifeLaunchPhone()                           // Alien head unit: Log Only
        assertTrue(peer.keys.isEmpty())
        core.onHuStatistic("20032131")                        // h672 Kia Seltos
        core.onCarlifeLaunchPhone()
        now += 200
        core.onCarlifeLaunchPhone()                           // Second of the pair: swallowing
        now += 5000
        core.onCarlifeLaunchPhone()                           // Next pressing
        assertEquals(listOf(5, 5), peer.keys.toList())
        core.attach(link)
        assertTrue("новая сессия - флаг заново", !core.huKia20032131)
    }

    @Test
    fun keyFrameAfterReverseOnlyOnKia20032131() {
        assertTrue("чужое ГУ", !core.onHuGear(5) && !core.onHuGear(3))
        core.onHuStatistic("20032131")
        assertTrue("P -> R", !core.onHuGear(2) && !core.onHuGear(5))
        assertTrue("R -> R", !core.onHuGear(5))
        assertTrue("R -> D", core.onHuGear(3))
        assertTrue("D -> P", !core.onHuGear(2))
        core.onHuGear(5)
        core.attach(link)
        assertTrue("новая сессия - передача заново", !core.onHuGear(3))
    }

    @Test
    fun seekKeysAnswerCall() {
        val peer = FakePeer()
        core.peer = peer
        var call = PhoneCall.RINGING
        core.phoneCallProvider = { call }
        core.onCarlifeHardKey(16)                             // Alien head unit: Rewind As Was
        core.onCarlifeHardKey(0)                              // Alien head unit: keycode 0 Ignore
        assertEquals(listOf(87), peer.keys.toList())
        peer.keys.clear()
        core.onHuStatistic("20082114")                        // VW CNS2 Delphi (Dmitry)
        core.onCarlifeHardKey(15)                             // calls: back (green left) = take
        core.onCarlifeHardKey(16)                             // Call: forward (red right) = reject
        call = PhoneCall.ACTIVE
        core.onCarlifeHardKey(15)                             // Talk: back swallowing
        core.onCarlifeHardKey(16)                             // talk: go ahead = put the phone down
        call = PhoneCall.IDLE
        core.onCarlifeHardKey(16)                             // without calling again rewind
        core.onCarlifeHardKey(0)                              // Rewind retention = pause/play
        assertEquals(listOf(5, 6, 6, 87, 85), peer.keys.toList())
        assertTrue(logs.any { it.contains("руль keycode=15 -> AA KEYCODE 5 (звонок RINGING)") })
    }

    @Test
    fun selectorKnobToRotary() {
        val peer = FakePeer()
        core.peer = peer
        core.onCarlifeHardKey(6)                              // SELECTOR_NEXT
        core.onCarlifeHardKey(7)                              // SELECTOR_PREVIOUS
        core.onCarlifeHardKey(0x14)                           // OK: pressing washer
        assertEquals(listOf(1, -1), peer.rotary.toList())
        assertEquals(listOf(23), peer.keys.toList())
        assertTrue(logs.any { it.contains("шайба keycode=6 -> AA rotary 1") })
        assertTrue(AapMessages.KEYCODE_ROTARY_CONTROLLER in AapMessages.KEYCODES_SUPPORTED)
        core.peer = null
        core.onCarlifeHardKey(6)                              // AA not connected: Ignoring without exception
        assertEquals(2, peer.rotary.size)
    }

    @Test
    fun sourceSwitchPlaceholderToAaAndBackThenToNewAaStream() {
        core.onCarlifeStart()
        core.onCodecConfig(cfg)
        core.onFrame(idr, 1)
        assertEquals(AaBridgeCore.Source.AA, core.activeSource)
        // AA finished after installation: stub with the right text, CarLife do not touch
        core.onAaEnded(established = true)
        assertEquals(AaBridgeCore.Source.PLACEHOLDER, core.activeSource)
        assertEquals(AaBridgeCore.TEXT_AA_LOST, link.texts.last())
        // server restarted: new session with another config -> new SPS+PPS+IDR
        core.onAaConnecting()
        val sps2 = Golden.hex("00000001 67 4d401f ac2c a5")
        val cfg2 = sps2 + pps
        core.onFrame(idr, 5)                                  // IDR without config from the new session: nothing to glue
        assertEquals(1, link.sent.size)
        core.onCodecConfig(cfg2)
        core.onFrame(p1, 6)
        assertEquals(1, link.sent.size)
        core.onFrame(idr, 7)
        assertEquals(2, link.sent.size)
        assertArrayEquals(cfg2 + idr, link.sent[1])
        assertEquals(AaBridgeCore.Source.AA, core.activeSource)
        assertEquals(2, link.hides)
    }

    @Test
    fun refusedConnectionKeepsConnectingText() {
        core.onAaConnecting()
        core.onAaEnded(established = false)
        assertEquals(listOf(AaBridgeCore.TEXT_CONNECTING), link.texts.toList())
        assertEquals(AaBridgeCore.TEXT_CONNECTING, core.currentPlaceholderText)
    }

    @Test
    fun rejectedFrameClosesGateUntilNextIdr() {
        core.onCarlifeStart()
        core.onCodecConfig(cfg)
        core.onFrame(idr, 1)
        link.accept = false
        core.onFrame(p1, 2)                                   // CarLife queue is full: frame is lost
        link.accept = true
        core.onFrame(p1, 3)                                   // P can not be further: waiting for IDR
        assertEquals(1, link.sent.size)
        core.onFrame(idr, 4)
        assertEquals(2, link.sent.size)
    }

    // --- "Exit" in AA (VIDEO_FOCUS NATIVE) -> CarLife GO_TO_DESKTOP ---
    @Test
    fun exitRequestSendsGoToDesktopThenNativeOnHuPause() {
        val peer = FakePeer()
        core.peer = peer
        core.onCarlifeStart()
        val keyframesBefore = peer.keyframes
        core.onAaNativeFocusRequest()
        assertEquals(1, link.desktops)
        assertEquals("AA сразу получает цикл NATIVE -> PROJECTED (Desay не шлёт PAUSE/START)", keyframesBefore + 1, peer.keyframes)
        assertEquals("NATIVE уходит в AA только когда ГУ реально вышел (PAUSE)", 0, peer.natives)
        core.onCarlifePause()
        assertEquals(1, peer.natives)
        assertTrue(logs.any { it.contains("«Выйти»") })
        core.onCarlifePause()                                 // The PAUSE: flag has been removed.
        assertEquals(1, peer.natives)
    }

    @Test
    fun huPauseWithoutExitRequestSendsNothingToAa() {
        val peer = FakePeer()
        core.peer = peer
        core.onCarlifeStart()
        core.onCarlifePause()
        assertEquals(0, peer.natives)
        assertEquals(0, link.desktops)
    }

    @Test
    fun exitRequestWithoutCarlifeStartOrLinkIsIgnored() {
        val peer = FakePeer()
        core.peer = peer
        core.onAaNativeFocusRequest()                         // CarLife is not yet START
        core.onCarlifePause()
        assertEquals(0, link.desktops)
        assertEquals(0, peer.natives)
        val bare = AaBridgeCore({ _, _ -> }, { now })         // There is no CarLife.
        bare.peer = peer
        bare.onAaNativeFocusRequest()
        bare.onCarlifePause()
        assertEquals(0, peer.natives)
    }

    @Test
    fun exitPendingIsClearedByCloseAndReattach() {
        val peer = FakePeer()
        core.peer = peer
        core.onCarlifeStart()
        core.onAaNativeFocusRequest()
        core.onCarlifeClosed(link)
        core.attach(link)
        core.onCarlifeStart()
        core.onCarlifePause()
        assertEquals(0, peer.natives)
    }

    @Test
    fun returnToCarlifeAfterExitRequestsKeyFrameCycle() {
        val peer = FakePeer()
        core.peer = peer
        core.onCarlifeStart()
        core.onCodecConfig(cfg)
        core.onFrame(idr, 1)
        core.onAaNativeFocusRequest()
        core.onCarlifePause()
        assertEquals(1, peer.natives)
        val before = peer.keyframes
        core.onCarlifeStart()                                 // head unit is back: cycle NATIVE -> PROJECTED
        assertEquals(before + 1, peer.keyframes)
    }
}

/** Virtual time for audio core timers: advance(ms) performs tasks in order that it misses. */
class FakeTimers {
    private class T(val at: Long, val task: Runnable) : MicTimerHandle {
        var cancelled = false
        override fun cancel() {
            cancelled = true
        }
    }

    private val list = ArrayList<T>()
    var now = 0L
        private set

    fun schedule(delayMs: Long, task: Runnable): MicTimerHandle = T(now + delayMs, task).also { list.add(it) }

    fun advance(ms: Long) {
        val end = now + ms
        while (true) {
            val t = list.filter { !it.cancelled && it.at <= end }.minByOrNull { it.at } ?: break
            list.remove(t)
            now = t.at
            t.task.run()
        }
        now = end
    }
}

class AaBridgeAudioTest {
    private val link = FakeLink()
    private val logs = CopyOnWriteArrayList<String>()
    private val timers = FakeTimers()
    private val core = AaBridgeCore({ l, _ -> logs.add(l) }, audioSchedule = timers::schedule).also { it.attach(link) }
    private val MEDIA = AapMessages.CH_AUDIO_MEDIA
    private val SPEECH = AapMessages.CH_AUDIO_SPEECH
    private fun pcm(n: Int, v: Int = 1) = ByteArray(n) { (v + it % 5).toByte() }
    private fun hexs(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    @Test
    fun delphiCallPausesMediaAndDropsTail() {
        // h425: The Music Tail AA the Incoming Cup USB on ~ 20 c - pause immediately, the tail will not be sent to play
        core.peer = FakePeer()
        var call = PhoneCall.IDLE
        core.phoneCallProvider = { call }
        core.onHuStatistic("20082114")
        core.onCarlifeStart()
        core.onMediaStatus(AaMediaStatus(2, "YandexMusic", 10, null, null, null))
        core.onAudioData(AapMessages.CH_AUDIO_MEDIA, pcm(100), 1)
        call = PhoneCall.RINGING
        core.onMediaStatus(AaMediaStatus(3, "YandexMusic", 10, null, null, null))   // The player is under the ringtone.
        core.onAudioData(AapMessages.CH_AUDIO_MEDIA, pcm(100), 2)                   // buffer tail AA
        core.onCarlifeHardKey(0x20)                                                 // head unit went to the call: the second time will not pause
        core.onAudioStop(AapMessages.CH_AUDIO_MEDIA)
        assertEquals(listOf(0x30001, 0x30006, 0x30003), link.audio.map { it.type })
        call = PhoneCall.IDLE
        core.onAudioStart(AapMessages.CH_AUDIO_MEDIA)                               // AA has played again
        core.onAudioData(AapMessages.CH_AUDIO_MEDIA, pcm(100), 3)
        assertEquals(listOf(0x30001, 0x30006, 0x30003, 0x30001, 0x30006), link.audio.map { it.type })
    }

    @Test
    fun delphiTelephonyRingingPausesMediaAtOnce() {
        // h425: telephony reports RINGING before AA player changes status and AudioManager.mode becomes RINGTONE
        core.peer = FakePeer()
        core.onHuStatistic("20082114")
        core.onCarlifeStart()
        core.onMediaStatus(AaMediaStatus(2, "YandexMusic", 10, null, null, null))
        core.onAudioData(AapMessages.CH_AUDIO_MEDIA, pcm(100), 1)
        core.onPhoneCallState(1)                                                    // RINGING, player status has not changed, mode is still NORMAL
        core.onAudioData(AapMessages.CH_AUDIO_MEDIA, pcm(100), 2)                   // buffer tail AA
        core.onAudioData(AapMessages.CH_AUDIO_MEDIA, pcm(100), 3)
        assertEquals(listOf(0x30001, 0x30006, 0x30003), link.audio.map { it.type })
        assertEquals(1, link.mediaDrops)
        assertTrue(logs.any { it.contains("BRIDGE звонок RINGING (телефония)") })
    }

    @Test
    fun delphiTelephonyIdleReleasesPauseWhenPlayerPlaying() {
        // call reset quickly, the player AA did not have time to get up and there will be no events play: pause removes the end of the call
        core.peer = FakePeer()
        core.onHuStatistic("20082114")
        core.onCarlifeStart()
        core.onMediaStatus(AaMediaStatus(2, "YandexMusic", 10, null, null, null))
        core.onAudioData(AapMessages.CH_AUDIO_MEDIA, pcm(100), 1)
        core.onPhoneCallState(1)
        core.onAudioData(AapMessages.CH_AUDIO_MEDIA, pcm(100), 2)
        core.onPhoneCallState(0)                                                    // IDLE, player all the time playing
        core.onAudioData(AapMessages.CH_AUDIO_MEDIA, pcm(100), 3)
        assertEquals(listOf(0x30001, 0x30006, 0x30003, 0x30001, 0x30006), link.audio.map { it.type })
        assertTrue(logs.any { it.contains("BRIDGE звонок закончен, плеер AA играет") })
    }

    @Test
    fun delphiTelephonyIdleKeepsPauseWhenPlayerStopped() {
        core.peer = FakePeer()
        core.onHuStatistic("20082114")
        core.onCarlifeStart()
        core.onMediaStatus(AaMediaStatus(2, "YandexMusic", 10, null, null, null))
        core.onAudioData(AapMessages.CH_AUDIO_MEDIA, pcm(100), 1)
        core.onPhoneCallState(1)
        core.onMediaStatus(AaMediaStatus(3, "YandexMusic", 10, null, null, null))   // The player is under the ringtone.
        core.onPhoneCallState(0)
        core.onAudioData(AapMessages.CH_AUDIO_MEDIA, pcm(100), 2)                   // tail: player stands, pause holds
        assertEquals(listOf(0x30001, 0x30006, 0x30003), link.audio.map { it.type })
        core.onMediaStatus(AaMediaStatus(2, "YandexMusic", 10, null, null, null))   // user started again
        core.onAudioData(AapMessages.CH_AUDIO_MEDIA, pcm(100), 3)
        assertEquals(listOf(0x30001, 0x30006, 0x30003, 0x30001, 0x30006), link.audio.map { it.type })
    }

    @Test
    fun telephonyRingingDoesNotTouchMediaOnOtherHu() {
        core.peer = FakePeer()
        core.onCarlifeStart()
        core.onMediaStatus(AaMediaStatus(2, "YandexMusic", 10, null, null, null))
        core.onAudioData(AapMessages.CH_AUDIO_MEDIA, pcm(100), 1)
        core.onPhoneCallState(1)
        core.onAudioData(AapMessages.CH_AUDIO_MEDIA, pcm(100), 2)
        core.onPhoneCallState(0)
        assertEquals(listOf(0x30001, 0x30006, 0x30006), link.audio.map { it.type })
        assertEquals(0, link.mediaDrops)
    }

    @Test
    fun callDoesNotTouchMediaOnOtherHu() {
        core.peer = FakePeer()
        core.phoneCallProvider = { PhoneCall.RINGING }
        core.onCarlifeStart()
        core.onMediaStatus(AaMediaStatus(2, "YandexMusic", 10, null, null, null))
        core.onAudioData(AapMessages.CH_AUDIO_MEDIA, pcm(100), 1)
        core.onMediaStatus(AaMediaStatus(3, "YandexMusic", 10, null, null, null))
        core.onAudioData(AapMessages.CH_AUDIO_MEDIA, pcm(100), 2)
        assertEquals(listOf(0x30001, 0x30006, 0x30006), link.audio.map { it.type })
    }

    @Test
    fun mediaInitThenDataOnMediaChannelWithExactCodesAndBodies() {
        core.onCarlifeStart()
        val d = pcm(3840)
        core.onAudioData(AapMessages.CH_AUDIO_MEDIA, d, 1)
        core.onAudioData(AapMessages.CH_AUDIO_MEDIA, d, 2)
        assertEquals(3, link.audio.size)
        val init = link.audio[0]
        assertEquals(3, init.chan)
        assertEquals(0x00030001, init.type)
        assertEquals("0880f70210021810", hexs(init.payload))           // CarlifeMusicInit{48000, 2, 16}
        assertEquals(3, link.audio[1].chan)
        assertEquals(0x00030006, link.audio[1].type)
        assertArrayEquals(d, link.audio[1].payload)
        assertEquals(0x00030006, link.audio[2].type)                   // second package without repeated INIT
    }

    @Test
    fun mediaChunkSettingSplitsMusicIntoFixedPackets() {
        core.mediaChunkProvider = { MediaChunk.B2048 }
        core.onCarlifeStart()
        val d = pcm(8192)
        core.onAudioData(AapMessages.CH_AUDIO_MEDIA, d, 1)
        assertEquals(0x00030001, link.audio[0].type)
        val data = link.audio.drop(1)
        assertEquals(List(4) { 0x00030006 }, data.map { it.type })
        assertEquals(List(4) { 2048 }, data.map { it.payload.size })
        assertArrayEquals(d, data.flatMap { it.payload.toList() }.toByteArray())
        assertEquals(1, logs.count { it.contains("пакеты по 2048 Б") })
    }

    @Test
    fun mediaChunkAutoKeepsAaPacketWhole() {
        core.mediaChunkProvider = { MediaChunk.AUTO }
        core.onCarlifeStart()
        core.onAudioData(AapMessages.CH_AUDIO_MEDIA, pcm(8192), 1)
        assertEquals(listOf(0x00030001, 0x00030006), link.audio.map { it.type })
        assertEquals(8192, link.audio[1].payload.size)
        assertEquals(0, logs.count { it.contains("пакеты по") })
    }

    @Test
    fun speechGoesToTtsChannel() {
        core.onCarlifeStart()
        core.onAudioData(AapMessages.CH_AUDIO_SPEECH, pcm(640), 1)
        assertEquals(listOf(4 to 0x00040001, 4 to 0x00040003), link.audio.map { it.chan to it.type })
        assertEquals("08807d10011810", hexs(link.audio[0].payload))     // CarlifeTTSInit{16000, 1, 16}
    }

    @Test
    fun bigChunkIsSplitAtTenKib() {
        core.onCarlifeStart()
        val d = pcm(25000)
        core.onAudioData(AapMessages.CH_AUDIO_MEDIA, d, 1)
        val data = link.audio.filter { it.type == 0x00030006 }
        assertEquals(listOf(10240, 10240, 4520), data.map { it.payload.size })
        assertArrayEquals(d, data.fold(ByteArray(0)) { a, c -> a + c.payload })
    }

    @Test
    fun mediaAndGuidanceSwitchEachInitOnceAndStopIndependently() {
        core.onCarlifeStart()
        core.onAudioData(AapMessages.CH_AUDIO_MEDIA, pcm(100), 1)
        core.onAudioData(AapMessages.CH_AUDIO_SPEECH, pcm(100), 2)       // hint on top of music
        core.onAudioData(AapMessages.CH_AUDIO_MEDIA, pcm(100), 3)
        core.onAudioData(AapMessages.CH_AUDIO_SPEECH, pcm(100), 4)
        core.onAudioStop(AapMessages.CH_AUDIO_SPEECH)                    // The hint is over.
        timers.advance(1500)                                             // TTS_STOP leaves with a delay of 1500 ms
        core.onAudioData(AapMessages.CH_AUDIO_MEDIA, pcm(100), 5)        // Music continues without the new INIT
        assertEquals(
            listOf(
                3 to 0x30001, 3 to 0x30006, 4 to 0x40001, 4 to 0x40003, 3 to 0x30006, 4 to 0x40003,
                4 to 0x40002, 3 to 0x30006,
            ),
            link.audio.map { it.chan to it.type }
        )
        assertEquals(0, link.audio[6].payload.size)                      // STOP without body
        // Next tip: new INIT
        core.onAudioData(AapMessages.CH_AUDIO_SPEECH, pcm(100), 6)
        assertEquals(listOf(4 to 0x40001, 4 to 0x40003), link.audio.takeLast(2).map { it.chan to it.type })
    }

    @Test
    fun mediaStopSendsPauseOnlyIfStreamWasInitedAndNextDataReinits() {
        core.onCarlifeStart()
        core.onAudioStop(AapMessages.CH_AUDIO_MEDIA)                     // nothing played: nothing slurred
        assertEquals(0, link.audio.size)
        core.onAudioData(AapMessages.CH_AUDIO_MEDIA, pcm(100), 1)
        core.onAudioStop(AapMessages.CH_AUDIO_MEDIA)                     // PAUSE 0x30003, not STOP
        core.onAudioStop(AapMessages.CH_AUDIO_MEDIA)                     // PAUSE is not duplicated
        core.onAudioData(AapMessages.CH_AUDIO_MEDIA, pcm(100), 2)
        assertEquals(listOf(0x30001, 0x30006, 0x30003, 0x30001, 0x30006), link.audio.map { it.type })
        assertEquals(listOf(3 to 1, 3 to 0, 3 to 1), link.modules.toList())   // MUSIC=1 on INIT, 0 on PAUSE
    }

    @Test
    fun aaSessionEndStopsOpenStreams() {
        core.onCarlifeStart()
        core.onAudioData(AapMessages.CH_AUDIO_MEDIA, pcm(100), 1)
        core.onAudioData(AapMessages.CH_AUDIO_SPEECH, pcm(100), 2)
        core.onAaEnded(established = true)
        assertEquals(setOf(3 to 0x30002, 4 to 0x40002), link.audio.takeLast(2).map { it.chan to it.type }.toSet())
    }

    @Test
    fun carlifeStartAfterPauseResetsInit() {
        core.onCarlifeStart()
        core.onAudioData(AapMessages.CH_AUDIO_MEDIA, pcm(100), 1)
        core.onCarlifePause()
        core.onCarlifeStart()
        core.onAudioData(AapMessages.CH_AUDIO_MEDIA, pcm(100), 2)
        assertEquals(listOf(0x30001, 0x30006, 0x30001, 0x30006), link.audio.map { it.type })
    }

    @Test
    fun carlifeStartAfterPauseFlushesPendingTtsStop() {
        // h482: hint ended on the screen head unit, START came before 1500 ms - TTS_STOP lost, music jammed
        core.onCarlifeStart()
        core.onCarlifePause()
        core.onAudioData(AapMessages.CH_AUDIO_SPEECH, pcm(100), 1)
        core.onAudioStop(AapMessages.CH_AUDIO_SPEECH)
        timers.advance(800)
        core.onCarlifeStart()
        assertEquals(4 to 0x40002, link.audio.last().let { it.chan to it.type })
        timers.advance(1500)
        assertEquals(1, link.audio.count { it.type == 0x40002 })
    }

    @Test
    fun carlifeStartWithoutPauseKeepsAudio() {
        // Desay G6S: START for every pressing of NAVI/MEDIA without PAUSE - repeated INIT ripping sound
        core.onCarlifeStart()
        core.onAudioData(AapMessages.CH_AUDIO_MEDIA, pcm(100), 1)
        core.onCarlifeStart()
        core.onAudioData(AapMessages.CH_AUDIO_MEDIA, pcm(100), 2)
        assertEquals(listOf(0x30001, 0x30006, 0x30006), link.audio.map { it.type })
        assertEquals(listOf(3 to 1), link.modules.toList())
    }

    @Test
    fun startAfterReattachResetsInit() {
        core.onCarlifeStart()
        core.onAudioData(AapMessages.CH_AUDIO_MEDIA, pcm(100), 1)
        val link2 = FakeLink()
        core.attach(link2)
        core.onCarlifeStart()
        core.onAudioData(AapMessages.CH_AUDIO_MEDIA, pcm(100), 2)
        assertEquals(listOf(0x30001, 0x30006), link2.audio.map { it.type })
    }

    @Test
    fun systemChannelAndNotStartedAreDropped() {
        core.onAudioData(AapMessages.CH_AUDIO_MEDIA, pcm(100), 1)          // CarLife is not yet START
        core.onCarlifeStart()
        core.onAudioData(AapMessages.CH_AUDIO_SYSTEM, pcm(100), 2)
        assertEquals(0, link.audio.size)
        assertEquals(2L, core.audioDropped)
    }

    @Test
    fun bluetoothModeAndUiSwitchStopUsbAudio() {
        assertTrue(core.audioViaUsb)
        core.onHuFeatureConfig(0)
        assertTrue(core.audioViaUsb)
        core.onHuFeatureConfig(1)
        assertFalse(core.audioViaUsb)
        assertTrue(logs.any { it.contains("AUDIO_TRANSMISSION_MODE=1") && it.contains("Bluetooth") })
        core.onCarlifeStart()
        core.onAudioData(AapMessages.CH_AUDIO_MEDIA, pcm(100), 1)
        assertEquals(0, link.audio.size)
        val c2 = AaBridgeCore({ _, _ -> }).also { it.attach(FakeLink()) }
        c2.usbAudioEnabled = false
        assertFalse(c2.audioViaUsb)
    }

    @Test
    fun rejectedInitIsRetriedOnNextPacket() {
        core.onCarlifeStart()
        link.accept = false
        core.onAudioData(AapMessages.CH_AUDIO_MEDIA, pcm(100), 1)
        link.accept = true
        core.onAudioData(AapMessages.CH_AUDIO_MEDIA, pcm(100), 2)
        assertEquals(listOf(0x30001, 0x30006), link.audio.map { it.type })
    }

    // --------------------------------------------------- NAVI, debounce TTS, MEDIA queue, voice
    @Test
    fun naviStatusGoesBeforeFirstTtsInitOnlyOncePerSession() {
        core.onCarlifeStart()
        core.onAudioData(SPEECH, pcm(640), 1)
        core.onAudioData(SPEECH, pcm(640), 2)
        assertEquals(listOf("M2=1", "A4:0x40001", "A4:0x40003", "A4:0x40003"), link.events.toList())
        core.onAudioStop(SPEECH)
        timers.advance(1500)
        core.onAudioData(SPEECH, pcm(640), 3)                            // new INIT, but NAVI already holds
        assertEquals(1, link.modules.count { it == 2 to 1 })
    }

    @Test
    fun naviResetByStopAllAndByCarlifeStart() {
        core.onCarlifeStart()
        core.onAudioData(SPEECH, pcm(640), 1)
        core.onAaEnded(established = true)
        assertEquals(listOf("M2=1", "A4:0x40001", "A4:0x40003", "A4:0x40002", "M2=0"), link.events.toList())
        core.onCarlifeStart()
        core.onAudioData(SPEECH, pcm(640), 2)
        assertEquals(2, link.modules.count { it == 2 to 1 })             // flag dropped: in the new session again NAVI=1
        core.onCarlifeClosed(link)
        assertEquals(1, link.modules.count { it == 2 to 0 })             // Closing session of NAVI=0 is non-slot (USB is dead)
    }

    @Test
    fun vwCns3TtsGetsSilencePrerollAfterInitOnlyOnRealInit() {
        core.onHuInfo("Volkswagen", "CNS3")
        core.onHuStatistic("20082122")                                   // h222: hissing at the beginning of the hint
        core.onCarlifeStart()
        val speech = pcm(2048, 7)
        core.onAudioStart(SPEECH)
        core.onAudioData(SPEECH, speech, 1)
        val tts = link.audio.filter { it.chan == 4 }
        assertEquals(0x40001, tts[0].type)
        val pre = tts.drop(1).dropLast(1)
        assertEquals(listOf(4096, 4096, 4096, 512), pre.map { it.payload.size })  // 400 ms 16 kHz mono = 12800 B pieces Baidu
        assertTrue(pre.all { p -> p.type == 0x40003 && p.payload.all { it == 0.toByte() } })
        assertArrayEquals(speech, tts.last().payload)                    // Speech after silence, no change
        // STOP cancelled by new START: INIT not repeated, pre-roll also no
        core.onAudioStop(SPEECH)
        timers.advance(1000)
        core.onAudioStart(SPEECH)
        core.onAudioData(SPEECH, speech, 2)
        assertEquals(tts.size + 1, link.audio.count { it.chan == 4 })
        // after real TTS_STOP next INIT again with pre-roll
        core.onAudioStop(SPEECH)
        timers.advance(1500)
        val before = link.audio.size
        core.onAudioData(SPEECH, speech, 3)
        assertEquals(listOf(0x40001) + List(5) { 0x40003 }, link.audio.drop(before).map { it.type })
    }

    @Test
    fun ttsWithoutVwFlagHasNoPreroll() {
        core.onHuInfo("", "")
        core.onHuStatistic("20622116")                                   // Desay: Other head unit
        core.onCarlifeStart()
        core.onAudioData(SPEECH, pcm(640), 1)
        assertEquals(listOf(0x40001, 0x40003), link.audio.map { it.type })
    }

    @Test
    fun ttsPrerollSettingSendsZerosOnAnyHuAndAutoSkipsNonVw() {
        core.ttsPrerollProvider = { TtsPreroll.MS100 }
        core.onHuStatistic("20622116")                                   // not VW
        core.onCarlifeStart()
        core.onAudioData(SPEECH, pcm(640), 1)
        val tts = link.audio.filter { it.chan == 4 }
        assertEquals(listOf(0x40001, 0x40003, 0x40003), tts.map { it.type })
        assertEquals(3200, tts[1].payload.size)                          // 100 ms 16 kHz mono, one piece (< 4096)
        assertTrue(tts[1].payload.all { it == 0.toByte() })
        assertEquals(1, logs.count { it.contains("пре-ролл 100 мс тишины (3200 Б)") })
        // Auto on non-VW: pre-roll no
        val link2 = FakeLink()
        val c2 = AaBridgeCore({ _, _ -> }, audioSchedule = timers::schedule).also { it.attach(link2) }
        c2.ttsPrerollProvider = { TtsPreroll.AUTO }
        c2.onCarlifeStart()
        c2.onAudioData(SPEECH, pcm(640), 1)
        assertEquals(listOf(0x40001, 0x40003), link2.audio.map { it.type })
    }

    @Test
    fun ttsPrerollOffSkipsPrerollOnVw() {
        core.ttsPrerollProvider = { TtsPreroll.MS0 }
        core.onHuStatistic("20082122")
        core.onCarlifeStart()
        core.onAudioData(SPEECH, pcm(640), 1)
        assertEquals(listOf(0x40001, 0x40003), link.audio.map { it.type })
    }

    @Test
    fun voiceChunkAutoOnDelphiSplitsVrSpeechIntoKib() {
        core.onHuStatistic("20082114")
        core.micModeProvider = { MicMode.CAR }
        core.onCarlifeStart()
        core.onVoiceSession(1)
        val s = pcm(2048, 3)
        core.onAudioData(SPEECH, s, 1)
        val data = link.audio.filter { it.type == 0x50003 }
        assertEquals(listOf(1024, 1024), data.map { it.payload.size })
        assertArrayEquals(s, data.flatMap { it.payload.toList() }.toByteArray())
        assertEquals(1, logs.count { it.contains("BRIDGE голос: пакеты по 1024 Б (авто ГУ)") })
    }

    @Test
    fun voiceChunkSettingSplitsTtsOnNonDelphi() {
        core.voiceChunkProvider = { VoiceChunk.B512 }
        core.onCarlifeStart()
        val s = pcm(2048, 4)
        core.onAudioData(SPEECH, s, 1)
        assertEquals(0x40001, link.audio[0].type)
        val data = link.audio.drop(1)
        assertEquals(List(4) { 0x40003 }, data.map { it.type })
        assertEquals(List(4) { 512 }, data.map { it.payload.size })
        assertArrayEquals(s, data.flatMap { it.payload.toList() }.toByteArray())
        assertEquals(1, logs.count { it.contains("BRIDGE голос: пакеты по 512 Б (настройка)") })
    }

    @Test
    fun voiceChunkAutoOnOtherHuKeepsAaPacketWhole() {
        core.onCarlifeStart()
        core.onAudioData(SPEECH, pcm(2048), 1)
        assertEquals(listOf(0x40001, 0x40003), link.audio.map { it.type })
        assertEquals(2048, link.audio[1].payload.size)
        assertEquals(0, logs.count { it.contains("BRIDGE голос") })
    }

    @Test
    fun voiceChunkSizesPrerollZerosWhenSet() {
        core.voiceChunkProvider = { VoiceChunk.B1024 }
        core.onHuStatistic("20082122")                                   // VW CNS3, Auto pre-roll 400 ms = 12800 B
        core.onCarlifeStart()
        core.onAudioData(SPEECH, pcm(640), 1)
        val pre = link.audio.filter { it.chan == 4 }.drop(1).dropLast(1)
        assertEquals(List(12) { 1024 } + 512, pre.map { it.payload.size })
    }

    @Test
    fun ttsStartStopStartWithinOneSecondGivesOneInitAndNoStopBetween() {
        core.onCarlifeStart()
        core.onAudioStart(SPEECH)
        core.onAudioData(SPEECH, pcm(640), 1)
        core.onAudioStop(SPEECH)
        timers.advance(1000)
        core.onAudioStart(SPEECH)                                        // SPEECH START Cancels Deferred STOP
        core.onAudioData(SPEECH, pcm(640), 2)
        timers.advance(5000)
        assertEquals(listOf(0x40001, 0x40003, 0x40003), link.audio.map { it.type })
        // ch4 data also cancels the timer
        core.onAudioStop(SPEECH)
        timers.advance(1400)
        core.onAudioData(SPEECH, pcm(640), 3)
        timers.advance(5000)
        assertEquals(0, link.audio.count { it.type == 0x40002 })
        // and the real STOP comes exactly through 1500 ms
        core.onAudioStop(SPEECH)
        timers.advance(1499)
        assertEquals(0, link.audio.count { it.type == 0x40002 })
        timers.advance(1)
        assertEquals(1, link.audio.count { it.type == 0x40002 })
    }

    @Test
    fun stopAllAudioSendsTtsStopImmediatelyAndCancelsTimer() {
        core.onCarlifeStart()
        core.onAudioData(SPEECH, pcm(640), 1)
        core.onAudioStop(SPEECH)
        core.onAaEnded(established = true)
        assertEquals(1, link.audio.count { it.type == 0x40002 })
        timers.advance(5000)
        assertEquals(1, link.audio.count { it.type == 0x40002 })         // timer canceled: second STOP no
    }

    @Test
    fun mediaStopDuringTtsWaitsForTtsStopPlus300() {
        core.onCarlifeStart()
        core.onAudioData(MEDIA, pcm(100), 1)
        core.onAudioData(SPEECH, pcm(100), 2)
        core.onAudioStop(MEDIA)                                          // PAUSE
        assertEquals(0, link.audio.count { it.type == 0x30003 })
        core.onAudioStop(SPEECH)
        timers.advance(1500)                                             // Actual TTS_STOP
        assertEquals(1, link.audio.count { it.type == 0x40002 })
        timers.advance(299)
        assertEquals(0, link.audio.count { it.type == 0x30003 })
        timers.advance(1)
        assertEquals(
            listOf("A3:0x30001", "M3=1", "A3:0x30006", "M2=1", "A4:0x40001", "A4:0x40003", "A4:0x40002", "A3:0x30003", "M3=0"),
            link.events.toList()
        )
    }

    @Test
    fun mediaDataIsDroppedWhileTtsBusyAndMediaNotInitedThenReinitsAfterRelease() {
        core.onCarlifeStart()
        core.onAudioData(MEDIA, pcm(100), 1)
        core.onAudioStop(MEDIA)                                          // PAUSE immediately: TTS Not coming.
        core.onAudioData(SPEECH, pcm(100), 2)
        for (i in 0 until 1001) core.onAudioData(MEDIA, pcm(100), 10L + i)   // MEDIA not initialized, TTS busy
        assertEquals(listOf(0x30001, 0x30006, 0x30003, 0x40001, 0x40003), link.audio.map { it.type })
        assertEquals(3, logs.count { it.contains("MEDIA данные отброшены") })   // #1, #500, #1000
        core.onAudioStop(SPEECH)
        timers.advance(1500 + 299)
        core.onAudioData(MEDIA, pcm(100), 3)                             // still in the window +300
        assertEquals(1, link.audio.count { it.type == 0x30001 })         // INIT is not available yet
        timers.advance(1)
        core.onAudioData(MEDIA, pcm(100), 4)
        assertEquals(listOf(0x40002, 0x30001, 0x30006), link.audio.takeLast(3).map { it.type })
    }

    @Test
    fun mediaDataKeepsFlowingWhenInitedAndTtsPlays() {
        core.onCarlifeStart()
        core.onAudioData(MEDIA, pcm(100), 1)
        core.onAudioData(SPEECH, pcm(100), 2)
        core.onAudioData(MEDIA, pcm(100), 3)                             // head unit
        assertEquals(listOf(0x30001, 0x30006, 0x40001, 0x40003, 0x30006), link.audio.map { it.type })
    }

    @Test
    fun vwCns2DelphiRoutesAssistantSpeechToVrDuringVoiceSession() {
        core.onHuStatistic("20082114")                                   // h425: assistant response in TTS not heard
        core.micModeProvider = { MicMode.CAR }
        core.onCarlifeStart()
        core.onVoiceSession(1)
        val s = pcm(2048, 3)
        core.onAudioStart(SPEECH)
        core.onAudioData(SPEECH, s, 1)
        core.onAudioData(SPEECH, s, 2)
        core.onAudioStop(SPEECH)
        val vr = link.audio.filter { it.chan == 5 }
        assertEquals(listOf(0x50002, 0x50003, 0x50003, 0x50003, 0x50003, 0x50004), vr.map { it.type })
        assertEquals("08807d10011810", hexs(vr[0].payload))             // {16000, 1, 16}
        assertArrayEquals(s.copyOfRange(0, 1024), vr[1].payload)        // Auto on Delphi: packages by 1024 B
        assertArrayEquals(s.copyOfRange(1024, 2048), vr[2].payload)
        assertTrue(link.audio.none { it.chan == 4 })
        assertTrue("VR=0 не раньше выхода из голоса", "M4=0" !in link.events)
        core.onVoiceSession(2)
        timers.advance(1000)
        assertTrue("после голоса и VR STOP - снова VR=0 (громкость)", "M4=0" in link.events)
    }

    @Test
    fun vwCns2DelphiDelaysFirstTtsInitAfterNaviAndKeepsSpeech() {
        val l = FakeLink()
        val c = AaBridgeCore({ m, _ -> logs.add(m) }, clockMs = { timers.now }, audioSchedule = timers::schedule).also { it.attach(l) }
        c.ttsPrerollProvider = { TtsPreroll.MS0 }
        c.onHuStatistic("20082114")                                      // h425: first session hint is silent
        c.onCarlifeStart()
        val a = pcm(2048, 1)
        val b = pcm(2048, 2)
        c.onAudioStart(SPEECH)
        c.onAudioData(SPEECH, a, 1)
        timers.advance(100)
        c.onAudioData(SPEECH, b, 2)
        assertTrue(l.audio.none { it.chan == 4 })                          // NAVI=1 is gone, INIT is not yet: the speech is piling up
        assertTrue("M2=1" in l.events)
        timers.advance(200)
        val tts = l.audio.filter { it.chan == 4 }
        assertEquals(0x40001, tts[0].type)
        assertArrayEquals(a + b, tts.drop(1).flatMap { it.payload.toList() }.toByteArray())
        // The NAVI=1 has been around for a long time - without delay
        c.onAudioStop(SPEECH)
        timers.advance(1500)
        val before = l.audio.size
        c.onAudioData(SPEECH, a, 3)
        assertEquals(0x40001, l.audio[before].type)
    }

    @Test
    fun vwCns2DelphiShortPhraseStoppedWhileWaitingForInitStillPlays() {
        val l = FakeLink()
        val c = AaBridgeCore({ m, _ -> logs.add(m) }, clockMs = { timers.now }, audioSchedule = timers::schedule).also { it.attach(l) }
        c.ttsPrerollProvider = { TtsPreroll.MS0 }
        c.onHuStatistic("20082114")
        c.onCarlifeStart()
        c.onAudioStart(SPEECH)
        c.onAudioData(SPEECH, pcm(2048), 1)
        c.onAudioStop(SPEECH)
        timers.advance(300)
        assertEquals(listOf(0x40001, 0x40003, 0x40003), l.audio.filter { it.chan == 4 }.map { it.type })  // Auto on Delphi: by 1024 B
        timers.advance(1500)
        assertEquals(0x40002, l.audio.last { it.chan == 4 }.type)        // STOP after issuance, with normal delay
    }

    @Test
    fun speechInVoiceSessionStaysOnTtsWithoutDelphiOrWithPhoneMic() {
        core.onCarlifeStart()
        core.onVoiceSession(1)
        core.onAudioData(SPEECH, pcm(2048), 1)
        assertTrue(link.audio.none { it.chan == 5 })
        assertEquals(0x40001, link.audio.first { it.chan == 4 }.type)

        val l2 = FakeLink()
        val c2 = AaBridgeCore({ l, _ -> logs.add(l) }, audioSchedule = timers::schedule).also { it.attach(l2) }
        c2.onHuStatistic("20082114")
        c2.micModeProvider = { MicMode.PHONE }
        c2.onCarlifeStart()
        c2.onVoiceSession(1)
        c2.onAudioData(SPEECH, pcm(2048), 1)
        assertTrue(l2.audio.none { it.chan == 5 })
    }

    @Test
    fun voiceKeyDoubleFromHuIsDebounced() {
        var now = 1_000L
        val c = AaBridgeCore({ l, _ -> logs.add(l) }, clockMs = { now })
        val peer = FakePeer()
        c.peer = peer
        c.onCarlifeHardKey(0x21)                                         // h425: Delphi sends VR_START twice for 1-2 ms
        now += 2
        c.onCarlifeHardKey(0x21)
        assertEquals(listOf(AaBridgeCore.KEYCODE_SEARCH), peer.keys.toList())
        now += 600
        c.onCarlifeHardKey(0x21)
        assertEquals(listOf(AaBridgeCore.KEYCODE_SEARCH, AaBridgeCore.KEYCODE_SEARCH), peer.keys.toList())
    }

    @Test
    fun voiceStartPausesMediaStopsForwardingAndBurstExitsOnce() {
        core.onCarlifeStart()
        core.onAudioData(MEDIA, pcm(100), 1)
        core.onVoiceSession(1)
        assertEquals(listOf("A3:0x30001", "M3=1", "A3:0x30006", "A3:0x30003", "M3=0"), link.events.toList())
        core.onAudioData(MEDIA, pcm(100), 2)                             // goes voice: MEDIA_DATA do not send
        assertEquals(3, link.audio.size)
        // 2/2/1/2/2 pack: one output, through 500 ms after the last deuce
        core.onVoiceSession(2)
        timers.advance(200)
        core.onVoiceSession(2)
        timers.advance(200)
        core.onVoiceSession(1)                                           // exit canceled
        timers.advance(1000)
        core.onAudioData(MEDIA, pcm(100), 3)
        assertEquals(3, link.audio.size)                                 // voice is still coming.
        core.onVoiceSession(2)
        timers.advance(100)
        core.onVoiceSession(2)
        timers.advance(399)
        core.onAudioData(MEDIA, pcm(100), 4)
        assertEquals(3, link.audio.size)
        timers.advance(1)
        assertEquals(1, logs.count { it.contains("голосовая сессия: выход, ") })
        core.onAudioData(MEDIA, pcm(100), 5)                             // after release: INIT on first data
        assertEquals(listOf(0x30001, 0x30006, 0x30003, 0x30001, 0x30006), link.audio.map { it.type })
        timers.advance(5000)
        assertEquals(1, logs.count { it.contains("голосовая сессия: выход, ") })
    }

    @Test
    fun voicePauseWaitsForTtsSerialization() {
        core.onCarlifeStart()
        core.onAudioData(MEDIA, pcm(100), 1)
        core.onAudioData(SPEECH, pcm(100), 2)
        core.onVoiceSession(1)
        assertEquals(0, link.audio.count { it.type == 0x30003 })
        core.onAudioStop(SPEECH)
        timers.advance(1799)
        assertEquals(0, link.audio.count { it.type == 0x30003 })
        timers.advance(1)
        assertEquals(1, link.audio.count { it.type == 0x30003 })
    }

    @Test
    fun ttsFirstPacketsLogPeakAndRms() {
        core.onCarlifeStart()
        val pcm = ByteArray(8)                                           // Samples 0, 1000, -2000, 0
        pcm[2] = (1000 and 0xFF).toByte(); pcm[3] = (1000 shr 8).toByte()
        pcm[4] = ((-2000) and 0xFF).toByte(); pcm[5] = ((-2000) shr 8).toByte()
        for (i in 1..5) core.onAudioData(SPEECH, pcm, i.toLong())
        val diag = logs.filter { it.contains("BRIDGE TTS пакет") }
        assertEquals(3, diag.size)
        assertTrue(diag[0].contains("peak=2000") && diag[0].contains("rms=1118"))
        core.onAudioData(SPEECH, ByteArray(8), 6L)
        assertEquals(3, logs.count { it.contains("BRIDGE TTS пакет") })
        // new TTS-session: three packs again, zeros marked
        core.onAudioStop(SPEECH)
        timers.advance(1500)
        for (i in 1..4) core.onAudioData(SPEECH, ByteArray(8), i.toLong())
        val diag2 = logs.filter { it.contains("BRIDGE TTS пакет") }.drop(3)
        assertEquals(3, diag2.size)
        assertTrue(diag2.all { it.contains("peak=0") && it.contains("ВСЕ НУЛИ") })
    }

    @Test
    fun mediaInitedFlagFollowsInitAndPause() {
        core.onCarlifeStart()
        assertFalse(core.isMediaInited)
        core.onAudioData(MEDIA, pcm(100), 1)
        assertTrue(core.isMediaInited)
        core.onAudioStop(MEDIA)
        assertFalse(core.isMediaInited)
    }

    @Test
    fun flagsOffRestoreImmediateBehaviour() {
        try {
            AaBridgeCore.TTS_DEBOUNCE_ENABLED = false
            AaBridgeCore.NAVI_STATUS_ENABLED = false
            AaBridgeCore.MODULE_MUSIC_ENABLED = false
            AaBridgeCore.MEDIA_PAUSE_ENABLED = false
            AaBridgeCore.VOICE_SESSION_ENABLED = false
            core.onCarlifeStart()
            core.onAudioData(MEDIA, pcm(100), 1)
            core.onAudioData(SPEECH, pcm(100), 2)
            core.onAudioStop(MEDIA)                                      // without queue and without PAUSE: directly MEDIA_STOP
            core.onAudioStop(SPEECH)                                     // without debounce: directly TTS_STOP
            core.onVoiceSession(1)
            assertEquals(listOf(0x30001, 0x30006, 0x40001, 0x40003, 0x30002, 0x40002), link.audio.map { it.type })
            assertEquals(0, link.modules.size)
        } finally {
            AaBridgeCore.TTS_DEBOUNCE_ENABLED = true
            AaBridgeCore.NAVI_STATUS_ENABLED = true
            AaBridgeCore.MODULE_MUSIC_ENABLED = true
            AaBridgeCore.MEDIA_PAUSE_ENABLED = true
            AaBridgeCore.VOICE_SESSION_ENABLED = true
        }
    }
}

/** AA-bridge session vs script phone by loopback: video -> CarLife-link, touch CarLife -> phone. */
class AaBridgeIntegrationTest {
    @Test
    fun aaVideoReachesCarlifeAndCarlifeTouchReachesPhoneThenPlaceholderOnEnd() {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val link = FakeLink()
        val logs = CopyOnWriteArrayList<String>()
        val core = AaBridgeCore({ l, _ -> logs.add(l) }).also { it.attach(link) }
        core.onCarlifeStart()
        val sessionRef = AtomicReference<AapSession>()
        val sessionCreated = CountDownLatch(1)
        val creds = AapCerts.load(TestAssets.opener, "jvc")
        val connector = AaConnector(
            core, creds, { l, _ -> logs.add(l) },
            options = { AapOptions(port = server.localPort, preVideoTimeoutMs = 10_000) },
            retryMs = 200,
            sessionFactory = { sink, o, lg, end ->
                AapSession(creds, sink, o, lg, onEnd = end).also {
                    if (sessionRef.compareAndSet(null, it)) sessionCreated.countDown()
                }
            },
        )
        connector.start()
        val sock = server.accept()
        assertTrue(sessionCreated.await(5, TimeUnit.SECONDS))
        val phone = FakePhone(sock, "jvc", false, "hu_bye", externalTouch = true)
        phone.session = sessionRef.get()
        phone.start()
        // Wait until the video reaches CarLife-link
        val t0 = System.currentTimeMillis()
        while (link.sent.size < 2 && System.currentTimeMillis() - t0 < 20_000) Thread.sleep(20)
        assertEquals("видео не дошло: " + logs.joinToString("\n"), 2, link.sent.size)
        assertArrayEquals(FakePhone.SPS_PPS + FakePhone.IDR, link.sent[0])           // SPS+PPS+IDR
        assertEquals(listOf(7, 8, 5), AnnexB.nalTypes(link.sent[0]))
        assertArrayEquals(FakePhone.SMALL, link.sent[1])
        assertEquals(1, link.hides)
        // touch head unit -> AA-phone
        core.onCarlifeTouch(0, 40, 675)
        core.onCarlifeTouch(1, 40, 675)
        // phone after two touch itself closes the session (requestStop -> ByeBye)
        phone.join(20_000)
        assertEquals(null, phone.error)
        assertEquals(listOf(Triple(0, 40, 675), Triple(1, 40, 675)), phone.touches.toList())
        assertTrue(phone.touchTs.all { it > 0 })
        // AA is over: stub with text, USB (link) does not close
        val t1 = System.currentTimeMillis()
        while (link.texts.last() != AaBridgeCore.TEXT_AA_LOST && System.currentTimeMillis() - t1 < 10_000) Thread.sleep(20)
        assertEquals(AaBridgeCore.TEXT_AA_LOST, link.texts.last())
        // server is missing: connection refused, connector repeats connection
        server.close()
        val a0 = connector.attempts
        val t2 = System.currentTimeMillis()
        while (connector.attempts < a0 + 3 && System.currentTimeMillis() - t2 < 10_000) Thread.sleep(20)
        assertTrue("повторов подключения нет: ${connector.attempts} (было $a0)", connector.attempts >= a0 + 3)
        assertEquals("после отказов текст остаётся", AaBridgeCore.TEXT_AA_LOST, link.texts.last())
        val stopped = CountDownLatch(1)
        connector.stop { stopped.countDown() }
        assertTrue("коннектор не остановился", stopped.await(10, TimeUnit.SECONDS))
        assertFalse(logs.isEmpty())
    }

    @Test
    fun refusedConnectionRetriesAndShowsStartServerText() {
        val port = ServerSocket(0).use { it.localPort }        // port free: connection refused
        val link = FakeLink()
        val logs = CopyOnWriteArrayList<String>()
        val core = AaBridgeCore({ l, _ -> logs.add(l) }).also { it.attach(link) }
        val creds = AapCerts.load(TestAssets.opener, "jvc")
        // own meter: common AaState.serverFailures remembers "server worked" from neighboring tests
        val connector = AaConnector(core, creds, { l, _ -> logs.add(l) }, options = { AapOptions(port = port) }, retryMs = 100, failures = ServerFailures())
        connector.start()
        val t0 = System.currentTimeMillis()
        while (connector.attempts < 4 && System.currentTimeMillis() - t0 < 10_000) Thread.sleep(20)
        assertTrue(connector.attempts >= 4)
        // the server in this process has not yet worked: after the first failure, please immediately turn on
        assertEquals(listOf(AaBridgeCore.TEXT_CONNECTING, AaBridgeCore.TEXT_START_SERVER), link.texts.toList())
        assertEquals(AaBridgeCore.Source.PLACEHOLDER, core.activeSource)
        assertEquals("в логе одна запись про недоступность", 1, logs.count { it.contains("BRIDGE сервер ГУ Android Auto недоступен") })
        val stopped = CountDownLatch(1)
        connector.stop { stopped.countDown() }
        assertTrue(stopped.await(10, TimeUnit.SECONDS))
    }

    @Test
    fun fullAudioSinksAreAnnouncedAndPcmReachesCarlifeChannels() {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val link = FakeLink()
        val logs = CopyOnWriteArrayList<String>()
        val core = AaBridgeCore({ l, _ -> logs.add(l) }).also { it.attach(link) }
        core.onCarlifeStart()
        val sessionRef = AtomicReference<AapSession>()
        val created = CountDownLatch(1)
        val creds = AapCerts.load(TestAssets.opener, "jvc")
        val connector = AaConnector(
            core, creds, { l, _ -> logs.add(l) },
            options = { AapOptions(port = server.localPort, sdProvider = { AapSdOptions(fullAudio = core.audioViaUsb) }) },
            retryMs = 200,
            sessionFactory = { sink, o, lg, end ->
                AapSession(creds, sink, o, lg, onEnd = end).also { if (sessionRef.compareAndSet(null, it)) created.countDown() }
            },
        )
        connector.start()
        val sock = server.accept()
        assertTrue(created.await(5, TimeUnit.SECONDS))
        val phone = FakePhone(sock, "jvc", false, "hu_bye", externalTouch = true, fullAudio = true)
        phone.session = sessionRef.get()
        phone.start()
        val t0 = System.currentTimeMillis()
        while (link.audio.size < 4 && System.currentTimeMillis() - t0 < 20_000) Thread.sleep(20)
        // phone waiting for two touch, then he completes the session
        core.onCarlifeTouch(0, 1, 1)
        core.onCarlifeTouch(1, 1, 1)
        phone.join(20_000)
        assertEquals("ошибка телефона (SDR с аудио / ack): ${phone.error}\n" + logs.joinToString("\n"), null, phone.error)
        // Order as phone: MEDIA init+data, SPEECH init+data, MEDIA stop (-> CarLife PAUSE, TTS is coming: pause waiting); SYSTEM (ch5) is not sent
        val seq = link.audio.map { it.chan to it.type }
        assertEquals(listOf(3 to 0x30001, 3 to 0x30006, 4 to 0x40001, 4 to 0x40003), seq.take(4))
        val t1 = System.currentTimeMillis()
        while (link.audio.size < 6 && System.currentTimeMillis() - t1 < 10_000) Thread.sleep(20)     // End AA: stopAllAudio -> MEDIA_STOP + TTS_STOP
        assertEquals(setOf(3 to 0x30002, 4 to 0x40002), link.audio.drop(4).take(2).map { it.chan to it.type }.toSet())
        assertEquals(3840, link.audio[1].payload.size)
        assertEquals(640, link.audio[3].payload.size)
        assertEquals("0880f70210021810", link.audio[0].payload.joinToString("") { "%02x".format(it) })
        val stopped = CountDownLatch(1)
        server.close()
        connector.stop { stopped.countDown() }
        assertTrue(stopped.await(15, TimeUnit.SECONDS))
    }
}
