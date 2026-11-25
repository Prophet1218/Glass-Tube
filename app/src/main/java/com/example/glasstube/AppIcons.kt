package com.example.glasstube

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.graphics.ColorUtils

/**
 * Selectable launcher icons, the same styles and colours as in Glass Browser. Each one has an
 * activity-alias in the manifest; switching enables the chosen alias and disables the rest.
 */
object AppIcons {
    const val DEFAULT = "frost_blue"

    val styles = listOf(
        "flat" to R.string.icon_style_flat, "frost" to R.string.icon_style_frost,
        "liquid" to R.string.icon_style_liquid, "mono" to R.string.icon_style_mono
    )
    val colors = listOf(
        "blue" to R.string.icon_color_blue, "purple" to R.string.icon_color_purple,
        "green" to R.string.icon_color_green, "orange" to R.string.icon_color_orange,
        "pink" to R.string.icon_color_pink, "graphite" to R.string.icon_color_graphite
    )

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun current(ctx: Context): String = prefs(ctx).getString("app_icon", DEFAULT) ?: DEFAULT

    private fun aliasName(ctx: Context, key: String): String =
        ctx.packageName + ".Icon" + key.split('_').joinToString("") { p -> p.replaceFirstChar { it.uppercase() } }

    fun label(ctx: Context, key: String): String {
        val parts = key.split('_')
        val style = styles.firstOrNull { it.first == parts.getOrNull(0) }?.second ?: return key
        val color = colors.firstOrNull { it.first == parts.getOrNull(1) }?.second ?: return key
        return ctx.getString(style) + ", " + ctx.getString(color)
    }

    fun artRes(ctx: Context, key: String): Int =
        ctx.resources.getIdentifier("icon_art_$key", "drawable", ctx.packageName)

    fun apply(ctx: Context, key: String): Boolean = try {
        val pm = ctx.packageManager
        // enable the new one first so the app is never without a launcher entry
        pm.setComponentEnabledSetting(
            ComponentName(ctx.packageName, aliasName(ctx, key)),
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP
        )
        for ((s, _) in styles) for ((c, _) in colors) {
            val other = "${s}_$c"
            if (other == key) continue
            pm.setComponentEnabledSetting(
                ComponentName(ctx.packageName, aliasName(ctx, other)),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP
            )
        }
        prefs(ctx).edit().putString("app_icon", key).apply()
        true
    } catch (e: Exception) {
        false
    }

    /** Inline picker for the settings page: one row of six colours per style. */
    fun picker(ctx: Context, onChanged: () -> Unit): View {
        val d = ctx.resources.displayMetrics.density
        val text = Glass.textColor(ctx)
        val column = LinearLayout(ctx)
        column.orientation = LinearLayout.VERTICAL
        column.setPadding((18 * d).toInt(), 0, (18 * d).toInt(), (10 * d).toInt())
        val rings = HashMap<String, View>()

        fun select(key: String) {
            for ((k, ring) in rings) {
                val on = k == key
                ring.animate().alpha(if (on) 1f else 0f).scaleX(if (on) 1f else 0.9f).scaleY(if (on) 1f else 0.9f)
                    .setDuration(260).setInterpolator(OvershootInterpolator(1.4f)).start()
            }
        }

        for ((style, styleName) in styles) {
            val label = TextView(ctx)
            label.text = ctx.getString(styleName)
            label.textSize = 13f
            label.setTextColor(ColorUtils.setAlphaComponent(text, 170))
            label.setPadding((4 * d).toInt(), (10 * d).toInt(), 0, (6 * d).toInt())
            column.addView(label)

            val row = LinearLayout(ctx)
            row.orientation = LinearLayout.HORIZONTAL
            for ((color, _) in colors) {
                val key = "${style}_$color"
                val cell = FrameLayout(ctx)
                val pad = (4 * d).toInt()
                cell.setPadding(pad, pad, pad, pad)

                val ring = View(ctx)
                ring.background = GradientDrawable().apply {
                    cornerRadius = 16 * d
                    setColor(Color.TRANSPARENT)
                    setStroke((2 * d).toInt(), ColorUtils.setAlphaComponent(text, 220))
                }
                val on = key == current(ctx)
                ring.alpha = if (on) 1f else 0f
                ring.scaleX = if (on) 1f else 0.9f
                ring.scaleY = ring.scaleX
                rings[key] = ring

                val mask = FrameLayout(ctx)
                mask.clipToOutline = true
                mask.outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, view.width * 0.26f)
                    }
                }
                val img = ImageView(ctx)
                img.adjustViewBounds = true
                img.setImageResource(artRes(ctx, key))
                // only the middle of the art is visible on the home screen
                img.scaleX = 1.5f
                img.scaleY = 1.5f
                img.contentDescription = label(ctx, key)
                mask.addView(img, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                val inner = (3 * d).toInt()
                cell.addView(ring, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                cell.addView(mask, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
                    setMargins(inner, inner, inner, inner)
                })
                cell.setOnClickListener {
                    if (key == current(ctx)) return@setOnClickListener
                    if (apply(ctx.applicationContext, key)) {
                        select(key)
                        onChanged()
                        Toast.makeText(ctx, R.string.icon_changed, Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(ctx, R.string.icon_failed, Toast.LENGTH_SHORT).show()
                    }
                }
                row.addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            column.addView(row)
        }
        return column
    }
}
