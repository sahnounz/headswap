package com.yegorkapomidorka.headswap.aa

import com.yegorkapomidorka.headswap.R

/**
 * "Frequency frame" setting: Auto (as requested by head unit in VIDEO_ENCODER_INIT) or fixed; default Auto.
 * It is read once per connection (SessionConfig), passthrough is not valid.
 */
enum class FpsSetting(val titleRes: Int, val subRes: Int, val fixed: Int?) {
    AUTO(R.string.fps_auto_title, R.string.fps_auto_sub, null),
    FPS15(R.string.fps_15_title, R.string.fps_15_sub, 15),
    FPS30(R.string.fps_30_title, R.string.fps_30_sub, 30),
    FPS60(R.string.fps_60_title, R.string.fps_60_sub, 60);

    companion object {
        val DEFAULT = AUTO

        /** By name from prefs; unknown/empty = by default. */
        fun parse(key: String?): FpsSetting = values().firstOrNull { it.name == key } ?: DEFAULT
    }
}

/**
 * frame gate before encoder: at timestamps decides whether to give frame. encoder with Surface- input encodes
 * each swap, so KEY_FRAME_RATE frequency does not limit - it is cut only by this gate.
 * By default, the target >= 30 (or <= 0) misses everything; the Surface desktop line is set by limitHighFps=true.
 * For AA:, AA input is faster than 30 does not happen, and timestep tolerance on 30/30 would lose frame
 * from jitter login. Below 30 is a grid of slots: the slot every period, frame is taken half a period before the slot and later.
 * Called with one thread (GL-relay); targetFps can be changed with any.
 */
class FrameGate(initialFps: Int, private val limitHighFps: Boolean = false) {
    @Volatile var targetFps: Int = initialFps
    private var nextNs = NONE

    /** true = frame give away, false = skip. */
    fun shouldEmit(nowNs: Long): Boolean {
        val fps = targetFps
        if (fps <= 0 || (!limitHighFps && fps >= PASS_ALL_FPS)) {
            nextNs = NONE           // when switching to a smaller target, the grid will start again
            return true
        }
        val period = 1_000_000_000L / fps
        if (nextNs == NONE) {
            nextNs = nowNs + period
            return true
        }
        // High-refresh desktop input needs a narrow rounding tolerance, not AA's
        // half-period jitter allowance (which can emit adjacent fast frames).
        val tolerance = if (limitHighFps) minOf(1_000_000L, period / 2) else period / 2
        if (nowNs < nextNs - tolerance) return false
        nextNs += period
        if (nowNs - nextNs > period) nextNs = nowNs + period     // lag more than a period (entry slower than target): grid re-
        return true
    }

    /** The target frequency period in ns (0 = no limit). */
    fun periodNs(): Long = targetFps.let { if (it <= 0) 0L else 1_000_000_000L / it }

    /** New AA-session: the first frame is given immediately. */
    fun reset() {
        nextNs = NONE
    }

    private companion object {
        const val NONE = Long.MIN_VALUE
        const val PASS_ALL_FPS = 30
    }
}
