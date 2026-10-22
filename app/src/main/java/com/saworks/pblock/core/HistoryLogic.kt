package com.saworks.pblock.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Per-day counters that survive deleting the detailed log, so calendar marks never change after a cleanup. */
data class DayCount(val attempts: Int = 0, val warnings: Int = 0, val hard: Int = 0) {
    operator fun plus(o: DayCount) = DayCount(attempts + o.attempts, warnings + o.warnings, hard + o.hard)
}

object HistoryLogic {
    /** Retention choices in days; 0 = keep forever. */
    val RETENTION_CHOICES = intArrayOf(7, 30, 90, 0)
    const val DEFAULT_RETENTION_DAYS = 30
    private const val DAY_MS = 24 * 60 * 60_000L

    /** Events older than this are removed; null if nothing should be removed. */
    fun cutoff(now: Long, retentionDays: Int): Long? = if (retentionDays <= 0) null else now - retentionDays * DAY_MS

    data class Event(val time: Long, val kind: String)

    /** Counts events per day, for the summary that replaces deleted details. Only events since [countFrom] count. */
    fun summarize(events: List<Event>, zone: ZoneId, countFrom: Long): Map<LocalDate, DayCount> {
        val out = HashMap<LocalDate, DayCount>()
        if (countFrom <= 0) return out
        for (e in events) {
            if (e.time < countFrom) continue
            val c = when {
                Events.isAttempt(e.kind) -> DayCount(attempts = 1)
                Events.isWarning(e.kind) -> DayCount(warnings = 1)
                Events.isInfo(e.kind) -> DayCount(hard = 1)
                else -> continue
            }
            val d = Instant.ofEpochMilli(e.time).atZone(zone).toLocalDate()
            out[d] = (out[d] ?: DayCount()) + c
        }
        return out
    }

    fun merge(a: Map<LocalDate, DayCount>, b: Map<LocalDate, DayCount>): Map<LocalDate, DayCount> {
        val out = HashMap(a)
        b.forEach { (d, c) -> out[d] = (out[d] ?: DayCount()) + c }
        return out
    }
}
