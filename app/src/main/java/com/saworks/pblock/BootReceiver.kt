package com.saworks.pblock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.saworks.pblock.data.Lock
import com.saworks.pblock.data.Prefs
import com.saworks.pblock.data.ProtectionMonitor
import com.saworks.pblock.vpn.PBlockVpnService

/** Restarts the DNS filter after reboot (Always-on VPN does this too, if it is enabled) and checks for missed boots. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        ProtectionMonitor.check(context)
        if ((Prefs.vpnWanted(context) || Lock.isLocked(context)) && VpnService.prepare(context) == null) {
            PBlockVpnService.start(context)
        }
    }
}
