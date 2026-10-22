package com.saworks.pblock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.saworks.pblock.data.ProtectionMonitor
import com.saworks.pblock.data.TrustedTime

/** Device clock or time zone changed: log tampering (while locked) and re-check against trusted time. */
class TimeChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_TIME_CHANGED && intent.action != Intent.ACTION_TIMEZONE_CHANGED) return
        val pending = goAsync()
        Thread({
            try {
                ProtectionMonitor.check(context)
                TrustedTime.syncAsync(context, force = true)
            } finally {
                pending.finish()
            }
        }, "pblock-timeset").start()
    }

    companion object {
        /** Services register too, in case the manifest receiver is not delivered on some devices. */
        fun register(ctx: Context, r: TimeChangeReceiver) {
            try {
                val f = IntentFilter(Intent.ACTION_TIME_CHANGED).apply { addAction(Intent.ACTION_TIMEZONE_CHANGED) }
                ContextCompat.registerReceiver(ctx, r, f, ContextCompat.RECEIVER_NOT_EXPORTED) // system broadcasts still arrive
            } catch (_: Exception) {}
        }

        fun unregister(ctx: Context, r: TimeChangeReceiver) {
            try { ctx.unregisterReceiver(r) } catch (_: Exception) {}
        }
    }
}
