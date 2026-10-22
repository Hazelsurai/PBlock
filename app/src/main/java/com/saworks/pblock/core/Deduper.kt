package com.saworks.pblock.core

/** Lets one event per key through per window (browsers retry DNS lookups many times). */
class Deduper(private val windowMillis: Long = 60_000L, private val maxKeys: Int = 4096) {
    private val last = HashMap<String, Long>()

    @Synchronized
    fun shouldLog(key: String, nowMillis: Long): Boolean {
        val prev = last[key]
        if (prev != null && nowMillis - prev in 0 until windowMillis) return false
        if (last.size >= maxKeys) last.clear()
        last[key] = nowMillis
        return true
    }
}
