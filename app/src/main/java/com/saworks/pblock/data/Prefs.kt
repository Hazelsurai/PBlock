package com.saworks.pblock.data

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import androidx.appcompat.app.AppCompatDelegate
import com.saworks.pblock.access.BlockerAccessibilityService
import com.saworks.pblock.core.HistoryLogic
import com.saworks.pblock.core.LockPolicy
import com.saworks.pblock.core.PinHasher
import com.saworks.pblock.core.PinThrottle
import com.saworks.pblock.core.TimeModel
import com.saworks.pblock.vpn.Blocklist

object Prefs {
    private const val FILE = "pblock"
    private const val KEY_LOCK_AT = "lock_activated_at"
    private const val KEY_VPN_WANTED = "vpn_wanted"

    const val MODE_CHALLENGE = "challenge"
    const val MODE_FILTER = "filter"

    const val THEME_SYSTEM = "system"
    const val THEME_LIGHT = "light"
    const val THEME_DARK = "dark"

    const val LOCK_NONE = "none"
    const val LOCK_TIME = "time"
    const val LOCK_PIN = "pin"

    private const val K_MODE = "mode"
    private const val K_THEME = "theme"
    private const val K_APPS_ENABLED = "filter_apps_enabled"
    private const val K_APPS = "filter_apps"
    private const val K_BLOCKED = "filter_blocked_domains"
    private const val K_ALLOWED = "filter_allowed_domains"
    private const val K_FAMILY = "filter_family_dns"
    private const val K_LOCK_TYPE = "filter_lock_type"
    private const val K_LOCK_UNTIL = "filter_lock_until"
    private const val K_RETENTION = "log_retention_days"
    private const val K_PURGE_AT_END = "log_purge_at_challenge_end"
    private const val K_PURGED_AT_END = "log_purged_at_challenge_end"

    internal fun sp(ctx: Context): SharedPreferences = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun lockActivatedAt(ctx: Context): Long = sp(ctx).getLong(KEY_LOCK_AT, 0L)

    fun vpnWanted(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_VPN_WANTED, false)
    fun setVpnWanted(ctx: Context, v: Boolean) { sp(ctx).edit().putBoolean(KEY_VPN_WANTED, v).apply() }

    internal fun setLockActivatedAt(ctx: Context, v: Long) { sp(ctx).edit().putLong(KEY_LOCK_AT, v).commit() }

    // ---- mode
    fun mode(ctx: Context): String = if (sp(ctx).getString(K_MODE, MODE_CHALLENGE) == MODE_FILTER) MODE_FILTER else MODE_CHALLENGE
    fun isFilterMode(ctx: Context) = mode(ctx) == MODE_FILTER
    fun setMode(ctx: Context, m: String) {
        sp(ctx).edit().putString(K_MODE, m).commit()
        Blocklist.invalidateRules()
    }

    // ---- appearance
    fun theme(ctx: Context): String = sp(ctx).getString(K_THEME, THEME_DARK) ?: THEME_DARK
    fun setTheme(ctx: Context, t: String) { sp(ctx).edit().putString(K_THEME, t).apply() }
    fun nightMode(theme: String): Int = when (theme) {
        THEME_SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
        else -> AppCompatDelegate.MODE_NIGHT_YES
    }
    fun applyTheme(ctx: Context) = AppCompatDelegate.setDefaultNightMode(nightMode(theme(ctx)))

    // ---- content filter
    fun appBlockingEnabled(ctx: Context) = sp(ctx).getBoolean(K_APPS_ENABLED, false)
    fun setAppBlockingEnabled(ctx: Context, v: Boolean) { sp(ctx).edit().putBoolean(K_APPS_ENABLED, v).apply() }
    /** null until the user saved a selection (the picker then preselects the suggestions). */
    fun blockedApps(ctx: Context): Set<String>? = sp(ctx).getStringSet(K_APPS, null)?.toSet()
    fun setBlockedApps(ctx: Context, v: Set<String>) { sp(ctx).edit().putStringSet(K_APPS, HashSet(v)).apply() }

    fun blockedDomains(ctx: Context): Set<String> = sp(ctx).getStringSet(K_BLOCKED, emptySet())!!.toSortedSet()
    fun allowedDomains(ctx: Context): Set<String> = sp(ctx).getStringSet(K_ALLOWED, emptySet())!!.toSortedSet()
    fun setBlockedDomains(ctx: Context, v: Set<String>) { sp(ctx).edit().putStringSet(K_BLOCKED, HashSet(v)).commit(); Blocklist.invalidateRules() }
    fun setAllowedDomains(ctx: Context, v: Set<String>) { sp(ctx).edit().putStringSet(K_ALLOWED, HashSet(v)).commit(); Blocklist.invalidateRules() }

