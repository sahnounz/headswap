package com.yegorkapomidorka.headswap.aa

import com.yegorkapomidorka.headswap.Handshake
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThermalThrottleTest {
    private val nan = Float.NaN

    @Test
    fun coolPhoneIsNotThrottled() {
        val t = ThermalThrottle()
        assertNull(t.update(0, 0, nan))
        assertNull(t.update(1000, 1, 0.5f))     // LIGHT and stock according to forecast: no unloading
        assertEquals(ThermalLevel.NORMAL, t.level)
        assertEquals(30, t.capFps(30))
        assertEquals(4_000_000, t.capBitrate(4_000_000))
    }

    @Test
    fun moderateAndSevereStepDownImmediately() {
        val t = ThermalThrottle()
        assertEquals(ThermalLevel.WARM, t.update(0, ThermalThrottle.STATUS_MODERATE, nan))
        assertEquals(20, t.capFps(30))
        assertEquals(15, t.capFps(15))          // Below the ceiling, as is
        assertEquals(3_000_000, t.capBitrate(4_000_000))
        assertEquals(ThermalLevel.HOT, t.update(1000, ThermalThrottle.STATUS_SEVERE, nan))
        assertEquals(15, t.capFps(30))
        assertEquals(2_000_000, t.capBitrate(4_000_000))
        assertEquals(ThermalLevel.HOT, t.levelFor(6, nan))
    }

    @Test
    fun headroomForecastThrottlesBeforeModerate() {
        val t = ThermalThrottle()
        assertEquals(ThermalLevel.WARM, t.update(0, 1, 0.9f))
    }

    @Test
    fun relaxesOneStepAfterQuietPeriod() {
        val t = ThermalThrottle(relaxMs = 60_000)
        t.update(0, ThermalThrottle.STATUS_SEVERE, nan)
        assertNull(t.update(1_000, 0, nan))
        assertNull(t.update(60_000, 0, nan))
        assertEquals(ThermalLevel.WARM, t.update(61_000, 0, nan))
        // to NORMAL again wait full period
        assertNull(t.update(100_000, 0, nan))
        assertEquals(ThermalLevel.NORMAL, t.update(121_000, 0, nan))
        assertEquals(ThermalLevel.HOT, t.maxLevel)
    }

    @Test
    fun bounceResetsRelaxTimer() {
        val t = ThermalThrottle(relaxMs = 60_000)
        t.update(0, ThermalThrottle.STATUS_MODERATE, nan)
        t.update(1_000, 0, nan)
        t.update(30_000, ThermalThrottle.STATUS_MODERATE, nan)     // Warm again: countdown again
        assertNull(t.update(31_000, 0, nan))
        assertNull(t.update(90_000, 0, nan))
        assertEquals(ThermalLevel.NORMAL, t.update(91_000, 0, nan))
    }

    @Test
    fun countsThrottledTime() {
        val t = ThermalThrottle()
        t.update(0, 0, nan)
        t.update(1_000, ThermalThrottle.STATUS_MODERATE, nan)
        t.update(6_000, ThermalThrottle.STATUS_MODERATE, nan)
        assertEquals(5_000, t.throttledMs)
    }

    @Test
    fun bitratePolicyThermalCapCutsAndReleases() {
        val p = BitratePolicy(4_000_000)
        assertTrue(p.setThermalCap(2_000_000))
        assertEquals(2_000_000, p.current)
        // Quiet line: above the heat ceiling does not rise
        var now = 0L
        repeat(60) {
            p.update(now, 0.0, 500_000.0)
            now += 1000
        }
        assertEquals(2_000_000, p.current)
        // cool: ceiling is removed, lifting by conventional steps to the ceiling of the mode
        assertFalse(p.setThermalCap(Int.MAX_VALUE))
        repeat(120) {
            p.update(now, 0.0, 500_000.0)
            now += 1000
        }
        assertEquals(4_000_000, p.current)
    }

    @Test
    fun pioneerCeilingHoldsWithThermalCap() {
        // Pioneer RM56E8B (h277): 800 kbit/s ceiling ( = floor) under the flag of head unit, mode asks for 4 Mbit/s; heating is taken from the already lowered ceiling
        val ceiling = AaBridgeCore.PIONEER_RM56_CEILING_BPS
        val p = BitratePolicy(4_000_000)
        val t = ThermalThrottle()
        assertTrue(p.lowerCeiling(ceiling))
        assertEquals(ceiling, p.current)
        // Quiet line: above the ceiling head unit does not rise
        var now = 0L
        repeat(120) {
            p.update(now, 0.0, 2_000_000.0)
            now += 1000
            assertTrue(p.current <= ceiling)
        }
        assertEquals(ceiling, p.current)
        // heating HOT (×0.5 from the ceiling head unit, not below the floor) cuts harder, cooling returns to the ceiling head unit, but not higher
        t.update(0, ThermalThrottle.STATUS_SEVERE, nan)
        p.setThermalCap(t.capBitrate(p.ceilingBps))      // head unit ceiling is already equal to the floor: there is nowhere to cut, current does not change
        assertEquals(BitratePolicy.FLOOR_BPS, p.current)
        p.setThermalCap(Int.MAX_VALUE)
        repeat(300) {
            p.update(now, 0.0, 2_000_000.0)
            now += 1000
            assertTrue(p.current <= ceiling)
        }
        assertEquals(ceiling, p.current)
        // ceiling head unit comes later than thermal (flag after heating measurement): the result is still min
        val q = BitratePolicy(4_000_000)
        q.setThermalCap(3_000_000)
        assertTrue(q.lowerCeiling(ceiling))
        assertEquals(ceiling, q.current)
        repeat(120) {
            q.update(now, 0.0, 2_000_000.0)
            now += 1000
        }
        assertEquals(ceiling, q.current)
    }

    @Test
    fun pioneerFpsCapCombinesWithThermalViaMin() {
        val cap = AaBridgeCore.PIONEER_RM56_MAX_FPS
        val t = ThermalThrottle()
        assertEquals(10, t.capFps(Handshake.capFps(30, cap)))
        t.update(0, ThermalThrottle.STATUS_MODERATE, nan)      // WARM: 20, ceiling head unit 10 remains
        assertEquals(10, t.capFps(Handshake.capFps(30, cap)))
        assertEquals(8, t.capFps(Handshake.capFps(8, cap)))
        t.update(1_000, ThermalThrottle.STATUS_SEVERE, nan)    // HOT: 15, ceiling head unit 10
        assertEquals(10, t.capFps(Handshake.capFps(30, cap)))
        assertEquals(15, t.capFps(30))
    }

    @Test
    fun bitratePolicyThermalCapNotBelowFloor() {
        val p = BitratePolicy(4_000_000)
        p.setThermalCap(100_000)
        assertEquals(BitratePolicy.FLOOR_BPS, p.current)
    }
}
