package com.saworks.pblock

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import com.saworks.pblock.data.ErrorLog

/**
 * One process-wide background thread for the protection checks (monitor timers, down marks, attempt
 * logging, app-rule lookups). PBlock's services share the main thread with its screens, and the
 * system kills the app with an ANR when a service callback has to wait behind slow work there, so
 * file, PackageManager and binder calls stay off it.
 */
object Workers {
    val bg: Handler by lazy {
        val t = HandlerThread("pblock-bg").apply { start() }
        Handler(t.looper)
    }

    /** Runs [block] on [bg], swallowing (and recording) failures so a bad check cannot kill the thread. */
    fun run(tag: String, ctx: Context, block: () -> Unit) {
        val app = ctx.applicationContext
        bg.post {
            try { block() } catch (e: Exception) { ErrorLog.note(app, tag, e) }
        }
    }
}