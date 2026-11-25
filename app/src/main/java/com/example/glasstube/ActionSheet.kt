package com.example.glasstube

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils

/** A frosted glass sheet sliding up from the bottom with a list of actions. */
object ActionSheet {

    data class Item(val icon: Int, val label: String, val action: () -> Unit)

    fun show(ctx: Context, title: String?, items: List<Item>) {
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val dialog = Dialog(ctx)
        val root = FrameLayout(ctx)
        val card = LinearLayout(ctx)
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(dp(8), dp(10), dp(8), dp(10))
        card.background = Glass.skin(ctx, 28 * d, lens = false)
        val text = Glass.textColor(ctx)
        if (title != null) {
            val t = TextView(ctx)
            t.text = title
            t.textSize = 14f
            t.maxLines = 2
            t.setTextColor(ColorUtils.setAlphaComponent(text, 170))
            t.setPadding(dp(16), dp(6), dp(16), dp(10))
            card.addView(t)
        }
        for (item in items) {
            val row = LinearLayout(ctx)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(dp(16), dp(13), dp(16), dp(13))
            val ta = ctx.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
            row.background = ta.getDrawable(0)
            ta.recycle()
            val iv = ImageView(ctx)
            iv.setImageResource(item.icon)
            iv.setColorFilter(text)
            row.addView(iv, LinearLayout.LayoutParams(dp(22), dp(22)))
            val tv = TextView(ctx)
            tv.text = item.label
            tv.textSize = 16f
            tv.setTextColor(text)
            row.addView(tv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(16) })
            row.setOnClickListener {
                dialog.dismiss()
                item.action()
            }
            card.addView(row)
        }
        root.addView(card, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
            setMargins(dp(12), 0, dp(12), dp(12))
        })
        root.setOnClickListener { dialog.dismiss() }
        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setDimAmount(0.35f)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setGravity(Gravity.BOTTOM)
        }
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, 0, 0, b.bottom)
            insets
        }
        dialog.show()
        card.translationY = dp(400).toFloat()
        card.animate().translationY(0f).setDuration(340).setInterpolator(PathInterpolator(0.2f, 0f, 0f, 1f)).start()
    }

    /** Asks for a name (new playlist). */
    fun askText(ctx: Context, title: String, hint: String, done: (String) -> Unit) {
        val input = EditText(ctx)
        input.hint = hint
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        val box = FrameLayout(ctx)
        val pad = (20 * ctx.resources.displayMetrics.density).toInt()
        box.setPadding(pad, pad / 2, pad, 0)
        box.addView(input)
        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle(title)
            .setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val t = input.text.toString().trim()
                if (t.isNotEmpty()) done(t)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    @Suppress("unused")
    private fun accent(ctx: Context) = ContextCompat.getColor(ctx, R.color.accent)

    @Suppress("unused")
    private val bold = Typeface.DEFAULT_BOLD
}
