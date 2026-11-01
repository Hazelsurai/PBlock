package com.saworks.pblock.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSafetyTest {
    private fun kinds(label: String, pkg: String) = AppSafety.importantKinds(label, pkg)

    @Test fun banksAndPaymentsAreFlagged() {
        assertTrue(AppSafety.Kind.BANK in kinds("OTP Bank", "hu.otpbank.mobile"))
        assertTrue(AppSafety.Kind.BANK in kinds("K&H mobilbank", "com.kh.mobilbank"))
        assertTrue(AppSafety.Kind.BANK in kinds("George", "at.erstegroup.george"))
        assertTrue(AppSafety.Kind.BANK in kinds("Revolut", "com.revolut.revolut"))
        assertTrue(AppSafety.Kind.BANK in kinds("Google Wallet", "com.google.android.apps.walletnfcrel"))
        assertTrue(AppSafety.Kind.BANK in kinds("Raiffeisen", "hu.raiffeisen.mobile"))
    }

    @Test fun accentsAndCaseDoNotMatter() {
        assertTrue(AppSafety.Kind.GOV in kinds("ÜGYFÉLKAPU+", "hu.gov.app"))
        assertTrue(AppSafety.Kind.MAPS in kinds("Térkép", "com.example.x"))
        assertTrue(AppSafety.Kind.HEALTH in kinds("EESZT Egészség", "hu.eeszt.app"))
        assertTrue(AppSafety.looksImportant("Microsoft Authenticator", "com.azure.authenticator"))
    }

    @Test fun shortTokensNeedWholeWords() {
        // "otp" inside another word is not a bank.
        assertFalse(AppSafety.Kind.BANK in kinds("Hotpot Recipes", "com.example.hotpot"))
        assertFalse(AppSafety.Kind.GOV in kinds("Navigator Game", "com.example.navigatorgame"))
        assertFalse(AppSafety.looksImportant("TikTok", "com.zhiliaoapp.musically"))
        assertFalse(AppSafety.looksImportant("YouTube", "com.google.android.youtube"))
        assertFalse(AppSafety.looksImportant("Instagram", "com.instagram.android"))
        assertFalse(AppSafety.looksImportant("Netflix", "com.netflix.mediaclient"))
    }

    @Test fun neverBlockList() {
        assertTrue(AppSafety.neverBlock("com.saworks.pblock", null))
        assertTrue(AppSafety.neverBlock("com.android.settings", null))
        assertTrue(AppSafety.neverBlock("com.samsung.android.dialer", null))
        assertTrue(AppSafety.neverBlock("com.google.android.dialer", null))
        assertTrue(AppSafety.neverBlock("com.example.mydialer", "com.example.mydialer"))
        assertFalse(AppSafety.neverBlock("com.zhiliaoapp.musically", "com.samsung.android.dialer"))
    }

    @Test fun graceWindow() {
        val t = 1_000_000_000L
        assertTrue(FilterGrace.inGrace(t, t))
        assertTrue(FilterGrace.inGrace(t, t + FilterGrace.GRACE_MS))
        assertFalse(FilterGrace.inGrace(t, t + FilterGrace.GRACE_MS + 1))
        assertFalse(FilterGrace.inGrace(0, t))
        assertFalse(FilterGrace.inGrace(t, t - 1)) // clock went backwards
        assertEquals(15 * 60_000L, FilterGrace.GRACE_MS)
    }

    @Test fun removalNeedsEveryItemInGrace() {
        val now = 2_000_000_000L
        val added = mapOf("a" to now - 60_000, "b" to now - FilterGrace.GRACE_MS - 1)
        assertTrue(FilterGrace.removalAllowed(setOf("a"), { added[it] ?: 0L }, now))
        assertFalse(FilterGrace.removalAllowed(setOf("a", "b"), { added[it] ?: 0L }, now))
        assertFalse(FilterGrace.removalAllowed(setOf("c"), { added[it] ?: 0L }, now))
    }
}