    /** Cloudflare for Families upstream. Always on in challenge mode; optional (default on) in filter mode. */
    fun familyDns(ctx: Context): Boolean = !isFilterMode(ctx) || sp(ctx).getBoolean(K_FAMILY, true)
    fun familyDnsSetting(ctx: Context): Boolean = sp(ctx).getBoolean(K_FAMILY, true)
    fun setFamilyDns(ctx: Context, v: Boolean) { sp(ctx).edit().putBoolean(K_FAMILY, v).apply() }

    fun lockType(ctx: Context): String = when (sp(ctx).getString(K_LOCK_TYPE, LOCK_NONE)) {
        LOCK_TIME -> LOCK_TIME
        LOCK_PIN -> LOCK_PIN
        else -> LOCK_NONE
    }
    fun lockUntil(ctx: Context): Long = sp(ctx).getLong(K_LOCK_UNTIL, 0L)
    internal fun setLock(ctx: Context, type: String, until: Long = 0L) {
        sp(ctx).edit().putString(K_LOCK_TYPE, type).putLong(K_LOCK_UNTIL, until).commit()
    }

    // ---- privacy
    fun retentionDays(ctx: Context): Int = sp(ctx).getInt(K_RETENTION, HistoryLogic.DEFAULT_RETENTION_DAYS)
    fun setRetentionDays(ctx: Context, d: Int) { sp(ctx).edit().putInt(K_RETENTION, d).apply() }
    fun purgeAtChallengeEnd(ctx: Context) = sp(ctx).getBoolean(K_PURGE_AT_END, false)
    fun setPurgeAtChallengeEnd(ctx: Context, v: Boolean) { sp(ctx).edit().putBoolean(K_PURGE_AT_END, v).apply() }
    fun purgedAtChallengeEnd(ctx: Context) = sp(ctx).getBoolean(K_PURGED_AT_END, false)
    fun setPurgedAtChallengeEnd(ctx: Context) { sp(ctx).edit().putBoolean(K_PURGED_AT_END, true).apply() }
}

/** Parent PIN: salted PBKDF2 hash, rate-limited tries and a short unlock session after a correct PIN. */
object Pin {
    private const val FILE = "pblock_pin"
    private const val K_HASH = "hash"
    private const val K_FAILS = "fails"
    private const val K_LAST_FAIL = "last_fail"
    private const val K_SESSION_E = "session_until_elapsed"
    private const val K_SESSION_B = "session_boot"
    const val SESSION_MS = 5 * 60_000L

    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun isSet(ctx: Context) = !sp(ctx).getString(K_HASH, null).isNullOrEmpty()

    /** Slow (PBKDF2): call off the main thread. */
    fun set(ctx: Context, pin: String) {
        require(PinHasher.isValidPin(pin))
        sp(ctx).edit().putString(K_HASH, PinHasher.hash(pin)).putInt(K_FAILS, 0).putLong(K_LAST_FAIL, 0L).commit()
    }

    fun clear(ctx: Context) { sp(ctx).edit().clear().commit() }

    fun waitMs(ctx: Context): Long {
        val p = sp(ctx)
        return PinThrottle.waitMs(p.getInt(K_FAILS, 0), p.getLong(K_LAST_FAIL, 0L), TrustedTime.now(ctx))
    }

    sealed interface Result {
        data object Ok : Result
        data class Wrong(val waitMs: Long) : Result
        data class TooSoon(val waitMs: Long) : Result
    }

    /** Slow (PBKDF2): call off the main thread. A correct PIN opens an unlock session. */
    @Synchronized
    fun check(ctx: Context, pin: String): Result {
        val wait = waitMs(ctx)
        if (wait > 0) return Result.TooSoon(wait)
        val p = sp(ctx)
        if (PinHasher.verify(pin, p.getString(K_HASH, null))) {
            p.edit().putInt(K_FAILS, 0).putLong(K_LAST_FAIL, 0L).commit()
            startSession(ctx)
            return Result.Ok
        }
        p.edit().putInt(K_FAILS, p.getInt(K_FAILS, 0) + 1).putLong(K_LAST_FAIL, TrustedTime.now(ctx)).commit()
        return Result.Wrong(waitMs(ctx))
    }

    private fun startSession(ctx: Context) {
        sp(ctx).edit().putLong(K_SESSION_E, SystemClock.elapsedRealtime() + SESSION_MS)
            .putInt(K_SESSION_B, TrustedTime.bootCount(ctx)).commit()
    }

    fun endSession(ctx: Context) { sp(ctx).edit().remove(K_SESSION_E).remove(K_SESSION_B).commit() }

    /** Remaining unlock-session time (elapsed-realtime based: the device clock cannot extend it). */
    fun sessionLeftMs(ctx: Context): Long {
        val p = sp(ctx)
        if (p.getInt(K_SESSION_B, -2) != TrustedTime.bootCount(ctx)) return 0L
        val left = p.getLong(K_SESSION_E, 0L) - SystemClock.elapsedRealtime()
        return if (left in 1..SESSION_MS) left else 0L
    }
}

