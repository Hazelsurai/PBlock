package com.saworks.pblock.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.saworks.pblock.R
import com.saworks.pblock.access.BlockerAccessibilityService
import com.saworks.pblock.core.AppSafety
import com.saworks.pblock.core.DomainInput
import com.saworks.pblock.core.FilterGrace
import com.saworks.pblock.core.SettingsGuard
import com.saworks.pblock.data.AppRules
import com.saworks.pblock.data.Lock
import com.saworks.pblock.data.Prefs
import com.saworks.pblock.data.TrustedTime
import com.saworks.pblock.databinding.ActivityFilterSettingsBinding
import com.saworks.pblock.vpn.Blocklist

/**
 * Content-filter rules: own blocked domains, exceptions, family DNS and the optional app list.
 * Tightening is always allowed; loosening needs the parent PIN, and is refused while a time lock or the challenge
 * holds. Something added by mistake can still be taken back within [FilterGrace.GRACE_MS].
 *
 * In challenge mode the same screen edits the lists the challenge may also enforce (opt-in switch); the family DNS
 * (always on in the challenge) and the exceptions (never used by the challenge) are hidden there.
 */
class FilterSettingsActivity : AppCompatActivity() {

    private lateinit var b: ActivityFilterSettingsBinding
    private val main = Handler(Looper.getMainLooper())
    private var updating = false

