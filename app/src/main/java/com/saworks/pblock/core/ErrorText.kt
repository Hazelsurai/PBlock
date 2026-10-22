package com.saworks.pblock.core

import java.io.PrintWriter
import java.io.StringWriter

/** Formatting for the local error log (pure Kotlin, unit tested). */
object ErrorText {
    const val MAX_CRASH_CHARS = 12_000
    const val MAX_LINES = 40

    fun crash(time: String, thread: String, version: String, t: Throwable, max: Int = MAX_CRASH_CHARS): String {
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        val head = "$time  thread=$thread  app=$version\n"
        val text = head + sw.toString()
        return if (text.length <= max) text else text.take(max) + "\n…(truncated)"
    }

    /** One line: "time where: ExceptionClass: message" (no newlines/tabs, bounded length). */
    fun line(time: String, where: String, t: Throwable?, message: String? = null): String {
        val what = when {
            t != null -> t.javaClass.simpleName + (t.message?.let { ": $it" } ?: "")
            else -> message.orEmpty()
        }
        return "$time $where: $what".replace(Regex("[\\r\\n\\t]+"), " ").take(300)
    }

    fun keepLast(lines: List<String>, max: Int = MAX_LINES): List<String> =
        lines.filter { it.isNotBlank() }.takeLast(max)
}
