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

    /** Real refracting glass: Android 13+ and not turned off in the settings. */
    var reduceTransparency = false

    /** Colour fringing at the edges of every liquid glass surface. */
    var dispersion = false

    /** Strength of the refraction of every liquid glass surface (1 = normal, 0 = none). */
    var refraction = 1f

    /** Icon tile for the settings: a small piece of liquid glass with a shine on top. */
    fun iconTile(ctx: Context, radius: Float): Drawable {
        val night = isNight(ctx)
        val base = GradientDrawable().apply {
            cornerRadius = radius
            setColor(if (night) Color.argb(34, 255, 255, 255) else Color.argb(16, 0, 0, 0))
        }
        val sheen = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(
                Color.argb(if (night) 70 else 120, 255, 255, 255),
                Color.argb(0, 255, 255, 255),
                Color.argb(if (night) 18 else 30, 255, 255, 255)
            )
        ).apply { cornerRadius = radius }
        val skin = GlassSkinDrawable(ctx.resources.displayMetrics.density)
        skin.cornerRadius = radius
        skin.setTint(0.5f, null)
        skin.setRim(true)
        skin.setConvex(1f)
        skin.setContentColor(textColor(ctx))
        return LayerDrawable(arrayOf(base, sheen, skin))
    }

    fun lensSupported(): Boolean = Build.VERSION.SDK_INT >= 33 && !reduceTransparency

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
    fun pill(ctx: Context, radius: Float, strong: Boolean = false): Drawable {
        val night = isNight(ctx)
        val fill = GradientDrawable().apply {
            cornerRadius = radius
            setColor(
                when {
                    strong && night -> Color.argb(70, 255, 255, 255)
                    strong -> Color.argb(255, 255, 255, 255)
                    night -> Color.argb(40, 255, 255, 255)
                    else -> Color.argb(14, 0, 0, 0)
                }
            )
        }
        val skin = GlassSkinDrawable(ctx.resources.displayMetrics.density)
        skin.cornerRadius = radius
        skin.setTint(0.6f, null)
        skin.setRim(true)
        skin.setContentColor(textColor(ctx))
        return LayerDrawable(arrayOf(fill, skin))
    }

    /**
     * A glass button over video: dark frost (so the white icon reads on any picture), a sheen
     * and the rim.
     */
    fun overVideo(ctx: Context, radius: Float?, lens: Boolean = false): Drawable {
        val fill = GradientDrawable().apply {
            if (radius != null) cornerRadius = radius else shape = GradientDrawable.OVAL
            // with the real lens underneath only a light veil, so the refraction shows
            setColor(if (lens) Color.argb(28, 20, 22, 28) else Color.argb(70, 20, 22, 28))
        }
        val skin = GlassSkinDrawable(ctx.resources.displayMetrics.density)
        skin.cornerRadius = radius
        skin.setTint(0.75f, null)
        skin.setRim(true)
        skin.setContentColor(Color.WHITE)
        return LayerDrawable(arrayOf(fill, skin))
    }
}
