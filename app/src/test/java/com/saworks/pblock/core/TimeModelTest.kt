package com.saworks.pblock.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZonedDateTime

class TimeModelTest {
    private val zone = LockPolicy.ZONE
    private fun t(d: Int, h: Int = 12, mo: Int = 11) = ZonedDateTime.of(2026, mo, d, h, 0, 0, 0, zone).toInstant().toEpochMilli()
    private val activated = t(1, 8)

    @Test fun trustedNowOnlyOnSameBoot() {
        val s = TimeState(anchorTrusted = t(10), anchorElapsed = 1_000, anchorBoot = 5)
        assertEquals(t(10) + 60_000, TimeModel.trustedNow(s, 61_000, 5))
        assertNull(TimeModel.trustedNow(s, 61_000, 6))
        assertNull(TimeModel.trustedNow(s, 500, 5))
        assertNull(TimeModel.trustedNow(TimeState(), 61_000, 5))
    }

    @Test fun deviceClockForwardDoesNotUnlock() {
        // Device clock says December, but trusted time is mid-November.
        val s = TimeState(anchorTrusted = t(15), anchorElapsed = 0, anchorBoot = 3, maxTrusted = t(15))
        val lb = TimeModel.lowerBound(s, 60_000, 3)
        assertTrue(TimeModel.isLocked(activated, lb))
        assertFalse(TimeModel.isFinished(activated, lb))
        // The estimate ignores the wall clock while trusted time is known.
        assertEquals(t(15) + 60_000, TimeModel.estimate(s, 60_000, 3, wall = t(5, 12, 12)))
    }

    @Test fun noTrustedTimeFailsClosed() {
        val s = TimeState()
        val lb = TimeModel.lowerBound(s, 1_000, 1)
        assertEquals(0L, lb)
        assertTrue(TimeModel.isLocked(activated, lb))
        assertTrue(TimeModel.awaitingTrustedEnd(activated, lb, estimate = t(2, 12, 12)))
        assertFalse(TimeModel.awaitingTrustedEnd(activated, lb, estimate = t(20)))
    }

    @Test fun unlocksWhenTrustedTimePassesEnd() {
        val s = TimeState(anchorTrusted = LockPolicy.LOCK_END_MILLIS - 1_000, anchorElapsed = 0, anchorBoot = 1)
        assertTrue(TimeModel.isLocked(activated, TimeModel.lowerBound(s, 500, 1)))
        assertTrue(TimeModel.isFinished(activated, TimeModel.lowerBound(s, 1_000, 1)))
        // After a reboot the persisted max still counts (it never goes backwards).
        val after = s.copy(maxTrusted = LockPolicy.LOCK_END_MILLIS + 5)
        assertTrue(TimeModel.isFinished(activated, TimeModel.lowerBound(after, 10, 2)))
    }

    @Test fun notActivatedIsNeverLocked() {
        assertFalse(TimeModel.isLocked(0, 0))
        assertFalse(TimeModel.isFinished(0, LockPolicy.LOCK_END_MILLIS + 1))
    }

    @Test fun estimateOfflineUsesBootBaselineAndFloor() {
        // Baseline captured at boot 7: wall - elapsed. Later the clock is set back a week; estimate ignores it.
        val s = TimeState(maxTrusted = t(9), baseOffset = t(10) - 1_000, baseBoot = 7)
        assertEquals(t(10) + 59_000, TimeModel.estimate(s, 60_000, 7, wall = t(3)))
        // Different boot without a baseline: wall clock, but never below the trusted floor.
        assertEquals(t(9), TimeModel.estimate(s, 60_000, 8, wall = t(3)))
        assertEquals(t(20), TimeModel.estimate(s, 60_000, 8, wall = t(20)))
    }

    @Test fun clockJumpAndTamper() {
        val offset = t(10) - 5_000 // wall - elapsed
        assertEquals(0L, TimeModel.clockJump(offset, t(10) + 10_000, 15_000))
        val jump = TimeModel.clockJump(offset, t(12) + 10_000, 15_000)
        assertEquals(t(12) - t(10), jump)
        assertTrue(TimeModel.isTamper(jump, t(12), trusted = t(10)))
        assertTrue(TimeModel.isTamper(jump, t(12), trusted = null))
        assertFalse(TimeModel.isTamper(30_000, t(10), trusted = null)) // small drift correction
        // Setting the clock back to the real time is a restoration, not tampering.
        assertFalse(TimeModel.isTamper(-(t(12) - t(10)), t(10), trusted = t(10) + 20_000))
    }

    @Test fun behindTrustedAfterBoot() {
        assertTrue(TimeModel.isBehindTrusted(t(5), t(10)))
        assertFalse(TimeModel.isBehindTrusted(t(10) - 1_000, t(10)))
        assertFalse(TimeModel.isBehindTrusted(t(5), 0))
    }

    @Test fun sanityRange() {
        assertTrue(TimeModel.isSane(t(10)))
        assertFalse(TimeModel.isSane(0))
        assertFalse(TimeModel.isSane(ZonedDateTime.of(2041, 1, 1, 0, 0, 0, 0, zone).toInstant().toEpochMilli()))
    }
}
