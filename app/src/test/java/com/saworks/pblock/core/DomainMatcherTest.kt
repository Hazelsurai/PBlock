package com.saworks.pblock.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainMatcherTest {
    private val m = DomainMatcher(DomainMatcher.CORE + listOf("example-adult.net", "cdn.somehost.org"))

    @Test fun exactAndSubdomains() {
        assertEquals("pornhub.com", m.match("pornhub.com"))
        assertEquals("pornhub.com", m.match("www.pornhub.com"))
        assertEquals("pornhub.com", m.match("a.b.c.pornhub.com"))
        assertEquals("thisvid.com", m.match("thisvid.com"))
        assertEquals("cdn.somehost.org", m.match("x.cdn.somehost.org"))
    }

    @Test fun caseAndTrailingDot() {
        assertTrue(m.isBlocked("WWW.PornHub.COM."))
    }

    @Test fun noFalsePositives() {
        assertFalse(m.isBlocked("notpornhub.com"))
        assertFalse(m.isBlocked("pornhub.com.example.org"))
        assertFalse(m.isBlocked("somehost.org"))
        assertFalse(m.isBlocked("com"))
        assertFalse(m.isBlocked("google.com"))
        assertNull(m.match(""))
    }

    @Test fun adultTlds() {
        assertEquals(".xxx", m.match("anything.xxx"))
        assertEquals(".porn", m.match("www.site.porn"))
        assertFalse(m.isBlocked("sex.com.example")) // only the real TLD counts
    }

    @Test fun parseHostsAndPlainLines() {
        assertEquals("pornhub.com", DomainMatcher.parseLine("0.0.0.0 PornHub.com # comment"))
        assertEquals("thisvid.com", DomainMatcher.parseLine("127.0.0.1\tthisvid.com"))
        assertEquals("example.org", DomainMatcher.parseLine("example.org"))
        assertNull(DomainMatcher.parseLine("# only a comment"))
        assertNull(DomainMatcher.parseLine("0.0.0.0 0.0.0.0"))
        assertNull(DomainMatcher.parseLine("   "))
    }
}
