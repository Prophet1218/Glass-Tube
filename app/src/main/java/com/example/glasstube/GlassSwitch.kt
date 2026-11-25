package com.example.glasstube

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.core.graphics.ColorUtils

/**
 * iOS-style switch with a liquid glass knob: pressed, the knob grows and turns into clear glass
 * (rim + sheen), then springs to the other side.
 */
class GlassSwitch @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val d = resources.displayMetrics.density
    private val night = Glass.isNight(context)
    private val trackW = 52f * d
    private val trackH = 31f * d
    private val knob = 27f * d

    var isChecked = false
        private set
    var onChange: ((Boolean) -> Unit)? = null

    /** 0 = off, 1 = on (overshoots a little while moving). */
    private var pos = 0f
    /** 0 = resting white knob, 1 = pressed glass knob. */
    private var press = 0f
    private var posAnim: ValueAnimator? = null
    private var pressAnim: ValueAnimator? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.2f * d }
    private val rect = RectF()
    private val offColor = if (night) Color.argb(70, 120, 120, 128) else Color.argb(40, 120, 120, 128)
    private val onColor = 0xFF34C759.toInt()

    init {
        isClickable = true
    }

    fun setChecked(value: Boolean, animate: Boolean = false) {
        if (value == isChecked && !animate) {
            // a rebind during the toggle animation must not make the knob jump
            if (posAnim?.isRunning == true) return
            pos = if (value) 1f else 0f
            invalidate()
            return
        }
        isChecked = value
        if (!animate) {
            posAnim?.cancel()
            pos = if (value) 1f else 0f
            invalidate()
        } else {
            animatePos(if (value) 1f else 0f)
        }
    }

    private fun animatePos(target: Float) {
        posAnim?.cancel()
        posAnim = ValueAnimator.ofFloat(pos, target).apply {
            duration = 360L
            interpolator = OvershootInterpolator(0.9f)
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
            duration = if (target > press) 180L else 300L
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener {
                press = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension((trackW + 12 * d).toInt(), (trackH + 12 * d).toInt())
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                animatePress(1f)
            }
            MotionEvent.ACTION_UP -> {
                animatePress(0f)
                toggleByUser()
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> animatePress(0f)
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    fun toggleByUser() {
        setChecked(!isChecked, animate = true)
        onChange?.invoke(isChecked)
    }

    override fun onDraw(canvas: Canvas) {
        val left = (width - trackW) / 2f
        val top = (height - trackH) / 2f
        val p = pos.coerceIn(0f, 1f)
        // track
        paint.color = ColorUtils.blendARGB(offColor, onColor, p)
        rect.set(left, top, left + trackW, top + trackH)
        canvas.drawRoundRect(rect, trackH / 2f, trackH / 2f, paint)

        // knob: wider and taller while pressed
        val pr = press.coerceIn(0f, 1f)
        val kw = knob + 8f * d * pr
        val kh = knob + 3f * d * pr
        val inset = (trackH - knob) / 2f
        // the knob (even the grown one) stays inside the track; the spring only squashes it
        val minC = left + inset + kw / 2f
        val maxC = left + trackW - inset - kw / 2f
        val raw = minC + (maxC - minC) * pos
        val over = if (raw < minC) minC - raw else if (raw > maxC) raw - maxC else 0f
        val cx = raw.coerceIn(minC, maxC)
        val cy = top + trackH / 2f
        val squash = (over * 0.6f).coerceAtMost(4f * d)
        val w2 = kw / 2f - squash / 2f
        val sh = if (raw < minC) -squash / 2f else squash / 2f
        rect.set(cx - w2 + sh, cy - kh / 2f, cx + w2 + sh, cy + kh / 2f)
        val r = kh / 2f
        // white knob fades into clear glass
        paint.color = Color.argb((255 * (1f - 0.8f * pr)).toInt(), 255, 255, 255)
        paint.setShadowLayer(0.5f * d + 2.5f * d * (1f - pr), 0f, 1f * d, Color.argb((50 * (1f - pr)).toInt(), 0, 0, 0))
        canvas.drawRoundRect(rect, r, r, paint)
        paint.clearShadowLayer()
        if (pr > 0.01f) {
            paint.shader = android.graphics.LinearGradient(
                0f, rect.top, 0f, rect.bottom,
                Color.argb((90 * pr).toInt(), 255, 255, 255), Color.argb((10 * pr).toInt(), 255, 255, 255),
                android.graphics.Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(rect, r, r, paint)
            paint.shader = null
            rim.color = Color.argb((200 * pr).toInt(), 255, 255, 255)
            canvas.drawRoundRect(rect, r, r, rim)
        }
    }
}
