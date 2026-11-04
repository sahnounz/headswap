package com.yegorkapomidorka.headswap.aa

import com.yegorkapomidorka.headswap.AnnexB

/** A producer may render directly into this surface; its lifetime belongs to the encoder. */
interface SurfaceVideoInput {
    val surface: android.view.Surface
    val width: Int get() = DesktopVideoGeometry.WIDTH
    val height: Int get() = DesktopVideoGeometry.HEIGHT
    val density: Int get() = DesktopVideoGeometry.density
}

/** All callbacks run in order on a worker, never under the bridge monitor. */
interface DesktopSurfaceListener {
    fun onSurfaceAvailable(generation: Long, input: SurfaceVideoInput)

    /** Release the matching virtual display before returning, before its consumer is destroyed. */
    fun onSurfaceClosing(generation: Long)
    fun onFirstFrame(generation: Long) {}
}

/** Where the transcoder gives the result (realizes the core of the bridge). Challenges come with thread MediaCodec. */
interface TranscodeOutput {
    /** SPS+PPS encoder (Annex-B, 4-byte start codes). */
    fun onEncoderConfig(csd: ByteArray)

    /** frame encoder (Annex-B). */
    fun onEncoderFrame(data: ByteArray, key: Boolean)

    fun onTranscodeError(msg: String)

    /** decoder needs a new IDR from AA (frame is lost). */
    fun onNeedAaKeyFrame()
}

/**
 * AA H.264 -> decoder (Surface) -> Surface input encoder -> H.264 for CarLife. Realization on MediaCodec
 * (AaTranscoder); the bridge core works with the interface so that logic is tested on JVM.
 */
interface VideoTranscoder {
    /** Non-null only for an encoder accepting raw surface frames instead of compressed AA video. */
    val surfaceInput: SurfaceVideoInput? get() = null

    /**
     * CODEC_CONFIG AA (SPS+PPS). The new SPS -> decoder will be re-created on the next IDR.
     * Does not block, processed on thread transcoder; "nofeedthread" in tuning is the old synchronous path.
     */
    fun feedConfig(annexB: ByteArray)

    /**
     * frame AA (access unit). The first frame decoder should be IDR.
     * Does not block, processed on thread transcoder (queue full -> frame is lost to IDR, onNeedAaKeyFrame);
     * "nofeedthread" in tuning is an old synchronous path.
     */
    fun feedFrame(annexB: ByteArray, ptsUs: Long)

    /**
     * reset decoder (new AA-session): encoder stays alive. Does not block, runs on thread transcoder
     * after already taken frame; "nofeedthread" in tuning is the old synchronous path.
     */
    fun resetDecoder()

    fun requestSyncFrame()

    /** Start the transition from frame stub to picture AA (zoom-solution) with the first next frame. Without GL - nothing. */
    fun startIntro(banner: android.graphics.Bitmap) {}

    /** Target frequency of exit (0 = not limited). */
    val targetFps: Int get() = 0

    /** Change the target frequency on the fly, without recreating the codec. */
    fun setTargetFps(fps: Int) {}

    /** Current bitrate encoder, bit/s (0 = unknown). */
    val currentBitrate: Int get() = 0

    /** Replace bitrate encoder on the fly (adaptation to the drain speed head unit), without recreating the codec. Errors do not throw. */
    fun setBitrate(bps: Int) {}

    /** Stats line out the window from the previous call (decoder->encoder delay, fps, repeats). */
    fun statsLine(): String

    fun stop()
}

/** Annex-B flow parsing H.264 (pure JVM-logic): NAL- and access unit-. */
object H264Stream {
    class AccessUnit(val data: ByteArray, val idr: Boolean)

    /** NAL-s stream entirely, each with a 4-byte start code. */
    fun nalUnits(stream: ByteArray): List<ByteArray> {
        val d = AnnexB.normalize(stream)
        val starts = ArrayList<Int>()
        var i = 0
        while (i + 3 < d.size) {
            if (d[i].toInt() == 0 && d[i + 1].toInt() == 0 && d[i + 2].toInt() == 0 && d[i + 3].toInt() == 1) {
                starts.add(i)
                i += 4
            } else {
                i++
            }
        }
        val res = ArrayList<ByteArray>()
        for ((k, s) in starts.withIndex()) {
            val e = if (k + 1 < starts.size) starts[k + 1] else d.size
            res.add(d.copyOfRange(s, e))
        }
        return res
    }

    fun nalType(nal: ByteArray): Int = if (nal.size > 4) nal[4].toInt() and 0x1F else -1

    /** SPS and PPS (first) from config/stream. */
    fun spsPps(stream: ByteArray): Pair<ByteArray?, ByteArray?> {
        val nals = nalUnits(stream)
        return Pair(nals.firstOrNull { nalType(it) == 7 }, nals.firstOrNull { nalType(it) == 8 })
    }

    /**
     * Divides the stream by access unit-y: new image starts on slide with first_mb_in_slice == 0
     * (older first byte bit after header NAL = 1) or on SPS/PPS/SEI/AUD after slices.
     * Returns (SPS+PPS first frame, list AU).
     */
    fun split(stream: ByteArray): Pair<ByteArray?, List<AccessUnit>> {
        val nals = nalUnits(stream)
        val aus = ArrayList<AccessUnit>()
        var cur = ArrayList<ByteArray>()
        var curVcl = false
        var curIdr = false
        fun flush() {
            if (cur.isNotEmpty() && curVcl) {
                val size = cur.sumOf { it.size }
                val out = ByteArray(size)
                var p = 0
                for (n in cur) {
                    System.arraycopy(n, 0, out, p, n.size)
                    p += n.size
                }
                aus.add(AccessUnit(out, curIdr))
            }
            cur = ArrayList()
            curVcl = false
            curIdr = false
        }
        for (n in nals) {
            val t = nalType(n)
            when (t) {
                1, 5 -> {
                    val firstSlice = n.size > 5 && (n[5].toInt() and 0x80) != 0
                    if (curVcl && firstSlice) flush()
                    cur.add(n)
                    curVcl = true
                    if (t == 5) curIdr = true
                }
                6, 7, 8, 9 -> {
                    if (curVcl) flush()
                    cur.add(n)
                }
                else -> cur.add(n)
            }
        }
        flush()
        val cfgNals = nals.filter { nalType(it) == 7 || nalType(it) == 8 }
        val sps = cfgNals.firstOrNull { nalType(it) == 7 }
        val pps = cfgNals.firstOrNull { nalType(it) == 8 }
        val cfg = if (sps != null && pps != null) sps + pps else null
        return Pair(cfg, aus)
    }
}