object Lock {
    // ---- No Nut November challenge
    /** Locked from the start until trusted time confirms the end; device clock changes cannot end it. */
    fun challengeLocked(ctx: Context): Boolean = TimeModel.isLocked(Prefs.lockActivatedAt(ctx), TrustedTime.lowerBound(ctx))

    fun isFinished(ctx: Context): Boolean = TimeModel.isFinished(Prefs.lockActivatedAt(ctx), TrustedTime.lowerBound(ctx))

    /** The challenge ended by trusted time, or the period is over (started or not). */
    fun challengeOver(ctx: Context): Boolean = TrustedTime.lowerBound(ctx) >= LockPolicy.LOCK_END_MILLIS || isFinished(ctx)

    // ---- content filter
    fun timeLocked(ctx: Context): Boolean = Prefs.isFilterMode(ctx) && Prefs.lockType(ctx) == Prefs.LOCK_TIME &&
        TimeModel.isLockedUntil(Prefs.lockUntil(ctx), TrustedTime.lowerBound(ctx))

    fun pinConfigured(ctx: Context): Boolean = Prefs.isFilterMode(ctx) && Prefs.lockType(ctx) == Prefs.LOCK_PIN && Pin.isSet(ctx)

    fun pinLocked(ctx: Context): Boolean = pinConfigured(ctx) && Pin.sessionLeftMs(ctx) <= 0

    /**
     * Protections may not be loosened: the challenge is running, a time lock holds, or the parent PIN is set
     * and not currently unlocked. While true the settings guard bounces PBlock's Settings pages.
     */
    fun isLocked(ctx: Context): Boolean =
        if (Prefs.isFilterMode(ctx)) timeLocked(ctx) || pinLocked(ctx) else challengeLocked(ctx)

    /** Time-based locks also guard the clock (Date & time page, tamper log). */
    fun isTimeLocked(ctx: Context): Boolean = if (Prefs.isFilterMode(ctx)) timeLocked(ctx) else challengeLocked(ctx)

    /** The device clock says the lock is over but no trusted time has confirmed it yet (e.g. offline). */
    fun awaitingTrustedEnd(ctx: Context): Boolean = if (Prefs.isFilterMode(ctx)) {
        Prefs.lockType(ctx) == Prefs.LOCK_TIME &&
            TimeModel.awaitingTrustedUntil(Prefs.lockUntil(ctx), TrustedTime.lowerBound(ctx), TrustedTime.now(ctx))
    } else {
        TimeModel.awaitingTrustedEnd(Prefs.lockActivatedAt(ctx), TrustedTime.lowerBound(ctx), TrustedTime.now(ctx))
    }

    fun activate(ctx: Context, now: Long = TrustedTime.now(ctx)): Boolean {
        if (!LockPolicy.canActivate(now) || challengeLocked(ctx)) return false
        Prefs.setMode(ctx, Prefs.MODE_CHALLENGE)
        Prefs.setLockActivatedAt(ctx, now)
        Prefs.setVpnWanted(ctx, true)
        return true
    }

    /** Starts a content-filter time lock until [until] (epoch millis). */
    fun activateTimeLock(ctx: Context, until: Long): Boolean {
        if (isLocked(ctx) || until <= TrustedTime.now(ctx)) return false
        Prefs.setLock(ctx, Prefs.LOCK_TIME, until)
        Prefs.setVpnWanted(ctx, true)
        return true
    }

    /** Parent PIN lock; the PIN must already be stored with [Pin.set]. */
    fun activatePinLock(ctx: Context) {
        Prefs.setLock(ctx, Prefs.LOCK_PIN)
        Prefs.setVpnWanted(ctx, true)
        Pin.endSession(ctx)
    }

    /** Removes the filter lock (only when not locked, i.e. after the PIN was entered or the time is up). */
    fun removeFilterLock(ctx: Context): Boolean {
        if (isLocked(ctx)) return false
        Prefs.setLock(ctx, Prefs.LOCK_NONE)
        Pin.clear(ctx)
        return true
    }

    /** Switching mode drops nothing that is locked. */
    fun canSwitchMode(ctx: Context): Boolean = !challengeLocked(ctx) && !timeLocked(ctx) && !pinLocked(ctx)
}

/** Which apps the app blocker closes, per mode. */
object AppRules {
    /** Package -> display name of the app to block, or null. */
    fun blockedLabel(ctx: Context, pkg: String): String? {
        if (!Prefs.isFilterMode(ctx)) return BlockerAccessibilityService.BLOCKED_APPS[pkg]
        if (!Prefs.appBlockingEnabled(ctx)) return null
        if (pkg !in (Prefs.blockedApps(ctx) ?: return null)) return null
        return BlockerAccessibilityService.BLOCKED_APPS[pkg] ?: labelOf(ctx, pkg)
    }

    private val labels = HashMap<String, String>()

    fun labelOf(ctx: Context, pkg: String): String = synchronized(labels) {
        labels.getOrPut(pkg) {
            try {
                val pm = ctx.packageManager
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            } catch (_: Exception) {
                pkg
            }
        }
    }
}
