package com.saworks.pblock.ui

import android.animation.ValueAnimator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.core.view.WindowInsetsCompat
import com.saworks.pblock.R
import com.saworks.pblock.core.BoxBreathing
import com.saworks.pblock.core.Events
import com.saworks.pblock.data.AttemptLog
import com.saworks.pblock.data.ChallengeStats
import com.saworks.pblock.data.Prefs
import com.saworks.pblock.databinding.ActivityHardMomentBinding
import kotlin.random.Random

/** "Hard moment": calm screen with box breathing, a kind message and a small action. Using it is never a failure. */
class HardMomentActivity : AppCompatActivity() {

    private lateinit var b: ActivityHardMomentBinding
    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var startedAt = 0L
    private var lastPhaseIndex = -1
    private var messageIndex = -1
    private var scaleAnim: ValueAnimator? = null

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            val elapsed = android.os.SystemClock.elapsedRealtime() - startedAt
            val st = BoxBreathing.at(elapsed)
            if (st == null) { finishBreathing(true); return }
            if (st.index != lastPhaseIndex) {
                lastPhaseIndex = st.index
                animateTo(if (st.phase == BoxBreathing.Phase.IN || st.phase == BoxBreathing.Phase.HOLD_FULL) MAX else MIN)
            }
            b.tvPhase.text = getString(R.string.hm_phase_fmt, phaseName(st.phase), st.secondsLeft)
            b.tvRound.text = getString(R.string.hm_round, st.round, BoxBreathing.ROUNDS)
            handler.postDelayed(this, 200)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        b = ActivityHardMomentBinding.inflate(layoutInflater)
        setContentView(b.root)
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val s = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(s.left, s.top, s.right, s.bottom)
            insets
        }
        if (savedInstanceState == null) AttemptLog.log(this, Events.HARD_MOMENT, "open")
        messageIndex = savedInstanceState?.getInt(KEY_MSG, -1) ?: -1
        val actionIndex = savedInstanceState?.getInt(KEY_ACTION, -1) ?: -1

        val actions = resources.getStringArray(R.array.hard_moment_actions)
        val ai = if (actionIndex in actions.indices) actionIndex else Random.nextInt(actions.size)
        b.tvAction.text = actions[ai]
        b.tvAction.tag = ai
        showMessage(next = messageIndex !in resources.getStringArray(R.array.hard_moment_messages).indices)

        b.btnAnother.setOnClickListener { showMessage(next = true) }
        b.btnBreath.setOnClickListener { if (running) finishBreathing(false) else startBreathing() }
        b.btnBack.setOnClickListener { finish() }
        b.tvPhase.text = getString(R.string.hm_ready)
        b.tvRound.text = getString(R.string.hm_round_info, BoxBreathing.ROUNDS, BoxBreathing.TOTAL_MS / 1000)

        val sum = ChallengeStats.summary(this)
        b.tvStreak.text = resources.getQuantityString(R.plurals.hm_streak, sum.streak, sum.streak)
        b.tvDaysLeft.text = resources.getQuantityString(R.plurals.hm_days_left, sum.daysRemaining, sum.daysRemaining)
        // Streak and days left only mean something during the challenge.
        val challenge = !Prefs.isFilterMode(this) && Prefs.lockActivatedAt(this) > 0 && sum.daysRemaining > 0
        b.tvStreak.isVisible = challenge
        b.tvDaysLeft.isVisible = challenge
        b.tvUses.text = resources.getQuantityString(R.plurals.hm_uses, sum.hardMoments, sum.hardMoments)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_MSG, messageIndex)
        outState.putInt(KEY_ACTION, (b.tvAction.tag as? Int) ?: -1)
    }

    override fun onPause() {
        if (running) finishBreathing(false)
        super.onPause()
    }

    private fun showMessage(next: Boolean) {
        val msgs = resources.getStringArray(R.array.hard_moment_messages)
        if (next) {
            var i = Random.nextInt(msgs.size)
            if (msgs.size > 1 && i == messageIndex) i = (i + 1) % msgs.size
            messageIndex = i
        }
        b.tvMessage.text = msgs[messageIndex]
    }

    private fun startBreathing() {
        running = true
        lastPhaseIndex = -1
        startedAt = android.os.SystemClock.elapsedRealtime()
        b.btnBreath.setText(R.string.hm_stop)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        handler.post(tick)
    }

    private fun finishBreathing(completed: Boolean) {
        running = false
        handler.removeCallbacks(tick)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        animateTo(MIN)
        b.btnBreath.setText(if (completed) R.string.hm_again else R.string.hm_start)
        b.tvPhase.text = getString(if (completed) R.string.hm_done else R.string.hm_ready)
        if (completed) showMessage(next = true)
    }

    private fun animateTo(target: Float) {
        scaleAnim?.cancel()
        val from = b.breathCircle.scaleX
        scaleAnim = ValueAnimator.ofFloat(from, target).apply {
            duration = if (from == target) 0 else BoxBreathing.PHASE_MS
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { val v = it.animatedValue as Float; b.breathCircle.scaleX = v; b.breathCircle.scaleY = v }
            start()
        }
    }

    private fun phaseName(p: BoxBreathing.Phase) = getString(
        when (p) {
            BoxBreathing.Phase.IN -> R.string.hm_breathe_in
            BoxBreathing.Phase.OUT -> R.string.hm_breathe_out
            else -> R.string.hm_hold
        }
    )

    override fun onDestroy() {
        scaleAnim?.cancel()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        private const val MIN = 0.55f
        private const val MAX = 1.0f
        private const val KEY_MSG = "msg"
        private const val KEY_ACTION = "action"
    }
}
