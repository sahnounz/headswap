package com.yegorkapomidorka.headswap.aa

/** Transcoder feeding queue element: frame AA or command thread feeding. */
sealed class FeedItem {
    class Frame(val annexB: ByteArray, val ptsUs: Long, val idr: Boolean, val atNs: Long) : FeedItem()
    class Config(val annexB: ByteArray) : FeedItem()
    object Reset : FeedItem()
    object Stop : FeedItem()
}

enum class Offer { ACCEPTED, DROPPED_FULL, DROPPED_GATED }

/**
 * The queue between thread AA and thread feeding decoder (pure JVM-logic). Login is never blocked.
 * frame limited [maxFrames]: in case of overflow frame Lost and the queue is closed until the next IDR
 * (P without frame decoder support is not needed). IDR is self-sufficient: it displaces all waiting frame
 * (command remain in place), removes the closure and passes always. command ([post]) is not denied.
 * One User ([take]), several manufacturers.
 */
class FeedQueue(private val maxFrames: Int = DEFAULT_MAX_FRAMES) {
    private val items = ArrayDeque<FeedItem>()
    private var frames = 0
    private var gated = false
    private var maxSeen = 0
    private val mon = Object()

    val pendingFrames: Int get() = synchronized(mon) { frames }

    /** Maximum depth of frame from the past [resetMaxPending] (without reset). */
    val maxPendingSeen: Int get() = synchronized(mon) { maxSeen }

    /** Returns the maximum depth from the previous call and starts the window anew from the current depth. */
    fun resetMaxPending(): Int = synchronized(mon) {
        val m = maxSeen
        maxSeen = frames
        m
    }

    fun offerFrame(f: FeedItem.Frame): Offer = synchronized(mon) {
        if (gated && !f.idr) return Offer.DROPPED_GATED
        if (f.idr) {
            if (frames > 0) {
                items.removeAll { it is FeedItem.Frame }
                frames = 0
            }
            gated = false
        } else if (frames >= maxFrames) {
            gated = true
            return Offer.DROPPED_FULL
        }
        items.addLast(f)
        frames++
        if (frames > maxSeen) maxSeen = frames
        mon.notifyAll()
        Offer.ACCEPTED
    }

    /** The Config/Reset/Stop: is always in the tail, regardless of filling. */
    fun post(cmd: FeedItem) {
        require(cmd !is FeedItem.Frame) { "кадры - через offerFrame" }
        synchronized(mon) {
            items.addLast(cmd)
            mon.notifyAll()
        }
    }

    /** Waiting for the element to [timeoutMs] ms; null - timeout (or interruption of thread). */
    fun take(timeoutMs: Long): FeedItem? = synchronized(mon) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (items.isEmpty()) {
            val leftNs = deadline - System.nanoTime()
            if (leftNs <= 0) return null
            try {
                mon.wait(leftNs / 1_000_000L, (leftNs % 1_000_000L).toInt())
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
        }
        val x = items.removeFirst()
        if (x is FeedItem.Frame) frames--
        x
    }

    /** Drop everything and remove the closure. */
    fun clear() = synchronized(mon) {
        items.clear()
        frames = 0
        gated = false
    }

    companion object {
        /**
         * About 0.5 seconds at 30 fps. Decoder creation takes 80–300 ms (up to 18 frames at 60 fps), and those frames must wait
         * it, not knock out IDR-request to AA at every start. On 4 frame pack of 10 frame crowded queue
         * directly during createDecoder (transcode_test burst, 23.10.2026).
         */
        const val DEFAULT_MAX_FRAMES = 16
    }
}
