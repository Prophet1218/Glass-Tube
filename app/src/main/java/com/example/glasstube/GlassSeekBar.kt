package com.example.glasstube

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.LinearGradient
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * The player's seek bar: just a bar (no knob), drawn at the bottom of the view. It thickens while
 * touched and glows softly around the played part, brightest at the finger. Tapping anywhere on
 * it seeks there; dragging scrubs.
 */
class GlassSeekBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val d = resources.displayMetrics.density

    var durationMs = 0L
        set(v) {
            field = v
            invalidate()
        }
    var positionMs = 0L
        set(v) {
            field = v
            if (!scrubbing) invalidate()
        }
    var bufferedMs = 0L
        set(v) {
            field = v
            invalidate()
        }

    /** Side padding of the bar (0 = edge to edge). */
    var sidePadding = 0f
        set(v) {
            field = v
            invalidate()
        }

    /** Bar thickness when not touched (thin while the player controls are hidden). */
    var restThickness = 3f * d
        set(v) {
            field = v
            invalidate()
        }

    /** While scrubbing: the position under the finger. */
    var onScrub: ((Long) -> Unit)? = null
    var onScrubStart: (() -> Unit)? = null
    /** Released: seek to this position. */
    var onSeek: ((Long) -> Unit)? = null

    var scrubbing = false
        private set
    private var scrubMs = 0L
    /** 0..1: thickness and glow of the touched state. */
    private var touch = 0f
    private var anim: ValueAnimator? = null

    /** Glow strength while not touched (0 = none). */
    var restGlow = 0f
        set(v) {
            field = v
            invalidate()
        }

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 255, 255, 255) }
    private val buffer = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(110, 255, 255, 255) }
    private val played = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val core = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var gradLeft = -1f
    private var gradRight = -1f

    private fun animateTouch(target: Float) {
        anim?.cancel()
        anim = ValueAnimator.ofFloat(touch, target).apply {
            duration = if (target > touch) 140L else 360L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                touch = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun fractionAt(x: Float): Float {
        val w = width - sidePadding * 2
        if (w <= 0f) return 0f
        return ((x - sidePadding) / w).coerceIn(0f, 1f)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (durationMs <= 0) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                scrubbing = true
                scrubMs = (fractionAt(event.x) * durationMs).toLong()
                onScrubStart?.invoke()
                onScrub?.invoke(scrubMs)
                animateTouch(1f)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                scrubMs = (fractionAt(event.x) * durationMs).toLong()
                onScrub?.invoke(scrubMs)
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                scrubMs = (fractionAt(event.x) * durationMs).toLong()
                scrubbing = false
                positionMs = scrubMs
                onSeek?.invoke(scrubMs)
                animateTouch(0f)
            }
            MotionEvent.ACTION_CANCEL -> {
                scrubbing = false
                animateTouch(0f)
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        if (width == 0) return
        val thick = restThickness + (7f * d - restThickness) * touch
        val bottom = height - 2f * d * touch - 1f * d
        val top = bottom - thick
        val cy = (top + bottom) / 2f
        val left = sidePadding
        val right = width - sidePadding
        val r = thick / 2f
        val dur = durationMs.coerceAtLeast(1)
        val pos = if (scrubbing) scrubMs else positionMs
        val px = left + (right - left) * (pos.toFloat() / dur).coerceIn(0f, 1f)
        val bx = left + (right - left) * (bufferedMs.toFloat() / dur).coerceIn(0f, 1f)

        rect.set(left, top, right, bottom)
        canvas.drawRoundRect(rect, r, r, track)
        if (bx > left) {
            rect.set(left, top, bx, bottom)
            canvas.drawRoundRect(rect, r, r, buffer)
        }

        val head = px.coerceAtLeast(left + thick)
        // comet-like glow trailing behind the head (stronger while dragging)
        val g = (restGlow + (1f - restGlow) * touch).coerceIn(0f, 1f)
        if (g > 0.01f && px > left) {
            val gr = 16f * d * (0.8f + 0.4f * touch)
            glow.shader = RadialGradient(
                0f, 0f, gr,
                intArrayOf(Color.argb((150 * g).toInt(), 225, 238, 255), Color.argb((60 * g).toInt(), 190, 215, 255), Color.TRANSPARENT),
                floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP
            )
            canvas.save()
            canvas.translate(head - gr * 1.2f, cy)
            canvas.scale(2.4f, 1f)
            canvas.drawCircle(0f, 0f, gr, glow)
            canvas.restore()
            val cr = thick * 2.2f
            core.shader = RadialGradient(
                head - r, cy, cr,
                Color.argb((200 * g).toInt(), 255, 255, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP
            )
            canvas.drawCircle(head - r, cy, cr, core)
        }
        if (px > left) {
            // light blue fading into white at the head
            if (gradLeft != left || gradRight != head) {
                gradLeft = left
                gradRight = head
                played.shader = LinearGradient(
                    left, 0f, head, 0f,
                    Color.argb(235, 160, 196, 240), Color.WHITE, Shader.TileMode.CLAMP
                )
            }
            rect.set(left, top, head, bottom)
            canvas.drawRoundRect(rect, r, r, played)
        }
    }
}
