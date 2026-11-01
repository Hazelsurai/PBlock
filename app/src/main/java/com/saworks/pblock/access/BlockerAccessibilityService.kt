package com.saworks.pblock.access

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.net.VpnService
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.saworks.pblock.R
import com.saworks.pblock.TimeChangeReceiver
import com.saworks.pblock.Workers
import com.saworks.pblock.core.EventCoalescer
import com.saworks.pblock.core.Events
import com.saworks.pblock.core.SettingsGuard
import com.saworks.pblock.data.AppRules
import com.saworks.pblock.data.AttemptLog
import com.saworks.pblock.data.ErrorLog
import com.saworks.pblock.data.Lock
import com.saworks.pblock.data.Prefs
import com.saworks.pblock.data.ProtectionMonitor
import com.saworks.pblock.ui.BlockedActivity
import com.saworks.pblock.vpn.PBlockVpnService
import java.util.concurrent.ConcurrentHashMap

/**
 * 1) Covers a blocked app with the block screen when it comes to the foreground: the fixed list (Brave, X,
 *    Telegram) in the challenge, the user's own optional list in content-filter mode.
 * 2) While a lock is active (challenge, time lock, or parent PIN), bounces out of the Settings screens that could disable PBlock
 *    (App info, accessibility page, device admin, VPN disconnect, uninstall dialog).
 *    Before the lock is started nothing in Settings is touched, so setup always works.
 *
 * Threads: the main thread only copies the event out. App rules, logging and the watchdog run on
 * [Workers.bg]; the Settings screen scan (one call into the other app per node) runs on its own
 * "pblock-guard" thread, so a slow or busy Settings app can never stall the main thread, which the
 * service's own lifecycle callbacks and PBlock's screens need.
 */
class BlockerAccessibilityService : AccessibilityService() {

    companion object {
        private const val RESCAN_DELAY_MS = 600L
        val BLOCKED_APPS: Map<String, String> = mapOf(
            "com.brave.browser" to "Brave",
            "com.brave.browser_beta" to "Brave",
            "com.brave.browser_nightly" to "Brave",
            "com.twitter.android" to "X",
            "com.twitter.android.lite" to "X",
            "org.telegram.messenger" to "Telegram",
            "org.telegram.messenger.web" to "Telegram",
            "org.telegram.messenger.beta" to "Telegram",
            "org.thunderdog.challegram" to "Telegram X",
        )

        @Volatile var connected = false
            private set
    }

    private val main = Handler(Looper.getMainLooper())
    private val bg get() = Workers.bg
    @Volatile private var guardThread: HandlerThread? = null
    @Volatile private var guardHandler: Handler? = null
    private val pending = EventCoalescer()

    // lastAppBlock: bg thread only; lastBounce/lastScan: guard thread only.
    private var lastAppBlock = 0L
    private var lastBounce = 0L
    private var lastScan = 0L
    private val windowClass = ConcurrentHashMap<String, String>()

    private val watchdog = object : Runnable {
        override fun run() {
            try {
                ensureVpn()
                ProtectionMonitor.check(this@BlockerAccessibilityService)
            } catch (e: Exception) {
                ErrorLog.note(this@BlockerAccessibilityService, "acc.watchdog", e)
            }
            bg.postDelayed(this, 30_000)
        }
    }

    private val timeReceiver = TimeChangeReceiver()

    override fun onServiceConnected() {
        connected = true
        if (guardHandler == null) {
            val t = HandlerThread("pblock-guard").apply { start() }
            guardThread = t
            guardHandler = Handler(t.looper)
        }
        TimeChangeReceiver.register(this, timeReceiver)
        bg.removeCallbacks(watchdog)
        bg.post(watchdog)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        markDown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        markDown()
        TimeChangeReceiver.unregister(this, timeReceiver)
        bg.removeCallbacks(watchdog)
        guardHandler?.removeCallbacksAndMessages(null)
        guardThread?.quitSafely()
        guardHandler = null
        guardThread = null
        super.onDestroy()
    }

    /** Flags the blocker as down right away; the bookkeeping (prefs, lock checks) happens off the main thread. */
    private fun markDown() {
        if (!connected) return
        connected = false
        Workers.run("acc.markDown", this) { ProtectionMonitor.markDown(this, Events.COMP_ACCESSIBILITY) }
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // The event is recycled after this returns: copy what is needed and leave the main thread.
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return
        val type = event.eventType
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return
        val windowChange = type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        val cls = if (windowChange) event.className?.toString() else null
        bg.post { onWindowEvent(pkg, windowChange, cls) }
    }

