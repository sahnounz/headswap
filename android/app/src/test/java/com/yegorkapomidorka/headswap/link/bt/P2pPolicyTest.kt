package com.yegorkapomidorka.headswap.link.bt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class P2pPolicyTest {
    @Test
    fun firstTargetStarts() {
        assertEquals(P2pOnTarget.START, P2pPolicy.onTarget(active = false, joined = false))
    }

    /** h126: failed to enter (BUSY), head unit reconnected BT and sent TARGET_INFO again - start over, not "I'm already entering". */
    @Test
    fun newTargetRestartsStuckAttempt() {
        assertEquals(P2pOnTarget.RESTART, P2pPolicy.onTarget(active = true, joined = false))
    }

    @Test
    fun newTargetSkippedWhenAlreadyInGroup() {
        assertEquals(P2pOnTarget.SKIP, P2pPolicy.onTarget(active = true, joined = true))
    }

    /** h211: rejoin asks TARGET_INFO times in 5-10 with; the same answer does not tear a fresh try, faded or with other props - rips. */
    @Test
    fun sameTargetKeepsFreshAttempt() {
        assertEquals(P2pOnTarget.SKIP, P2pPolicy.onTarget(active = true, joined = false, same = true, ageMs = 9_000L, freshMs = 30_000L))
        assertEquals(P2pOnTarget.RESTART, P2pPolicy.onTarget(active = true, joined = false, same = true, ageMs = 30_000L, freshMs = 30_000L))
        assertEquals(P2pOnTarget.RESTART, P2pPolicy.onTarget(active = true, joined = false, same = false, ageMs = 1_000L, freshMs = 30_000L))
        assertEquals(P2pOnTarget.START, P2pPolicy.onTarget(active = false, joined = false, same = false, ageMs = 0L))
    }

    @Test
    fun targetSameAs() {
        val a = P2pTarget("CarLife-x", null, null, null)
        assertEquals(true, a.sameAs(P2pTarget("CarLife-x", null, null, null)))
        assertEquals(false, a.sameAs(P2pTarget("CarLife-y", null, null, null)))
        assertEquals(false, a.sameAs(P2pTarget("CarLife-x", "DIRECT-ab", "12345678", null)))
    }

    /** h211: after the collapse of the group when alive BT ask TARGET_INFO themselves - first quickly, then once in 10 s, then enough. */
    @Test
    fun rejoinBackoff() {
        assertEquals(2_000L, P2pPolicy.rejoinDelayMs(0))
        assertEquals(5_000L, P2pPolicy.rejoinDelayMs(1))
        assertEquals(10_000L, P2pPolicy.rejoinDelayMs(2))
        assertEquals(10_000L, P2pPolicy.rejoinDelayMs(P2pPolicy.REJOIN_ATTEMPTS - 1))
        assertEquals(null, P2pPolicy.rejoinDelayMs(P2pPolicy.REJOIN_ATTEMPTS))
    }

    /** h211/h185: four requests ( ~ 27 (c) Without entrance, first 30-second attempt ended - on the step 4 (~37 (c) Ripe RFCOMM. */
    @Test
    fun rejoinDropsBtAfterFirstFailedJoin() {
        assertEquals(false, P2pPolicy.rejoinDropBt(0))
        assertEquals(false, P2pPolicy.rejoinDropBt(P2pPolicy.REJOIN_DROP_BT_AFTER - 1))
        assertEquals(true, P2pPolicy.rejoinDropBt(P2pPolicy.REJOIN_DROP_BT_AFTER))
        val dropAtMs = (0 until P2pPolicy.REJOIN_DROP_BT_AFTER + 1).sumOf { P2pPolicy.rejoinDelayMs(it)!! }
        assertTrue("сброс BT раньше конца первой попытки входа: $dropAtMs мс", dropAtMs > 32_000L)
    }
}
