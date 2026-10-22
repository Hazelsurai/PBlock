package com.saworks.pblock.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.saworks.pblock.BuildConfig
import com.saworks.pblock.R
import com.saworks.pblock.core.BugReport
import com.saworks.pblock.data.AttemptLog
import com.saworks.pblock.data.Diagnostics
import com.saworks.pblock.data.ErrorLog
import com.saworks.pblock.databinding.ActivityBugReportBinding
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Bug report / suggestion form. Builds a plain-text report, shows it, then hands it to the user's
 * e-mail app (nothing is sent by PBlock itself). Not gated by any lock, so it also works while locked;
 * only attaching the activity log asks for the parent PIN when that lock holds.
 */
class BugReportActivity : AppCompatActivity() {

    private lateinit var b: ActivityBugReportBinding
    private val main = Handler(Looper.getMainLooper())
    private var settingLog = false
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        b = ActivityBugReportBinding.inflate(layoutInflater)
        setContentView(b.root)
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val s = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(s.left, s.top, s.right, s.bottom)
            insets
        }
        b.toolbar.setNavigationOnClickListener { finish() }

        b.checkLog.setOnCheckedChangeListener { _, on ->
            if (settingLog || !on) return@setOnCheckedChangeListener
            // Attaching blocked sites is a privacy action: same rule as viewing the log.
            setLogChecked(false)
            LockUi.withPinIfSet(this) { setLogChecked(true) }
        }
        b.inputDescription.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, c: Int, d: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, c: Int, d: Int) { b.layoutDescription.error = null }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        b.btnPreview.setOnClickListener { preview() }
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        // The restored checkbox state was already approved; don't ask for the PIN again.
        settingLog = true
        super.onRestoreInstanceState(savedInstanceState)
        settingLog = false
    }

    private fun setLogChecked(on: Boolean) {
        settingLog = true
        b.checkLog.isChecked = on
        settingLog = false
    }

    private fun category(): BugReport.Category = when (b.groupCategory.checkedRadioButtonId) {
        R.id.catBlocking -> BugReport.Category.BLOCKING_FAILS
        R.id.catWrongBlock -> BugReport.Category.WRONGLY_BLOCKED
        R.id.catSuggestion -> BugReport.Category.SUGGESTION
        R.id.catOther -> BugReport.Category.OTHER
        else -> BugReport.Category.BUG
    }

    private fun preview() {
        if (busy) return
        val description = b.inputDescription.text?.toString().orEmpty()
        val contact = b.inputContact.text?.toString().orEmpty().trim()
        var ok = true
        if (description.isBlank()) { b.layoutDescription.error = getString(R.string.bug_description_required); ok = false }
        if (!BugReport.isValidContact(contact)) { b.layoutContact.error = getString(R.string.bug_contact_invalid); ok = false } else b.layoutContact.error = null
        if (!ok) { (if (description.isBlank()) b.inputDescription else b.inputContact).requestFocus(); return }

        val withDiag = b.checkDiagnostics.isChecked
        val withLog = b.checkLog.isChecked
        val cat = category()
        val steps = b.inputSteps.text?.toString().orEmpty()
        busy = true
        b.btnPreview.isEnabled = false
        val app = applicationContext
        Thread {
            val input = try {
                BugReport.Input(
                    category = cat,
                    description = description,
                    steps = steps,
                    contact = contact,
                    diagnostics = if (withDiag) Diagnostics.collect(app) else null,
                    errorLines = if (withDiag) ErrorLog.recent(app) else emptyList(),
                    lastCrash = if (withDiag) ErrorLog.lastCrash(app) else null,
                    activityLog = if (withLog) activityLog() else null,
                    createdAt = ZonedDateTime.now().format(STAMP),
                )
            } catch (e: Exception) {
                ErrorLog.note(app, "bugreport.collect", e)
                BugReport.Input(cat, description, steps, contact, createdAt = ZonedDateTime.now().format(STAMP))
            }
            main.post {
                busy = false
                b.btnPreview.isEnabled = true
                if (!isFinishing && !isDestroyed) showPreview(input)
            }
        }.start()
    }

    private fun activityLog(): List<String> {
        val zone = ZoneId.systemDefault()
        return AttemptLog.readAll(applicationContext).sortedByDescending { it.time }.take(LOG_MAX).map {
            Instant.ofEpochMilli(it.time).atZone(zone).format(STAMP) + "  " + it.kind + "  " + it.target
        }
    }

    private fun showPreview(input: BugReport.Input) {
        val subject = BugReport.subject(BuildConfig.VERSION_NAME, input.category)
        val full = BugReport.fullText(input)
        val attach = BugReport.needsAttachment(full)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val note = TextView(this).apply {
            text = getString(if (attach) R.string.bug_preview_note_attach else R.string.bug_preview_note, BugReport.ADDRESS)
            setTextColor(ContextCompat.getColor(this@BugReportActivity, R.color.pb_muted))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, 0, 0, pad / 2)
        }
        val body = TextView(this).apply {
            text = getString(R.string.bug_preview_subject, subject) + "\n\n" + full
            typeface = Typeface.MONOSPACE
            setTextColor(ContextCompat.getColor(this@BugReportActivity, R.color.pb_text))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextIsSelectable(true)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad + pad / 2, pad / 2, pad + pad / 2, 0)
            addView(note)
            addView(body)
        }
        val scroll = ScrollView(this).apply { addView(box, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.bug_preview_title)
            .setView(scroll)
            .setPositiveButton(R.string.bug_send) { _, _ -> send(input, subject, full, attach) }
            .setNeutralButton(R.string.bug_copy) { _, _ -> copy(subject, full, showDialog = false) }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun send(input: BugReport.Input, subject: String, full: String, attach: Boolean) {
        val mailto = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
        val hasMail = packageManager.queryIntentActivities(mailto, 0).isNotEmpty()
        try {
            if (!hasMail) throw ActivityNotFoundException("no mailto handler")
            if (!attach) {
                val intent = Intent(Intent.ACTION_SENDTO, Uri.parse(BugReport.mailtoUri(subject, full))).apply {
                    putExtra(Intent.EXTRA_EMAIL, arrayOf(BugReport.ADDRESS))
                    putExtra(Intent.EXTRA_SUBJECT, subject)
                    putExtra(Intent.EXTRA_TEXT, full)
                }
                startActivity(intent)
            } else {
                val uri = writeReportFile(full)
                // Explicit SEND intents for each installed e-mail app (a mailto: selector
                // inside a chooser shows "No apps can perform this action" on Android 14).
                val mailPkgs = packageManager.queryIntentActivities(mailto, 0)
                    .map { it.activityInfo.packageName }.distinct()
                val targets = mailPkgs.map { pkg ->
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        setPackage(pkg)
                        putExtra(Intent.EXTRA_EMAIL, arrayOf(BugReport.ADDRESS))
                        putExtra(Intent.EXTRA_SUBJECT, subject)
                        putExtra(Intent.EXTRA_TEXT, BugReport.attachmentBody(input, FILE_NAME))
                        putExtra(Intent.EXTRA_STREAM, uri)
                        clipData = ClipData.newRawUri(FILE_NAME, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                }.filter { packageManager.queryIntentActivities(it, 0).isNotEmpty() }
                if (targets.isEmpty()) throw ActivityNotFoundException("no e-mail app accepts attachments")
                targets.forEach { t -> t.`package`?.let { grantUriPermission(it, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
                if (targets.size == 1) {
                    startActivity(targets[0])
                } else {
                    val chooser = Intent.createChooser(targets[0], getString(R.string.bug_chooser_title)).apply {
                        putExtra(Intent.EXTRA_INITIAL_INTENTS, targets.drop(1).toTypedArray())
                    }
                    startActivity(chooser)
                }
            }
        } catch (e: Exception) {
            if (e !is ActivityNotFoundException) ErrorLog.note(applicationContext, "bugreport.send", e)
            copy(subject, full, showDialog = true)
        }
    }

    private fun writeReportFile(full: String): Uri {
        val dir = File(cacheDir, "reports").apply { mkdirs() }
        val f = File(dir, FILE_NAME)
        f.writeText(full)
        return FileProvider.getUriForFile(this, "${BuildConfig.APPLICATION_ID}.fileprovider", f)
    }

    private fun copy(subject: String, full: String, showDialog: Boolean) {
        val cm = getSystemService(ClipboardManager::class.java)
        cm?.setPrimaryClip(ClipData.newPlainText(subject, "To: ${BugReport.ADDRESS}\nSubject: $subject\n\n$full"))
        if (showDialog) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.bug_no_email_title)
                .setMessage(getString(R.string.bug_no_email_text, BugReport.ADDRESS))
                .setPositiveButton(R.string.btn_ok, null)
                .show()
        } else {
            Toast.makeText(this, getString(R.string.bug_copied, BugReport.ADDRESS), Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        private const val FILE_NAME = "pblock-report.txt"
        private const val LOG_MAX = 300
        private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm xxx")
    }
}
