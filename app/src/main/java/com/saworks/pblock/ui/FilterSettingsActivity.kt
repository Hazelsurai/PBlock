package com.saworks.pblock.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
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
import com.saworks.pblock.core.DomainInput
import com.saworks.pblock.core.SettingsGuard
import com.saworks.pblock.data.AppRules
import com.saworks.pblock.data.Lock
import com.saworks.pblock.data.Prefs
import com.saworks.pblock.databinding.ActivityFilterSettingsBinding
import com.saworks.pblock.vpn.Blocklist

/**
 * Content-filter rules: own blocked domains, exceptions, family DNS and the optional app list.
 * Tightening is always allowed; loosening needs the parent PIN, and is refused while a time lock holds.
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

        b.switchFamily.setOnCheckedChangeListener { _, on ->
            if (updating) return@setOnCheckedChangeListener
            revert { b.switchFamily.isChecked = !on }
            change(loosens = !on) { Prefs.setFamilyDns(this, on) }
        }
        b.switchApps.setOnCheckedChangeListener { _, on ->
            if (updating) return@setOnCheckedChangeListener
            revert { b.switchApps.isChecked = !on }
            change(loosens = !on) {
                if (on && Prefs.blockedApps(this) == null) Prefs.setBlockedApps(this, installedSuggestions())
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

    private fun render() {
        b.tvLockNote.isVisible = Lock.isLocked(this)
        b.tvLockNote.setText(if (Lock.pinLocked(this)) R.string.fs_locked_pin else R.string.fs_locked_time)

        updating = true
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
                    // Removing a block loosens the filter; removing an exception tightens it.
                    change(loosens = blocked) {
                        if (blocked) Prefs.setBlockedDomains(this@FilterSettingsActivity, Prefs.blockedDomains(this@FilterSettingsActivity) - d)
                        else Prefs.setAllowedDomains(this@FilterSettingsActivity, Prefs.allowedDomains(this@FilterSettingsActivity) - d)
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
            if (blocked) Prefs.setBlockedDomains(this, Prefs.blockedDomains(this) + d)
            else Prefs.setAllowedDomains(this, Prefs.allowedDomains(this) + d)
            input.text?.clear()
        }
    }

    /** Applies [action] now if it only tightens the filter; otherwise only when unlocked (or after the PIN). */
    private fun change(loosens: Boolean, action: () -> Unit) {
        val run = { action(); render() }
        when {
            !loosens || !Lock.isLocked(this) -> run()
            Lock.pinLocked(this) -> LockUi.askPin(this) { run() }
            else -> Toast.makeText(this, R.string.fs_locked_time, Toast.LENGTH_LONG).show()
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

    private fun isInstalled(pkg: String) = try {
        packageManager.getApplicationInfo(pkg, 0); true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    /** Apps with a launcher icon, minus PBlock, Settings and the system UI. Suggestions first. */
    private fun launcherApps(): List<AppEntry> {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val suggestions = BlockerAccessibilityService.BLOCKED_APPS.keys
        return pm.queryIntentActivities(intent, 0)
            .map { it.activityInfo.packageName }
            .distinct()
            .filter { it != packageName && it !in SettingsGuard.SETTINGS_PKGS && it != SettingsGuard.SYSTEMUI }
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
                change(loosens = !chosen.containsAll(current)) { Prefs.setBlockedApps(this, chosen) }
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }
}
