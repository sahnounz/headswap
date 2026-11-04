package com.yegorkapomidorka.headswap.aa

import org.junit.Assert.assertEquals
import org.junit.Test

class TouchPacerTest {
    private val timers = FakeTimers()
    private val out = ArrayList<Pair<Long, Int>>()     // Sending time, y*10+action
    private val pacer = TouchBurstPacer({ timers.now }, timers::schedule) { a, _, y, _, _ -> out.add(timers.now to (y * 10 + (a and 0xFF))) }

    private fun move(y: Int) = pacer.onTouch(AapMessages.TOUCH_MOVE, 100, y, 0, 0)

    /** A pack of n MOVE with a gap of gap ms, like the Delphi. */
    private fun burst(from: Int, n: Int, gap: Long = 1) {
        for (i in 0 until n) {
            move(from + i)
            if (i < n - 1) timers.advance(gap)
        }
    }

    private fun moveTimes() = out.filter { it.second % 10 == AapMessages.TOUCH_MOVE }.map { it.first }

    @Test
    fun burstIsSpreadByItsOwnSize() {
        pacer.onTouch(AapMessages.TOUCH_DOWN, 100, 0, 0, 0)
        burst(1, 14)                                               // 0..13 ms; pitch 196 / 14 = 14 ms
        assertEquals(listOf(0L), moveTimes())                      // first MOVE immediately, the rest are waiting
        timers.advance(196 - 13)
        assertEquals((0..13).map { it * 14L }, moveTimes())
        out.clear()
        burst(15, 7)                                               // short pack at the end of gesture: step 196 / 7 = 28 ms
        timers.advance(196)
        assertEquals((0..6).map { 196 + it * 28L }, moveTimes())
        assertEquals((15..21).map { it * 10 + AapMessages.TOUCH_MOVE }, out.map { it.second })
    }

    @Test
    fun slowBurstReplansOnEveryEvent() {
        pacer.onTouch(AapMessages.TOUCH_DOWN, 100, 0, 0, 0)
        burst(1, 14, gap = 2)                                      // pack stretches 26 ms, longer step
        timers.advance(196 - 26)
        // while the pack is incomplete, the second MOVE went according to 196 / 10; after the last event - grid by 14 ms
        assertEquals(listOf(0L, 19L) + (2..13).map { it * 14L }, moveTimes())
    }

    @Test
    fun tailIsFlushedInOrderWhenNextBurstComesEarly() {
        burst(1, 14)
        timers.advance(100 - 13)                                   // gone 0, 14 .. 98: the first 8
        assertEquals(8, out.size)
        burst(15, 2)
        assertEquals((1..15).map { it * 10 + AapMessages.TOUCH_MOVE }, out.map { it.second })
        assertEquals(List(7) { 100L }, out.drop(8).map { it.first })  // 6 tail and first MOVE new pack
    }

    @Test
    fun fingerPauseDoesNotStretchInterval() {
        burst(1, 14)
        timers.advance(1000 - 13)                                  // finger stood: 1000 ms > 1,5 x 196
        out.clear()
        burst(15, 14)
        timers.advance(196)
        assertEquals((0..13).map { 1000 + it * 14L }, moveTimes())  // step still 14 ms
    }

    @Test
    fun upFlushesQueueAndStopsTimer() {
        pacer.onTouch(AapMessages.TOUCH_DOWN, 100, 0, 0, 0)
        burst(1, 14)
        timers.advance(196 - 13)
        burst(15, 14)
        timers.advance(5)                                          // pack 196..209, the second MOVE went to 196 + 14
        val sent = out.size
        pacer.onTouch(AapMessages.TOUCH_UP, 100, 99, 0, 0)          // UP: first the whole queue, then UP
        assertEquals((17..28).map { it * 10 + AapMessages.TOUCH_MOVE } + (99 * 10 + AapMessages.TOUCH_UP), out.drop(sent).map { it.second })
        timers.advance(1000)
        assertEquals(sent + 13, out.size)                          // after UP timer sends nothing
        assertEquals(1, pacer.bursts)
    }
}
