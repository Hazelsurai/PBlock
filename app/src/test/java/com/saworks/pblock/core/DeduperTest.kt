package com.saworks.pblock.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeduperTest {
    @Test fun onePerKeyPerMinute() {
        val d = Deduper(60_000L)
        assertTrue(d.shouldLog("dns|pornhub.com", 1_000))
        assertFalse(d.shouldLog("dns|pornhub.com", 30_000))
        assertTrue(d.shouldLog("dns|thisvid.com", 30_000))
        assertFalse(d.shouldLog("dns|pornhub.com", 60_999))
        assertTrue(d.shouldLog("dns|pornhub.com", 61_000))
    }
}
