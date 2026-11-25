package com.example.glasstube

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.CornerPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import android.view.animation.PathInterpolator

/**
 * Play / pause icon that morphs between the two shapes (the triangle splits into the two bars),
 * like the YouTube app.
 */
class PlayPauseView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        pathEffect = CornerPathEffect(1.6f * resources.displayMetrics.density)
    }
    private val path = Path()

    /** 0 = play triangle, 1 = pause bars. */
    private var morph = 0f
    private var anim: ValueAnimator? = null
    var isPause = false
        private set

    fun setColor(c: Int) {
        paint.color = c
        invalidate()
    }

    fun setPause(pause: Boolean, animate: Boolean = true) {
        if (pause == isPause && (anim?.isRunning != true)) {
            if (!animate) {
                morph = if (pause) 1f else 0f
                invalidate()
            }
            return
        }
        isPause = pause
        anim?.cancel()
        val target = if (pause) 1f else 0f
        if (!animate || !isAttachedToWindow) {
            morph = target
            invalidate()
            return
        }
        anim = ValueAnimator.ofFloat(morph, target).apply {
            duration = 260
            interpolator = PathInterpolator(0.4f, 0f, 0.2f, 1f)
            addUpdateListener {
                morph = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    // 24x24 shapes; each half is a quad (play: halves of the triangle, pause: the bars)
    private val playL = floatArrayOf(8f, 5f, 13.5f, 8.5f, 13.5f, 15.5f, 8f, 19f)
    private val pauseL = floatArrayOf(6.5f, 5f, 10.5f, 5f, 10.5f, 19f, 6.5f, 19f)
    private val playR = floatArrayOf(13.5f, 8.5f, 19.5f, 12f, 19.5f, 12f, 13.5f, 15.5f)
    private val pauseR = floatArrayOf(13.5f, 5f, 17.5f, 5f, 17.5f, 19f, 13.5f, 19f)

    private fun quad(a: FloatArray, b: FloatArray, t: Float, s: Float, ox: Float, oy: Float) {
        for (i in 0 until 4) {
            val x = (a[i * 2] + (b[i * 2] - a[i * 2]) * t) * s + ox
            val y = (a[i * 2 + 1] + (b[i * 2 + 1] - a[i * 2 + 1]) * t) * s + oy
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
    }

    override fun onDraw(canvas: Canvas) {
        val size = minOf(width, height).toFloat()
        val s = size / 24f
        val ox = (width - size) / 2f
        val oy = (height - size) / 2f
        path.reset()
        quad(playL, pauseL, morph, s, ox, oy)
        quad(playR, pauseR, morph, s, ox, oy)
        canvas.drawPath(path, paint)
    }
}
