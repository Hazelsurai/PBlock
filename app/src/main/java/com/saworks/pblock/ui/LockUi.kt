package com.saworks.pblock.ui

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.saworks.pblock.R
import com.saworks.pblock.core.PinHasher
import com.saworks.pblock.data.Lock
import com.saworks.pblock.data.Pin

/** Shared lock handling for screens: PIN prompt, PIN setup, "is this change allowed now?". */
object LockUi {
    private val main = Handler(Looper.getMainLooper())

    /**
     * Runs [action] if nothing is locked; asks for the parent PIN if the PIN lock holds; otherwise
     * (challenge or time lock) refuses with a toast.
     */
    fun withUnlock(a: Activity, action: () -> Unit) {
        when {
            !Lock.isLocked(a) -> action()
            Lock.pinLocked(a) -> askPin(a, action)
            else -> Toast.makeText(a, R.string.toast_locked, Toast.LENGTH_SHORT).show()
        }
    }

    /** Privacy actions (activity log): PIN needed only when a parent PIN is set and locked. */
    fun withPinIfSet(a: Activity, action: () -> Unit) {
        if (Lock.pinLocked(a)) askPin(a, action) else action()
    }

    fun askPin(a: Activity, onOk: () -> Unit) {
        val wait = Pin.waitMs(a)
        if (wait > 0) { toast(a, a.getString(R.string.pin_wait, Durations.span(a, wait, seconds = true))); return }
        val input = pinField(a, R.string.pin_hint)
        val dialog = MaterialAlertDialogBuilder(a)
            .setTitle(R.string.pin_enter_title)
            .setView(box(a, input))
            .setPositiveButton(R.string.btn_ok, null)
            .setNegativeButton(R.string.btn_cancel, null)
            .create()
        dialog.setOnShowListener {
            val ok = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            ok.setOnClickListener {
                val pin = input.text.toString()
                ok.isEnabled = false
                ok.setText(R.string.pin_checking)
                Thread {
                    val r = Pin.check(a.applicationContext, pin)
                    main.post {
                        if (a.isFinishing || a.isDestroyed) return@post
                        ok.isEnabled = true
                        ok.setText(R.string.btn_ok)
                        when (r) {
                            Pin.Result.Ok -> { dialog.dismiss(); onOk() }
                            is Pin.Result.Wrong -> {
                                input.text.clear()
                                input.error = a.getString(R.string.pin_wrong)
                                if (r.waitMs > 0) {
                                    dialog.dismiss()
                                    toast(a, a.getString(R.string.pin_wrong) + " " + a.getString(R.string.pin_wait, Durations.span(a, r.waitMs, seconds = true)))
                                }
                            }
                            is Pin.Result.TooSoon -> {
                                dialog.dismiss()
                                toast(a, a.getString(R.string.pin_wait, Durations.span(a, r.waitMs, seconds = true)))
                            }
                        }
                    }
                }.start()
            }
        }
        dialog.show()
    }

    /** Asks for a new PIN twice and stores its salted hash; then [onDone]. */
    fun createPin(a: Activity, onDone: () -> Unit) {
        val p1 = pinField(a, R.string.pin_hint)
        val p2 = pinField(a, R.string.pin_repeat_hint)
        val dialog = MaterialAlertDialogBuilder(a)
            .setTitle(R.string.pin_set_title)
            .setMessage(R.string.pin_set_text)
            .setView(box(a, p1, p2))
            .setPositiveButton(R.string.btn_ok, null)
            .setNegativeButton(R.string.btn_cancel, null)
            .create()
        dialog.setOnShowListener {
            val ok = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            ok.setOnClickListener {
                val pin = p1.text.toString()
                when {
                    !PinHasher.isValidPin(pin) -> { p1.error = a.getString(R.string.pin_invalid); return@setOnClickListener }
                    pin != p2.text.toString() -> { p2.error = a.getString(R.string.pin_mismatch); return@setOnClickListener }
                }
                ok.isEnabled = false
                Thread {
                    Pin.set(a.applicationContext, pin)
                    main.post {
                        if (a.isFinishing || a.isDestroyed) return@post
                        dialog.dismiss()
                        onDone()
                    }
                }.start()
            }
        }
        dialog.show()
    }

    private fun pinField(a: Activity, hint: Int) = EditText(a).apply {
        this.hint = a.getString(hint)
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        isSingleLine = true
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        filters = arrayOf(android.text.InputFilter.LengthFilter(PinHasher.MAX_LEN))
    }

    private fun box(a: Activity, vararg views: View) = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        val d = a.resources.displayMetrics.density
        setPadding((24 * d).toInt(), (8 * d).toInt(), (24 * d).toInt(), 0)
        views.forEach { addView(it) }
    }

    private fun toast(a: Activity, s: String) = Toast.makeText(a, s, Toast.LENGTH_LONG).show()
}

/** Human-readable durations in the UI language. */
object Durations {
    /** Largest two non-zero units ("2 hours 5 minutes"); with [seconds] short waits show seconds. */
    fun span(a: android.content.Context, ms: Long, seconds: Boolean = false): String {
        val r = a.resources
        if (seconds && ms < 60_000L) {
            val s = ((ms + 999) / 1000).toInt().coerceAtLeast(1)
            return r.getQuantityString(R.plurals.duration_seconds, s, s)
        }
        val totalMin = (kotlin.math.abs(ms) / 60_000L).coerceAtLeast(1)
        val d = (totalMin / (24 * 60)).toInt()
        val h = ((totalMin / 60) % 24).toInt()
        val m = (totalMin % 60).toInt()
        val parts = ArrayList<String>()
        if (d > 0) parts += r.getQuantityString(R.plurals.duration_days, d, d)
        if (h > 0) parts += r.getQuantityString(R.plurals.duration_hours, h, h)
        if (m > 0 && d == 0) parts += r.getQuantityString(R.plurals.duration_minutes, m, m)
        return parts.take(2).joinToString(" ")
    }
}
