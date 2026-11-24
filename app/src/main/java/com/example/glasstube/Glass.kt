package com.example.glasstube

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build

/**
 * Shared look of the glass surfaces (same recipe as Glass Browser's "liquid" mode): the skin draws
 * a light tint, a frost layer and the rim; the real blur + edge refraction comes from [LensEffect]
 * underneath (Android 13+). Without the lens a solid frosted plate keeps text readable.
 */
object Glass {

    fun isNight(ctx: Context): Boolean =
        (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    fun textColor(ctx: Context): Int = if (isNight(ctx)) Color.WHITE else 0xFF1D1F24.toInt()

    fun lensSupported(): Boolean = Build.VERSION.SDK_INT >= 33

    /** A glass skin for a surface that has the lens underneath ([lens] = true) or not. */
    fun skin(ctx: Context, radius: Float?, lens: Boolean): GlassSkinDrawable {
        val night = isNight(ctx)
        val skin = GlassSkinDrawable(ctx.resources.displayMetrics.density)
        skin.cornerRadius = radius
        if (lens) {
            skin.setTint(0.45f, null)
            skin.setFrost(if (night) Color.argb(110, 22, 24, 30) else Color.argb(150, 250, 250, 252))
        } else {
            skin.setTint(1f, if (night) Color.argb(235, 27, 30, 38) else Color.argb(235, 248, 249, 251))
            skin.setFrost(Color.TRANSPARENT)
        }
        skin.setRim(true)
        skin.setContentColor(textColor(ctx))
        return skin
    }

    /**
     * A glass pill used on screens without the lens (e.g. the action buttons under the player):
     * a soft frosted fill plus the skin's sheen and rim.
     */
    fun pill(ctx: Context, radius: Float): Drawable {
        val night = isNight(ctx)
        val fill = GradientDrawable().apply {
            cornerRadius = radius
            setColor(if (night) Color.argb(40, 255, 255, 255) else Color.argb(14, 0, 0, 0))
        }
        val skin = GlassSkinDrawable(ctx.resources.displayMetrics.density)
        skin.cornerRadius = radius
        skin.setTint(0.6f, null)
        skin.setRim(true)
        skin.setContentColor(textColor(ctx))
        return LayerDrawable(arrayOf(fill, skin))
    }
}
