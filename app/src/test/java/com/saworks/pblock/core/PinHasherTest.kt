package com.saworks.pblock.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PinHasherTest {
    @Test fun validPins() {
        assertTrue(PinHasher.isValidPin("1234"))
        assertTrue(PinHasher.isValidPin("123456789012"))
        assertFalse(PinHasher.isValidPin("123"))
        assertFalse(PinHasher.isValidPin("1234567890123"))
        assertFalse(PinHasher.isValidPin("12a4"))
        assertFalse(PinHasher.isValidPin(""))
        assertFalse(PinHasher.isValidPin("١٢٣٤")) // non-ASCII digits
    }

    @Test fun hashVerifiesAndIsSalted() {
        val a = PinHasher.hash("2468", iterations = 1000)
        val b = PinHasher.hash("2468", iterations = 1000)
        assertTrue(a.startsWith("pbkdf2-sha256\$1000\$"))
        assertNotEquals(a, b) // random salt
        assertFalse(a.contains("2468"))
        assertTrue(PinHasher.verify("2468", a))
        assertTrue(PinHasher.verify("2468", b))
        assertFalse(PinHasher.verify("2469", a))
        assertFalse(PinHasher.verify("", a))
    }

    @Test fun deterministicWithFixedSalt() {
        val salt = ByteArray(16) { it.toByte() }
        assertEquals(PinHasher.hash("1111", 500, salt), PinHasher.hash("1111", 500, salt))
        assertNotEquals(PinHasher.hash("1111", 500, salt), PinHasher.hash("1111", 501, salt))
    }

    @Test fun malformedStoredValues() {
        assertFalse(PinHasher.verify("1234", null))
        assertFalse(PinHasher.verify("1234", ""))
        assertFalse(PinHasher.verify("1234", "garbage"))
        assertFalse(PinHasher.verify("1234", "pbkdf2-sha256\$0\$AAAA\$AAAA"))
        assertFalse(PinHasher.verify("1234", "pbkdf2-sha256\$1000\$!!!\$AAAA"))
        assertFalse(PinHasher.verify("1234", "md5\$1000\$AAAA\$AAAA"))
    }

    @Test fun throttleDelays() {
        assertEquals(0L, PinThrottle.delayAfter(0))
        assertEquals(0L, PinThrottle.delayAfter(2))
        assertEquals(30_000L, PinThrottle.delayAfter(3))
        assertEquals(60_000L, PinThrottle.delayAfter(4))
        assertEquals(120_000L, PinThrottle.delayAfter(5))
        assertEquals(PinThrottle.MAX_MS, PinThrottle.delayAfter(10))
        assertEquals(PinThrottle.MAX_MS, PinThrottle.delayAfter(1000))
    }

    @Test fun throttleWait() {
        val t = 1_000_000L
        assertEquals(0L, PinThrottle.waitMs(2, t, t))
        assertEquals(30_000L, PinThrottle.waitMs(3, t, t))
        assertEquals(20_000L, PinThrottle.waitMs(3, t, t + 10_000))
        assertEquals(0L, PinThrottle.waitMs(3, t, t + 31_000))
        // clock moved backwards: never longer than the delay itself
        assertEquals(30_000L, PinThrottle.waitMs(3, t, t - 500_000))
        assertEquals(0L, PinThrottle.waitMs(5, 0L, t))
    }
}
