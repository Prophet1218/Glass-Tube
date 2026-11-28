package com.example.glasstube

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Shown (in its own process) when the app crashed: the error text, to copy or share, and a
 * button to start again. Deliberately plain, so it works even when the rest of the app can't.
 */
class CrashActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val trace = intent.getStringExtra(EXTRA_TRACE) ?: "?"
        val d = resources.displayMetrics.density
        val pad = (16 * d).toInt()
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.rgb(16, 17, 22))
        root.setPadding(pad, pad * 3, pad, pad * 2)

        val title = TextView(this)
        title.text = getString(R.string.crash_title)
        title.textSize = 22f
        title.setTextColor(Color.WHITE)
        title.setTypeface(title.typeface, Typeface.BOLD)
        root.addView(title)

        val sub = TextView(this)
        sub.text = getString(R.string.crash_sub)
        sub.textSize = 14f
        sub.setTextColor(Color.argb(190, 255, 255, 255))
        sub.setPadding(0, pad / 2, 0, pad)
        root.addView(sub)

        val text = TextView(this)
        text.text = trace
        text.textSize = 11f
        text.typeface = Typeface.MONOSPACE
        text.setTextColor(Color.argb(220, 255, 255, 255))
        text.setTextIsSelectable(true)
        val scroll = ScrollView(this)
        scroll.addView(text)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.setPadding(0, pad, 0, 0)
        fun button(label: Int, action: () -> Unit) {
            val b = Button(this)
            b.setText(label)
            b.isAllCaps = false
            b.setOnClickListener { action() }
            row.addView(b, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        button(R.string.crash_copy) {
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("Lucent crash", trace))
            Toast.makeText(this, R.string.crash_copied, Toast.LENGTH_SHORT).show()
        }
        button(R.string.crash_share) {
            startActivity(Intent.createChooser(
                Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, trace), null
            ))
        }
        button(R.string.crash_restart) {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            finish()
        }
        root.addView(row)
        setContentView(root)
    }

    companion object {
        const val EXTRA_TRACE = "trace"
    }
}
