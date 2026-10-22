package com.saworks.pblock.data

import android.content.Context
import com.saworks.pblock.core.Deduper
import com.saworks.pblock.core.DayCount
import com.saworks.pblock.core.Events
import com.saworks.pblock.core.HistoryLogic
import com.saworks.pblock.core.LockPolicy
import java.io.File
import java.io.FileWriter
import java.io.IOException

/**
 * Append-only event log (one line: millis \t kind \t target), deduped per kind+target per minute.
 * Timestamps come from [TrustedTime], so changing the device clock does not move events to other days.
 */
object AttemptLog {
    const val KIND_DNS = Events.DNS
    const val KIND_DNS_FILTER = Events.DNS_FILTER
    const val KIND_APP = Events.APP

    data class Attempt(val time: Long, val kind: String, val target: String)

    private val deduper = Deduper(60_000L)
    private val lock = Any()

    private fun file(ctx: Context) = File(ctx.filesDir, "attempts.tsv")

    fun log(ctx: Context, kind: String, target: String, now: Long = TrustedTime.now(ctx)): Boolean {
        if (!deduper.shouldLog("$kind|$target", now)) return false
        val clean = target.replace('\t', ' ').replace('\n', ' ').take(200)
        synchronized(lock) {
            try {
                FileWriter(file(ctx.applicationContext), true).use { it.write("$now\t$kind\t$clean\n") }
            } catch (_: IOException) {
                return false
            }
        }
        return true
    }

    fun readAll(ctx: Context): List<Attempt> = synchronized(lock) {
        val f = file(ctx.applicationContext)
        if (!f.exists()) return emptyList()
        try {
            f.readLines().mapNotNull { line ->
                val p = line.split('\t')
                val t = p.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
                Attempt(t, p.getOrElse(1) { "" }, p.getOrElse(2) { "" })
            }
        } catch (_: IOException) {
            emptyList()
        }
    }

    private fun summaryFile(ctx: Context) = File(ctx.applicationContext.filesDir, "day_counts.tsv")

    /** Per-day counters of deleted events (challenge zone): date \t attempts \t warnings \t hard. */
    fun readSummary(ctx: Context): Map<java.time.LocalDate, DayCount> = synchronized(lock) {
        val f = summaryFile(ctx)
        if (!f.exists()) return emptyMap()
        try {
            f.readLines().mapNotNull { line ->
                val p = line.split('\t')
                val d = try { java.time.LocalDate.parse(p[0]) } catch (_: Exception) { return@mapNotNull null }
                d to DayCount(p.getOrNull(1)?.toIntOrNull() ?: 0, p.getOrNull(2)?.toIntOrNull() ?: 0, p.getOrNull(3)?.toIntOrNull() ?: 0)
            }.toMap()
        } catch (_: IOException) {
            emptyMap()
        }
    }

    /**
     * Deletes detailed events older than [cutoff] (all of them if null cutoff and [all]). Their per-day counts
     * are kept (no names, no times), so the challenge calendar looks the same afterwards. Returns removed count.
     */
    fun purge(ctx: Context, cutoff: Long?, all: Boolean = false): Int = synchronized(lock) {
        if (cutoff == null && !all) return 0
        val events = readAll(ctx)
        val (old, keep) = events.partition { all || it.time < cutoff!! }
        if (old.isEmpty()) return 0
        val add = HistoryLogic.summarize(old.map { HistoryLogic.Event(it.time, it.kind) }, LockPolicy.ZONE, Prefs.lockActivatedAt(ctx))
        val merged = HistoryLogic.merge(readSummary(ctx), add)
        try {
            writeAtomic(summaryFile(ctx), merged.entries.sortedBy { it.key }
                .joinToString("") { (d, c) -> "$d\t${c.attempts}\t${c.warnings}\t${c.hard}\n" })
            writeAtomic(file(ctx.applicationContext), keep.joinToString("") { "${it.time}\t${it.kind}\t${it.target}\n" })
        } catch (_: IOException) {
            return 0
        }
        old.size
    }

    private fun writeAtomic(f: File, text: String) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) { f.writeText(text); tmp.delete() }
    }
}
