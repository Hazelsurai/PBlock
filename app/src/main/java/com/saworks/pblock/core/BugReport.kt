package com.saworks.pblock.core

import java.net.URLEncoder

/**
 * Builds the bug report e-mail. Pure Kotlin so it can be unit tested. The report itself is in English
 * (it is read by the developer); the user's own text is kept as typed.
 */
object BugReport {
    const val ADDRESS = "saworksl1dev@gmail.com"

    /** Reports longer than this are attached as a text file (mailto: URIs and some mail apps cut long bodies). */
    const val INLINE_MAX_CHARS = 1800

    const val MAX_FIELD_CHARS = 8000

    enum class Category(val english: String) {
        BUG("Bug"),
        BLOCKING_FAILS("Blocking doesn't work"),
        WRONGLY_BLOCKED("Wrongly blocked site"),
        SUGGESTION("Suggestion"),
        OTHER("Other"),
    }

    data class Input(
        val category: Category,
        val description: String,
        val steps: String = "",
        val contact: String = "",
        /** "key: value" lines, or null if the user unticked diagnostics. */
        val diagnostics: List<Pair<String, String>>? = null,
        val errorLines: List<String> = emptyList(),
        val lastCrash: String? = null,
        /** Formatted activity log lines, or null if not attached. */
        val activityLog: List<String>? = null,
        val createdAt: String = "",
    )

    fun subject(version: String, c: Category) = "[PBlock v$version] ${c.english}"

    /** The part the user wrote (also the e-mail text when the full report goes into an attachment). */
    fun userPart(i: Input): String = buildString {
        append("Category: ").append(i.category.english).append('\n')
        if (i.createdAt.isNotEmpty()) append("Created: ").append(i.createdAt).append('\n')
        append("Reply to: ").append(i.contact.trim().ifEmpty { "(no reply address given)" }).append('\n')
        append("\nDescription:\n").append(clean(i.description)).append('\n')
        val steps = clean(i.steps)
        if (steps.isNotEmpty()) append("\nSteps to reproduce:\n").append(steps).append('\n')
    }

    fun fullText(i: Input): String = buildString {
        append(userPart(i))
        i.diagnostics?.let { d ->
            append("\n--- Diagnostics ---\n")
            d.forEach { (k, v) -> append(k).append(": ").append(v).append('\n') }
            append("\n--- Recent internal errors ---\n")
            if (i.errorLines.isEmpty()) append("(none)\n") else i.errorLines.forEach { append(it).append('\n') }
            append("\n--- Last crash ---\n")
            append(i.lastCrash?.trimEnd()?.ifEmpty { null } ?: "(none)").append('\n')
        }
        i.activityLog?.let { log ->
            append("\n--- Activity log (newest first, ").append(log.size).append(" entries) ---\n")
            if (log.isEmpty()) append("(empty)\n") else log.forEach { append(it).append('\n') }
        }
    }

    fun needsAttachment(full: String) = full.length > INLINE_MAX_CHARS

    /** Short e-mail text that goes with the attached full report. */
    fun attachmentBody(i: Input, fileName: String) =
        userPart(i) + "\n(The full report with diagnostics is in the attached file $fileName.)\n"

    fun mailtoUri(subject: String, body: String): String =
        "mailto:$ADDRESS?subject=" + enc(subject) + "&body=" + enc(body)

    /** Percent-encoding for mailto: (spaces as %20, not '+'). */
    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    fun clean(s: String): String = s.replace("\r\n", "\n").trim().take(MAX_FIELD_CHARS)

    private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
    fun isValidContact(s: String): Boolean = s.isBlank() || EMAIL.matches(s.trim())
}
