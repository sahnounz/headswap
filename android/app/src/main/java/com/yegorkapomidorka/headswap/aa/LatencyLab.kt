package com.yegorkapomidorka.headswap.aa

/**
 * Model head unit, which shows frame with a constant frequency [fps] without passes (on its own clock or on the frame counter).
 * Then each frame above [fps] per second remains in the queue forever, and leaves the queue only in the pauses of the stream.
 * The delay account of this model in the log is compared with the measurement on the screen: the same - head unit is arranged like this.
 */
class GuQueueModel(val fps: Int = 30) {
    private var level = 0.0
    private var last = Long.MIN_VALUE
    var maxMs = 0L
        private set

    fun onFrame(nowMs: Long) {
        decay(nowMs)
        level += 1.0
        val ms = delayMs(nowMs)
        if (ms > maxMs) maxMs = ms
    }

    /** Late display of the latest frame in the model: frame in line in front of it, ms. */
    fun delayMs(nowMs: Long): Long {
        decay(nowMs)
        return ((level - 1.0).coerceAtLeast(0.0) * 1000 / fps).toLong()
    }

    fun resetMax() {
        maxMs = 0
    }

    /** head unit reset decoder (new CarLife-session, IDR from AA after loss): queue is empty. */
    fun reset() {
        level = 0.0
        last = Long.MIN_VALUE
    }

    private fun decay(nowMs: Long) {
        if (last != Long.MIN_VALUE && nowMs > last) level = (level - (nowMs - last) * fps / 1000.0).coerceAtLeast(0.0)
        if (nowMs > last) last = nowMs
    }
}

/**
 * SPS parsing for log. head unit without bitstream_restriction can keep in DPB to MaxDpbFrames level frame before
 * output (C.4.5.3, "bumping"); max_num_reorder_frames = 0 allows you to output frame immediately after decoding.
 */
object SpsVui {
    class Info(
        val profile: Int, val level: Int, val pocType: Int, val maxRef: Int, val vui: Boolean,
        val timing: String?, val hrd: Boolean, val reorder: Int?, val maxDecBuf: Int?,
    ) {
        override fun toString(): String = "profile $profile level $level, POC type $pocType, max_num_ref_frames $maxRef, " +
            if (!vui) "VUI нет" else "VUI: " + listOfNotNull(
                timing?.let { "timing $it" },
                if (hrd) "HRD" else null,
                if (reorder != null) "max_num_reorder_frames $reorder, max_dec_frame_buffering $maxDecBuf" else "bitstream_restriction нет",
            ).joinToString(", ")
    }

    private val HIGH_PROFILES = setOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)

    /** nal - SPS without start code. */
    fun parse(nal: ByteArray): Info {
        require(nal.isNotEmpty() && nal[0].toInt() and 0x1F == 7) { "не SPS" }
        val rbsp = SpsCrop.removeEmulationPrevention(nal)
        val r = IdrSplice.Bits(rbsp)
        try {
            r.skip(8)
            val profile = r.u(8)
            r.skip(8)
            val level = r.u(8)
            r.ue()
            if (profile in HIGH_PROFILES) {
                val chroma = r.ue()
                if (chroma == 3) r.skip(1)
                r.ue(); r.ue()
                r.skip(1)
                if (r.u(1) == 1) for (k in 0 until if (chroma != 3) 8 else 12) if (r.u(1) == 1) skipScalingList(r, if (k < 6) 16 else 64)
            }
            r.ue()                                      // log2_max_frame_num_minus4
            val pocType = r.ue()
            when (pocType) {
                0 -> r.ue()
                1 -> {
                    r.skip(1); r.se(); r.se()
                    repeat(r.ue()) { r.se() }
                }
            }
            val maxRef = r.ue()
            r.skip(1)
            r.ue(); r.ue()
            if (r.u(1) == 0) r.skip(1)                  // frame_mbs_only_flag, mb_adaptive_frame_field_flag
            r.skip(1)                                   // direct_8x8_inference_flag
            if (r.u(1) == 1) repeat(4) { r.ue() }       // frame_cropping
            if (r.u(1) == 0) return Info(profile, level, pocType, maxRef, false, null, false, null, null)
            if (r.u(1) == 1 && r.u(8) == 255) r.skip(32)    // aspect_ratio_info, Extended_SAR
            if (r.u(1) == 1) r.skip(1)                  // overscan
            if (r.u(1) == 1) {                          // video_signal_type
                r.skip(4)
                if (r.u(1) == 1) r.skip(24)
            }
            if (r.u(1) == 1) {                          // chroma_loc_info
                r.ue(); r.ue()
            }
            var timing: String? = null
            if (r.u(1) == 1) {
                val units = r.u32()
                val scale = r.u32()
                val fixed = r.u(1) == 1
                timing = "$units/$scale${if (fixed) " fixed" else ""}"
            }
            val nalHrd = r.u(1) == 1
            if (nalHrd) skipHrd(r)
            val vclHrd = r.u(1) == 1
            if (vclHrd) skipHrd(r)
            if (nalHrd || vclHrd) r.skip(1)             // low_delay_hrd_flag
            r.skip(1)                                   // pic_struct_present_flag
            var reorder: Int? = null
            var dec: Int? = null
            if (r.u(1) == 1) {                          // bitstream_restriction
                r.skip(1)                               // motion_vectors_over_pic_boundaries_flag
                repeat(4) { r.ue() }                    // max_bytes_per_pic_denom, max_bits_per_mb_denom, log2_max_mv_length h/v
                reorder = r.ue()
                dec = r.ue()
            }
            return Info(profile, level, pocType, maxRef, true, timing, nalHrd || vclHrd, reorder, dec)
        } catch (e: IndexOutOfBoundsException) {
            throw IllegalArgumentException("SPS обрывается на бите ${r.pos}")
        }
    }

    private fun IdrSplice.Bits.u32(): Long = (u(16).toLong() shl 16) or u(16).toLong()

    private fun skipHrd(r: IdrSplice.Bits) {
        val cpbCnt = r.ue() + 1
        r.skip(8)                                       // bit_rate_scale, cpb_size_scale
        repeat(cpbCnt) {
            r.ue(); r.ue(); r.skip(1)
        }
        r.skip(20)                                      // 4 field by 5 bit
    }

    private fun skipScalingList(r: IdrSplice.Bits, size: Int) {
        var last = 8
        var next = 8
        for (j in 0 until size) {
            if (next != 0) next = (last + r.se() + 256) % 256
            last = if (next == 0) last else next
        }
    }
}
