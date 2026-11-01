package com.saworks.pblock.vpn

import android.content.Context
import com.saworks.pblock.core.DomainMatcher
import com.saworks.pblock.core.FilterRules
import com.saworks.pblock.data.Prefs
import java.io.IOException

object Blocklist {
    @Volatile private var matcher: DomainMatcher? = null

    fun get(ctx: Context): DomainMatcher =
        matcher ?: synchronized(this) { matcher ?: load(ctx).also { matcher = it } }

    @Volatile private var rules: FilterRules? = null

    /**
     * The user's own blocked/allowed domains in content-filter mode. In the challenge only the own blocked
     * domains, and only if the user opted in; exceptions never loosen the challenge.
     */
    fun rules(ctx: Context): FilterRules = rules ?: synchronized(this) {
        rules ?: when {
            Prefs.isFilterMode(ctx) -> FilterRules(Prefs.blockedDomains(ctx), Prefs.allowedDomains(ctx))
            Prefs.challengeUsesFilterLists(ctx) -> FilterRules(Prefs.blockedDomains(ctx), emptyList())
            else -> FilterRules.EMPTY
        }.also { rules = it }
    }

    /** Call after the lists or the mode changed. */
    fun invalidateRules() { rules = null }

    private fun load(ctx: Context): DomainMatcher {
        val list = ArrayList<String>(60_000)
        list += DomainMatcher.CORE
        try {
            ctx.assets.open("blocklist.txt").bufferedReader().useLines { lines ->
                lines.forEach { l -> DomainMatcher.parseLine(l)?.let { list += it } }
            }
        } catch (_: IOException) {
        }
        return DomainMatcher(list)
    }
}
