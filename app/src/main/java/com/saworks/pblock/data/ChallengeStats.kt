package com.saworks.pblock.data

import android.content.Context
import com.saworks.pblock.core.CalendarLogic
import com.saworks.pblock.core.DayCount
import com.saworks.pblock.core.Events
import com.saworks.pblock.core.HistoryLogic
import com.saworks.pblock.core.LockPolicy
import java.time.Instant
import java.time.LocalDate

/** Challenge history (live log + counters of deleted entries) and the Hard moment statistics. */
object ChallengeStats {
    data class Summary(val streak: Int, val daysRemaining: Int, val hardMoments: Int)

    /** Per-day counts since the challenge start. */
    fun dayCounts(ctx: Context, all: List<AttemptLog.Attempt> = AttemptLog.readAll(ctx)): Map<LocalDate, DayCount> {
        val activatedAt = Prefs.lockActivatedAt(ctx)
        if (activatedAt <= 0) return emptyMap()
        val live = HistoryLogic.summarize(all.map { HistoryLogic.Event(it.time, it.kind) }, LockPolicy.ZONE, activatedAt)
        return HistoryLogic.merge(AttemptLog.readSummary(ctx), live)
    }

    fun summary(ctx: Context): Summary {
        val zone = LockPolicy.ZONE
        val today: LocalDate = Instant.ofEpochMilli(TrustedTime.now(ctx)).atZone(zone).toLocalDate()
        val all = AttemptLog.readAll(ctx)
        val hard = all.count { it.kind == Events.HARD_MOMENT } + AttemptLog.readSummary(ctx).values.sumOf { it.hard }
        val activatedAt = Prefs.lockActivatedAt(ctx)
        if (activatedAt <= 0) return Summary(0, CalendarLogic.daysRemaining(today), hard)
        val start = Instant.ofEpochMilli(activatedAt).atZone(zone).toLocalDate()
        val counts = dayCounts(ctx, all)
        val per = counts.mapValues { it.value.attempts }.filterValues { it > 0 }
        val warn = counts.mapValues { it.value.warnings }.filterValues { it > 0 }
        val cells = LockPolicy.CHALLENGE.months.flatMap { CalendarLogic.buildMonth(it, start, today, per, warn) }
        return Summary(CalendarLogic.currentStreak(cells, today), CalendarLogic.daysRemaining(today), hard)
    }
}

/** Applies the history retention settings (throttled; called from the monitor and app start). */
object HistoryRetention {
    @Volatile private var lastRun = 0L

    fun apply(ctx: Context, force: Boolean = false) {
        val el = android.os.SystemClock.elapsedRealtime()
        if (!force && lastRun != 0L && el - lastRun < 30 * 60_000L) return
        lastRun = el
        val now = TrustedTime.now(ctx)
        AttemptLog.purge(ctx, HistoryLogic.cutoff(now, Prefs.retentionDays(ctx)))
        if (Prefs.purgeAtChallengeEnd(ctx) && !Prefs.purgedAtChallengeEnd(ctx) && Lock.isFinished(ctx)) {
            AttemptLog.purge(ctx, null, all = true)
            Prefs.setPurgedAtChallengeEnd(ctx)
        }
    }
}
