package com.example.glasstube

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable

/**
 * Egy üvegfelület "bőre": halvány fehér árnyalat, a beállításokban választott szín és sötétítés.
 * Peremet nem rajzol.
 *
 * A keresősáv mindhárom része, a menü és a javaslatok panelje ugyanezt használja, ezért egyforma
 * a színük. Az elmosást és a fénytörést nem ez, hanem a [LensEffect] rajzolja alá.
 */
class GlassSkinDrawable(density: Float) : Drawable() {

    /** A sarok sugara képpontban; null = teljes kapszula (a magasság fele). */
    var cornerRadius: Float? = null
        set(value) {
            field = value
            invalidateSelf()
        }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val overlay = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shade = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.TRANSPARENT }
    private val frost = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.TRANSPARENT }

    /** Dér réteg az üvegen (világos vagy sötét), hogy a szöveg minden oldal fölött olvasható legyen. */
    fun setFrost(color: Int) {
        if (frost.color == color) return
        frost.color = color
        invalidateSelf()
    }
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.3f * density
    }
    private var rimOn = false
    private val dens = density
    /** Domború (3D) hatás erőssége, 0 = nincs. */
    private var convexAmt = 0f
    private val convexBody = Paint(Paint.ANTI_ALIAS_FLAG)
    private val convexRim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
    }
    private var contentColor = Color.WHITE
    private val rect = RectF()
    private var tintAlpha = 0.45f
    private var plate: Int? = null
    private var overlayColor = 0
    private var layerAlpha = 255

    /** A körív (perem) be- vagy kikapcsolása. */
    fun setRim(on: Boolean) {
        if (rimOn == on) return
        rimOn = on
        rebuild()
        invalidateSelf()
    }

    /**
     * A sáv szövegének pillanatnyi színe: a körív ebből kapja a színét, ezért a szöveggel együtt,
     * ugyanazzal az átmenettel vált sötétről világosra.
     */
    fun setContentColor(color: Int) {
        if (contentColor == color) return
        contentColor = color
        if (rimOn) {
            rebuild()
            invalidateSelf()
        }
    }

    /**
     * Domború, vastag üveg kinézete (0..1): felül fényes, alul enyhén árnyékos felület és egy
     * vékony, fényes belső perem. A shader nélküli (egyszerű) üvegen ez adja a térhatást, a
     * shaderes üvegen csak finoman ráerősít.
     */
    fun setConvex(amount: Float) {
        val a = amount.coerceIn(0f, 1f)
        if (convexAmt == a) return
        convexAmt = a
        rebuild()
        invalidateSelf()
    }

    /** Sötétítés: fekete fátyol az üvegen, [alpha] 0..255. */
    fun setShade(alpha: Int) {
        val a = alpha.coerceIn(0, 255)
        if (shade.alpha == a) return
        shade.color = Color.argb(a, 0, 0, 0)
        invalidateSelf()
    }

    /**
     * [alpha]: a fehér árnyalat erőssége (0..1). [plateColor]: ha nem null, árnyalat helyett ez a
     * szín tölti ki (egyszerű üveg, amikor nincs elmosás alatta).
     */
    fun setTint(alpha: Float, plateColor: Int?) {
        if (tintAlpha == alpha && plate == plateColor) return
        tintAlpha = alpha
        plate = plateColor
        rebuild()
        invalidateSelf()
    }

    /** A választott szín, az alfa csatornájában az erősséggel. */
    fun setOverlay(color: Int) {
        if (overlayColor == color) return
        overlayColor = color
        overlay.color = color
        invalidateSelf()
    }

    override fun onBoundsChange(bounds: Rect) {
        rebuild()
    }

    private fun rebuild() {
        val b = bounds
        if (b.isEmpty) return
        val top = b.top.toFloat()
        val bottom = b.bottom.toFloat()

        val p = plate
        if (p != null) {
            fill.shader = null
            fill.color = p
        } else {
            // felül világosabb, mint az igazi üvegen
            fill.color = Color.WHITE
            fill.shader = LinearGradient(
                0f, top, 0f, bottom,
                Color.argb((0x42 * tintAlpha).toInt().coerceIn(0, 255), 255, 255, 255),
                Color.argb((0x1C * tintAlpha).toInt().coerceIn(0, 255), 255, 255, 255),
                Shader.TileMode.CLAMP
            )
        }

        if (convexAmt > 0f) {
            val a = convexAmt
            fun white(f: Float) = Color.argb((255 * f * a).toInt().coerceIn(0, 255), 255, 255, 255)
            fun black(f: Float) = Color.argb((255 * f * a).toInt().coerceIn(0, 255), 0, 0, 0)
            // a "kupola" teteje visszaveri a fényt, az alja felé az üveg vastagsága sötétít
            convexBody.shader = LinearGradient(
                0f, top, 0f, bottom,
                intArrayOf(white(0.20f), white(0.05f), Color.TRANSPARENT, black(0.08f)),
                floatArrayOf(0f, 0.38f, 0.66f, 1f), Shader.TileMode.CLAMP
            )
            // vékony belső fényperem: felül erős, alul (az átütő fénytől) halványabb
            convexRim.shader = LinearGradient(
                0f, top, 0f, bottom,
                intArrayOf(white(0.62f), white(0.06f), white(0.30f)),
                floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
            )
        } else {
            convexBody.shader = null
            convexRim.shader = null
        }

        if (rimOn) {
            // körív: felül és alul látszik, középen elhalványul
            val base = Color.alpha(contentColor)
            fun shadeOf(f: Float) =
                (contentColor and 0x00FFFFFF) or ((base * f).toInt().coerceIn(0, 255) shl 24)
            rim.shader = LinearGradient(
                0f, top, 0f, bottom,
                intArrayOf(shadeOf(0.55f), shadeOf(0.07f), shadeOf(0.40f)),
                floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
            )
        }
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val maxR = minOf(b.width(), b.height()) / 2f
        val r = (cornerRadius ?: maxR).coerceAtMost(maxR)

        val layer = if (layerAlpha < 255) {
            canvas.saveLayerAlpha(
                b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(), layerAlpha
            )
        } else {
            -1
        }

        rect.set(b)
        canvas.drawRoundRect(rect, r, r, fill)
        if (Color.alpha(frost.color) > 0) canvas.drawRoundRect(rect, r, r, frost)
        if (Color.alpha(overlayColor) > 0) canvas.drawRoundRect(rect, r, r, overlay)
        if (shade.alpha > 0) canvas.drawRoundRect(rect, r, r, shade)
        if (convexAmt > 0f && convexBody.shader != null) {
            canvas.drawRoundRect(rect, r, r, convexBody)
            // a fényperem kicsit beljebb, mint a körív: az üveg vastagságát érzékelteti
            val ci = 1.1f * dens
            rect.inset(ci, ci)
            val cr = (r - ci).coerceAtLeast(0f)
            canvas.drawRoundRect(rect, cr, cr, convexRim)
            rect.set(b)
        }
        if (rimOn) {
            val rh = rim.strokeWidth / 2f
            rect.inset(rh, rh)
            canvas.drawRoundRect(rect, (r - rh).coerceAtLeast(0f), (r - rh).coerceAtLeast(0f), rim)
        }

        if (layer >= 0) canvas.restoreToCount(layer)
    }

    override fun setAlpha(alpha: Int) {
        if (layerAlpha == alpha) return
        layerAlpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fill.colorFilter = colorFilter
        overlay.colorFilter = colorFilter
        shade.colorFilter = colorFilter
        convexBody.colorFilter = colorFilter
        convexRim.colorFilter = colorFilter
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
