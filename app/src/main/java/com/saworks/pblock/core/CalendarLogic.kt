package com.saworks.pblock.core

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** NONE: nothing to show; STAR: clean day; CHECK: blocked attempts; WARN: protections down / clock tampering. */
enum class DayMark { NONE, STAR, CHECK, WARN }

data class DayCell(
    val date: LocalDate,
    val mark: DayMark,
    val attempts: Int,
    val isToday: Boolean,
    val inChallenge: Boolean,
    val warnings: Int = 0,
)

object CalendarLogic {

    /** Empty cells before day 1 in a Monday-first grid. */
    fun leadingBlanks(ym: YearMonth): Int = ym.atDay(1).dayOfWeek.value - 1

    /**
     * Since the challenge start: WARN for any day (today included) with a warning event (safe mode,
     * protections off, clock change), else for past days CHECK if there were blocked attempts, STAR if clean.
     * A WARN day is never counted as a clean star. Future days and days before the start stay NONE.
     */
    fun buildMonth(
        ym: YearMonth,
        challengeStart: LocalDate?,
        today: LocalDate,
        attemptsPerDay: Map<LocalDate, Int>,
        warningsPerDay: Map<LocalDate, Int> = emptyMap(),
    ): List<DayCell> =
        (1..ym.lengthOfMonth()).map { d ->
            val date = ym.atDay(d)
            val attempts = attemptsPerDay[date] ?: 0
            val warnings = warningsPerDay[date] ?: 0
            val inChallenge = challengeStart != null && !date.isBefore(challengeStart)
            val mark = when {
                !inChallenge -> DayMark.NONE
                date.isAfter(today) -> DayMark.NONE
                warnings > 0 -> DayMark.WARN
                date == today -> DayMark.NONE
                attempts > 0 -> DayMark.CHECK
                else -> DayMark.STAR
            }
            DayCell(date, mark, attempts, date == today, inChallenge, warnings)
        }

    fun countPerDay(timesMillis: List<Long>, zone: ZoneId): Map<LocalDate, Int> =
        timesMillis.groupingBy { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }.eachCount()

    /** Consecutive clean (STAR) days ending yesterday. */
    fun currentStreak(cells: List<DayCell>, today: LocalDate): Int {
        val byDate = cells.associateBy { it.date }
        var d = today.minusDays(1)
        var n = 0
        while (byDate[d]?.mark == DayMark.STAR) { n++; d = d.minusDays(1) }
        return n
    }

    /** Calendar days left until the lock ends (today included), 0 afterwards. */
    fun daysRemaining(today: LocalDate, challenge: Challenge = LockPolicy.CHALLENGE): Int =
        ChronoUnit.DAYS.between(today, challenge.endInclusive.plusDays(1)).toInt().coerceAtLeast(0)
}
