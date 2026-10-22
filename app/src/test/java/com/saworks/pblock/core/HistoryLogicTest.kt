package com.saworks.pblock.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class HistoryLogicTest {
    private val zone = ZoneId.of("Europe/Budapest")
    private fun t(d: Int, h: Int) = ZonedDateTime.of(2026, 11, d, h, 0, 0, 0, zone).toInstant().toEpochMilli()

    @Test fun cutoff() {
        assertNull(HistoryLogic.cutoff(1_000_000_000L, 0))
        assertEquals(1_000_000_000L - 30L * 86_400_000L, HistoryLogic.cutoff(1_000_000_000L, 30))
        assertTrue(HistoryLogic.DEFAULT_RETENTION_DAYS in HistoryLogic.RETENTION_CHOICES)
    }

    @Test fun summarizeCountsPerLocalDay() {
        val ev = listOf(
            HistoryLogic.Event(t(1, 10), Events.DNS),
            HistoryLogic.Event(t(1, 23), Events.APP),
            HistoryLogic.Event(t(2, 0), Events.DNS_FILTER),
            HistoryLogic.Event(t(2, 5), Events.CLOCK),
            HistoryLogic.Event(t(2, 6), Events.HARD_MOMENT),
            HistoryLogic.Event(t(2, 7), "unknown"),
            HistoryLogic.Event(t(1, 1), Events.DNS), // before countFrom
        )
        val m = HistoryLogic.summarize(ev, zone, countFrom = t(1, 5))
        assertEquals(DayCount(attempts = 2), m[LocalDate.of(2026, 11, 1)])
        assertEquals(DayCount(attempts = 1, warnings = 1, hard = 1), m[LocalDate.of(2026, 11, 2)])
        assertTrue(HistoryLogic.summarize(ev, zone, countFrom = 0).isEmpty())
    }

    @Test fun merge() {
        val d1 = LocalDate.of(2026, 11, 1); val d2 = LocalDate.of(2026, 11, 2)
        val m = HistoryLogic.merge(mapOf(d1 to DayCount(1, 0, 0)), mapOf(d1 to DayCount(2, 1, 0), d2 to DayCount(0, 0, 1)))
        assertEquals(DayCount(3, 1, 0), m[d1])
        assertEquals(DayCount(0, 0, 1), m[d2])
    }
}
