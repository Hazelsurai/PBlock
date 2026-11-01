package com.saworks.pblock.core

import java.util.Locale

/**
 * Safety checks for the app blocker: apps that are never blocked (PBlock, Settings, phone/emergency) and a
 * name-based warning for apps that look important (bank, authenticator, maps, ...), so a mis-tap in the
 * picker does not lock someone out of their bank app for the rest of a lock. Pure Kotlin for unit tests.
 */
object AppSafety {

    /** Never blockable, whatever the list says: blocking them could make the phone unusable or unsafe. */
    val NEVER_BLOCK = setOf(
        "com.saworks.pblock",
        "com.android.systemui",
        "com.android.settings",
        "com.android.phone",
        "com.android.server.telecom",
        "com.android.dialer",
        "com.google.android.dialer",
        "com.samsung.android.dialer",
        "com.samsung.android.app.telephonyui",
        "com.android.incallui",
        "com.samsung.android.incallui",
        "com.android.emergency",
        "com.google.android.apps.safetyhub",
        "com.samsung.android.emergencymode.service",
        "com.sec.android.emergencymode.service",
        "com.android.cellbroadcastreceiver",
        "com.google.android.cellbroadcastreceiver",
    )

    /** @param defaultDialer the device's current default phone app, if known. */
    fun neverBlock(pkg: String, defaultDialer: String? = null): Boolean =
        pkg in NEVER_BLOCK || (defaultDialer != null && pkg == defaultDialer)

    enum class Kind { BANK, AUTH, GOV, HEALTH, MAPS, COMM, EMERGENCY }

    /**
     * Keywords (lowercase, accent-free). Long ones match anywhere in the name or package; ones of three
     * characters or fewer only as a whole word / package segment, so "otp" does not hit "hotspot".
     */
    val KEYWORDS: Map<Kind, List<String>> = mapOf(
        Kind.BANK to listOf(
            "bank", "banking", "otp", "k&h", "khbank", "erste", "george", "raiffeisen", "mbh", "cib", "unicredit",
            "revolut", "wise", "paypal", "granit", "magnet", "simplepay", "barion", "penz", "wallet", "pay",
            "fizet", "kartya", "card", "sparkasse", "volksbank", "commerzbank", "sberbank", "tinkoff", "alipay",
            "wechat pay", "nubank", "itau", "bradesco", "santander", "monzo", "n26", "finance", "crypto", "coinbase",
            "binance", "phantom", "brokerage", "trading",
        ),
        Kind.AUTH to listOf(
            "authenticator", "2fa", "authy", "otp auth", "duo", "okta", "keepass", "bitwarden", "1password",
            "lastpass", "password", "jelszo", "token",
        ),
        Kind.GOV to listOf(
            "ugyfelkapu", "kau", "dap", "egov", "nav", "eeszt", "eszja", "kormany", "gov", "elster", "gosuslugi",
        ),
        Kind.HEALTH to listOf("egeszseg", "health", "doctor", "orvos", "medical", "medicine", "patika", "pharmacy", "insulin"),
        Kind.MAPS to listOf("maps", "terkep", "navigation", "waze"),
        Kind.COMM to listOf(
            "mail", "phone", "dialer", "telefon", "sms", "messages", "uzenetek", "contacts", "nevjegy",
        ),
        Kind.EMERGENCY to listOf("emergency", "112", "sos", "segelyhivo", "veszhelyzet", "notruf"),
    )

    fun normalize(s: String): String =
        java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .lowercase(Locale.ROOT)

    private fun words(s: String): Set<String> = s.split(Regex("[^\\p{L}\\p{N}&]+")).filter { it.isNotEmpty() }.toSet()

    /** The kinds an app looks like by its label and package name; empty if it does not look important. */
    fun importantKinds(label: String, pkg: String): Set<Kind> {
        val l = normalize(label)
        val p = normalize(pkg)
        val tokens = words(l) + words(p)
        val out = LinkedHashSet<Kind>()
        for ((kind, keys) in KEYWORDS) {
            if (keys.any { k -> if (k.length <= 3) k in tokens else l.contains(k) || p.contains(k.replace(" ", "")) }) out += kind
        }
        return out
    }

    fun looksImportant(label: String, pkg: String): Boolean = importantKinds(label, pkg).isNotEmpty()
}

/**
 * Grace period for list entries: something added (to the blocked apps / sites, or the challenge switch) can be
 * taken back for [GRACE_MS] even while a lock only allows tightening, in case it was a mis-tap. Uses trusted
 * time; an entry with an unknown or future add time is never in grace.
 */
object FilterGrace {
    const val GRACE_MS = 15 * 60_000L

    fun inGrace(addedAt: Long, now: Long): Boolean = addedAt > 0 && now >= addedAt && now - addedAt <= GRACE_MS

    /** True if every removed entry is still within its grace period (nothing removed is also true). */
    fun removalAllowed(removed: Collection<String>, addedAt: (String) -> Long, now: Long): Boolean =
        removed.all { inGrace(addedAt(it), now) }
}
