package com.example.glasstube

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.widget.LinearLayout
import kotlin.math.abs

/**
 * A panel that can be swiped away: downwards when it sits under the video, to the right when it
 * is a side panel ([sideways]). A drag starting on the list only takes over when the list is
 * already scrolled to the top (downwards case), so scrolling the list still works.
 */
class DismissPanel(context: Context) : LinearLayout(context) {

    var sideways: () -> Boolean = { false }
    var canScrollUp: () -> Boolean = { false }
    var onDismiss: () -> Unit = {}
    var onSettle: () -> Unit = {}

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var side = false
    private var velocity: VelocityTracker? = null

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX
                downY = ev.rawY
                dragging = false
                side = sideways()
                velocity?.recycle()
                velocity = VelocityTracker.obtain()
                track(ev)
            }
            MotionEvent.ACTION_MOVE -> {
                track(ev)
                val dx = ev.rawX - downX
                val dy = ev.rawY - downY
                if (side) {
                    if (dx > slop && abs(dx) > abs(dy) * 1.2f) start()
                } else {
                    if (dy > slop && abs(dy) > abs(dx) * 1.2f && !canScrollUp()) start()
                }
            }
        }
        return dragging
    }

    private fun start() {
        dragging = true
        animate().cancel()
        parent?.requestDisallowInterceptTouchEvent(true)
    }

    private fun track(ev: MotionEvent) {
        val e = MotionEvent.obtain(ev)
        e.setLocation(ev.rawX, ev.rawY)
        velocity?.addMovement(e)
        e.recycle()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // touch on the panel's own background (e.g. the header): may start a drag too
                downX = ev.rawX
                downY = ev.rawY
                side = sideways()
                velocity?.recycle()
                velocity = VelocityTracker.obtain()
                track(ev)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                track(ev)
                val dx = ev.rawX - downX
                val dy = ev.rawY - downY
                if (!dragging) {
                    if (side && dx > slop && abs(dx) > abs(dy)) start()
                    else if (!side && dy > slop && abs(dy) > abs(dx)) start()
                }
                if (dragging) {
                    if (side) translationX = dx.coerceAtLeast(0f) else translationY = dy.coerceAtLeast(0f)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                track(ev)
                velocity?.computeCurrentVelocity(1000)
                val v = if (side) velocity?.xVelocity ?: 0f else velocity?.yVelocity ?: 0f
                velocity?.recycle()
                velocity = null
                if (dragging) {
                    dragging = false
                    val moved = if (side) translationX else translationY
                    val size = if (side) width else height
                    if (moved > size * 0.25f || v > 1000f) {
                        onDismiss()
                    } else {
                        animate().translationX(0f).translationY(0f).setDuration(240)
                            .setInterpolator(android.view.animation.DecelerateInterpolator(1.6f))
                            .withEndAction { onSettle() }.start()
                    }
                }
                return true
            }
        }
        return super.onTouchEvent(ev)
    }
}
