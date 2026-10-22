package com.saworks.pblock.core

import java.util.Locale

/**
 * Decides whether a system screen (Settings, package installer, SystemUI) could be used to disable PBlock
 * while the challenge lock is active. Pure Kotlin so it can be unit tested.
 *
 * The keyword tables cover the *system* language (en, hu, de, ru, zh, pt, ja), independent of the
 * language PBlock itself is shown in. Language-independent signals (window class names and view IDs)
 * are checked first.
 */
object SettingsGuard {

    const val SYSTEMUI = "com.android.systemui"

    val SETTINGS_PKGS = setOf(
        "com.android.settings",
        "com.samsung.accessibility",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.samsung.android.packageinstaller",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
    )

    /** PBlock's label as it appears in system screens (same in every language). */
    val APP_LABELS = listOf("pblock")

    /** Window/activity/fragment class fragments of screens that manage a single app, admin or VPN. */
    val CLASS_HINTS = listOf(
        "DeviceAdminAdd", "Uninstall", "InstalledAppDetails", "AppInfoDashboard", "AppInfoSettings",
        "vpn2.AppDialog", "AppManagementFragment", "ToggleAccessibilityService", "AccessibilityDetailsSettings",
        "AccessibilityServiceWarning",
    )

    /** View-ID fragments of controls that could disable PBlock (lowercase). */
    val ID_HINTS = listOf(
        "uninstall", "force_stop", "forcestop", "clear_storage", "clear_data", "device_admin", "deactivate",
        "always_on", "alwayson", "lockdown", "forget", "disconnect", "entity_header", "accessibility_shortcut",
    )

    /** Keywords per system language (lowercase). */
    val SETTINGS_KEYWORDS: Map<String, List<String>> = mapOf(
        "en" to listOf(
            "uninstall", "force stop", "clear storage", "clear data", "device admin", "deactivate",
            "accessibility", "use pblock", "shortcut", "always-on", "forget", "disconnect",
        ),
        "hu" to listOf(
            "eltávolít", "kényszerített", "leállítás kényszer", "tárhely törlése", "adatok törlése",
            "rendszergazda", "eszközfelügyel", "inaktivál", "kisegítő", "pblock használata", "parancsikon",
            "mindig bekapcsolt", "elfelejt", "leválaszt", "szétkapcsol",
        ),
        "de" to listOf(
            "deinstallieren", "beenden erzwingen", "stopp erzwingen", "speicher leeren", "speicherinhalt löschen",
            "daten löschen", "geräteadministrator", "geräteverwaltung", "deaktivieren", "bedienungshilfen",
            "pblock verwenden", "verknüpfung", "kurzbefehl", "durchgehend aktiv", "immer aktiv", "vergessen",
            "trennen",
        ),
        "ru" to listOf(
            "удалить", "остановить", "очистить хранилище", "стереть данные", "очистить данные",
            "администратор", "отключить", "деактивировать", "специальные возможности", "использовать pblock",
            "быстрое включение", "ярлык", "постоянная vpn", "постоянное подключение", "забыть", "отключиться",
            "разъединить",
        ),
        "zh" to listOf(
            "卸载", "卸載", "解除安裝", "强行停止", "強制停止", "清除存储空间", "清除儲存空間", "清除数据",
            "设备管理", "裝置管理", "停用", "无障碍", "協助工具", "使用 pblock", "使用pblock", "快捷方式", "捷徑",
            "始终开启", "一律開啟", "忘记", "清除 vpn", "断开连接", "中斷連線",
        ),
        "pt" to listOf(
            "desinstalar", "forçar parada", "forçar interrupção", "limpar armazenamento", "limpar dados",
            "administrador do dispositivo", "desativar", "acessibilidade", "usar o pblock", "usar pblock", "atalho",
            "vpn sempre ativa", "sempre ativa", "esquecer", "desconectar",
        ),
        "ja" to listOf(
            "アンインストール", "強制停止", "ストレージを消去", "データを消去", "デバイス管理", "無効にする", "無効化",
            "ユーザー補助", "pblock を使用", "pblockを使用", "ショートカット", "常時接続", "削除", "切断",
        ),
    )

    val DISCONNECT_KEYWORDS = listOf(
        "disconnect", "leválaszt", "szétkapcsol", "bontás", "trennen", "отключ", "разъедин",
        "断开", "中斷", "desconectar", "切断",
    )

    /** Date & time screens: changing the clock is blocked during the lock (language-independent signals). */
    val DATETIME_CLASS_HINTS = listOf("DateTimeSettings", "DatePicker", "TimePicker", "ZonePicker", "TimeZoneSettings", "TimeZonePicker")
    val DATETIME_ID_HINTS = listOf("date_time", "datetime", "auto_time", "auto_zone", "timezone")

