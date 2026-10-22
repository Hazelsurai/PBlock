package com.saworks.pblock.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime

class CalendarLogicTest {
    private val nov = YearMonth.of(2026, 11)
    private fun d(day: Int) = LocalDate.of(2026, 11, day)

    @Test fun november2026StartsOnSunday() {
        assertEquals(6, CalendarLogic.leadingBlanks(nov))
        assertEquals(30, CalendarLogic.buildMonth(nov, null, d(1), emptyMap()).size)
    }

    @Test fun marksPastDaysSinceStart() {
        val cells = CalendarLogic.buildMonth(nov, d(3), d(10), mapOf(d(5) to 2, d(1) to 4, d(12) to 1))
        fun mark(day: Int) = cells[day - 1].mark
        assertEquals(DayMark.NONE, mark(1))   // before start, even with attempts
        assertEquals(DayMark.NONE, mark(2))
        assertEquals(DayMark.STAR, mark(3))   // start day, clean
        assertEquals(DayMark.STAR, mark(4))
        assertEquals(DayMark.CHECK, mark(5))  // blocked attempts
        assertEquals(2, cells[4].attempts)
        assertEquals(DayMark.STAR, mark(9))
        assertEquals(DayMark.NONE, mark(10))  // today stays neutral
        assertTrue(cells[9].isToday)
        assertEquals(DayMark.NONE, mark(11))  // future
        assertEquals(DayMark.NONE, mark(12))
    }

    @Test fun noChallengeMeansAllNeutral() {
        val cells = CalendarLogic.buildMonth(nov, null, d(20), mapOf(d(5) to 3))
        assertTrue(cells.all { it.mark == DayMark.NONE && !it.inChallenge })
    }

    @Test fun startedInOctoberCoversWholeMonth() {
        val cells = CalendarLogic.buildMonth(nov, LocalDate.of(2026, 10, 25), LocalDate.of(2026, 12, 5), emptyMap())
        assertTrue(cells.all { it.mark == DayMark.STAR })
    }

    @Test fun countsPerDayInBudapestTime() {
        val zone = ZoneId.of("Europe/Budapest")
        val lateUtc = ZonedDateTime.of(2026, 11, 4, 23, 30, 0, 0, ZoneId.of("UTC")).toInstant().toEpochMilli() // 00:30 on Nov 5 in Budapest
        val noon = ZonedDateTime.of(2026, 11, 4, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
        val map = CalendarLogic.countPerDay(listOf(lateUtc, noon, noon + 1000), zone)
        assertEquals(2, map[d(4)])
        assertEquals(1, map[d(5)])
    }

    @Test fun warningDayIsNotAStar() {
        val cells = CalendarLogic.buildMonth(nov, d(1), d(10), mapOf(d(3) to 2), mapOf(d(4) to 1, d(3) to 1, d(10) to 1, d(12) to 1))
        val m = cells.associate { it.date to it.mark }
        assertEquals(DayMark.STAR, m[d(2)])
        assertEquals(DayMark.WARN, m[d(3)]) // warning beats check
        assertEquals(DayMark.WARN, m[d(4)])
        assertEquals(DayMark.WARN, m[d(10)]) // shown already today
        assertEquals(DayMark.NONE, m[d(12)]) // future
        assertEquals(1, cells.first { it.date == d(4) }.warnings)
    }

    @Test fun streakCountsCleanDaysBeforeToday() {
        val cells = CalendarLogic.buildMonth(nov, d(1), d(10), mapOf(d(3) to 1), mapOf(d(6) to 1))
        assertEquals(3, CalendarLogic.currentStreak(cells, d(10))) // 7, 8, 9
        assertEquals(0, CalendarLogic.currentStreak(cells, d(7))) // yesterday (6) had a warning
        assertEquals(2, CalendarLogic.currentStreak(cells, d(3))) // 1 and 2
        assertEquals(0, CalendarLogic.currentStreak(cells, d(1)))
    }

    @Test fun daysRemaining() {
        assertEquals(30, CalendarLogic.daysRemaining(d(1)))
        assertEquals(1, CalendarLogic.daysRemaining(d(30)))
        assertEquals(0, CalendarLogic.daysRemaining(LocalDate.of(2026, 12, 2)))
        assertEquals(40, CalendarLogic.daysRemaining(LocalDate.of(2026, 10, 22)))
    }
}
