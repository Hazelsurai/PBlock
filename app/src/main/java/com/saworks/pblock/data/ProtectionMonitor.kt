package com.saworks.pblock.data

import android.content.Context
import android.content.SharedPreferences
import com.saworks.pblock.access.BlockerAccessibilityService
import com.saworks.pblock.core.Events
import com.saworks.pblock.core.MonitorLogic
import com.saworks.pblock.vpn.PBlockVpnService

/**
 * Notices when protections were down while the challenge is locked:
 *  - restarts PBlock never saw (BOOT_COUNT jumped by more than one), typically safe mode, where Android
 *    does not run third-party apps at all,
 *  - PackageManager.isSafeMode(),
 *  - the DNS filter or the app blocker being off for longer than a short grace period,
 *  - device clock changes (via [TrustedTime.observe]).
 * Called from the services' timers, app start, boot and TIME_SET.
 */
object ProtectionMonitor {
    private const val FILE = "pblock_monitor"
    private const val K_LAST_BOOT = "last_boot"

    private fun sp(ctx: Context): SharedPreferences = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Immediately note that a component went down (VPN revoked/destroyed, accessibility unbound). */
    @Synchronized
    fun markDown(ctx: Context, component: String) {
        if (!Lock.isLocked(ctx)) return
        val p = sp(ctx)
        val boot = TrustedTime.bootCount(ctx)
        if (p.getLong("down_since_$component", 0L) > 0 && p.getInt("down_boot_$component", -2) == boot) return
        p.edit().putLong("down_since_$component", TrustedTime.now(ctx)).putInt("down_boot_$component", boot)
            .putBoolean("down_logged_$component", false).apply()
    }

    @Synchronized
    fun check(ctx: Context) {
        val app = ctx.applicationContext
        TrustedTime.observe(app)?.let { if (Lock.isTimeLocked(app)) AttemptLog.log(app, Events.CLOCK, it) }
        TrustedTime.syncAsync(app)
        HistoryRetention.apply(app)

        val p = sp(app)
        val locked = Lock.isLocked(app)
        val boot = TrustedTime.bootCount(app)
        val lastBoot = p.getInt(K_LAST_BOOT, -1)
        val missed = MonitorLogic.missedBoots(lastBoot, boot)
        if (locked && missed > 0) AttemptLog.log(app, Events.SAFE_MODE, "boots|$missed")
        if (boot >= 0 && boot != lastBoot) p.edit().putInt(K_LAST_BOOT, boot).apply()
        if (locked && app.packageManager.isSafeMode) AttemptLog.log(app, Events.SAFE_MODE, "now")

        val e = p.edit()
        for (c in listOf(Events.COMP_VPN, Events.COMP_ACCESSIBILITY)) {
            if (!locked) {
                e.remove("down_since_$c").remove("down_boot_$c").remove("down_logged_$c")
                continue
            }
            val now = TrustedTime.now(app)
            val since = p.getLong("down_since_$c", 0L).takeIf { p.getInt("down_boot_$c", -2) == boot } ?: 0L
            val logged = p.getBoolean("down_logged_$c", false)
            when (MonitorLogic.step(isUp(app, c), since, logged, now)) {
                MonitorLogic.Action.NONE -> {}
                MonitorLogic.Action.START -> e.putLong("down_since_$c", now).putInt("down_boot_$c", boot).putBoolean("down_logged_$c", false)
                MonitorLogic.Action.LOG_OFF -> {
                    AttemptLog.log(app, Events.DOWN, "$c|off|$since")
                    e.putBoolean("down_logged_$c", true)
                }
                MonitorLogic.Action.LOG_ON -> {
                    AttemptLog.log(app, Events.DOWN, "$c|on|${now - since}")
                    e.remove("down_since_$c").remove("down_boot_$c").remove("down_logged_$c")
                }
                MonitorLogic.Action.CLEAR -> e.remove("down_since_$c").remove("down_boot_$c").remove("down_logged_$c")
            }
        }
        e.apply()
    }

    private fun isUp(ctx: Context, c: String): Boolean = when (c) {
        Events.COMP_VPN -> PBlockVpnService.running
        else -> BlockerAccessibilityService.connected && Protections.accessibilityEnabled(ctx)
    }
}
