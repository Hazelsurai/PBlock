package com.saworks.pblock.core

import java.util.Locale

/** Minimal DNS wire-format helpers (RFC 1035): parse a query, build a blocked reply, inspect answers. */
object DnsMessage {

    const val TYPE_A = 1
    const val TYPE_AAAA = 28
    const val RCODE_NXDOMAIN = 3

    /** @param questionEnd length of header + first question, relative to the message start. */
    data class Query(val id: Int, val name: String, val qtype: Int, val questionEnd: Int)

    fun parseQuery(buf: ByteArray, off: Int, len: Int): Query? {
        if (len < 12 || off + len > buf.size) return null
        val flags = u16(buf, off + 2)
        if (flags and 0x8000 != 0) return null // a response, not a query
        if (u16(buf, off + 4) < 1) return null
        val end = off + len
        var p = off + 12
        val sb = StringBuilder()
        while (true) {
            if (p >= end) return null
            val l = u8(buf, p)
            if (l == 0) { p++; break }
            if (l and 0xC0 != 0) return null // no compression inside a query question
            p++
            if (p + l > end) return null
            if (sb.isNotEmpty()) sb.append('.')
            for (i in 0 until l) sb.append(u8(buf, p + i).toChar())
            p += l
            if (sb.length > 253) return null
        }
        if (p + 4 > end) return null
        return Query(u16(buf, off), sb.toString().lowercase(Locale.ROOT), u16(buf, p), p + 4 - off)
    }

    /** NXDOMAIN reply that echoes the id and the first question of [query]. */
    fun buildBlockedResponse(query: ByteArray, off: Int, q: Query): ByteArray {
        val out = query.copyOfRange(off, off + q.questionEnd)
        val flags = u16(query, off + 2)
        val newFlags = 0x8000 or (flags and 0x7800) or (flags and 0x0100) or 0x0080 or RCODE_NXDOMAIN
        put16(out, 2, newFlags)
        put16(out, 4, 1)
        put16(out, 6, 0)
        put16(out, 8, 0)
        put16(out, 10, 0)
        return out
    }

    fun rcode(buf: ByteArray, off: Int): Int = u16(buf, off + 2) and 0x000F

    /**
     * True if the response contains an A record 0.0.0.0 or an AAAA record ::,
     * which is how family-filter resolvers (e.g. Cloudflare 1.1.1.3) answer blocked names.
     */
    fun isNullRouted(buf: ByteArray, off: Int, len: Int): Boolean {
        if (len < 12) return false
        val end = off + len
        val qd = u16(buf, off + 4)
        val an = u16(buf, off + 6)
        var p = off + 12
        repeat(qd) {
            p = skipName(buf, p, end)
            if (p < 0 || p + 4 > end) return false
            p += 4
        }
        repeat(an) {
            p = skipName(buf, p, end)
            if (p < 0 || p + 10 > end) return false
            val type = u16(buf, p)
            val rdLen = u16(buf, p + 8)
            p += 10
            if (p + rdLen > end) return false
            if ((type == TYPE_A && rdLen == 4) || (type == TYPE_AAAA && rdLen == 16)) {
                var allZero = true
                for (i in 0 until rdLen) if (buf[p + i].toInt() != 0) { allZero = false; break }
                if (allZero) return true
            }
            p += rdLen
        }
        return false
    }

    private fun skipName(buf: ByteArray, start: Int, end: Int): Int {
        var p = start
        while (p < end) {
            val l = u8(buf, p)
            if (l == 0) return p + 1
            if (l and 0xC0 == 0xC0) return if (p + 2 <= end) p + 2 else -1
            p += l + 1
        }
        return -1
    }
}
