package com.saworks.pblock.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZonedDateTime

class LockPolicyTest {
    private fun t(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int = 0) =
        ZonedDateTime.of(y, mo, d, h, mi, s, 0, LockPolicy.ZONE).toInstant().toEpochMilli()

    private val activated = t(2026, 10, 30, 12, 0)

    @Test fun lockedDuringNovember() {
        assertTrue(LockPolicy.isLocked(activated, t(2026, 11, 15, 3, 0)))
        assertTrue(LockPolicy.isLocked(activated, t(2026, 11, 30, 23, 59, 59)))
    }

    @Test fun unlocksAtMidnightBudapest() {
        assertFalse(LockPolicy.isLocked(activated, t(2026, 12, 1, 0, 0)))
        assertTrue(LockPolicy.isFinished(activated, t(2026, 12, 1, 0, 0)))
        assertFalse(LockPolicy.canActivate(t(2026, 12, 1, 0, 0)))
    }

    @Test fun notActivatedNeverLocked() {
        assertFalse(LockPolicy.isLocked(0L, t(2026, 11, 10, 10, 0)))
        assertFalse(LockPolicy.isFinished(0L, t(2026, 12, 10, 10, 0)))
    }

    @Test fun remainingTime() {
        assertEquals(1L, LockPolicy.remaining(t(2026, 11, 30, 0, 0)).toDays())
        assertEquals(0L, LockPolicy.remaining(t(2026, 12, 2, 0, 0)).toMillis())
    }
}
