package com.example.glasstube

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.OvershootInterpolator
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils

/**
 * Segmented selector with a glass thumb that slides (and squashes a little) to the chosen option.
 * The thumb can also be dragged along the options.
 */
class GlassSegmented @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val d = resources.displayMetrics.density
    private val night = Glass.isNight(context)
    var options: List<String> = emptyList()
        set(v) {
            field = v
            invalidate()
        }
    var selected = 0
        private set
    var onSelect: ((Int) -> Unit)? = null

    /** Thumb position in option units (animated). */
    private var pos = 0f
    private var press = 0f
    private var anim: ValueAnimator? = null
    private var pressAnim: ValueAnimator? = null
    private var dragging = false
    private var downX = 0f

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.chip_bg)
    }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * d
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 14f * resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.CENTER
    }
    private val rect = RectF()
    private val textColor = Glass.textColor(context)

    fun setSelected(index: Int, animate: Boolean) {
        selected = index.coerceIn(0, (options.size - 1).coerceAtLeast(0))
        if (!animate) {
            anim?.cancel()
            pos = selected.toFloat()
            invalidate()
        } else animateTo(selected.toFloat())
    }

    private fun animateTo(target: Float) {
        anim?.cancel()
        anim = ValueAnimator.ofFloat(pos, target).apply {
            duration = 420
            interpolator = OvershootInterpolator(1.3f)
            addUpdateListener {
                pos = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun animatePress(target: Float) {
        pressAnim?.cancel()
        pressAnim = ValueAnimator.ofFloat(press, target).apply {
            duration = if (target > press) 150 else 320
            addUpdateListener {
                press = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (40 * d).toInt())
    }

    private fun segW(): Float = if (options.isEmpty()) width.toFloat() else (width - 6 * d) / options.size

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (options.isEmpty()) return false
        val sw = segW()
        fun indexAt(x: Float) = ((x - 3 * d) / sw).toInt().coerceIn(0, options.size - 1)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                downX = e.x
                dragging = false
                animatePress(1f)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && kotlin.math.abs(e.x - downX) > 8 * d) dragging = true
                if (dragging) {
                    anim?.cancel()
                    pos = ((e.x - 3 * d) / sw - 0.5f).coerceIn(0f, (options.size - 1).toFloat())
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                animatePress(0f)
                val i = if (dragging) Math.round(pos) else indexAt(e.x)
                val changed = i != selected
                setSelected(i, true)
                if (changed) onSelect?.invoke(i)
            }
            MotionEvent.ACTION_CANCEL -> {
                animatePress(0f)
                setSelected(selected, true)
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        rect.set(0f, 0f, width.toFloat(), h)
        canvas.drawRoundRect(rect, h / 2f, h / 2f, trackPaint)
        if (options.isEmpty()) return
        val sw = segW()
        val inset = 3 * d
        // thumb: grows a little and turns into clear glass while pressed
        val grow = 3 * d * press
        val l = inset + sw * pos - grow
        rect.set(l, inset - grow, l + sw + grow * 2, h - inset + grow)
        val r = rect.height() / 2f
        thumbPaint.color = if (night) {
            ColorUtils.blendARGB(Color.argb(70, 255, 255, 255), Color.argb(30, 255, 255, 255), press)
        } else {
            ColorUtils.blendARGB(Color.WHITE, Color.argb(90, 255, 255, 255), press)
        }
        thumbPaint.setShadowLayer(3 * d * (1 - press), 0f, 1 * d, Color.argb(40, 0, 0, 0))
        canvas.drawRoundRect(rect, r, r, thumbPaint)
        thumbPaint.clearShadowLayer()
        rimPaint.color = Color.argb((70 + 150 * press).toInt(), 255, 255, 255)
        canvas.drawRoundRect(rect, r, r, rimPaint)

        val fm = textPaint.fontMetrics
        val ty = h / 2f - (fm.ascent + fm.descent) / 2f
        options.forEachIndexed { i, label ->
            val near = (1f - kotlin.math.abs(pos - i)).coerceIn(0f, 1f)
            textPaint.typeface = if (near > 0.5f) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            textPaint.color = ColorUtils.setAlphaComponent(textColor, (150 + 105 * near).toInt())
            canvas.drawText(label, inset + sw * i + sw / 2f, ty, textPaint)
        }
    }
}
