package com.saworks.pblock.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FilterRulesTest {
    @Test fun domainInputNormalizes() {
        assertEquals("example.com", DomainInput.parse("https://www.Example.com/path?q=1#x"))
        assertEquals("www.com", DomainInput.parse("www.com"))
        assertEquals("example.com", DomainInput.parse("  example.com.  "))
        assertEquals("example.com", DomainInput.parse("*.example.com"))
        assertEquals("example.com", DomainInput.parse("user@example.com:8080"))
        assertEquals("xn--bcher-kva.de", DomainInput.parse("bücher.de"))
    }

    @Test fun domainInputRejects() {
        assertNull(DomainInput.parse(""))
        assertNull(DomainInput.parse("localhost"))
        assertNull(DomainInput.parse("192.168.1.1"))
        assertNull(DomainInput.parse("exa mple.com"))
        assertNull(DomainInput.parse("-bad.com"))
        assertNull(DomainInput.parse("a..com"))
    }

    private val builtIn = DomainMatcher(listOf("adult.example", "bad.test"), emptySet())

    @Test fun builtInStillApplies() {
        assertEquals("adult.example", FilterRules.EMPTY.match("www.adult.example", builtIn))
        assertNull(FilterRules.EMPTY.match("news.example", builtIn))
    }

    @Test fun userBlockedDomainsIncludeSubdomains() {
        val r = FilterRules(listOf("tiktok.com"), emptyList())
        assertEquals("tiktok.com", r.match("tiktok.com", builtIn))
        assertEquals("tiktok.com", r.match("m.tiktok.com", builtIn))
        assertNull(r.match("nottiktok.com", builtIn))
    }

    @Test fun allowlistWins() {
        val r = FilterRules(listOf("tiktok.com"), listOf("bad.test", "edu.tiktok.com"))
        assertNull(r.match("bad.test", builtIn))
        assertNull(r.match("cdn.bad.test", builtIn))
        assertNull(r.match("edu.tiktok.com", builtIn))
        assertEquals("tiktok.com", r.match("www.tiktok.com", builtIn))
        assertEquals("adult.example", r.match("adult.example", builtIn))
    }
}
