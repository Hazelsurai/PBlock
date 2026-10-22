package com.saworks.pblock.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneOffset
import java.time.ZonedDateTime

class TimeParsersTest {
    private val ntpOffset = 2_208_988_800L

    private fun reply(unixSec: Long, fracHalf: Boolean = false, mode: Int = 4, stratum: Int = 2, leap: Int = 0): ByteArray {
        val b = ByteArray(48)
        b[0] = ((leap shl 6) or (4 shl 3) or mode).toByte()
        b[1] = stratum.toByte()
        val sec = unixSec + ntpOffset
        for (i in 0 until 4) b[40 + i] = (sec shr (24 - 8 * i)).toByte()
        if (fracHalf) b[44] = 0x80.toByte()
        return b
    }

    private val nov22 = ZonedDateTime.of(2026, 11, 22, 10, 30, 0, 0, ZoneOffset.UTC).toEpochSecond()

    @Test fun sntpRequestIsClientV3() {
        val r = Sntp.request()
        assertEquals(48, r.size)
        assertEquals(0x1B, r[0].toInt())
    }

    @Test fun sntpParsesTransmitTimestamp() {
        assertEquals(nov22 * 1000, Sntp.parse(reply(nov22)))
        assertEquals(nov22 * 1000 + 500, Sntp.parse(reply(nov22, fracHalf = true)))
    }

    @Test fun sntpRejectsBadReplies() {
        assertNull(Sntp.parse(reply(nov22, mode = 3)))
        assertNull(Sntp.parse(reply(nov22, stratum = 0))) // kiss-o'-death
        assertNull(Sntp.parse(reply(nov22, stratum = 16)))
        assertNull(Sntp.parse(reply(nov22, leap = 3))) // unsynchronized
        assertNull(Sntp.parse(ByteArray(20), 20))
        assertNull(Sntp.parse(reply(0L))) // 1970: outside the sane range
    }

    @Test fun httpDate() {
        assertEquals(nov22 * 1000, HttpDate.parse("Sun, 22 Nov 2026 10:30:00 GMT"))
        assertNull(HttpDate.parse(null))
        assertNull(HttpDate.parse("garbage"))
        assertNull(HttpDate.parse("Thu, 01 Jan 1970 00:00:00 GMT"))
    }
}
