package com.yegorkapomidorka.headswap.aa

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor

class LatencyLabTest {
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    /** SPS AA from record pc-headunit: Baseline 1280x720, POC 2, VUI with bitstream_restriction (reorder 0, dec_buf 1). */
    private val aa720 = hex("6742801fda014016e9a8283028368509a8")
    /** It is also without VUI and with max_num_reorder_frames = 2, max_dec_frame_buffering = 3 (assembled manually). */
    private val aa720NoVui = hex("6742801fda014016e4")
    private val aa720Reorder2 = hex("6742801fda014016e9a82830283682211648")
    private val x264Baseline1080 = hex("6742c028d900780227e5c044000003000400000300f03c60c920")

    @Test
    fun parsesAaVui() {
        val i = SpsVui.parse(aa720)
        assertEquals(66, i.profile)
        assertEquals(31, i.level)
        assertEquals(2, i.pocType)
        assertEquals(1, i.maxRef)
        assertTrue(i.vui)
        assertNull(i.timing)
        assertEquals(0, i.reorder)
        assertEquals(1, i.maxDecBuf)
    }

    @Test
    fun parsesTimingAndCrop() {
        val i = SpsVui.parse(x264Baseline1080)
        assertEquals("1/60", i.timing)
        assertEquals(3, i.maxRef)
        assertEquals(0, i.reorder)
        assertEquals(3, i.maxDecBuf)
    }

    @Test
    fun parsesMissingVuiAndReorder() {
        assertFalse(SpsVui.parse(aa720NoVui).vui)
        assertEquals(2, SpsVui.parse(aa720Reorder2).reorder)
        assertEquals(3, SpsVui.parse(aa720Reorder2).maxDecBuf)
    }

    @Test
    fun queueModelGrowsOnlyWithExtraFrames() {
        val m = GuQueueModel(30)
        var t = 0L
        for (k in 0 until 900) {                                // 30 with 30 fps
            t = k * 1000L / 30
            m.onFrame(t)
        }
        assertTrue("${m.delayMs(t)}", m.delayMs(t) <= 40)
        for (k in 900 until 2700) {                             // more 60 s, extra frame times in 2 s
            t = k * 1000L / 30
            m.onFrame(t)
            if (k % 60 == 0) m.onFrame(t)
        }
        assertTrue("${m.delayMs(t)}", m.delayMs(t) in 900..1100)
        assertTrue(m.maxMs >= m.delayMs(t))
        assertEquals(0, m.delayMs(t + 1500))                    // pause in the flow: the queue is gone
    }
}

/** Its IDR instead of frame AA and the frame emission in front of it on the x264 stream from IdrSpliceTest. */
class IdrSpliceDropTest {
    private var now = 0L
    private val link = TranscodeLink()
    private val logs = CopyOnWriteArrayList<String>()
    private val dec = FakeRefDecoder()
    private var queueBuilds = false
    private val builds = ArrayList<Runnable>()
    private val core = AaBridgeCore({ l, _ -> logs.add(l) }, { now }).also {
        it.transcodeEnabled = false
        it.spliceMs = 2000
        it.spliceDrop = 0                                       // release - by default, is checked explicitly
        it.refDecoderFactory = { _ -> dec }
        it.spliceExecutor = Executor { r -> if (queueBuilds) builds.add(r) else r.run() }
        it.attach(link)
    }
    private val split = SpliceAssets.split()
    private val cfg = split.first
    private val aus = split.second

    private fun passed(seq: Long) {
        core.wantsRefPicture(seq)
    }

    private fun decoded(seq: Long) {
        assertTrue(core.wantsRefPicture(seq))
        core.onRefPicture(seq, SpliceAssets.picture((seq - 1).toInt()))
    }

    private fun startWith(frames: Int) {
        core.onCarlifeStart()
        core.onCodecConfig(cfg)
        for (k in 0 until frames) core.onFrame(aus[k], k.toLong())
    }

    /** Before his first IDR: frame 1-5 gone, frame 6 (picture # 6) came at the moment when it is time and leaves IDR instead of him. */
    private fun runToSplice(): Int {
        startWith(4)
        core.tick()                                             // start of the statistics window
        passed(4)
        now = 1990
        core.onFrame(aus[4], 4)
        passed(5)
        now = 2000
        core.onFrame(aus[5], 5)
        val before = link.sent.size
        core.onFrame(aus[6], 6)                                 // held up to IDR
        assertEquals(before, link.sent.size)
        decoded(6)
        return before
    }

