package com.saworks.pblock.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class DnsMessageTest {

    private fun qname(name: String): ByteArray {
        val out = ByteArrayOutputStream()
        name.split('.').forEach { l -> out.write(l.length); out.write(l.toByteArray()) }
        out.write(0)
        return out.toByteArray()
    }

    private fun query(id: Int, name: String, qtype: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf((id shr 8).toByte(), id.toByte(), 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 0))
        out.write(qname(name))
        out.write(byteArrayOf(0, qtype.toByte(), 0, 1))
        return out.toByteArray()
    }

    @Test fun parsesQuery() {
        val q = DnsMessage.parseQuery(query(0x1234, "www.PornHub.com", 1), 0, query(0x1234, "www.PornHub.com", 1).size)
        assertNotNull(q)
        assertEquals(0x1234, q!!.id)
        assertEquals("www.pornhub.com", q.name)
        assertEquals(DnsMessage.TYPE_A, q.qtype)
    }

    @Test fun parsesWithOffset() {
        val raw = query(7, "thisvid.com", 28)
        val buf = ByteArray(5) + raw
        val q = DnsMessage.parseQuery(buf, 5, raw.size)!!
        assertEquals("thisvid.com", q.name)
        assertEquals(DnsMessage.TYPE_AAAA, q.qtype)
    }

    @Test fun rejectsResponsesAndGarbage() {
        val raw = query(1, "a.com", 1)
        raw[2] = (raw[2].toInt() or 0x80).toByte()
        assertNull(DnsMessage.parseQuery(raw, 0, raw.size))
        assertNull(DnsMessage.parseQuery(ByteArray(5), 0, 5))
        val trunc = query(1, "abc.com", 1)
        assertNull(DnsMessage.parseQuery(trunc, 0, 15))
    }

    @Test fun blockedResponseIsNxdomain() {
        val raw = query(0xBEEF, "pornhub.com", 1)
        val q = DnsMessage.parseQuery(raw, 0, raw.size)!!
        val r = DnsMessage.buildBlockedResponse(raw, 0, q)
        assertEquals(0xBEEF, u16(r, 0))
        assertTrue(u16(r, 2) and 0x8000 != 0)          // QR
        assertTrue(u16(r, 2) and 0x0100 != 0)          // RD echoed
        assertEquals(DnsMessage.RCODE_NXDOMAIN, DnsMessage.rcode(r, 0))
        assertEquals(1, u16(r, 4))
        assertEquals(0, u16(r, 6))
        assertArrayEquals(raw.copyOfRange(12, raw.size), r.copyOfRange(12, r.size))
    }

    private fun response(ip: ByteArray, type: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0, 1, 0x81.toByte(), 0x80.toByte(), 0, 1, 0, 1, 0, 0, 0, 0))
        out.write(qname("x.example.com"))
        out.write(byteArrayOf(0, type.toByte(), 0, 1))
        out.write(byteArrayOf(0xC0.toByte(), 12, 0, type.toByte(), 0, 1, 0, 0, 0, 60, 0, ip.size.toByte()))
        out.write(ip)
        return out.toByteArray()
    }

    @Test fun detectsNullRoutedAnswers() {
        val blocked = response(byteArrayOf(0, 0, 0, 0), 1)
        assertTrue(DnsMessage.isNullRouted(blocked, 0, blocked.size))
        val blocked6 = response(ByteArray(16), 28)
        assertTrue(DnsMessage.isNullRouted(blocked6, 0, blocked6.size))
        val ok = response(byteArrayOf(93, 184.toByte(), 216.toByte(), 34), 1)
        assertFalse(DnsMessage.isNullRouted(ok, 0, ok.size))
        assertFalse(DnsMessage.isNullRouted(ok, 0, 20)) // truncated: no crash
    }
}
