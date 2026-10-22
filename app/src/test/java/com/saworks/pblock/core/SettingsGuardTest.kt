package com.saworks.pblock.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsGuardTest {
    private val settings = "com.android.settings"

    private fun bounce(text: String, pkg: String = settings, cls: String? = "com.android.settings.SubSettings", ids: List<String> = emptyList()) =
        SettingsGuard.shouldBounce(SettingsGuard.Screen(pkg, cls, text, ids))

    @Test fun appInfoInEverySupportedLanguage() {
        assertTrue(bounce("PBlock\nUninstall\nForce stop"))
        assertTrue(bounce("PBlock\nEltávolítás\nKényszerített leállítás"))
        assertTrue(bounce("PBlock\nDeinstallieren\nBeenden erzwingen"))
        assertTrue(bounce("PBlock\nУдалить\nОстановить"))
        assertTrue(bounce("PBlock\n卸载\n强行停止"))
        assertTrue(bounce("PBlock\nDesinstalar\nForçar parada"))
        assertTrue(bounce("PBlock\nアンインストール\n強制停止"))
    }

    @Test fun accessibilityDeviceAdminAndVpnPages() {
        assertTrue(bounce("PBlock-Schutz verwenden\nBedienungshilfen"))
        assertTrue(bounce("Защита PBlock\nСпециальные возможности"))
        assertTrue(bounce("PBlock 防护\n无障碍"))
        assertTrue(bounce("PBlock\nApps zur Geräteverwaltung\nDiese Admin-App deaktivieren"))
        assertTrue(bounce("PBlock\nアプリをデバイス管理アプリとして無効にする"))
        assertTrue(bounce("PBlock\nVPN sempre ativa\nEsquecer VPN"))
        assertTrue(bounce("PBlock\n常時接続 VPN\nVPN を削除"))
    }

    @Test fun languageIndependentSignals() {
        // Unknown language, but the window class or a view ID gives it away.
        assertTrue(bounce("PBlock\n???", cls = "com.android.settings.applications.specialaccess.deviceadmin.DeviceAdminAdd"))
        assertTrue(bounce("PBlock\n???", ids = listOf("com.android.settings:id/entity_header_title")))
        assertTrue(bounce("PBlock\n???", pkg = "com.google.android.packageinstaller", cls = "com.android.packageinstaller.UninstallerActivity"))
    }

    @Test fun systemUiDisconnectDialog() {
        assertTrue(bounce("PBlock\nVPN\nTrennen", pkg = SettingsGuard.SYSTEMUI, cls = null))
        assertTrue(bounce("PBlock\nVPN\n断开连接", pkg = SettingsGuard.SYSTEMUI, cls = null))
        assertFalse(bounce("PBlock\nVPN aktiv", pkg = SettingsGuard.SYSTEMUI, cls = null))
    }

    @Test fun leavesOtherScreensAlone() {
        assertFalse(bounce("WLAN\nNetzwerk & Internet\nDeinstallieren")) // no PBlock on screen
        assertFalse(bounce("Apps\nPBlock\n12 MB\nChrome", cls = "com.android.settings.spa.SpaActivity"))
        assertFalse(bounce("PBlock\nUninstall", pkg = "com.example.other"))
        // Updating PBlock through the package installer stays possible.
        assertFalse(bounce("PBlock\nDo you want to update this app?", pkg = "com.google.android.packageinstaller", cls = "com.android.packageinstaller.PackageInstallerActivity"))
    }

    @Test fun dateTimeSettingsInEveryLanguage() {
        // No PBlock on screen: clock screens are bounced on their own.
        assertTrue(bounce("Date & time\nSet time automatically\nSet time zone automatically\nTime zone"))
        assertTrue(bounce("Automatic date and time\nSet date"))
        assertTrue(bounce("Dátum és idő\nIdő automatikus beállítása\nIdőzóna automatikus beállítása"))
        assertTrue(bounce("Datum und Uhrzeit\nUhrzeit automatisch einstellen"))
        assertTrue(bounce("Дата и время\nАвтоматически устанавливать время"))
        assertTrue(bounce("日期和时间\n自动设置时间"))
        assertTrue(bounce("Data e hora\nDefinir horário automaticamente"))
        assertTrue(bounce("日付と時刻\n時刻を自動的に設定"))
    }

    @Test fun dateTimeLanguageIndependentSignals() {
        assertTrue(bounce("", cls = "com.android.settings.Settings\$DateTimeSettingsActivity"))
        assertTrue(bounce("", cls = "android.app.DatePickerDialog"))
        assertTrue(bounce("x", ids = listOf("com.android.settings:id/auto_time")))
    }

    @Test fun dateTimePageByTitle() {
        // Android 14 emulator: the automatic-time switches are not in the accessibility tree.
        assertTrue(bounce("Date & time\nNavigate up\nDate & time\nDate\nOctober 22, 2026\nTime\n4:58 PM\nTime zone"))
        assertTrue(bounce("Dátum és idő\nVissza\nDátum"))
        assertTrue(bounce("日付と時刻\n上へ移動"))
        assertFalse(bounce("Search settings\nDate & time")) // not the title
    }

    @Test fun dateTimeDoesNotHitOtherPages() {
        // The System page lists "Date & time" as an entry: must stay reachable.
        assertFalse(bounce("System\nLanguages\nKeyboard\nDate & time\nGMT+01:00 Central European Standard Time\nBackup\nReset options"))
        assertFalse(bounce("Rendszer\nNyelvek\nDátum és idő\nGMT+01:00\nBiztonsági mentés"))
        assertFalse(bounce("Internet\nWi-Fi\nHotspot & tethering\nAirplane mode"))
        // Other packages are never treated as date & time settings.
        assertFalse(bounce("Set time automatically", pkg = "com.example.clock"))
    }

    @Test fun confirmationWord() {
        val accepted = listOf("I COMMIT", "VÁLLALOM")
        assertTrue(SettingsGuard.confirmMatches("i commit", accepted))
        assertTrue(SettingsGuard.confirmMatches("  I   Commit ", accepted))
        assertTrue(SettingsGuard.confirmMatches("vallalom", accepted))
        assertTrue(SettingsGuard.confirmMatches("Vállalom", accepted))
        assertFalse(SettingsGuard.confirmMatches("", accepted))
        assertFalse(SettingsGuard.confirmMatches("I COMMI", accepted))
        assertTrue(SettingsGuard.confirmMatches("я справлюсь", listOf("Я СПРАВЛЮСЬ", "VÁLLALOM")))
        assertTrue(SettingsGuard.confirmMatches("eu me comprometo", listOf("EU ME COMPROMETO", "VÁLLALOM")))
        assertTrue(SettingsGuard.confirmMatches("やりぬきます", listOf("やりぬきます", "VÁLLALOM")))
    }
}
