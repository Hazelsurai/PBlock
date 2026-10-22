package com.saworks.pblock.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

class LockModelTest {
    @Test fun timeLockFailsClosed() {
        val until = 2_000_000L
        assertFalse(TimeModel.isLockedUntil(0L, 5_000_000L))
        assertTrue(TimeModel.isLockedUntil(until, 0L)) // no trusted time yet
        assertTrue(TimeModel.isLockedUntil(until, until - 1))
        assertFalse(TimeModel.isLockedUntil(until, until))
    }

    @Test fun awaitingTrustedUntil() {
        val until = 2_000_000L
        assertTrue(TimeModel.awaitingTrustedUntil(until, 1_000_000L, until + 10))
        assertFalse(TimeModel.awaitingTrustedUntil(until, 1_000_000L, until - 10))
        assertFalse(TimeModel.awaitingTrustedUntil(until, until, until + 10))
    }

    @Test fun defaultChallengeIsNovember2026() {
        val c = LockPolicy.CHALLENGE
        assertEquals(listOf(YearMonth.of(2026, 11)), c.months)
        assertEquals(LockPolicy.LOCK_END_MILLIS, c.endMillis)
        assertEquals(LocalDate.of(2026, 12, 1), c.end.toLocalDate())
    }

    @Test fun challengeSpanningMonths() {
        val c = Challenge(LocalDate.of(2026, 12, 15), LocalDate.of(2027, 2, 2), ZoneId.of("UTC"))
        assertEquals(listOf(YearMonth.of(2026, 12), YearMonth.of(2027, 1), YearMonth.of(2027, 2)), c.months)
        assertEquals(java.time.Instant.parse("2027-02-03T00:00:00Z").toEpochMilli(), c.endMillis)
    }

    @Test(expected = IllegalArgumentException::class)
    fun challengeEndBeforeStartRejected() {
        Challenge(LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 1), ZoneId.of("UTC"))
    }

    @Test fun dateTimeGuardOnlyForTimeLocks() {
        val s = SettingsGuard.Screen("com.android.settings", "com.android.settings.SubSettings",
            "Date & time\nSet time automatically\nTime zone")
        assertTrue(SettingsGuard.shouldBounce(s))
        assertFalse(SettingsGuard.shouldBounce(s, guardDateTime = false))
        val app = SettingsGuard.Screen("com.android.settings", "com.android.settings.SubSettings", "PBlock\nUninstall\nForce stop")
        assertTrue(SettingsGuard.shouldBounce(app, guardDateTime = false))
    }
}
