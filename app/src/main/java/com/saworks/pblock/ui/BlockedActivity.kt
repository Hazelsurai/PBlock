package com.saworks.pblock.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.saworks.pblock.R
import com.saworks.pblock.data.Prefs
import com.saworks.pblock.databinding.ActivityBlockedBinding

class BlockedActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_APP = "app"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val b = ActivityBlockedBinding.inflate(layoutInflater)
        setContentView(b.root)
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val s = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(s.left, s.top, s.right, s.bottom)
            insets
        }
        val app = intent.getStringExtra(EXTRA_APP) ?: getString(R.string.app_generic)
        b.blockedTitle.text = getString(R.string.blocked_title, app)
        if (Prefs.isFilterMode(this)) b.blockedText.setText(R.string.blocked_text_filter)
        b.btnBlockedHome.setOnClickListener { goHome() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goHome()
        })
    }

    private fun goHome() {
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }
}
