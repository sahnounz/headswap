package com.yegorkapomidorka.headswap.aa

/**
 * Smoothing packs touch (h425, VW CNS2 Delphi 20082114). head unit Sucks MOVE ~196 ms and gives a pack of 9-14 Events
 * after 5-20 ms: points are complete, time is down, in AA Scroll goes jerks with frequency ~ 5 Hz. First MOVE packs go away immediately,
 * the rest are distributed evenly on the interval between the packs: k-i - at the time of beginning + k * interval / size of the pack. Size
 * take from the current pack and recalculate for each of its events: by the first shipment, the pack is usually already all in line, and guess
 * the past can not - at the edges of the gesture packs shorter. The interval - the average of the packs without pauses finger. Price - constant
 * delay of following the finger by one pack. DOWN / UP / second finger: first send the queue, then the event as it is.
 */
class TouchBurstPacer(
    private val clockMs: () -> Long,
    private val schedule: (Long, Runnable) -> MicTimerHandle,
    private val sink: (action: Int, x: Int, y: Int, px: Int, py: Int) -> Unit,
) {
    private class Ev(val action: Int, val x: Int, val y: Int, val px: Int, val py: Int)

    private val lock = Any()
    private val queue = ArrayDeque<Ev>()
    private var tick: MicTimerHandle? = null
    private var gen = 0
    private var lastArrival = NEVER
    private var burstStart = NEVER
    private var burstSize = 0
    private var sent = 0                       // how many events of the current pack have already been sent
    private var interval = DEFAULT_INTERVAL_MS

    /** How many packs are smoothed (for log). */
    var bursts = 0
        private set

    fun onTouch(action: Int, x: Int, y: Int, px: Int, py: Int) = synchronized(lock) {
        val act = action and 0xFF
        val single = px == 0 && py == 0 && action ushr 8 == 0
        if (act != AapMessages.TOUCH_MOVE || !single) {
            flushLocked()
            resetBurstLocked()
            sink(action, x, y, px, py)
            return@synchronized
        }
        val now = clockMs()
        val newBurst = now - lastArrival > BURST_GAP_MS
        lastArrival = now
        if (newBurst) {
            if (burstStart != NEVER) {
                if (burstSize > 1) bursts++
                val gap = now - burstStart
                // the finger was standing (a pack-two head unit did not send): in the average do not take
                if (gap <= interval * 3 / 2) interval = ((interval + gap + 1) / 2).coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS)
            }
            // the tail of the last pack did not have time to distribute: we send so that the delay does not accumulate
            flushLocked()
            burstStart = now
            burstSize = 1
            sent = 1
            sink(action, x, y, px, py)
            return@synchronized
        }
        burstSize++
        queue.addLast(Ev(action, x, y, px, py))
        armLocked()
    }

    /** Session closed: queue and timer are thrown out. */
    fun reset() = synchronized(lock) {
        queue.clear()
        cancelTickLocked()
        resetBurstLocked()
        interval = DEFAULT_INTERVAL_MS
    }

    /** The timer to the next event queues for the current size of the pack; a new pack event rearranges it earlier. */
    private fun armLocked() {
        cancelTickLocked()
        if (queue.isEmpty()) return
        val at = burstStart + sent * interval / burstSize
        val g = ++gen
        tick = schedule((at - clockMs()).coerceAtLeast(0), Runnable {
            synchronized(lock) {
                if (g != gen) return@synchronized
                tick = null
                val e = queue.removeFirstOrNull() ?: return@synchronized
                sink(e.action, e.x, e.y, e.px, e.py)
                sent++
                armLocked()
            }
        })
    }

    private fun flushLocked() {
        cancelTickLocked()
        while (queue.isNotEmpty()) {
            val e = queue.removeFirst()
            sink(e.action, e.x, e.y, e.px, e.py)
        }
    }

    private fun cancelTickLocked() {
        gen++
        tick?.cancel()
        tick = null
    }

    private fun resetBurstLocked() {
        lastArrival = NEVER
        burstStart = NEVER
        burstSize = 0
        sent = 0
    }

    companion object {
        // log h425 22.10 (66 packs): inside the pack gaps 0-4 ms, between packs from 176 ms, median interval 196 ms, p10-p90 191-199
        const val BURST_GAP_MS = 7L
        const val DEFAULT_INTERVAL_MS = 196L
        const val MIN_INTERVAL_MS = 30L
        const val MAX_INTERVAL_MS = 300L
        private const val NEVER = Long.MIN_VALUE / 4
    }
}