    private data class AppEntry(val pkg: String, val label: String, val suggested: Boolean)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        b = ActivityFilterSettingsBinding.inflate(layoutInflater)
        setContentView(b.root)
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val s = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(s.left, s.top, s.right, s.bottom)
            insets
        }
        b.toolbar.setNavigationOnClickListener { finish() }

        b.btnAddBlocked.setOnClickListener { addDomain(b.inputBlocked, blocked = true) }
        b.btnAddAllowed.setOnClickListener { addDomain(b.inputAllowed, blocked = false) }
        b.inputBlocked.setOnEditorActionListener { _, a, _ -> (a == EditorInfo.IME_ACTION_DONE).also { if (it) addDomain(b.inputBlocked, true) } }
        b.inputAllowed.setOnEditorActionListener { _, a, _ -> (a == EditorInfo.IME_ACTION_DONE).also { if (it) addDomain(b.inputAllowed, false) } }

        b.switchChallenge.setOnCheckedChangeListener { _, on ->
            if (updating) return@setOnCheckedChangeListener
            revert { b.switchChallenge.isChecked = !on }
            val grace = !on && inGrace(Prefs.ADDED_SWITCH, SWITCH_ITEM)
            change(loosens = !on, grace = grace) {
                if (on) Prefs.markAdded(this, Prefs.ADDED_SWITCH, listOf(SWITCH_ITEM), TrustedTime.now(this))
                else Prefs.clearAdded(this, Prefs.ADDED_SWITCH, listOf(SWITCH_ITEM))
                Prefs.setChallengeUsesFilterLists(this, on)
            }
        }
        b.switchFamily.setOnCheckedChangeListener { _, on ->
            if (updating) return@setOnCheckedChangeListener
            revert { b.switchFamily.isChecked = !on }
            change(loosens = !on) { Prefs.setFamilyDns(this, on) }
        }
        b.switchApps.setOnCheckedChangeListener { _, on ->
            if (updating) return@setOnCheckedChangeListener
            revert { b.switchApps.isChecked = !on }
            val grace = !on && inGrace(Prefs.ADDED_SWITCH, APPS_ITEM)
            change(loosens = !on && Prefs.filterListsEnforced(this), grace = grace) {
                if (on && Prefs.blockedApps(this) == null) {
                    val s = installedSuggestions()
                    Prefs.setBlockedApps(this, s)
                    Prefs.markAdded(this, Prefs.ADDED_APP, s, TrustedTime.now(this))
                }
                if (on) Prefs.markAdded(this, Prefs.ADDED_SWITCH, listOf(APPS_ITEM), TrustedTime.now(this))
                Prefs.setAppBlockingEnabled(this, on)
            }
        }
        b.btnChooseApps.setOnClickListener { chooseApps() }

        Thread {
            val n = try { Blocklist.get(applicationContext).size } catch (_: Exception) { 0 }
            main.post { if (!isFinishing) b.tvDns.text = getString(R.string.fs_dns_text, resources.getQuantityString(R.plurals.blocklist_domains, n, n)) }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private val challengeMode get() = !Prefs.isFilterMode(this)

    private fun inGrace(kind: String, item: String) = FilterGrace.inGrace(Prefs.addedAt(this, kind, item), TrustedTime.now(this))

    private fun lockedMessage() = when {
        Lock.pinLocked(this) -> R.string.fs_locked_pin
        challengeMode -> R.string.fs_locked_challenge
        else -> R.string.fs_locked_time
    }

    private fun render() {
        val challenge = challengeMode
        b.toolbar.setTitle(if (challenge) R.string.fs_challenge_screen_title else R.string.filter_settings_title)
        b.cardChallengeLists.isVisible = challenge
        b.cardFamily.isVisible = !challenge
        b.cardAllowed.isVisible = !challenge
        b.tvLockNote.isVisible = Lock.isLocked(this)
        b.tvLockNote.setText(lockedMessage())

        updating = true
        b.switchChallenge.isChecked = Prefs.challengeUsesFilterLists(this)
        b.switchFamily.isChecked = Prefs.familyDnsSetting(this)
        b.switchApps.isChecked = Prefs.appBlockingEnabled(this)
        updating = false

        renderList(b.listBlocked, Prefs.blockedDomains(this), blocked = true)
        renderList(b.listAllowed, Prefs.allowedDomains(this), blocked = false)

        val apps = Prefs.blockedApps(this) ?: emptySet()
        b.btnChooseApps.isEnabled = Prefs.appBlockingEnabled(this)
        b.tvApps.text = when {
            !Prefs.appBlockingEnabled(this) -> getString(R.string.filter_summary_apps_off)
            apps.isEmpty() -> getString(R.string.fs_apps_none)
            else -> apps.map { AppRules.labelOf(this, it) }.distinct().sortedWith(String.CASE_INSENSITIVE_ORDER).joinToString(", ")
        }
    }

    private fun renderList(list: LinearLayout, domains: Set<String>, blocked: Boolean) {
        list.removeAllViews()
        if (domains.isEmpty()) {
            list.addView(TextView(this).apply {
                setText(R.string.fs_list_empty)
                setTextColor(ContextCompat.getColor(context, R.color.pb_muted))
                textSize = 14f
            })
            return
        }
        for (d in domains) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(TextView(this).apply {
                text = d
                setTextColor(ContextCompat.getColor(context, R.color.pb_text))
                textSize = 15f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
                setText(R.string.btn_remove)
                contentDescription = getString(R.string.btn_remove) + " " + d
                setOnClickListener {
                    // Removing a block loosens the filter (if the lists are in force); removing an exception tightens it.
                    val ctx = this@FilterSettingsActivity
                    change(loosens = blocked && Prefs.filterListsEnforced(ctx), grace = blocked && inGrace(Prefs.ADDED_DOMAIN, d)) {
                        if (blocked) {
                            Prefs.setBlockedDomains(ctx, Prefs.blockedDomains(ctx) - d)
                            Prefs.clearAdded(ctx, Prefs.ADDED_DOMAIN, listOf(d))
                        } else {
                            Prefs.setAllowedDomains(ctx, Prefs.allowedDomains(ctx) - d)
                        }
                    }
                }
            })
            list.addView(row)
        }
    }

    private fun addDomain(input: EditText, blocked: Boolean) {
        val d = DomainInput.parse(input.text.toString())
        if (d == null) {
            input.error = getString(R.string.fs_invalid_domain)
            return
        }
        change(loosens = !blocked) {
            if (blocked) {
                Prefs.setBlockedDomains(this, Prefs.blockedDomains(this) + d)
                Prefs.markAdded(this, Prefs.ADDED_DOMAIN, listOf(d), TrustedTime.now(this))
            } else {
                Prefs.setAllowedDomains(this, Prefs.allowedDomains(this) + d)
            }
            input.text?.clear()
        }
    }

    /**
     * Applies [action] now if it only tightens the filter, or only takes back something added within the grace
     * period; otherwise only when unlocked (or after the PIN).
     */
    private fun change(loosens: Boolean, grace: Boolean = false, action: () -> Unit) {
        val run = { action(); render() }
        when {
            !loosens || !Lock.isLocked(this) || grace -> run()
            Lock.pinLocked(this) -> LockUi.askPin(this) { run() }
            else -> Toast.makeText(this, lockedMessage(), Toast.LENGTH_LONG).show()
        }
    }

    private fun revert(r: () -> Unit) {
        updating = true
        r()
        updating = false
    }

    // ---------------------------------------------------------------- apps

    private fun installedSuggestions(): Set<String> =
        BlockerAccessibilityService.BLOCKED_APPS.keys.filter { isInstalled(it) }.toSet()

    private companion object {
        const val SWITCH_ITEM = "challenge"
        const val APPS_ITEM = "apps"
    }

    private fun isInstalled(pkg: String) = try {
        packageManager.getApplicationInfo(pkg, 0); true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    /** Apps with a launcher icon, minus the never-blockable ones (PBlock, Settings, phone, emergency). Suggestions first. */
    private fun launcherApps(): List<AppEntry> {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val suggestions = BlockerAccessibilityService.BLOCKED_APPS.keys
        val dialer = AppRules.defaultDialer(this)
        return pm.queryIntentActivities(intent, 0)
            .map { it.activityInfo.packageName }
            .distinct()
            .filter {
                it != packageName && it !in SettingsGuard.SETTINGS_PKGS && it != SettingsGuard.SYSTEMUI &&
                    !AppSafety.neverBlock(it, dialer)
            }
            .map { AppEntry(it, AppRules.labelOf(this, it), it in suggestions) }
            .sortedWith(compareBy<AppEntry> { !it.suggested }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.label })
    }

    private fun chooseApps() {
        val toast = Toast.makeText(this, R.string.fs_loading, Toast.LENGTH_SHORT)
        toast.show()
        Thread {
            val apps = launcherApps()
            main.post {
                toast.cancel()
                if (!isFinishing) showAppPicker(apps)
            }
        }.start()
    }

    private fun showAppPicker(apps: List<AppEntry>) {
        val current = Prefs.blockedApps(this) ?: installedSuggestions()
        val checked = BooleanArray(apps.size) { apps[it].pkg in current }
        val labels = apps.map { if (it.suggested) getString(R.string.fs_suggested, it.label) else it.label }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.btn_choose_apps)
            .setMultiChoiceItems(labels, checked) { _, i, on -> checked[i] = on }
            .setPositiveButton(R.string.btn_ok) { _, _ ->
                val shown = apps.map { it.pkg }.toSet()
                // Keep entries for apps not shown (e.g. uninstalled) untouched.
                val chosen = (current - shown) + apps.filterIndexed { i, _ -> checked[i] }.map { it.pkg }
                val added = chosen - current
                if (added.isEmpty()) applyApps(current, chosen.toSet())
                else confirmAdded(current, chosen.toSet(), apps.filter { it.pkg in added })
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    /** Step 1 after picking: show the newly picked apps (icon + name) so a mis-tap is visible. */
    private fun confirmAdded(current: Set<String>, chosen: Set<String>, added: List<AppEntry>) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(24)
            setPadding(pad, dp(8), pad, 0)
        }
        for (a in added) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, dp(6))
            }
            row.addView(ImageView(this).apply {
                try { setImageDrawable(packageManager.getApplicationIcon(a.pkg)) } catch (_: Exception) {}
                layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginEnd = dp(12) }
                importantForAccessibility = ImageView.IMPORTANT_FOR_ACCESSIBILITY_NO
            })
            row.addView(TextView(this).apply {
                text = a.label
                setTextColor(ContextCompat.getColor(context, R.color.pb_text))
                textSize = 16f
            })
            box.addView(row)
        }
        val enforcedLock = Prefs.filterListsEnforced(this) && Lock.isLocked(this) && !Lock.pinLocked(this)
        box.addView(TextView(this).apply {
            setText(if (enforcedLock) R.string.fs_confirm_apps_locked else R.string.fs_confirm_apps_text)
            setTextColor(ContextCompat.getColor(context, R.color.pb_muted))
            textSize = 13f
            setPadding(0, dp(8), 0, 0)
        })
        MaterialAlertDialogBuilder(this)
            .setTitle(resources.getQuantityString(R.plurals.fs_confirm_apps_title, added.size, added.size))
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton(R.string.fs_confirm_apps_ok) { _, _ -> checkImportant(current, chosen, added) }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    /** Step 2: a name-based check of the newly picked apps; apps that look important need a second yes. */
    private fun checkImportant(current: Set<String>, chosen: Set<String>, added: List<AppEntry>) {
        val flagged = added.mapNotNull { a ->
            val kinds = AppSafety.importantKinds(a.label, a.pkg)
            if (kinds.isEmpty()) null else a to kinds
        }
        if (flagged.isEmpty()) {
            applyApps(current, chosen)
            return
        }
        val names = flagged.joinToString(", ") { it.first.label }
        val kinds = flagged.flatMap { it.second }.distinct().joinToString(" / ") { getString(kindLabel(it)) }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.fs_important_title)
            .setMessage(getString(R.string.fs_important_text, names, kinds))
            .setPositiveButton(R.string.fs_important_yes) { _, _ -> applyApps(current, chosen) }
            .setNegativeButton(R.string.fs_important_no) { _, _ -> applyApps(current, chosen - flagged.map { it.first.pkg }.toSet()) }
            .setCancelable(false)
            .show()
    }

    private fun kindLabel(k: AppSafety.Kind) = when (k) {
        AppSafety.Kind.BANK -> R.string.imp_kind_bank
        AppSafety.Kind.AUTH -> R.string.imp_kind_auth
        AppSafety.Kind.GOV -> R.string.imp_kind_gov
        AppSafety.Kind.HEALTH -> R.string.imp_kind_health
        AppSafety.Kind.MAPS -> R.string.imp_kind_maps
        AppSafety.Kind.COMM -> R.string.imp_kind_comm
        AppSafety.Kind.EMERGENCY -> R.string.imp_kind_emergency
    }

    /** Saves the new app list: removals need the lock to allow them, unless every removed app is still in grace. */
    private fun applyApps(current: Set<String>, chosen: Set<String>) {
        val removed = current - chosen
        val added = chosen - current
        if (removed.isEmpty() && added.isEmpty()) return
        val now = TrustedTime.now(this)
        val grace = FilterGrace.removalAllowed(removed, { Prefs.addedAt(this, Prefs.ADDED_APP, it) }, now)
        change(loosens = removed.isNotEmpty() && Prefs.filterListsEnforced(this), grace = grace) {
            Prefs.setBlockedApps(this, chosen)
            Prefs.markAdded(this, Prefs.ADDED_APP, added, now)
            Prefs.clearAdded(this, Prefs.ADDED_APP, removed)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
