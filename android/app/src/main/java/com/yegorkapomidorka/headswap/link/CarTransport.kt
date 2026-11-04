package com.yegorkapomidorka.headswap.link

/**
 * The transport properties of CarLife. for USB are the same as they were before abstraction.
 * kind goes to the log line "transport = ..." and to WL-log.
 */
class TransportCaps(
    /** usb-aoa, tcp-debug (one stream with external header), tcp6 (6 socket Baidu without external header). */
    val kind: String,
    /** Flow with external header [chan u32][len u32] (USB AOA, debug-TCP). */
    val outerHeader: Boolean,
    /** Each channel has its own socket, a writer on the channel. */
    val separateSockets: Boolean,
    /** The break comes with a system event (USB_ACCESSORY_DETACHED), not just EOF. */
    val nativeDetach: Boolean,
) {
    companion object {
        val USB_AOA = TransportCaps("usb-aoa", outerHeader = true, separateSockets = false, nativeDetach = true)
        val TCP_DEBUG = TransportCaps("tcp-debug", outerHeader = true, separateSockets = false, nativeDetach = false)
        val TCP6 = TransportCaps("tcp6", outerHeader = false, separateSockets = true, nativeDetach = false)
    }
}

/**
 * Transport CarLife at the level of "channel + inner- message" (inner = internal header 8/12 byte + body).
 * The protocol (CarLifeSession) does not know how the channels lie on the wire: one stream with an external header or its socket per channel.
 */
interface CarTransport {
    val caps: TransportCaps

    /** For logo and screen: description of accessory or address head unit. */
    val label: String

    /**
     * Start reading and writing. sink calls serialized (one thread at a time), onWritten too.
     * onClosed is called no more than once and only when the breakage is on the side of transport, not after close().
     */
    fun start(
        sink: (chan: Int, inner: ByteArray) -> Unit,
        onWritten: (chan: Int, inner: ByteArray) -> Unit,
        onClosed: (reason: String) -> Unit,
    )

    /**
     * Write as Baidu: 8 byte external header one write, all inner one write (h242, Mobis CCNC).
     * All you need is a stream with an external header; transports without it are ignored.
     */
    fun setWholeWrites(on: Boolean) {}

    /** Non-blocking inner in the channel queue. */
    fun send(chan: Int, inner: ByteArray)

    /** Close everything, wake up thread. Idempotently, onClosed does not call. */
    fun close()

    /** Accepted byte from head unit (all channels). */
    val rxBytes: Long

    /** Messages in the queue where the chan channel stands (the stream has one common queue for all channels). */
    fun queueSize(chan: Int): Int

    /**
     * Toss out of the queue the not-yet-recorded inner channel chan, for which [pred] is true; how many are discarded.
     * Video counters do not touch (the sound does not enter there). By default does not clean anything.
     */
    fun dropQueued(chan: Int, pred: (ByteArray) -> Boolean): Int = 0

    val queuedVideoBytes: Long
    val queuedVideoFrames: Int

    /** Video upload speed, bytes/s (0 = not yet measured). */
    val drainBytesPerSec: Double
}
