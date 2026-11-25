package com.example.glasstube

import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import android.view.View

/**
 * Copies of the tab icons / labels drawn inside the refracted content, right under where the
 * real ones sit, but only inside the pressed capsule. There the real icon is cut away and this
 * copy is seen through the glass drop; outside the capsule the real icon stays as it is.
 */
class GhostLayer(context: Context) : View(context) {

    /** [clip]: the capsule in window coordinates (empty = nothing to show). */
    class Source(val view: View, val alpha: () -> Float, val clip: RectF)

    val sources = ArrayList<Source>()
    private val loc = IntArray(2)
    private val own = IntArray(2)

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(canvas: Canvas) {
        if (sources.isEmpty()) return
        getLocationInWindow(own)
        for (s in sources) {
            val a = s.alpha().coerceIn(0f, 1f)
            if (a < 0.01f || s.clip.isEmpty) continue
            val v = s.view
            if (!v.isAttachedToWindow || v.width == 0) continue
            v.getLocationInWindow(loc)
            val sx = totalScaleX(v)
            val sy = totalScaleY(v)
            val save = canvas.save()
            canvas.clipRect(s.clip.left - own[0], s.clip.top - own[1], s.clip.right - own[0], s.clip.bottom - own[1])
            canvas.saveLayerAlpha(
                (loc[0] - own[0]).toFloat(), (loc[1] - own[1]).toFloat(),
                loc[0] - own[0] + v.width * sx, loc[1] - own[1] + v.height * sy, (a * 255).toInt()
            )
            canvas.translate((loc[0] - own[0]).toFloat(), (loc[1] - own[1]).toFloat())
            canvas.scale(sx, sy)
            // draw(Canvas) ignores the view's clipBounds (they apply when its parent draws it), so the
            // copy is whole even though the real view is cut away under the capsule
            v.draw(canvas)
            canvas.restoreToCount(save)
        }
    }

    companion object {
        fun totalScaleX(v: View): Float {
            var s = 1f
            var cur: View? = v
            while (cur != null) {
                s *= cur.scaleX
                cur = cur.parent as? View
            }
            return s
        }

        fun totalScaleY(v: View): Float {
            var s = 1f
            var cur: View? = v
            while (cur != null) {
                s *= cur.scaleY
                cur = cur.parent as? View
            }
            return s
        }

        private val tmpLoc = IntArray(2)

        /**
         * Cuts the part of [v] that is under [capWin] (window coordinates) away, or shows all of
         * it when [on] is false. The capsule always spans the full height of the icon, so the
         * visible part is the strip left or right of it.
         */
        fun cutUnder(v: View, capWin: RectF, on: Boolean) {
            if (!on || capWin.isEmpty || v.width == 0) {
                if (v.clipBounds != null) v.clipBounds = null
                return
            }
            v.getLocationInWindow(tmpLoc)
            val s = totalScaleX(v)
            val l = (capWin.left - tmpLoc[0]) / s
            val r = (capWin.right - tmpLoc[0]) / s
            val w = v.width
            val h = v.height
            val rect = when {
                r <= 0f || l >= w -> null                       // not under the capsule
                l <= 0f && r >= w -> Rect(0, 0, 0, 0)           // completely under it
                l <= 0f -> Rect(Math.ceil(r.toDouble()).toInt().coerceIn(0, w), 0, w, h)
                r >= w -> Rect(0, 0, l.toInt().coerceIn(0, w), h)
                else -> Rect(0, 0, l.toInt().coerceIn(0, w), h) // capsule narrower than the view
            }
            v.clipBounds = rect
        }
    }
}
