package com.saworks.pblock.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorTextTest {
    @Test fun crashContainsHeaderAndStackTrace() {
        val t = ErrorText.crash("2026-10-22T19:00+02:00", "main", "0.5.0 (5)", IllegalStateException("boom"))
        assertTrue(t.startsWith("2026-10-22T19:00+02:00  thread=main  app=0.5.0 (5)\n"))
        assertTrue(t.contains("java.lang.IllegalStateException: boom"))
        assertTrue(t.contains("ErrorTextTest"))
    }

    @Test fun crashIsTruncated() {
        val t = ErrorText.crash("t", "main", "v", RuntimeException("x".repeat(50_000)), max = 1000)
        assertTrue(t.length < 1100)
        assertTrue(t.endsWith("(truncated)"))
    }

    @Test fun lineIsSingleLineAndBounded() {
        val l = ErrorText.line("t", "vpn.packet", java.io.IOException("a\nb\tc" + "y".repeat(500)))
        assertFalse(l.contains('\n'))
        assertFalse(l.contains('\t'))
        assertTrue(l.startsWith("t vpn.packet: IOException: a b c"))
        assertEquals(300, l.length)
        assertEquals("t x: msg", ErrorText.line("t", "x", null, "msg"))
    }

    @Test fun keepLastDropsBlanksAndOldLines() {
        val lines = List(60) { "l$it" } + ""
        val kept = ErrorText.keepLast(lines)
        assertEquals(ErrorText.MAX_LINES, kept.size)
        assertEquals("l59", kept.last())
        assertEquals("l20", kept.first())
    }
}