    private fun onWindowEvent(pkg: String, windowChange: Boolean, cls: String?) {
        try {
            if (cls != null) windowClass[pkg] = cls
            val guarded = SettingsGuard.isGuardedPackage(pkg)
            val appName = if (guarded) null else AppRules.blockedLabel(this, pkg)
            if (appName != null) {
                if (windowChange || rootPackage() == pkg) blockApp(appName)
                return
            }
            if (guarded && Lock.isLocked(this)) requestGuard(pkg, windowChange)
        } catch (e: Exception) {
            ErrorLog.note(this, "acc.event", e)
        }
    }

    private fun rootPackage(): String? = try { rootInActiveWindow?.packageName?.toString() } catch (_: Exception) { null }

    private fun blockApp(appName: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastAppBlock < 1500) return
        lastAppBlock = now
        // The block screen covers the app; its button/back goes to the home screen.
        // (GLOBAL_ACTION_HOME before the start would race it and hide the block screen.)
        try {
            startActivity(
                Intent(this, BlockedActivity::class.java)
                    .putExtra(BlockedActivity.EXTRA_APP, appName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
        } catch (_: Exception) {
            performGlobalAction(GLOBAL_ACTION_HOME)
        }
        AttemptLog.log(this, AttemptLog.KIND_APP, appName)
    }

    /** Queues one scan for the latest guarded event; events arriving meanwhile are merged into it. */
    private fun requestGuard(pkg: String, windowChange: Boolean) {
        val h = guardHandler ?: return
        if (pending.offer(pkg, windowChange)) h.post(guardJob)
    }

    private val guardJob = Runnable {
        val r = pending.take() ?: return@Runnable
        try { guard(r.pkg, r.windowChange) } catch (e: Exception) { ErrorLog.note(this, "acc.guard", e) }
    }

    private fun guard(pkg: String, windowChange: Boolean) {
        val h = guardHandler ?: return
        val now = SystemClock.elapsedRealtime()
        if (now - lastBounce < 700) return
        // Throttled or still-loading screens get one more look shortly after (a page opened from inside
        // Settings can finish loading after the last event that reached us).
        h.removeCallbacks(rescan)
        h.postDelayed(rescan, RESCAN_DELAY_MS)
        if (!windowChange && now - lastScan < 300) return
        scan(pkg)
    }

    private val rescan = Runnable {
        try {
            val pkg = rootPackage() ?: return@Runnable
            if (SettingsGuard.isGuardedPackage(pkg) && Lock.isLocked(this)) scan(pkg)
        } catch (e: Exception) {
            ErrorLog.note(this, "acc.rescan", e)
        }
    }

    private fun scan(pkg: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastBounce < 700) return
        lastScan = now
        val root = try { rootInActiveWindow } catch (_: Exception) { null } ?: return
        if (root.packageName?.toString() != pkg) return
        val text = StringBuilder()
        val ids = ArrayList<String>()
        collect(root, text, ids, intArrayOf(0))
        val screen = SettingsGuard.Screen(pkg, windowClass[pkg], text.toString(), ids)
        if (!SettingsGuard.shouldBounce(screen, guardDateTime = Lock.isTimeLocked(this))) return
        lastBounce = SystemClock.elapsedRealtime()
        performGlobalAction(GLOBAL_ACTION_BACK)
        main.postDelayed({ performGlobalAction(GLOBAL_ACTION_HOME) }, 120)
        val msg = if (Prefs.isFilterMode(this)) R.string.toast_settings_locked_filter else R.string.toast_settings_locked
        main.post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }

    private fun collect(node: AccessibilityNodeInfo?, out: StringBuilder, ids: MutableList<String>, count: IntArray) {
        if (node == null || count[0] > 600) return
        count[0]++
        node.text?.let { out.append(it).append('\n') }
        node.contentDescription?.let { out.append(it).append('\n') }
        node.viewIdResourceName?.let { ids += it }
        for (i in 0 until node.childCount) {
            val child = try { node.getChild(i) } catch (_: Exception) { null }
            collect(child, out, ids, count)
        }
    }

    private fun ensureVpn() {
        if (PBlockVpnService.running) return
        if (!Lock.isLocked(this) && !Prefs.vpnWanted(this)) return
        if (VpnService.prepare(this) == null) PBlockVpnService.start(this)
    }
}