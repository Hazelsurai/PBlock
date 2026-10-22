package com.saworks.pblock.admin

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.saworks.pblock.R
import com.saworks.pblock.data.Lock
import com.saworks.pblock.data.Prefs

/** Device admin: while active, Android refuses to uninstall PBlock. */
class PBlockAdminReceiver : DeviceAdminReceiver() {

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence =
        context.getString(
            when {
                !Lock.isLocked(context) -> R.string.admin_disable_warning
                Prefs.isFilterMode(context) -> R.string.admin_disable_warning_filter_locked
                else -> R.string.admin_disable_warning_locked
            }
        )

    companion object {
        fun component(ctx: Context) = ComponentName(ctx, PBlockAdminReceiver::class.java)
    }
}
