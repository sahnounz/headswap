package com.yegorkapomidorka.headswap.aa

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * bitrate encoder by size key frame (Hyundai Tucson, Mobis CCNC, h242): on the map with movement IDR from
 * c2.qti.avc.encoder (VBR, 3,84 Mbit/s) grew every second 200 -> 712 KB, and after ~ 600 KB head unit Goes to Cycle
 * «repeat» VIDEO_ENCODER_START -> EIO». IDR more [limitBytes] - bitrate ×[downFactor] (not below) [minBps]and so on.
 * each such IDR; [calmCount] IDR less in a row [calmBytes] - back ×[upFactor] (not higher) [maxBps]).
 * Pure logic: the codec does not touch. It is called with thread encoder (transcoder and stub), so it is synchronized.
 */
class IdrBitrateCeiling(
    private val maxBps: Int,
    private val minBps: Int = MIN_BPS,
    private val limitBytes: Int = LIMIT_BYTES,
    private val calmBytes: Int = CALM_BYTES,
    private val calmCount: Int = CALM_COUNT,
    private val downFactor: Double = DOWN_FACTOR,
    private val upFactor: Double = UP_FACTOR,
) {
    /** Current target bitrate, bit/s. */
    @Volatile var current = maxBps
        private set

    private var calm = 0

    /** IDR is above the limit, and bitrate is already on the floor: how many of these are in a row (for one line of log, not for every frame). */
    var stuckAtFloor = 0
        private set

    /** Another IDR Size [size] byte. Returns new bitrateIf it has changed, otherwise null. */
    @Synchronized
    fun onKeyFrame(size: Int): Int? {
        if (size > limitBytes) {
            calm = 0
            val target = max(minBps, (current * downFactor).roundToInt())
            if (target >= current) {
                stuckAtFloor++
                return null
            }
            stuckAtFloor = 0
            current = target
            return target
        }
        stuckAtFloor = 0
        if (size >= calmBytes) {
            calm = 0
            return null
        }
        if (++calm < calmCount) return null
        calm = 0
        if (current >= maxBps) return null
        val target = min(maxBps, max(current + 1, (current * upFactor).roundToInt()))
        current = target
        return target
    }

    companion object {
        const val LIMIT_BYTES = 300 * 1024
        const val CALM_BYTES = 150 * 1024
        const val CALM_COUNT = 5
        const val MIN_BPS = 1_000_000
        const val DOWN_FACTOR = 0.7
        const val UP_FACTOR = 1.15
    }
}
