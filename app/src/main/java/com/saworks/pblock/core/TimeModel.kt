package com.saworks.pblock.core

import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/**
 * Persisted clock state. "Trusted" times come from the network (NTP, HTTPS Date header, Android's
 * network time); the anchor ties one of them to SystemClock.elapsedRealtime(), which the user cannot change
 * but which restarts at every boot.
 */
data class TimeState(
    val anchorTrusted: Long = 0L,
    val anchorElapsed: Long = 0L,
    val anchorBoot: Int = -1,
    /** Highest trusted time ever seen: the "never go backwards" floor. */
    val maxTrusted: Long = 0L,
    /** wall - elapsed captured the first time PBlock ran in a boot: a continuous, offline time base. */
    val baseOffset: Long = 0L,
    val baseBoot: Int = -1,
)

/** Pure clock logic, unit-tested. All decisions that could end the lock use only trusted time. */
object TimeModel {
    /** Clock jumps smaller than this are ordinary drift corrections. */
    const val TAMPER_THRESHOLD_MS = 120_000L

    private val MIN_SANE = ZonedDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()
    private val MAX_SANE = ZonedDateTime.of(2040, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()

    fun isSane(t: Long): Boolean = t in MIN_SANE until MAX_SANE

    /** Trusted "now" if a trusted time was anchored during this boot, else null. */
    fun trustedNow(s: TimeState, elapsed: Long, boot: Int): Long? {
        if (s.anchorTrusted <= 0 || boot < 0 || s.anchorBoot != boot || elapsed < s.anchorElapsed) return null
        return s.anchorTrusted + (elapsed - s.anchorElapsed)
    }

    /** A time the real time is known to be at or after. Device clock changes cannot raise it. */
    fun lowerBound(s: TimeState, elapsed: Long, boot: Int): Long = maxOf(s.maxTrusted, trustedNow(s, elapsed, boot) ?: 0L)

    /**
     * Best estimate for display and calendar days: trusted time when available, otherwise the per-boot
     * baseline (ignores clock changes made after PBlock first ran in this boot), never below the trusted floor.
     */
    fun estimate(s: TimeState, elapsed: Long, boot: Int, wall: Long): Long {
        trustedNow(s, elapsed, boot)?.let { return it }
        val base = if (boot >= 0 && s.baseBoot == boot) s.baseOffset + elapsed else wall
        return maxOf(base, s.maxTrusted)
    }

    /** Locked until trusted time says the end has passed. Without trusted time: stays locked (fail closed). */
    fun isLocked(activatedAt: Long, lowerBound: Long): Boolean = activatedAt > 0 && lowerBound < LockPolicy.LOCK_END_MILLIS

    fun isFinished(activatedAt: Long, lowerBound: Long): Boolean = activatedAt > 0 && lowerBound >= LockPolicy.LOCK_END_MILLIS

    /** Generic time lock (content-filter mode): locked until trusted time has passed [until]. Fail closed. */
    fun isLockedUntil(until: Long, lowerBound: Long): Boolean = until > 0 && lowerBound < until

    /** The device clock says the time lock is over but no trusted time has confirmed it yet. */
    fun awaitingTrustedUntil(until: Long, lowerBound: Long, estimate: Long): Boolean =
        isLockedUntil(until, lowerBound) && estimate >= until

    /** The device clock says the challenge is over but no trusted time has confirmed it yet. */
    fun awaitingTrustedEnd(activatedAt: Long, lowerBound: Long, estimate: Long): Boolean =
        isLocked(activatedAt, lowerBound) && estimate >= LockPolicy.LOCK_END_MILLIS

    /** How far the wall clock jumped relative to the elapsed-realtime base (positive = moved forward). */
    fun clockJump(lastOffset: Long, wall: Long, elapsed: Long): Long = (wall - elapsed) - lastOffset

    /** A jump is tampering unless it is small or it set the clock (back) to the trusted time. */
    fun isTamper(jump: Long, wall: Long, trusted: Long?): Boolean {
        if (abs(jump) < TAMPER_THRESHOLD_MS) return false
        if (trusted != null && abs(wall - trusted) < TAMPER_THRESHOLD_MS) return false
        return true
    }

    /** At the first observation in a boot: the clock is earlier than a time we already trusted. */
    fun isBehindTrusted(wall: Long, maxTrusted: Long): Boolean = maxTrusted > 0 && maxTrusted - wall >= TAMPER_THRESHOLD_MS
}

/** Minimal SNTP (RFC 4330) client packet handling. */
object Sntp {
    private const val NTP_EPOCH_OFFSET_S = 2_208_988_800L

    fun request(): ByteArray = ByteArray(48).also { it[0] = 0x1B } // LI 0, version 3, mode 3 (client)

    /** Server transmit time in Unix millis, or null if the reply is not a usable server answer. */
    fun parse(reply: ByteArray, length: Int = reply.size): Long? {
        if (length < 48) return null
        val mode = reply[0].toInt() and 0x07
        val leap = (reply[0].toInt() shr 6) and 0x03
        val stratum = reply[1].toInt() and 0xFF
        if (mode != 4 && mode != 5) return null
        if (leap == 3 || stratum == 0 || stratum > 15) return null
        val sec = readU32(reply, 40)
        val frac = readU32(reply, 44)
        if (sec == 0L) return null
        val t = (sec - NTP_EPOCH_OFFSET_S) * 1000L + (frac * 1000L ushr 32)
        return t.takeIf { TimeModel.isSane(it) }
    }

    private fun readU32(b: ByteArray, off: Int): Long =
        ((b[off].toLong() and 0xFF) shl 24) or ((b[off + 1].toLong() and 0xFF) shl 16) or
            ((b[off + 2].toLong() and 0xFF) shl 8) or (b[off + 3].toLong() and 0xFF)
}

object HttpDate {
    /** Parses an HTTP Date header ("Thu, 22 Oct 2026 16:40:00 GMT"). */
    fun parse(header: String?): Long? = try {
        if (header.isNullOrBlank()) null
        else Instant.from(DateTimeFormatter.RFC_1123_DATE_TIME.parse(header.trim())).toEpochMilli().takeIf { TimeModel.isSane(it) }
    } catch (_: Exception) {
        null
    }
}
