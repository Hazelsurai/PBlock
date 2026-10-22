package com.saworks.pblock.core

import java.util.Locale

/**
 * Hash-set based domain blocklist with suffix matching:
 * a rule "pornhub.com" also blocks "www.pornhub.com", "a.b.pornhub.com", but not "notpornhub.com".
 * Whole adult TLDs (.xxx, .porn, .sex, .adult) are blocked as well.
 */
class DomainMatcher(domains: Collection<String>, private val blockedTlds: Set<String> = ADULT_TLDS) {

    private val set: HashSet<String> = HashSet(domains.size * 2)

    init {
        for (d in domains) normalize(d)?.let { set.add(it) }
    }

    val size: Int get() = set.size

    /** Returns the matching rule (the blocked domain or TLD), or null if [host] is allowed. */
    fun match(host: String): String? {
        var h = normalize(host) ?: return null
        val tld = h.substringAfterLast('.')
        while (true) {
            if (set.contains(h)) return h
            val dot = h.indexOf('.')
            if (dot < 0) break
            h = h.substring(dot + 1)
            if (h.indexOf('.') < 0) break // never match a bare TLD via the domain set
        }
        return if (tld in blockedTlds) ".$tld" else null
    }

    fun isBlocked(host: String): Boolean = match(host) != null

    companion object {
        /** Always blocked, even if the bundled list fails to load. */
        val CORE = listOf("pornhub.com", "thisvid.com")
        val ADULT_TLDS = setOf("xxx", "porn", "sex", "adult")

        fun normalize(raw: String): String? {
            var s = raw.trim().lowercase(Locale.ROOT).trimEnd('.')
            if (s.startsWith("*.")) s = s.substring(2)
            if (s.isEmpty() || s.any { it.isWhitespace() }) return null
            return s
        }

        /** Parses hosts-file lines ("0.0.0.0 domain") or plain domain-per-line lists. */
        fun parseLine(line: String): String? {
            val l = line.substringBefore('#').trim()
            if (l.isEmpty()) return null
            val parts = l.split(' ', '\t').filter { it.isNotEmpty() }
            val d = if (parts.size >= 2 && (parts[0] == "0.0.0.0" || parts[0] == "127.0.0.1")) parts[1] else parts[0]
            if (d == "localhost" || d == "0.0.0.0") return null
            return normalize(d)
        }
    }
}
