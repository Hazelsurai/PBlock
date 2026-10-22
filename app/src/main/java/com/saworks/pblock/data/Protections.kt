package com.saworks.pblock.data

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.net.ConnectivityManager
import android.provider.Settings
import com.saworks.pblock.access.BlockerAccessibilityService
import com.saworks.pblock.admin.PBlockAdminReceiver
import com.saworks.pblock.vpn.PBlockVpnService

object Protections {
    fun vpnActive(): Boolean = PBlockVpnService.running

    fun accessibilityEnabled(ctx: Context): Boolean {
        val s = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val me = ComponentName(ctx, BlockerAccessibilityService::class.java)
        return s.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    fun adminActive(ctx: Context): Boolean =
        ctx.getSystemService(DevicePolicyManager::class.java).isAdminActive(PBlockAdminReceiver.component(ctx))

    fun allActive(ctx: Context): Boolean = vpnActive() && accessibilityEnabled(ctx) && adminActive(ctx)

    /** Hostname of a strict ("Private DNS provider hostname") Private DNS setting, which bypasses the filter. */
    @Suppress("DEPRECATION")
    fun privateDnsStrictHost(ctx: Context): String? {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return null
        return try {
            cm.allNetworks.firstNotNullOfOrNull { n -> cm.getLinkProperties(n)?.privateDnsServerName?.takeIf { it.isNotBlank() } }
        } catch (_: SecurityException) {
            null
        }
    }
}
