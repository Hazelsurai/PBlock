package com.saworks.pblock.core

/**
 * Merges accessibility events that arrive while a Settings screen scan is still queued or running, so
 * a slow screen (each node read is a call into the other app) cannot pile up a backlog of scans.
 * Merging only ever widens the next scan: the latest package wins, and a window change anywhere in
 * the batch keeps the next scan from being skipped by the content-change throttle.
 */
class EventCoalescer {
    data class Request(val pkg: String, val windowChange: Boolean)

    private var pkg: String? = null
    private var windowChange = false

    /** Records an event; true when no request was pending, i.e. the caller has to schedule a scan. */
    @Synchronized
    fun offer(pkg: String, windowChange: Boolean): Boolean {
        val first = this.pkg == null
        this.pkg = pkg
        this.windowChange = this.windowChange || windowChange
        return first
    }

    /** Takes the merged request (null if there is none) and clears it. */
    @Synchronized
    fun take(): Request? {
        val p = pkg ?: return null
        val r = Request(p, windowChange)
        pkg = null
        windowChange = false
        return r
    }
}