    /**
     * Keywords of the automatic time / set date switches per system language (lowercase). Deliberately not the
     * bare "date & time" title, which is also an entry on the System page.
     */
    val DATETIME_KEYWORDS: Map<String, List<String>> = mapOf(
        "en" to listOf(
            "time automatically", "time zone automatically", "automatic date", "automatic time", "set date",
            "network-provided time", "use network-provided",
        ),
        "hu" to listOf(
            "idő automatikus", "időzóna automatikus", "automatikus dátum", "automatikus idő", "dátum beállítása",
            "idő beállítása", "hálózati idő",
        ),
        "de" to listOf(
            "uhrzeit automatisch", "zeitzone automatisch", "automatische uhrzeit", "automatisches datum",
            "datum einstellen", "uhrzeit einstellen", "datum festlegen", "uhrzeit festlegen",
        ),
        "ru" to listOf(
            "время автоматически", "часовой пояс автоматически", "автоматически устанавливать время",
            "автоматическая установка времени", "автоматическое определение времени", "дата и время сети",
            "установить дату", "установка даты",
        ),
        "zh" to listOf("自动设置时间", "自动设置时区", "自动确定日期和时间", "设置日期", "自動設定時間", "自動設定時區", "設定日期"),
        "pt" to listOf(
            "horário automaticamente", "fuso horário automaticamente", "data e hora automáticas", "definir data",
            "definir hora",
        ),
        "ja" to listOf("時刻を自動", "タイムゾーンを自動", "日付と時刻を自動", "日付の設定", "日付を設定", "時刻の設定"),
    )

    /**
     * Title of the Date & time page. Matched only as the first text on screen (the toolbar title), so the
     * "Date & time" entry on the System page does not count. Some Settings versions hide the automatic-time
     * switches from accessibility, so the title is the most reliable text signal.
     */
    val DATETIME_TITLES = setOf(
        "date & time", "date and time", "dátum és idő", "dátum és időpont", "datum & uhrzeit", "datum und uhrzeit",
        "дата и время", "日期和时间", "日期与时间", "日期和時間", "日期與時間", "data e hora", "日付と時刻",
    )

    private val ALL_DATETIME_KEYWORDS: List<String> = DATETIME_KEYWORDS.values.flatten().distinct()

    private val ALL_KEYWORDS: List<String> = SETTINGS_KEYWORDS.values.flatten().distinct()

    data class Screen(
        val pkg: String,
        /** Class name of the current window (from the last TYPE_WINDOW_STATE_CHANGED), may be null. */
        val windowClass: String?,
        /** All visible texts and content descriptions. */
        val text: String,
        val viewIds: List<String> = emptyList(),
    )

    fun isGuardedPackage(pkg: String): Boolean = pkg in SETTINGS_PKGS || pkg == SYSTEMUI

    /** @param guardDateTime also bounce Date & time settings (only time-based locks need that). */
    fun shouldBounce(s: Screen, guardDateTime: Boolean = true): Boolean {
        if (!isGuardedPackage(s.pkg)) return false
        val t = s.text.lowercase(Locale.ROOT)
        if (guardDateTime && s.pkg in SETTINGS_PKGS && isDateTimeScreen(s, t)) return true
        if (APP_LABELS.none { t.contains(it) }) return false // only screens that show PBlock
        if (s.pkg == SYSTEMUI) return DISCONNECT_KEYWORDS.any { t.contains(it) }
        val cls = s.windowClass.orEmpty()
        if (CLASS_HINTS.any { cls.contains(it) }) return true
        if (s.viewIds.any { id -> val l = id.lowercase(Locale.ROOT); ID_HINTS.any { l.contains(it) } }) return true
        return ALL_KEYWORDS.any { t.contains(it) }
    }

    /** Date & time settings (anything that could set the device clock), with or without PBlock on screen. */
    fun isDateTimeScreen(s: Screen, lowerText: String = s.text.lowercase(Locale.ROOT)): Boolean {
        val cls = s.windowClass.orEmpty()
        if (DATETIME_CLASS_HINTS.any { cls.contains(it) }) return true
        if (s.viewIds.any { id -> val l = id.lowercase(Locale.ROOT); DATETIME_ID_HINTS.any { l.contains(it) } }) return true
        val title = lowerText.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
        if (title != null && title in DATETIME_TITLES) return true
        return ALL_DATETIME_KEYWORDS.any { lowerText.contains(it) }
    }

    /** Accepts the localized confirmation word and always the Hungarian original, ignoring case/accents/spacing. */
    fun confirmMatches(input: String, accepted: Collection<String>): Boolean {
        val n = normalize(input)
        return n.isNotEmpty() && accepted.any { normalize(it) == n }
    }

    fun normalize(s: String): String =
        java.text.Normalizer.normalize(s.trim(), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("\\s+"), " ")
            .uppercase(Locale.ROOT)
}
