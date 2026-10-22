package com.saworks.pblock.core

/** IPv4 + UDP parsing/building for the DNS-only tun interface. */
object IpUdp {

    class Packet(
        val srcIp: ByteArray,
        val dstIp: ByteArray,
        val srcPort: Int,
        val dstPort: Int,
        val payloadOff: Int,
        val payloadLen: Int,
    )

    fun parse(buf: ByteArray, len: Int): Packet? {
        if (len < 28) return null
        val vihl = u8(buf, 0)
        if (vihl shr 4 != 4) return null
        val ihl = (vihl and 0x0F) * 4
        if (ihl < 20 || len < ihl + 8) return null
        if (u8(buf, 9) != 17) return null // UDP only
        val total = u16(buf, 2)
        if (total > len || total < ihl + 8) return null
        if (u16(buf, 6) and 0x3FFF != 0) return null // fragmented: ignore
        val udpLen = u16(buf, ihl + 4)
        if (udpLen < 8 || ihl + udpLen > total) return null
        return Packet(
            srcIp = buf.copyOfRange(12, 16),
            dstIp = buf.copyOfRange(16, 20),
            srcPort = u16(buf, ihl),
            dstPort = u16(buf, ihl + 2),
            payloadOff = ihl + 8,
            payloadLen = udpLen - 8,
        )
    }

    fun buildUdp(src: ByteArray, dst: ByteArray, srcPort: Int, dstPort: Int, payload: ByteArray, pOff: Int = 0, pLen: Int = payload.size): ByteArray {
        val total = 28 + pLen
        val b = ByteArray(total)
        b[0] = 0x45
        put16(b, 2, total)
        put16(b, 6, 0x4000) // DF
        b[8] = 64
        b[9] = 17
        System.arraycopy(src, 0, b, 12, 4)
        System.arraycopy(dst, 0, b, 16, 4)
        put16(b, 10, checksum(b, 0, 20, 0))
        put16(b, 20, srcPort)
        put16(b, 22, dstPort)
        put16(b, 24, 8 + pLen)
        System.arraycopy(payload, pOff, b, 28, pLen)
        // UDP checksum over pseudo header + UDP header + payload
        var pseudo = 0L
        for (i in 12 until 20 step 2) pseudo += u16(b, i)
        pseudo += 17 + 8 + pLen
        var c = checksum(b, 20, 8 + pLen, pseudo)
        if (c == 0) c = 0xFFFF
        put16(b, 26, c)
        return b
    }

    fun checksum(b: ByteArray, off: Int, len: Int, initial: Long): Int {
        var sum = initial
        var i = off
        val end = off + len
        while (i + 1 < end) { sum += u16(b, i); i += 2 }
        if (i < end) sum += u8(b, i) shl 8
        while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        return (sum.inv() and 0xFFFF).toInt()
    }
}
