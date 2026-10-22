package com.saworks.pblock.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.app.admin.DevicePolicyManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Typeface
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.saworks.pblock.BuildConfig
import com.saworks.pblock.R
import com.saworks.pblock.admin.PBlockAdminReceiver
import com.saworks.pblock.core.CalendarLogic
import com.saworks.pblock.core.DayCell
import com.saworks.pblock.core.DayCount
import com.saworks.pblock.core.DayMark
import com.saworks.pblock.core.Events
import com.saworks.pblock.core.HistoryLogic
import com.saworks.pblock.core.LockPolicy
import com.saworks.pblock.core.SettingsGuard
import com.saworks.pblock.data.AttemptLog
import com.saworks.pblock.data.ChallengeStats
import com.saworks.pblock.data.HistoryRetention
import com.saworks.pblock.data.Lock
import com.saworks.pblock.data.Pin
import com.saworks.pblock.data.Prefs
import com.saworks.pblock.data.ProtectionMonitor
import com.saworks.pblock.data.Protections
import com.saworks.pblock.data.TrustedTime
import com.saworks.pblock.databinding.ActivityMainBinding
import com.saworks.pblock.vpn.Blocklist
import com.saworks.pblock.vpn.PBlockVpnService
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        private const val MAX_TIME_LOCK_MS = 366L * 24 * 60 * 60 * 1000
        private const val LOG_MAX_SHOWN = 300
    }

    private lateinit var b: ActivityMainBinding
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 30_000)
        }
    }
    private var updatingUi = false

    private val vpnConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) startVpnNow() else toast(R.string.vpn_consent_denied)
    }
    private val adminRequest = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val s = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(s.left, s.top, s.right, s.bottom)
            insets
        }

        b.btnVpn.setOnClickListener { onVpnClick() }
        b.btnAccessibility.setOnClickListener { onAccessibilityClick() }
        b.btnAdmin.setOnClickListener { onAdminClick() }
        b.btnVpnSettings.setOnClickListener { LockUi.withUnlock(this) { showAlwaysOnGuide() } }
        b.btnPrivateDns.setOnClickListener { openSettings(Settings.ACTION_WIRELESS_SETTINGS) }
        b.btnStart.setOnClickListener { onStartChallenge() }
        b.btnLicenses.setOnClickListener { showLicenses() }
        // Never lock-gated: reporting a problem must work while locked.
        b.btnBugReport.setOnClickListener { startActivity(Intent(this, BugReportActivity::class.java)) }
        b.btnHardMoment.setOnClickListener { startActivity(Intent(this, HardMomentActivity::class.java)) }
        b.btnContinueFilter.setOnClickListener { switchMode(Prefs.MODE_FILTER) }
        b.modeToggle.addOnButtonCheckedListener { _, id, checked ->
            if (checked && !updatingUi) onModeChosen(id == R.id.btnModeFilter)
        }
        b.btnFilterSettings.setOnClickListener { startActivity(Intent(this, FilterSettingsActivity::class.java)) }
        b.btnChangePin.setOnClickListener { changePin() }
        b.btnRemoveLock.setOnClickListener { removeLock() }
        b.btnTheme.setOnClickListener { chooseTheme() }
        b.btnRetention.setOnClickListener { chooseRetention() }
        b.switchPurgeAtEnd.setOnCheckedChangeListener { _, checked -> onPurgeAtEndToggled(checked) }
        b.btnViewLog.setOnClickListener { showLog() }
        b.btnClearLog.setOnClickListener { clearLog() }

        b.tvVersion.text = getString(R.string.version_fmt, BuildConfig.VERSION_NAME, if (BuildConfig.DEBUG) getString(R.string.version_debug_suffix) else "")
        // Load the blocklist off the main thread so the count is ready for the About text.
        Thread { Blocklist.get(applicationContext) }.start()
    }

    override fun onResume() {
        super.onResume()
        try { ProtectionMonitor.check(this) } catch (_: Exception) {}
        TrustedTime.syncAsync(this) { ok -> if (ok) handler.post { if (!isFinishing) refresh() } }
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    // ---------------------------------------------------------------- status

    private fun refresh() {
        val now = TrustedTime.now(this)
        val filter = Prefs.isFilterMode(this)
        val locked = Lock.isLocked(this)
        // With a parent PIN the buttons stay usable and ask for the PIN; other locks disable them.
        val canAct = !locked || Lock.pinLocked(this)
        val vpn = Protections.vpnActive()
        val acc = Protections.accessibilityEnabled(this)
        val adm = Protections.adminActive(this)

        updatingUi = true
        b.modeToggle.check(if (filter) R.id.btnModeFilter else R.id.btnModeChallenge)
        b.switchPurgeAtEnd.isChecked = Prefs.purgeAtChallengeEnd(this)
        updatingUi = false
        b.tvSubtitle.setText(if (filter) R.string.subtitle_filter else R.string.subtitle)
        b.cardChallenge.isVisible = !filter
        b.cardFilter.isVisible = filter
        b.btnHardMoment.isVisible = true

        setStatus(b.statusVpn, vpn)
        b.btnVpn.text = getString(if (vpn) R.string.btn_turn_off else R.string.btn_turn_on)
        b.btnVpn.isEnabled = !vpn || canAct

        setStatus(b.statusAccessibility, acc)
        b.btnAccessibility.text = getString(if (acc) R.string.btn_settings else R.string.btn_setup)
        b.btnAccessibility.isEnabled = !acc || canAct

        setStatus(b.statusAdmin, adm)
        b.btnAdmin.text = getString(if (adm) R.string.btn_turn_off else R.string.btn_activate)
        b.btnAdmin.isEnabled = !adm || canAct

        val lockdown = vpn && PBlockVpnService.lockdown
        val alwaysOn = vpn && PBlockVpnService.alwaysOn
        b.statusAlwaysOn.text = getString(
            when {
                !vpn -> R.string.status_unknown
                lockdown -> R.string.status_lockdown_on
                alwaysOn -> R.string.status_always_on_yes
                else -> R.string.status_always_on_no
            }
        )
        b.statusAlwaysOn.setTextColor(color(if (lockdown) R.color.pb_bad else if (alwaysOn) R.color.pb_ok else R.color.pb_warn))
        b.btnVpnSettings.isEnabled = canAct

        val strict = Protections.privateDnsStrictHost(this)
        b.cardPrivateDns.isVisible = strict != null
        if (strict != null) b.tvPrivateDns.text = getString(R.string.private_dns_warning, strict)

        if (filter) renderFilter(now) else renderChallenge(now, locked, vpn && acc && adm)

        b.tvTheme.text = themeLabel(Prefs.theme(this))
        b.tvRetention.text = retentionLabel(Prefs.retentionDays(this))

        val hasHistory = Prefs.lockActivatedAt(this) > 0
        b.cardCalendar.isVisible = !filter || hasHistory
        b.tvCalendarTitle.setText(if (filter) R.string.card_calendar_history else R.string.card_calendar)
        if (b.cardCalendar.isVisible) renderCalendar()
    }

    private fun renderChallenge(now: Long, locked: Boolean, allActive: Boolean) {
        b.btnContinueFilter.isVisible = false
        when {
            locked && Lock.awaitingTrustedEnd(this) -> {
                b.tvChallengeStatus.text = getString(R.string.challenge_awaiting_trusted_time)
                b.btnStart.isVisible = false
            }
            locked -> {
                val d = LockPolicy.remaining(now)
                var text = getString(R.string.challenge_locked, formatDuration(d.toDays().toInt(), (d.toHours() % 24).toInt(), (d.toMinutes() % 60).toInt()))
                if (TrustedTime.trustedNow(this) == null) text += "\n" + getString(R.string.time_not_verified)
                b.tvChallengeStatus.text = text
                b.btnStart.isVisible = false
            }
            Lock.isFinished(this) -> {
                b.tvChallengeStatus.text = getString(R.string.challenge_finished) + "\n\n" + getString(R.string.challenge_continue_hint)
                b.btnStart.isVisible = false
                b.btnContinueFilter.isVisible = true
            }
            !LockPolicy.canActivate(now) -> {
                b.tvChallengeStatus.text = getString(R.string.challenge_over_not_started) + "\n\n" + getString(R.string.challenge_continue_hint)
                b.btnStart.isVisible = false
                b.btnContinueFilter.isVisible = true
            }
            else -> {
                b.tvChallengeStatus.text = getString(if (allActive) R.string.challenge_ready else R.string.challenge_not_ready)
                b.btnStart.isVisible = true
                b.btnStart.isEnabled = allActive
            }
        }
    }

    private fun renderFilter(now: Long) {
        val lines = ArrayList<String>()
        lines += getString(if (Prefs.familyDnsSetting(this)) R.string.filter_summary_dns_family else R.string.filter_summary_dns_plain)
        val nb = Prefs.blockedDomains(this).size
        val na = Prefs.allowedDomains(this).size
        if (nb > 0) lines += resources.getQuantityString(R.plurals.filter_summary_custom, nb, nb)
        if (na > 0) lines += resources.getQuantityString(R.plurals.filter_summary_allowed, na, na)
        val apps = Prefs.blockedApps(this)?.size ?: 0
        lines += if (Prefs.appBlockingEnabled(this) && apps > 0) {
            resources.getQuantityString(R.plurals.filter_summary_apps, apps, apps)
        } else {
            getString(R.string.filter_summary_apps_off)
        }
        b.tvFilterSummary.text = lines.joinToString("\n")

        var type = Prefs.lockType(this)
        if (type == Prefs.LOCK_TIME && !Lock.timeLocked(this)) {
            Lock.removeFilterLock(this) // time is up (trusted time confirmed it)
            type = Prefs.LOCK_NONE
        }
        b.rowLockExtra.isVisible = false
        b.btnLock.isVisible = true
        when {
            type == Prefs.LOCK_TIME -> {
                val until = Prefs.lockUntil(this)
                b.tvLockStatus.text = if (Lock.awaitingTrustedEnd(this)) {
                    getString(R.string.lock_time_awaiting)
                } else {
                    getString(R.string.lock_time_active, formatDateTime(until), Durations.span(this, until - now))
                }
                b.btnLock.isVisible = false
            }
            type == Prefs.LOCK_PIN && Lock.pinLocked(this) -> {
                b.tvLockStatus.text = getString(R.string.lock_pin_active)
                b.btnLock.setText(R.string.btn_enter_pin)
                b.btnLock.setOnClickListener { LockUi.askPin(this) { refresh() } }
            }
            type == Prefs.LOCK_PIN -> {
                b.tvLockStatus.text = getString(R.string.lock_pin_session, Durations.span(this, Pin.sessionLeftMs(this)))
                b.btnLock.setText(R.string.btn_lock_now)
                b.btnLock.setOnClickListener { Pin.endSession(this); refresh() }
                b.rowLockExtra.isVisible = true
            }
            else -> {
                b.tvLockStatus.text = getString(R.string.lock_none)
                b.btnLock.setText(R.string.btn_set_lock)
                b.btnLock.setOnClickListener { chooseLock() }
            }
        }
    }

    private fun setStatus(tv: TextView, ok: Boolean) {
        tv.text = getString(if (ok) R.string.status_on else R.string.status_off)
        tv.setTextColor(color(if (ok) R.color.pb_ok else R.color.pb_bad))
    }

    // ---------------------------------------------------------------- actions

    private fun onVpnClick() {
        if (Protections.vpnActive()) {
            LockUi.withUnlock(this) {
                PBlockVpnService.stop(this)
                handler.postDelayed({ refresh() }, 600)
            }
            return
        }
        val consent = VpnService.prepare(this)
        if (consent != null) {
            try { vpnConsent.launch(consent) } catch (_: ActivityNotFoundException) { toast(R.string.vpn_consent_denied) }
        } else {
            startVpnNow()
        }
    }

    private fun startVpnNow() {
        Prefs.setVpnWanted(this, true)
        PBlockVpnService.start(this)
        handler.postDelayed({ refresh() }, 700)
    }

    private fun onAccessibilityClick() {
        if (Protections.accessibilityEnabled(this)) {
            LockUi.withUnlock(this) { openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS) }
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.acc_guide_title)
            .setMessage(R.string.acc_guide_text)
            .setPositiveButton(R.string.btn_open_settings) { _, _ -> openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS) }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun onAdminClick() {
        val comp = PBlockAdminReceiver.component(this)
        if (Protections.adminActive(this)) {
            LockUi.withUnlock(this) {
                getSystemService(DevicePolicyManager::class.java).removeActiveAdmin(comp)
                handler.postDelayed({ refresh() }, 600)
            }
            return
        }
        val i = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, comp)
            .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, getString(R.string.admin_explanation))
        try { adminRequest.launch(i) } catch (_: ActivityNotFoundException) { toast(R.string.settings_not_found) }
    }

    private fun showAlwaysOnGuide() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.always_on_title)
            .setMessage(R.string.always_on_text)
            .setPositiveButton(R.string.btn_open_vpn_settings) { _, _ -> openSettings(Settings.ACTION_VPN_SETTINGS) }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun onStartChallenge() {
        if (!Protections.allActive(this)) {
            toast(R.string.challenge_not_ready)
            return
        }
        confirmTyped(
            getString(R.string.confirm_title),
            getString(R.string.confirm_text, getString(R.string.confirm_word)),
            R.string.btn_start_challenge,
        ) {
            if (Lock.activate(this)) toast(R.string.challenge_started)
            refresh()
        }
    }

    /** A confirmation that only enables its button after the user types the confirm word. */
    private fun confirmTyped(title: String, message: String, positive: Int, onOk: () -> Unit) {
        val input = EditText(this).apply {
            hint = getString(R.string.confirm_word)
            isSingleLine = true
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(24)
            setPadding(pad, dp(8), pad, 0)
            addView(input)
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(message)
            .setView(box)
            .setPositiveButton(positive) { _, _ -> onOk() }
            .setNegativeButton(R.string.btn_cancel, null)
            .create()
        dialog.setOnShowListener {
            val ok = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            ok.isEnabled = false
            input.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    ok.isEnabled = SettingsGuard.confirmMatches(
                        s.toString(),
                        listOf(getString(R.string.confirm_word), getString(R.string.confirm_word_universal)),
                    )
                }
            })
        }
        dialog.show()
    }

    // ---------------------------------------------------------------- modes and locks

    private fun onModeChosen(filter: Boolean) {
        if (filter == Prefs.isFilterMode(this)) return
        val target = if (filter) Prefs.MODE_FILTER else Prefs.MODE_CHALLENGE
        when {
            Lock.challengeLocked(this) || Lock.timeLocked(this) -> toast(R.string.mode_locked)
            Lock.pinLocked(this) -> LockUi.askPin(this) { switchMode(target) }
            else -> switchMode(target)
        }
        refresh()
    }

    private fun switchMode(mode: String) {
        Prefs.setMode(this, mode)
        refresh()
    }

    private fun chooseLock() {
        if (!Protections.allActive(this)) {
            toast(R.string.lock_needs_protections)
            return
        }
        val items = arrayOf(getString(R.string.lock_choice_time), getString(R.string.lock_choice_pin))
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.lock_choose_title)
            .setItems(items) { _, which -> if (which == 0) pickTimeLock() else setUpPin() }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun setUpPin() {
        LockUi.createPin(this) {
            Lock.activatePinLock(this)
            toast(R.string.pin_set_done)
            refresh()
        }
    }

    private fun changePin() {
        LockUi.withUnlock(this) {
            LockUi.createPin(this) {
                Lock.activatePinLock(this)
                toast(R.string.pin_changed)
                refresh()
            }
        }
    }

    private fun removeLock() {
        LockUi.withUnlock(this) {
            Lock.removeFilterLock(this)
            toast(R.string.pin_removed)
            refresh()
        }
    }

    private fun pickTimeLock() {
        val zone = ZoneId.systemDefault()
        val start = Instant.ofEpochMilli(TrustedTime.now(this)).atZone(zone).plusDays(1)
        val dp = DatePickerDialog(this, { _, y, m, d ->
            TimePickerDialog(this, { _, h, min ->
                val until = LocalDateTime.of(y, m + 1, d, h, min).atZone(zone).toInstant().toEpochMilli()
                confirmTimeLock(until)
            }, start.hour, start.minute, android.text.format.DateFormat.is24HourFormat(this)).show()
        }, start.year, start.monthValue - 1, start.dayOfMonth)
        dp.datePicker.minDate = System.currentTimeMillis() - 60_000
        dp.show()
    }

    private fun confirmTimeLock(until: Long) {
        val now = TrustedTime.now(this)
        when {
            until <= now + 5 * 60_000L -> { toast(R.string.time_lock_too_soon); return }
            until > now + MAX_TIME_LOCK_MS -> { toast(R.string.time_lock_too_far); return }
        }
        confirmTyped(
            getString(R.string.time_lock_confirm_title),
            getString(R.string.time_lock_confirm_text, formatDateTime(until), Durations.span(this, until - now), getString(R.string.confirm_word)),
            R.string.btn_lock,
        ) {
            if (Protections.allActive(this) && Lock.activateTimeLock(this, until)) toast(R.string.time_lock_started)
            else toast(R.string.lock_needs_protections)
            refresh()
        }
    }

    // ---------------------------------------------------------------- settings

    private fun themeLabel(t: String) = getString(
        when (t) {
            Prefs.THEME_SYSTEM -> R.string.theme_system
            Prefs.THEME_LIGHT -> R.string.theme_light
            else -> R.string.theme_dark
        }
    )

    private fun retentionLabel(days: Int) =
        if (days <= 0) getString(R.string.retention_forever) else resources.getQuantityString(R.plurals.retention_days, days, days)

    private fun chooseTheme() {
        val values = arrayOf(Prefs.THEME_SYSTEM, Prefs.THEME_LIGHT, Prefs.THEME_DARK)
        val current = values.indexOf(Prefs.theme(this)).coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.setting_theme)
            .setSingleChoiceItems(values.map { themeLabel(it) }.toTypedArray(), current) { d, which ->
                d.dismiss()
                Prefs.setTheme(this, values[which])
                Prefs.applyTheme(this)
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun chooseRetention() {
        LockUi.withPinIfSet(this) {
            val values = HistoryLogic.RETENTION_CHOICES
            val current = values.indexOf(Prefs.retentionDays(this)).coerceAtLeast(0)
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.setting_history)
                .setSingleChoiceItems(values.map { retentionLabel(it) }.toTypedArray(), current) { d, which ->
                    d.dismiss()
                    Prefs.setRetentionDays(this, values[which])
                    Thread { HistoryRetention.apply(applicationContext, force = true); handler.post { if (!isFinishing) refresh() } }.start()
                }
                .setNegativeButton(R.string.btn_cancel, null)
                .show()
        }
    }

    private fun onPurgeAtEndToggled(checked: Boolean) {
        if (updatingUi) return
        updatingUi = true
        b.switchPurgeAtEnd.isChecked = !checked // only change after the PIN check
        updatingUi = false
        LockUi.withPinIfSet(this) {
            Prefs.setPurgeAtChallengeEnd(this, checked)
            if (checked) Thread { HistoryRetention.apply(applicationContext, force = true) }.start()
            refresh()
        }
    }

    private fun showLog() {
        LockUi.withPinIfSet(this) {
            val zone = ZoneId.systemDefault()
            val fmt = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(locale())
            val timeFmt = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale())
            val events = AttemptLog.readAll(this).sortedByDescending { it.time }.take(LOG_MAX_SHOWN)
            val text = if (events.isEmpty()) getString(R.string.log_empty) else events.joinToString("\n") { e ->
                val time = Instant.ofEpochMilli(e.time).atZone(zone).format(fmt)
                val what = when {
                    e.kind == Events.APP -> getString(R.string.kind_app) + ": " + e.target
                    e.kind == Events.DNS_FILTER -> getString(R.string.kind_dns_filter) + ": " + e.target
                    e.kind == Events.DNS -> getString(R.string.kind_dns) + ": " + e.target
                    e.kind == Events.HARD_MOMENT -> getString(R.string.log_hard_moment)
                    Events.isWarning(e.kind) -> describeWarning(e, timeFmt)
                    else -> e.target
                }
                getString(R.string.day_event_line, time, what)
            }
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.log_title)
                .setMessage(text)
                .setPositiveButton(R.string.btn_ok, null)
                .show()
        }
    }

    private fun clearLog() {
        LockUi.withPinIfSet(this) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.clear_log_title)
                .setMessage(R.string.clear_log_text)
                .setPositiveButton(R.string.btn_delete) { _, _ ->
                    Thread {
                        AttemptLog.purge(applicationContext, cutoff = Long.MAX_VALUE, all = true)
                        handler.post { if (!isFinishing) { toast(R.string.log_cleared); refresh() } }
                    }.start()
                }
                .setNegativeButton(R.string.btn_cancel, null)
                .show()
        }
    }

    private fun showLicenses() {
        val count = try { Blocklist.get(applicationContext).size } catch (_: Exception) { 0 }
        MaterialAlertDialogBuilder(this)
            .setIcon(R.drawable.pb_logo)
            .setTitle(R.string.licenses_title)
            .setMessage(getString(R.string.licenses_text, resources.getQuantityString(R.plurals.blocklist_domains, count, count)))
            .setPositiveButton(R.string.btn_ok, null)
            .show()
    }

    private fun openSettings(action: String) {
        try {
            startActivity(Intent(action))
        } catch (_: ActivityNotFoundException) {
            try { startActivity(Intent(Settings.ACTION_SETTINGS)) } catch (_: ActivityNotFoundException) { toast(R.string.settings_not_found) }
        }
    }

    // ---------------------------------------------------------------- calendar

    private fun renderCalendar() {
        val zone = LockPolicy.ZONE
        val today = Instant.ofEpochMilli(TrustedTime.now(this)).atZone(zone).toLocalDate()
        val activatedAt = Prefs.lockActivatedAt(this)
        val start = if (activatedAt > 0) Instant.ofEpochMilli(activatedAt).atZone(zone).toLocalDate() else null
        val all = AttemptLog.readAll(this)
        val events = if (activatedAt > 0) all.filter { it.time >= activatedAt } else emptyList()
        // Day counts survive the privacy clean-up (aggregated), the details do not.
        val counts = ChallengeStats.dayCounts(this, all)
        val perDay = counts.filterValues { it.attempts > 0 }.mapValues { it.value.attempts }
        val warnPerDay = counts.filterValues { it.warnings > 0 }.mapValues { it.value.warnings }

        val grid = b.calendarGrid
        grid.removeAllViews()
        val months = LockPolicy.CHALLENGE.months
        val allCells = ArrayList<DayCell>()
        val monthFmt = DateTimeFormatter.ofPattern("LLLL yyyy", locale())
        for (ym in months) {
            if (months.size > 1) {
                grid.addView(TextView(this).apply {
                    text = ym.format(monthFmt)
                    setTextColor(color(R.color.pb_text))
                    setTypeface(typeface, Typeface.BOLD)
                    setPadding(0, dp(8), 0, dp(4))
                })
            }
            val cells = CalendarLogic.buildMonth(ym, start, today, perDay, warnPerDay)
            allCells += cells
            val header = row()
            resources.getStringArray(R.array.weekdays_short).forEach { d ->
                header.addView(TextView(this).apply {
                    text = d
                    gravity = Gravity.CENTER
                    setTextColor(color(R.color.pb_muted))
                    textSize = 12f
                    layoutParams = LinearLayout.LayoutParams(0, dp(24), 1f)
                })
            }
            grid.addView(header)
            val blanks = CalendarLogic.leadingBlanks(ym)
            val rows = (blanks + cells.size + 6) / 7
            for (r in 0 until rows) {
                val line = row()
                for (c in 0 until 7) {
                    val i = r * 7 + c - blanks
                    line.addView(if (i in cells.indices) dayView(cells[i], events, counts[cells[i].date]) else spacer())
                }
                grid.addView(line)
            }
        }
        val stars = allCells.count { it.mark == DayMark.STAR }
        val checks = allCells.count { it.mark == DayMark.CHECK }
        val warns = allCells.count { it.mark == DayMark.WARN }
        val inRange = allCells.map { it.date }.toSet()
        val attempts = counts.filterKeys { it in inRange }.values.sumOf { it.attempts }
        val hard = counts.filterKeys { it in inRange }.values.sumOf { it.hard }
        b.tvCalendarStats.text = listOfNotNull(
            resources.getQuantityString(R.plurals.stats_clean_days, stars, stars),
            resources.getQuantityString(R.plurals.stats_check_days, checks, checks),
            if (warns > 0) resources.getQuantityString(R.plurals.stats_warn_days, warns, warns) else null,
            resources.getQuantityString(R.plurals.stats_total_attempts, attempts, attempts),
            if (hard > 0) resources.getQuantityString(R.plurals.stats_hard_moments, hard, hard) else null,
        ).joinToString("\n")
    }

    private fun row() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    }

    private fun spacer() = View(this).apply { layoutParams = LinearLayout.LayoutParams(0, dp(54), 1f) }

    private fun dayView(cell: DayCell, attempts: List<AttemptLog.Attempt>, count: DayCount?): View {
        val sb = SpannableStringBuilder(cell.date.dayOfMonth.toString())
        sb.append('\n')
        val (symbol, symColor) = when (cell.mark) {
            DayMark.STAR -> getString(R.string.symbol_star) to R.color.pb_star
            DayMark.CHECK -> getString(R.string.symbol_check) to R.color.pb_check
            DayMark.WARN -> getString(R.string.symbol_warn) to R.color.pb_alert
            DayMark.NONE -> getString(R.string.symbol_none) to R.color.pb_muted
        }
        val s = sb.length
        sb.append(symbol)
        sb.setSpan(ForegroundColorSpan(color(symColor)), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(RelativeSizeSpan(1.35f), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return TextView(this).apply {
            text = sb
            gravity = Gravity.CENTER
            textSize = 13f
            if (cell.isToday) setTypeface(typeface, Typeface.BOLD)
            setTextColor(color(if (cell.inChallenge) R.color.pb_text else R.color.pb_muted))
            setBackgroundResource(if (cell.isToday) R.drawable.bg_day_today else R.drawable.bg_day)
            layoutParams = LinearLayout.LayoutParams(0, dp(54), 1f).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) }
            setOnClickListener { LockUi.withPinIfSet(this@MainActivity) { showDay(cell, attempts, count) } }
        }
    }

    private fun showDay(cell: DayCell, events: List<AttemptLog.Attempt>, count: DayCount?) {
        val zone = LockPolicy.ZONE
        val dayList = events.filter { Instant.ofEpochMilli(it.time).atZone(zone).toLocalDate() == cell.date }
        val locale = locale()
        val title = cell.date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale))
        val fmt = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)
        fun time(a: AttemptLog.Attempt) = Instant.ofEpochMilli(a.time).atZone(zone).format(fmt)
        val attempts = dayList.filter { Events.isAttempt(it.kind) }
        val warnings = dayList.filter { Events.isWarning(it.kind) }
        val hard = dayList.count { it.kind == Events.HARD_MOMENT }
        val parts = ArrayList<String>()
        if (!cell.inChallenge) {
            parts += getString(R.string.day_not_in_challenge)
        } else {
            if (warnings.isNotEmpty()) {
                parts += resources.getQuantityString(R.plurals.day_warnings, warnings.size, warnings.size) + "\n" +
                    warnings.joinToString("\n") { getString(R.string.day_event_line, time(it), describeWarning(it, fmt)) }
            }
            if (attempts.isEmpty()) {
                if (warnings.isEmpty() && (count?.attempts ?: 0) == 0) parts += getString(if (cell.mark == DayMark.STAR) R.string.day_clean else R.string.day_no_attempts)
            } else {
                parts += resources.getQuantityString(R.plurals.day_attempts, attempts.size, attempts.size) + "\n" + attempts.joinToString("\n") { a ->
                    val kind = when (a.kind) {
                        Events.APP -> getString(R.string.kind_app)
                        Events.DNS_FILTER -> getString(R.string.kind_dns_filter)
                        else -> getString(R.string.kind_dns)
                    }
                    getString(R.string.day_attempt_line, time(a), kind, a.target)
                }
            }
            if (hard > 0) parts += resources.getQuantityString(R.plurals.day_hard_moments, hard, hard)
            val hidden = (count?.attempts ?: 0) - attempts.size
            if (hidden > 0) parts += resources.getQuantityString(R.plurals.day_details_deleted, hidden, hidden)
        }
        MaterialAlertDialogBuilder(this).setTitle(title).setMessage(parts.joinToString("\n\n")).setPositiveButton(R.string.btn_ok, null).show()
    }

    /** Renders the machine-readable target of a warning event in the UI language. */
    private fun describeWarning(a: AttemptLog.Attempt, fmt: DateTimeFormatter): String {
        val p = Events.parts(a.target)
        val n = p.getOrNull(1)?.toLongOrNull()
        return when (a.kind) {
            Events.CLOCK -> when {
                n == null -> getString(R.string.ev_clock_changed)
                p[0] == "behind" -> getString(R.string.ev_clock_behind, span(n))
                p[0] == "offset" -> getString(R.string.ev_clock_offset, span(n))
                n >= 0 -> getString(R.string.ev_clock_forward, span(n))
                else -> getString(R.string.ev_clock_back, span(n))
            }
            Events.SAFE_MODE -> if (p[0] == "boots" && n != null) {
                resources.getQuantityString(R.plurals.ev_missed_boots, n.toInt(), n.toInt())
            } else {
                getString(R.string.ev_safe_mode_now)
            }
            Events.DOWN -> {
                val comp = getString(if (p[0] == Events.COMP_VPN) R.string.comp_vpn else R.string.comp_accessibility)
                val v = p.getOrNull(2)?.toLongOrNull()
                when {
                    v == null -> getString(R.string.ev_down_generic, comp)
                    p[1] == "off" -> getString(R.string.ev_down_off, comp, Instant.ofEpochMilli(v).atZone(LockPolicy.ZONE).format(fmt))
                    else -> getString(R.string.ev_down_on, comp, span(v))
                }
            }
            else -> a.target
        }
    }

    private fun span(ms: Long): String = Durations.span(this, ms)

    // ---------------------------------------------------------------- helpers

    private fun formatDuration(days: Int, hours: Int, minutes: Int): String = getString(
        R.string.duration_fmt,
        resources.getQuantityString(R.plurals.duration_days, days, days),
        resources.getQuantityString(R.plurals.duration_hours, hours, hours),
        resources.getQuantityString(R.plurals.duration_minutes, minutes, minutes),
    )

    private fun formatDateTime(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale()))

    private fun locale(): Locale = resources.configuration.locales[0] ?: Locale.getDefault()
    private fun color(id: Int) = ContextCompat.getColor(this, id)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(id: Int) = Toast.makeText(this, id, Toast.LENGTH_SHORT).show()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
