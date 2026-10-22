package com.saworks.pblock

import android.app.Application
import com.saworks.pblock.data.ErrorLog
import com.saworks.pblock.data.Prefs

/**
 * Installs the local crash recorder (for bug reports) and applies the saved theme
 * (System / Light / Dark, default Dark) before any screen is created.
 */
class PBlockApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ErrorLog.install(this)
        Prefs.applyTheme(this)
    }
}
