package com.saworks.pblock.core

import java.time.Duration
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime

/** A challenge period: [start]..[endInclusive] (whole days) in [zone]. */
data class Challenge(val start: LocalDate, val endInclusive: LocalDate, val zone: ZoneId) {
    init { require(!endInclusive.isBefore(start)) }

    /** Exclusive end: midnight after the last day. */
    val end: ZonedDateTime get() = endInclusive.plusDays(1).atStartOfDay(zone)
    val endMillis: Long get() = end.toInstant().toEpochMilli()

    /** Calendar months the challenge spans, in order. */
    val months: List<YearMonth>
        get() {
            val out = ArrayList<YearMonth>()
            var ym = YearMonth.from(start)
            val last = YearMonth.from(endInclusive)
            while (!ym.isAfter(last)) { out += ym; ym = ym.plusMonths(1) }
            return out
        }
}

/** The challenge lock: active from the explicit start until the end of the challenge (default: 2026-11-30 23:59 Budapest). */
object LockPolicy {
    val ZONE: ZoneId = ZoneId.of("Europe/Budapest")

    /** No Nut November 2026. Change here for another period; everything else derives from it. */
    val CHALLENGE = Challenge(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30), ZONE)

    /** Exclusive end: the lock holds through the last day 23:59:59 in [ZONE]. */
    val LOCK_END: ZonedDateTime = CHALLENGE.end
    val LOCK_END_MILLIS: Long = CHALLENGE.endMillis

    fun isLocked(activatedAtMillis: Long, nowMillis: Long): Boolean =
        activatedAtMillis > 0 && nowMillis < LOCK_END_MILLIS

    fun canActivate(nowMillis: Long): Boolean = nowMillis < LOCK_END_MILLIS

    fun isFinished(activatedAtMillis: Long, nowMillis: Long): Boolean =
        activatedAtMillis > 0 && nowMillis >= LOCK_END_MILLIS

    fun remaining(nowMillis: Long, endMillis: Long = LOCK_END_MILLIS): Duration =
        Duration.ofMillis((endMillis - nowMillis).coerceAtLeast(0))
}
