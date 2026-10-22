package com.saworks.pblock.data

import android.content.Context
import android.util.Log
import com.saworks.pblock.BuildConfig
import com.saworks.pblock.core.Deduper
import com.saworks.pblock.core.ErrorText
import java.io.File
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * Lightweight local error log for bug reports: the last crash's stack trace and the most recent handled
 * errors (one line each). Stays on the phone; only sent if the user attaches diagnostics to a bug report.
 */
object ErrorLog {
    private const val TAG = "PBlockErr"
    private val lock = Any()
    private val deduper = Deduper(60_000L)
    @Volatile private var appCtx: Context? = null

    private fun dir(ctx: Context) = File(ctx.applicationContext.filesDir, "diag").apply { mkdirs() }
    private fun crashFile(ctx: Context) = File(dir(ctx), "last_crash.txt")
    private fun linesFile(ctx: Context) = File(dir(ctx), "errors.log")

    private fun stamp(): String = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME).take(25)

    /** Stores the stack trace of an uncaught exception, then lets the previous handler crash the app as usual. */
    fun install(ctx: Context) {
        appCtx = ctx.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            try {
                val c = appCtx
                if (c != null) {
                    val text = ErrorText.crash(stamp(), thread.name, "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", e)
                    synchronized(lock) { crashFile(c).writeText(text) }
                }
            } catch (_: Throwable) {
            }
            if (previous != null) previous.uncaughtException(thread, e) else {
                android.os.Process.killProcess(android.os.Process.myPid())
                kotlin.system.exitProcess(10)
            }
        }
    }

    /** Records a handled error (at most once a minute per [where]). Never throws. */
    fun note(ctx: Context?, where: String, t: Throwable? = null, message: String? = null) {
        if (t != null) Log.w(TAG, where, t) else Log.w(TAG, "$where: $message")
        val c = ctx?.applicationContext ?: appCtx ?: return
        if (!deduper.shouldLog(where, System.currentTimeMillis())) return
        try {
            synchronized(lock) {
                val f = linesFile(c)
                val old = if (f.exists()) f.readLines() else emptyList()
                val all = ErrorText.keepLast(old + ErrorText.line(stamp(), where, t, message))
                f.writeText(all.joinToString("\n", postfix = "\n"))
            }
        } catch (_: Exception) {
        }
    }

    fun recent(ctx: Context): List<String> = try {
        synchronized(lock) { linesFile(ctx).takeIf { it.exists() }?.readLines()?.let { ErrorText.keepLast(it) } ?: emptyList() }
    } catch (_: Exception) { emptyList() }

    fun lastCrash(ctx: Context): String? = try {
        synchronized(lock) { crashFile(ctx).takeIf { it.exists() }?.readText()?.takeIf { it.isNotBlank() } }
    } catch (_: Exception) { null }
}
