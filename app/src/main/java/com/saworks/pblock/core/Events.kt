package com.saworks.pblock.core

/** Event kinds in the attempt log and how they count on the calendar. Targets of the newer kinds are machine-readable. */
object Events {
    const val DNS = "dns"
    const val DNS_FILTER = "dns-filter"
    const val APP = "app"
    /** Device clock changed. Target: "jump|<ms>", "behind|<ms>" (clock earlier than trusted time at boot) or "offset|<ms>". */
    const val CLOCK = "clock"
    /** Target: "boots|<n>" (restarts PBlock did not see, e.g. safe mode) or "now". */
    const val SAFE_MODE = "safe-mode"
    /** Target: "<vpn|accessibility>|off|<sinceMillis>" or "<vpn|accessibility>|on|<durationMillis>". */
    const val DOWN = "down"
    /** "Hard moment" screen opened: informational, never a failure. */
    const val HARD_MOMENT = "hard-moment"

    const val COMP_VPN = "vpn"
    const val COMP_ACCESSIBILITY = "accessibility"

    fun isAttempt(kind: String) = kind == DNS || kind == DNS_FILTER || kind == APP
    fun isWarning(kind: String) = kind == CLOCK || kind == SAFE_MODE || kind == DOWN
    fun isInfo(kind: String) = kind == HARD_MOMENT

    fun parts(target: String): List<String> = target.split('|')
}
