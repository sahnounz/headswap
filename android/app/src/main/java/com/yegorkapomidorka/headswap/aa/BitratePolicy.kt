package com.yegorkapomidorka.headswap.aa

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Options [BitratePolicy] under transport. [USB] - previous behavior (cable: start from the ceiling, down to 0.8 plum,
 * up +10% Once in 10 (c) [WIFI] - wireless connection (Plan E3, 5.3Start with 0.75 ceiling, down not less than ×0.7,
 * up +20% times in 5 with after 10 with calm, above start - only after 60 with no downs.
 */
data class BitrateProfile(
    val name: String,
    /** Start of the session: share of the ceiling. */
    val startFraction: Double,
    /** Step down no less: the target is no higher than current * downStep (1.0 = plum only). */
    val downStep: Double,
    /** Down and without measured drain (only step [downStep]). */
    val downWithoutDrain: Boolean,
    val upFactor: Double,
    /** Calm down after the fall before you go up. */
    val upWaitMs: Long,
    /** Between the climbing steps. */
    val upIntervalMs: Long,
    /** Above the starting bitrate - only after so many ms without recessions (since the beginning of the session or the last recession). */
    val aboveStartAfterMs: Long,
) {
    companion object {
        val USB = BitrateProfile("usb", 1.0, 1.0, false, 1.1, 10_000L, 10_000L, 0L)
        val WIFI = BitrateProfile("wifi", 0.75, 0.7, true, 1.2, 10_000L, 5_000L, 60_000L)
    }
}

/**
 * Target bitrate encoder as of video queue CarLife: head unit receives video at limited speed
 * (Kia Seltos ~210 KiB/s), encoder on 6 Mbit/s scores the queue and the delay increases to seconds. Down - to 0.8 speed of discharge,
 * Then, after a pause (see below). [BitrateProfile]). Pure logic: time comes parameter, codec does not touch.
 * [update] called one. thread, [lowerCeiling] - with any; [current] Read with anyone.
 */
class BitratePolicy(ceilingBps: Int, floorBps: Int = FLOOR_BPS, val profile: BitrateProfile = BitrateProfile.USB) {
    val floorBps = min(floorBps, ceilingBps)

    /** Ceiling, bit / s. Only down and on the fly - [lowerCeiling] (head unit with a limit on size frame). */
    @Volatile var ceilingBps = ceilingBps
        private set

    /**
     * Phone heating ceiling, bit/s (Int.MAX_VALUE = no). Unlike [ceilingBps] returns back when
     * The phone has cooled: the rise goes on with the usual steps.
     */
    @Volatile var thermalCapBps = Int.MAX_VALUE
        private set

    /** bitrate start of the session: ceiling (USB) or its share (wireless connection). */
    val startBps = max(this.floorBps, (ceilingBps * profile.startFraction).toInt())

    /** Current target, bit/s. */
    @Volatile var current = startBps
        private set

    /** Backlog after smoothing, ms (for log and tests). */
    var smoothedMs = 0.0
        private set

    /** Description of the last change (for one line of log in caller). */
    var lastReason = ""
        private set

    var downs = 0
        private set
    var ups = 0
        private set
    var minBps = startBps
        private set

    private var lastSampleMs = NEVER
    private var lastDownMs = NEVER
    private var calmSinceMs = NEVER
    private var upNotBeforeMs = NEVER
    private var cleanSinceMs = NEVER

    /**
     * Another measurement: queue backlog (ms) and drain speed (byte/s, 0 = not yet measured). Returns a new bitrate,
     * if it has changed, otherwise null. Backlog is smoothed by EWMA time (tau 1 s), so single IDR on 160 KB
     * does not roll back, and the result does not depend on the frequency of calls.
     */
    @Synchronized
    fun update(nowMs: Long, backlogMs: Double, drainBytesPerSec: Double): Int? {
        smoothedMs = if (lastSampleMs == NEVER) backlogMs else {
            val dt = max(nowMs - lastSampleMs, 0L)
            smoothedMs + (1 - exp(-dt / TAU_MS)) * (backlogMs - smoothedMs)
        }
        lastSampleMs = nowMs
        if (cleanSinceMs == NEVER) cleanSinceMs = nowMs

        if (smoothedMs < UP_BACKLOG_MS) {
            if (calmSinceMs == NEVER) calmSinceMs = nowMs
        } else {
            calmSinceMs = NEVER
        }

        val drainOk = drainBytesPerSec > 0 || profile.downWithoutDrain
        if (smoothedMs > DOWN_BACKLOG_MS && drainOk && (lastDownMs == NEVER || nowMs - lastDownMs >= DOWN_INTERVAL_MS)) {
            var target = current
            if (drainBytesPerSec > 0) target = min(target, (drainBytesPerSec * 8 * DOWN_FACTOR).toInt())
            if (profile.downStep < 1.0) target = min(target, (current * profile.downStep).toInt())
            target = max(floorBps, target)
            if (target < current) {
                lastReason = "бэклог %.0f мс (сглаженный) > %d мс, слив %.0f КиБ/с".format(smoothedMs, DOWN_BACKLOG_MS, drainBytesPerSec / 1024)
                current = target
                lastDownMs = nowMs
                cleanSinceMs = nowMs
                calmSinceMs = NEVER
                upNotBeforeMs = nowMs + profile.upWaitMs
                downs++
                if (target < minBps) minBps = target
                return target
            }
        }

        val limit = min(ceilingBps, thermalCapBps)
        if (calmSinceMs != NEVER && nowMs - calmSinceMs >= profile.upWaitMs && nowMs >= upNotBeforeMs && current < limit) {
            val cap = if (nowMs - cleanSinceMs >= profile.aboveStartAfterMs) limit else min(startBps, limit)
            if (current >= cap) return null
            val target = min(cap, max(current + 1, (current * profile.upFactor).toInt()))
            lastReason = "бэклог < %d мс уже %d с".format(UP_BACKLOG_MS, (nowMs - calmSinceMs) / 1000)
            current = target
            upNotBeforeMs = nowMs + profile.upIntervalMs
            ups++
            return target
        }
        return null
    }

    /**
     * Lower the ceiling (not lower) [floorBps]It does not rise above the present. If [current] Above the new ceiling,
     * cuts to it. true = ceiling changed (caller matches [current] and applies to encoder).
     */
    @Synchronized
    fun lowerCeiling(newCeilingBps: Int): Boolean {
        val c = max(floorBps, newCeilingBps)
        if (c >= ceilingBps) return false
        ceilingBps = c
        if (current > c) {
            current = c
            lastReason = "потолок снижен до %d кбит/с".format(c / 1000)
            if (c < minBps) minBps = c
        }
        return true
    }

    /**
     * Heating ceiling (no lower than [floorBps]; Int.MAX_VALUE removes it). [current] above the ceiling is cut immediately.
     * true = [current] The Change (caller applies it to encoder).
     */
    @Synchronized
    fun setThermalCap(capBps: Int): Boolean {
        thermalCapBps = if (capBps == Int.MAX_VALUE) capBps else max(floorBps, capBps)
        if (current <= thermalCapBps) return false
        current = thermalCapBps
        lastReason = "нагрев, потолок %d кбит/с".format(thermalCapBps / 1000)
        if (current < minBps) minBps = current
        return true
    }

    companion object {
        const val FLOOR_BPS = 800_000
        const val DOWN_BACKLOG_MS = 300L
        const val UP_BACKLOG_MS = 100L
        const val DOWN_INTERVAL_MS = 1000L
        const val UP_WAIT_MS = 10_000L
        const val DOWN_FACTOR = 0.8
        const val UP_FACTOR = 1.1
        private const val TAU_MS = 1000.0
        private const val NEVER = Long.MIN_VALUE
    }
}
