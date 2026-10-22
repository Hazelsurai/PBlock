package com.saworks.pblock.data

import android.content.Context
import android.content.res.Resources
import android.net.ConnectivityManager
import android.os.Build
import android.provider.Settings
import androidx.appcompat.app.AppCompatDelegate
import com.saworks.pblock.BuildConfig
import com.saworks.pblock.vpn.Blocklist
import com.saworks.pblock.vpn.PBlockVpnService
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Technical facts for a bug report. Never includes the PIN or its hash, browsing history or the user's
 * own domain/app lists (only their sizes). Call off the main thread (loads the blocklist).
 */
object Diagnostics {
    private val TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm xxx")

    fun collect(ctx: Context): List<Pair<String, String>> {
        val c = ctx.applicationContext
        val out = ArrayList<Pair<String, String>>()
        fun add(k: String, v: Any?) { out += k to (v?.toString() ?: "?") }
        fun safe(block: () -> Any?): Any? = try { block() } catch (e: Exception) { "error: ${e.javaClass.simpleName}" }

        add("App", "${BuildConfig.VERSION_NAME} (versionCode ${BuildConfig.VERSION_CODE}), ${BuildConfig.BUILD_TYPE}")
        add("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), patch ${Build.VERSION.SECURITY_PATCH}")
        add("Device", "${Build.MANUFACTURER} ${Build.MODEL}")
        add("System language", safe { Resources.getSystem().configuration.locales.toLanguageTags() })
        add("App language", safe {
            val chosen = AppCompatDelegate.getApplicationLocales().toLanguageTags()
            val shown = c.resources.configuration.locales[0]?.toLanguageTag()
            if (chosen.isNullOrEmpty()) "$shown (follows system)" else "$shown (chosen: $chosen)"
        })
        add("Theme", Prefs.theme(c))
        add("Mode", Prefs.mode(c))
        add("Lock", safe { lockDescription(c) })
        add("Protections", safe {
            "DNS filter (VPN) ${onOff(Protections.vpnActive())}, app blocker (accessibility) ${onOff(Protections.accessibilityEnabled(c))}, " +
                "uninstall protection (device admin) ${onOff(Protections.adminActive(c))}"
        })
        add("Always-on VPN", safe {
            if (!PBlockVpnService.running) "unknown (VPN not running)"
            else "always-on ${onOff(PBlockVpnService.alwaysOn)}, block connections without VPN ${onOff(PBlockVpnService.lockdown)}"
        })
        add("Private DNS", safe { privateDns(c) })
        add("Blocklist", safe { "${Blocklist.get(c).size} built-in domains" })
        if (Prefs.isFilterMode(c)) {
            add("Filter", safe {
                "family DNS ${onOff(Prefs.familyDnsSetting(c))}, own blocked domains ${Prefs.blockedDomains(c).size}, " +
                    "exceptions ${Prefs.allowedDomains(c).size}, app blocking ${onOff(Prefs.appBlockingEnabled(c))} " +
                    "(${Prefs.blockedApps(c)?.size ?: 0} apps)"
            })
        }
        add("Trusted time", safe { if (TrustedTime.lowerBound(c) > 0) "synced" else "not synced" })
        return out
    }

    private fun onOff(b: Boolean) = if (b) "on" else "off"

    private fun fmt(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(TIME)

    /** Lock type and whether it holds right now. Never the PIN. */
    private fun lockDescription(c: Context): String = if (Prefs.isFilterMode(c)) {
        when (Prefs.lockType(c)) {
            Prefs.LOCK_TIME -> "time lock until ${fmt(Prefs.lockUntil(c))}, ${if (Lock.timeLocked(c)) "active" else "not active"}"
            Prefs.LOCK_PIN -> "parent PIN, " + when {
                !Pin.isSet(c) -> "no PIN set"
                Lock.pinLocked(c) -> "locked"
                else -> "unlocked session"
            }
            else -> "none"
        }
    } else {
        val at = Prefs.lockActivatedAt(c)
        if (at <= 0) "challenge not started"
        else "challenge started ${fmt(at)}, ${if (Lock.challengeLocked(c)) "locked" else "not locked"}"
    }

    private fun privateDns(c: Context): String {
        val mode = try { Settings.Global.getString(c.contentResolver, "private_dns_mode") } catch (_: Exception) { null }
        val cm = c.getSystemService(ConnectivityManager::class.java)
        val lp = cm?.activeNetwork?.let { cm.getLinkProperties(it) }
        val strict = Protections.privateDnsStrictHost(c)
        return buildString {
            append("setting ").append(mode ?: "unreadable")
            append(", active on current network ").append(lp?.isPrivateDnsActive?.let { onOff(it) } ?: "?")
            if (strict != null) append(", strict host ").append(strict)
        }
    }
}
