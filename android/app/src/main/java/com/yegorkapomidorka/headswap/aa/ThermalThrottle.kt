package com.yegorkapomidorka.headswap.aa

/** The source of heating of the phone (on the device - PhoneThermal on top of PowerManager; in JVM-tests - stub). */
interface ThermalSource {
    /** PowerManager.THERMAL_STATUS_* (0 = NONE). */
    fun status(): Int

    /** getThermalHeadroom forecast for 10 with (1.0 = SEVERE), NaN = not available. */
    fun headroom(): Float

    object NONE : ThermalSource {
        override fun status() = 0
        override fun headroom() = Float.NaN
    }
}

/**
 * transcoding's degree of unloading by phone heating. [fpsCap] - frequency ceiling encoder (null = no limit),
 * [bitrateFraction] - the ceiling share of bitrate mode.
 */
enum class ThermalLevel(val fpsCap: Int?, val bitrateFraction: Double) {
    NORMAL(null, 1.0),
    WARM(20, 0.75),
    HOT(15, 0.5),
}

/**
 * Unloading transcoding by heating (complaints: the phone overheats on the trip). Login - status PowerManager
 * (getCurrentThermalStatus: 0 NONE .. 6 SHUTDOWN) and forecast getThermalHeadroom (1.0 = SEVERE, NaN = unknown).
 * MODERATE or forecast >= [WARM_HEADROOM] - [ThermalLevel.WARM], SEVERE and above - [ThermalLevel.HOT].
 * Down (stronger unloading) - immediately; back - one step, and only if lower already [RELAX_MS] in a row,
 * so that the frequency does not jump back and forth at the status border. Pure logic: time comes with a parameter. [update] - with one thread.
 */
class ThermalThrottle(private val relaxMs: Long = RELAX_MS) {
    /** It is read with any thread ([capFps] from FRAME_RATE_CHANGE processing and creating a transcoder). */
    @Volatile var level = ThermalLevel.NORMAL
        private set

    /** The strongest step for the session and the time on it / above NORMAL - for the resume. */
    var maxLevel = ThermalLevel.NORMAL
        private set
    var throttledMs = 0L
        private set

    private var lowerSinceMs = NEVER
    private var lastMs = NEVER

    /** Step by step, without hysteresis. */
    fun levelFor(status: Int, headroom: Float): ThermalLevel = when {
        status >= STATUS_SEVERE -> ThermalLevel.HOT
        status == STATUS_MODERATE -> ThermalLevel.WARM
        !headroom.isNaN() && headroom >= WARM_HEADROOM -> ThermalLevel.WARM
        else -> ThermalLevel.NORMAL
    }

    /** Another measurement. Returns a new stage if it is changed, otherwise null. */
    fun update(nowMs: Long, status: Int, headroom: Float): ThermalLevel? {
        if (lastMs != NEVER && level != ThermalLevel.NORMAL) throttledMs += maxOf(nowMs - lastMs, 0L)
        lastMs = nowMs
        val want = levelFor(status, headroom)
        if (want > level) {
            level = want
            lowerSinceMs = NEVER
            if (want > maxLevel) maxLevel = want
            return want
        }
        if (want == level) {
            lowerSinceMs = NEVER
            return null
        }
        if (lowerSinceMs == NEVER) lowerSinceMs = nowMs
        if (nowMs - lowerSinceMs < relaxMs) return null
        level = ThermalLevel.values()[level.ordinal - 1]
        lowerSinceMs = if (want < level) nowMs else NEVER      // up to want another step: wait again for relaxMs
        return level
    }

    /** Frequency with heating: not higher than the ceiling of the stage. */
    fun capFps(fps: Int): Int = level.fpsCap?.let { minOf(fps, it) } ?: fps

    /** bitrate ceiling with heating. */
    fun capBitrate(ceilingBps: Int): Int = (ceilingBps * level.bitrateFraction).toInt()

    companion object {
        /** Values of PowerManager.THERMAL_STATUS_*. */
        const val STATUS_MODERATE = 2
        const val STATUS_SEVERE = 3

        /** Forecast for 10 with: so much from the threshold of SEVERE - already unload, without waiting for MODERATE. */
        const val WARM_HEADROOM = 0.85f
        const val RELAX_MS = 60_000L
        private const val NEVER = Long.MIN_VALUE
    }
}
