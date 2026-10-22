package com.saworks.pblock.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.saworks.pblock.core.HttpDate
import com.saworks.pblock.core.Sntp
import com.saworks.pblock.core.TimeModel
import com.saworks.pblock.core.TimeState
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Clock-tamper resistant time. Trusted network time (NTP, HTTPS Date header, Android's network time)
 * is anchored to SystemClock.elapsedRealtime(); see [TimeModel] for the rules.
 * The DNS-only VPN does not get in the way: only 10.111.222.53 is routed into it.
 */
object TrustedTime {
    private const val TAG = "PBlockTime"
    private const val FILE = "pblock_time"
    private const val K_ANCHOR_T = "anchor_trusted"
    private const val K_ANCHOR_E = "anchor_elapsed"
    private const val K_ANCHOR_B = "anchor_boot"
    private const val K_MAX = "max_trusted"
    private const val K_BASE_OFF = "base_offset"
    private const val K_BASE_B = "base_boot"
    private const val K_LAST_OFF = "last_offset"
    private const val K_OFFSET_FLAG_B = "offset_flag_boot"
    private const val K_LAST_ATTEMPT_E = "last_sync_attempt_elapsed"
    private const val K_LAST_ATTEMPT_B = "last_sync_attempt_boot"

    private const val RESYNC_MS = 3 * 60 * 60_000L
    private const val RETRY_MS = 5 * 60_000L

    val NTP_HOSTS = listOf("time.cloudflare.com", "time.google.com", "pool.ntp.org")
    val HTTPS_URLS = listOf("https://www.google.com/generate_204", "https://www.cloudflare.com/cdn-cgi/trace")

    private val syncing = AtomicBoolean(false)
    @Volatile private var cachedBoot: Int? = null

    private fun sp(ctx: Context): SharedPreferences = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Boot counter (Settings.Global.BOOT_COUNT); constant for the life of the process. -1 if unknown. */
    fun bootCount(ctx: Context): Int {
        cachedBoot?.let { return it }
        val b = try { Settings.Global.getInt(ctx.contentResolver, Settings.Global.BOOT_COUNT) } catch (_: Exception) { -1 }
        cachedBoot = b
        return b
    }

    fun state(ctx: Context): TimeState {
        val p = sp(ctx)
        return TimeState(
            anchorTrusted = p.getLong(K_ANCHOR_T, 0L),
            anchorElapsed = p.getLong(K_ANCHOR_E, 0L),
            anchorBoot = p.getInt(K_ANCHOR_B, -1),
            maxTrusted = p.getLong(K_MAX, 0L),
            baseOffset = p.getLong(K_BASE_OFF, 0L),
            baseBoot = p.getInt(K_BASE_B, -1),
        )
    }

    /** Best-estimate current time (calendar, logging, countdown). */
    fun now(ctx: Context): Long = TimeModel.estimate(state(ctx), SystemClock.elapsedRealtime(), bootCount(ctx), System.currentTimeMillis())

    /** Real time is at least this. Only this may end the lock. */
    fun lowerBound(ctx: Context): Long = TimeModel.lowerBound(state(ctx), SystemClock.elapsedRealtime(), bootCount(ctx))

    fun trustedNow(ctx: Context): Long? = TimeModel.trustedNow(state(ctx), SystemClock.elapsedRealtime(), bootCount(ctx))

    /**
     * Call often (services, app start, TIME_SET). Records the per-boot baseline, raises the persisted
     * trusted floor and returns a log target if the device clock was tampered with since the last call.
     */
    @Synchronized
    fun observe(ctx: Context): String? {
        val p = sp(ctx)
        val boot = bootCount(ctx)
        val el = SystemClock.elapsedRealtime()
        val wall = System.currentTimeMillis()
        val s = state(ctx)
        val trusted = TimeModel.trustedNow(s, el, boot)
        val e = p.edit()
        var tamper: String? = null
        if (boot < 0 || p.getInt(K_BASE_B, -2) != boot) {
            // First run in this boot: start a continuous time base from here, never below the trusted floor.
            val baseOff = maxOf(wall, s.maxTrusted) - el
            e.putLong(K_BASE_OFF, baseOff).putInt(K_BASE_B, boot).putLong(K_LAST_OFF, wall - el)
            if (TimeModel.isBehindTrusted(wall, s.maxTrusted)) tamper = "behind|${wall - s.maxTrusted}"
        } else {
            val jump = TimeModel.clockJump(p.getLong(K_LAST_OFF, wall - el), wall, el)
            if (kotlin.math.abs(jump) >= TimeModel.TAMPER_THRESHOLD_MS) {
                e.putLong(K_LAST_OFF, wall - el)
                if (TimeModel.isTamper(jump, wall, trusted)) tamper = "jump|$jump"
            }
        }
        if (trusted != null && trusted > s.maxTrusted) e.putLong(K_MAX, trusted)
        e.apply()
        return tamper
    }

    fun needsSync(ctx: Context): Boolean {
        val p = sp(ctx)
        val boot = bootCount(ctx)
        val el = SystemClock.elapsedRealtime()
        val s = state(ctx)
        val fresh = s.anchorBoot == boot && boot >= 0 && el >= s.anchorElapsed && el - s.anchorElapsed < RESYNC_MS
        if (fresh) return false
        val lastTry = p.getLong(K_LAST_ATTEMPT_E, -1L)
        return !(p.getInt(K_LAST_ATTEMPT_B, -2) == boot && lastTry >= 0 && el - lastTry in 0 until RETRY_MS)
    }

    /** Fetches trusted time in the background if the anchor is missing or older than 3 hours. */
    fun syncAsync(ctx: Context, force: Boolean = false, onDone: ((Boolean) -> Unit)? = null) {
        val app = ctx.applicationContext
        if (!force && !needsSync(app)) return
        if (!syncing.compareAndSet(false, true)) return
        Thread({
            val ok = try { syncNow(app) } catch (e: Exception) { ErrorLog.note(app, "time.sync", e); false } finally { syncing.set(false) }
            onDone?.invoke(ok)
        }, "pblock-time").start()
    }

    /** Blocking. Returns true if a trusted time was anchored. */
    fun syncNow(ctx: Context): Boolean {
        val boot = bootCount(ctx)
        sp(ctx).edit().putLong(K_LAST_ATTEMPT_E, SystemClock.elapsedRealtime()).putInt(K_LAST_ATTEMPT_B, boot).apply()
        val sample = NTP_HOSTS.firstNotNullOfOrNull { sntp(it) }
            ?: HTTPS_URLS.firstNotNullOfOrNull { httpsDate(it) }
            ?: networkClock()
            ?: return false
        anchor(ctx, sample.first, sample.second, boot)
        Log.i(TAG, "trusted time anchored (${sample.third}), wall offset ${System.currentTimeMillis() - (sample.first + SystemClock.elapsedRealtime() - sample.second)} ms")
        return true
    }

    @Synchronized
    private fun anchor(ctx: Context, trusted: Long, elapsed: Long, boot: Int) {
        val p = sp(ctx)
        val max = maxOf(p.getLong(K_MAX, 0L), trusted)
        p.edit().putLong(K_ANCHOR_T, trusted).putLong(K_ANCHOR_E, elapsed).putInt(K_ANCHOR_B, boot).putLong(K_MAX, max).apply()
        // A device clock far from the trusted time (set before PBlock could see the change) is logged once per boot.
        val now = trusted + (SystemClock.elapsedRealtime() - elapsed)
        val diff = System.currentTimeMillis() - now
        if (kotlin.math.abs(diff) >= 10 * 60_000L && p.getInt(K_OFFSET_FLAG_B, -2) != boot) {
            p.edit().putInt(K_OFFSET_FLAG_B, boot).apply()
            if (Lock.isTimeLocked(ctx)) AttemptLog.log(ctx, com.saworks.pblock.core.Events.CLOCK, "offset|$diff")
        }
    }

    /** (trusted millis, elapsedRealtime at that moment, source) */
    private fun sntp(host: String): Triple<Long, Long, String>? = try {
        DatagramSocket().use { s ->
            s.soTimeout = 3000
            val addr = InetAddress.getByName(host)
            val req = Sntp.request()
            val t0 = SystemClock.elapsedRealtime()
            s.send(DatagramPacket(req, req.size, addr, 123))
            val buf = ByteArray(64)
            val resp = DatagramPacket(buf, buf.size)
            s.receive(resp)
            val t1 = SystemClock.elapsedRealtime()
            Sntp.parse(buf, resp.length)?.let { Triple(it, (t0 + t1) / 2, "ntp:$host") }
        }
    } catch (_: Exception) {
        null
    }

    private fun httpsDate(url: String): Triple<Long, Long, String>? {
        var c: HttpURLConnection? = null
        return try {
            c = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "HEAD"
                connectTimeout = 4000
                readTimeout = 4000
                useCaches = false
                instanceFollowRedirects = false
            }
            val t0 = SystemClock.elapsedRealtime()
            c.connect()
            c.responseCode
            val t1 = SystemClock.elapsedRealtime()
            // Date has 1 s resolution: +500 ms is the expected midpoint.
            HttpDate.parse(c.getHeaderField("Date"))?.let { Triple(it + 500, (t0 + t1) / 2, "https") }
        } catch (_: Exception) {
            null
        } finally {
            c?.disconnect()
        }
    }

    /** Android's own network time (NITZ/NTP), independent of the user-set clock. API 33+. */
    private fun networkClock(): Triple<Long, Long, String>? {
        if (Build.VERSION.SDK_INT < 33) return null
        return try {
            val el = SystemClock.elapsedRealtime()
            val t = SystemClock.currentNetworkTimeClock().millis()
            if (TimeModel.isSane(t)) Triple(t, el, "network") else null
        } catch (_: Exception) {
            null
        }
    }

}
