package com.yegorkapomidorka.headswap.aa

/** CarLife video queue for bridge statistics (CarlifeLink.videoStats). */
class LinkVideoStats(
    /** Queue drain rate in USB, byte/s (0 = not yet measured). */
    val drainBytesPerSec: Double,
    /** Bytes of video in line / drain speed, ms. */
    val backlogMs: Long,
    val queueFrames: Int,
    val queueBytes: Long,
)

/**
 * Video queue drain speed in USB: bytes of video frames during recording (write+flushin the windows.1 c, smoothing EWMA.
 * Time is taken by recording ALL packages (sound and command share a channel with video), bytes - only video: it turns out
 * real channel bandwidth for video in the current mix. Writes one thread (writer), read any.
 */
class DrainMeter(private val windowMs: Long = WINDOW_MS) {
    /** Byte/s, 0 = not yet measured. */
    @Volatile var bytesPerSec = 0.0
        private set

    private var winStartMs = NEVER
    private var winVideoBytes = 0L
    private var winBusyNs = 0L

    fun onWrite(nowMs: Long, video: Boolean, bytes: Int, durNs: Long) {
        if (winStartMs == NEVER) winStartMs = nowMs
        if (video) winVideoBytes += bytes
        winBusyNs += durNs
        if (nowMs - winStartMs < windowMs) return
        if (winVideoBytes > 0 && winBusyNs >= MIN_BUSY_NS) {
            val sample = winVideoBytes * 1e9 / winBusyNs
            val old = bytesPerSec
            bytesPerSec = if (old <= 0) sample else ALPHA * sample + (1 - ALPHA) * old
        }
        winStartMs = nowMs
        winVideoBytes = 0
        winBusyNs = 0
    }

    private companion object {
        const val WINDOW_MS = 1000L
        const val ALPHA = 0.5
        const val MIN_BUSY_NS = 50_000_000L     // in the window almost did not write: assessment noisy, the former do not touch
        const val NEVER = Long.MIN_VALUE
    }
}

/** Limit video queue CarLife and recalculate its length during. Pure features. */
object VideoQueueLimit {
    const val MAX_FRAMES = 30
    const val MAX_BACKLOG_MS = 500L

    /** Until the plum is measured. */
    const val UNMEASURED_MAX_BYTES = 512 * 1024L

    /** Lower limit on bytes: With a very slow drain, one IDR should not be considered overflowing on its own. */
    const val MIN_BYTES = 64 * 1024L

    /** How much after overflow it counts as pressure on the bitrate and what backlog (see [policyBacklogMs]). */
    const val OVERFLOW_HOLD_MS = 1500L
    const val OVERFLOW_BACKLOG_MS = 1000.0

    /** Queue backlog in ms; 0 until drain is measured. */
    fun backlogMs(queueBytes: Long, drainBytesPerSec: Double): Long =
        if (drainBytesPerSec > 0) (queueBytes * 1000 / drainBytesPerSec).toLong() else 0L

    /**
     * The queue is full: there are more video frames already standing in it 30 or their bytes more ~ 500 ms plum. New frame in the account
     * does not go: in an empty queue is always accepted any frame (IDR on 160 KB), otherwise head unit would not have received any.
     */
    fun overflow(queueFrames: Int, queueBytes: Long, drainBytesPerSec: Double): Boolean {
        if (queueFrames > MAX_FRAMES) return true
        val limit = if (drainBytesPerSec > 0) maxOf((drainBytesPerSec * MAX_BACKLOG_MS / 1000).toLong(), MIN_BYTES) else UNMEASURED_MAX_BYTES
        return queueBytes > limit
    }

    /**
     * Backlog for bitrate. policy The very limit of queue cuts backlog (frame when overflowing do not get in line,
     * backlog saws from zero to IDR), and EWMA such saw does not reach 300 ms.
     * ([sinceOverflowMs] < [OVERFLOW_HOLD_MS]) is considered a backlog of at least [OVERFLOW_BACKLOG_MS].
     */
    fun policyBacklogMs(realMs: Long, sinceOverflowMs: Long): Double =
        if (sinceOverflowMs in 0 until OVERFLOW_HOLD_MS) maxOf(realMs.toDouble(), OVERFLOW_BACKLOG_MS) else realMs.toDouble()
}

/** IDR request limiter for encoder when queue overflows: no more than once in [minIntervalMs]. */
class IdrThrottle(private val minIntervalMs: Long = MIN_INTERVAL_MS) {
    private var lastMs = NEVER

    /** true = request can be sent (time is remembered); false = waiting for already requested or nearest natural IDR. */
    fun tryRequest(nowMs: Long): Boolean {
        if (lastMs != NEVER && nowMs - lastMs < minIntervalMs) return false
        lastMs = nowMs
        return true
    }

    fun reset() {
        lastMs = NEVER
    }

    companion object {
        const val MIN_INTERVAL_MS = 1500L
        private const val NEVER = Long.MIN_VALUE
    }
}