    @Test
    fun replaceSendsIdrInPlaceOfAaFrame() {
        val before = runToSplice()
        assertEquals(5, before)                                 // frame 6 is not gone: IDR will take its place
        assertEquals(7, link.sent.size)                         // frame in head unit is the same as from AA
        assertEquals(listOf(7, 8, 5), SpliceAssets.nalTypes(link.sent[5]))
        assertEquals(listOf(1, 1), SpliceAssets.nalTypes(link.sent[6]))
        assertFalse(aus[6].contentEquals(link.sent[6]))         // frame_num shifted from IDR
        assertTrue(logs.toString(), logs.any { it.contains("свой IDR #1") && it.contains("вместо кадра AA") })
        now = 31_000; core.tick()
        assertTrue(logs.toString(), logs.any { it.contains("своих IDR 1 (вместо кадра AA 1, лишних 0") })
        assertTrue(logs.toString(), logs.any { it.contains("BRIDGE SPS AA: profile 66") })
    }

    /** IDR did not have time: frame, instead of which he was waiting for, goes first, behind him restrained. */
    @Test
    fun replaceReleasesReplacedFrameFirstWhenIdrIsLate() {
        startWith(3)
        passed(3)
        now = 1990; core.onFrame(aus[3], 3)
        passed(4)
        queueBuilds = true
        now = 2000; core.onFrame(aus[4], 4)                     // Substituted
        core.onFrame(aus[5], 5)                                 // held
        assertEquals(4, link.sent.size)
        decoded(5)
        assertEquals(1, builds.size)
        now = 2300; core.tick()
        assertEquals(6, link.sent.size)
        assertArrayEquals(aus[4], link.sent[4])
        assertArrayEquals(aus[5], link.sent[5])
        builds.removeAt(0).run()
        assertEquals(6, link.sent.size)                         // The IDR is not going away.
    }

    /** The 1 emission: frame before the picture IDR does not go away, IDR stands instead of two frame AA - in head unit on frame less. */
    @Test
    fun dropSendsIdrInPlaceOfTwoAaFrames() {
        core.spliceDrop = 1
        startWith(4)
        core.tick()
        passed(4)
        now = 1990; core.onFrame(aus[4], 4)
        passed(5)
        now = 2000; core.onFrame(aus[5], 5)                     // Ejected
        assertEquals(5, link.sent.size)
        passed(6)
        core.onFrame(aus[6], 6)                                 // Substituted
        core.onFrame(aus[7], 7)                                 // held
        assertEquals(5, link.sent.size)
        decoded(7)
        assertEquals(7, link.sent.size)                         // 8 frame AA, in head unit 7: IDR instead of frame 6 and 7
        assertEquals(listOf(7, 8, 5), SpliceAssets.nalTypes(link.sent[5]))
        assertEquals(listOf(1, 1), SpliceAssets.nalTypes(link.sent[6]))
        assertTrue(logs.toString(), logs.any { it.contains("свой IDR #1") && it.contains("вместо 2 кадров AA") })
        now = 31_000; core.tick()
        assertTrue(logs.toString(), logs.any { it.contains("выброшено кадров AA 1 (за сессию 1), выброс отменён 0") })
    }

    /** The emissions started, and the AA fell silent: the discarded frame goes away as it was. */
    @Test
    fun dropFlushesWhenAaStops() {
        core.spliceDrop = 1
        startWith(4)
        passed(4)
        now = 1990; core.onFrame(aus[4], 4)
        passed(5)
        now = 2000; core.onFrame(aus[5], 5)
        assertEquals(5, link.sent.size)
        now = 2300; core.tick()
        assertEquals(6, link.sent.size)
        assertArrayEquals(aus[5], link.sent[5])
    }

    /** Static (frame after pause) - Assembly in the background, frame goes immediately and in replacement mode. */
    @Test
    fun replaceKeepsBackgroundBuildAfterPause() {
        startWith(3)
        passed(3)
        queueBuilds = true
        now = 2500
        core.onFrame(aus[3], 3)
        assertEquals(4, link.sent.size)
        assertArrayEquals(aus[3], link.sent.last())
    }
}
