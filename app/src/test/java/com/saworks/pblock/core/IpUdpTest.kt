package com.saworks.pblock.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class IpUdpTest {
    private val a = byteArrayOf(10, 111, 222.toByte(), 1)
    private val dns = byteArrayOf(10, 111, 222.toByte(), 53)

    @Test fun buildThenParseRoundTrip() {
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        val pkt = IpUdp.buildUdp(a, dns, 40000, 53, payload)
        val p = IpUdp.parse(pkt, pkt.size)
        assertNotNull(p)
        assertArrayEquals(a, p!!.srcIp)
        assertArrayEquals(dns, p.dstIp)
        assertEquals(40000, p.srcPort)
        assertEquals(53, p.dstPort)
        assertArrayEquals(payload, pkt.copyOfRange(p.payloadOff, p.payloadOff + p.payloadLen))
    }

    @Test fun checksumsVerify() {
        val pkt = IpUdp.buildUdp(dns, a, 53, 40000, ByteArray(33) { it.toByte() })
        assertEquals(0, IpUdp.checksum(pkt, 0, 20, 0)) // header checksum valid
        var pseudo = 0L
        for (i in 12 until 20 step 2) pseudo += u16(pkt, i)
        pseudo += 17 + (pkt.size - 20)
        assertEquals(0, IpUdp.checksum(pkt, 20, pkt.size - 20, pseudo)) // UDP checksum valid
    }

    @Test fun rejectsNonUdpAndIpv6() {
        val pkt = IpUdp.buildUdp(a, dns, 1, 53, ByteArray(4))
        val tcp = pkt.copyOf().also { it[9] = 6 }
        assertNull(IpUdp.parse(tcp, tcp.size))
        val v6 = pkt.copyOf().also { it[0] = 0x60 }
        assertNull(IpUdp.parse(v6, v6.size))
        assertNull(IpUdp.parse(pkt, 10))
    }
}
