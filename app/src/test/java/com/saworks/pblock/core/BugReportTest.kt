package com.saworks.pblock.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder

class BugReportTest {
    private val base = BugReport.Input(BugReport.Category.WRONGLY_BLOCKED, "  example.org is blocked  \r\n", createdAt = "2026-10-22 19:00 +02:00")

    @Test fun subjectHasVersionAndEnglishCategory() {
        assertEquals("[PBlock v0.5.0] Wrongly blocked site", BugReport.subject("0.5.0", BugReport.Category.WRONGLY_BLOCKED))
        assertEquals("[PBlock v1.2.3] Blocking doesn't work", BugReport.subject("1.2.3", BugReport.Category.BLOCKING_FAILS))
    }

    @Test fun minimalReportHasNoOptionalSections() {
        val t = BugReport.fullText(base)
        assertTrue(t.contains("Category: Wrongly blocked site"))
        assertTrue(t.contains("Description:\nexample.org is blocked\n"))
        assertTrue(t.contains("(no reply address given)"))
        assertFalse(t.contains("Steps to reproduce"))
        assertFalse(t.contains("--- Diagnostics ---"))
        assertFalse(t.contains("Activity log"))
    }

    @Test fun diagnosticsAndLogSections() {
        val t = BugReport.fullText(base.copy(
            steps = "1. open\n2. tap", contact = " me@example.com ",
            diagnostics = listOf("App" to "0.5.0 (5)", "Lock" to "parent PIN, locked"),
            errorLines = listOf("t vpn.packet: IOException"), lastCrash = null,
            activityLog = listOf("2026-10-22 18:00 +02:00  dns-filter  reddit.com"),
        ))
        assertTrue(t.contains("Steps to reproduce:\n1. open\n2. tap"))
        assertTrue(t.contains("Reply to: me@example.com"))
        assertTrue(t.contains("App: 0.5.0 (5)\nLock: parent PIN, locked"))
        assertTrue(t.contains("--- Recent internal errors ---\nt vpn.packet: IOException"))
        assertTrue(t.contains("--- Last crash ---\n(none)"))
        assertTrue(t.contains("Activity log (newest first, 1 entries)"))
        assertTrue(t.indexOf("Diagnostics") < t.indexOf("Activity log"))
    }

    @Test fun emptyLogAndErrorsAreMarked() {
        val t = BugReport.fullText(base.copy(diagnostics = emptyList(), activityLog = emptyList()))
        assertTrue(t.contains("--- Recent internal errors ---\n(none)"))
        assertTrue(t.contains("(empty)"))
    }

    @Test fun longReportsGoToAttachment() {
        assertFalse(BugReport.needsAttachment(BugReport.fullText(base)))
        val long = base.copy(activityLog = List(100) { "2026-10-22 18:00 +02:00  dns  example$it.com" })
        val full = BugReport.fullText(long)
        assertTrue(BugReport.needsAttachment(full))
        val body = BugReport.attachmentBody(long, "pblock-report.txt")
        assertTrue(body.contains("pblock-report.txt"))
        assertFalse(body.contains("example5.com"))
    }

    @Test fun fieldsAreCapped() {
        assertEquals(BugReport.MAX_FIELD_CHARS, BugReport.clean("x".repeat(20_000)).length)
    }

    @Test fun mailtoEncodesSpacesAndNewlines() {
        val uri = BugReport.mailtoUri("[PBlock v0.5.0] Bug", "a b\nc&d=é")
        assertTrue(uri.startsWith("mailto:saworksl1dev@gmail.com?subject="))
        assertFalse(uri.contains("+"))
        assertFalse(uri.contains(" "))
        val body = uri.substringAfter("&body=")
        assertEquals("a b\nc&d=é", URLDecoder.decode(body, "UTF-8"))
    }

    @Test fun contactValidation() {
        assertTrue(BugReport.isValidContact(""))
        assertTrue(BugReport.isValidContact(" a.b@c.hu "))
        assertFalse(BugReport.isValidContact("nope"))
        assertFalse(BugReport.isValidContact("a@b"))
    }
}
