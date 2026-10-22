package com.saworks.pblock.core

import java.net.IDN
import java.util.Locale

/** Parses what a user types as a domain ("https://www.Example.com/path" -> "example.com"). */
object DomainInput {
    private val LABEL = Regex("^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$")

    fun parse(raw: String): String? {
        var s = raw.trim().lowercase(Locale.ROOT)
        if (s.isEmpty()) return null
        s = s.substringAfter("://")
        s = s.substringBefore('/').substringBefore('?').substringBefore('#')
        s = s.substringAfterLast('@').substringBefore(':')
        if (s.startsWith("*.")) s = s.substring(2)
        s = s.trimEnd('.')
        s = try { IDN.toASCII(s, IDN.ALLOW_UNASSIGNED).lowercase(Locale.ROOT) } catch (_: Exception) { return null }
        if (s.length > 253) return null
        var labels = s.split('.')
        if (labels.size < 2) return null
        // "www.example.com" means the whole site, so m.example.com etc. are covered too.
        if (labels.size > 2 && labels[0] == "www") { labels = labels.drop(1); s = labels.joinToString(".") }
        if (labels.any { !LABEL.matches(it) }) return null
        if (labels.last().all { it.isDigit() }) return null // IP addresses are not domains
        return s
    }
}

/**
 * Content-filter mode rules on top of the built-in list: the allowlist wins (suffix match), then the user's
 * own blocked domains, then the built-in adult list.
 */
class FilterRules(blocked: Collection<String>, allowed: Collection<String>) {
    private val block = DomainMatcher(blocked, emptySet())
    private val allow = DomainMatcher(allowed, emptySet())

    fun match(host: String, builtIn: DomainMatcher): String? {
        if (allow.size > 0 && allow.match(host) != null) return null
        if (block.size > 0) block.match(host)?.let { return it }
        return builtIn.match(host)
    }

    companion object {
        val EMPTY = FilterRules(emptyList(), emptyList())
    }
}
