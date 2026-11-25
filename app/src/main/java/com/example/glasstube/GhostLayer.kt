package com.example.glasstube

import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import android.view.View

/**
 * The tab icons / labels as seen through the pressed capsule: drawn over the bar, clipped to the
 * capsule's shape and a little magnified around its centre, while the real ones are cut away
 * exactly under it. So the drop is in front of the icons, and they are never smeared or doubled.
 */
class GhostLayer(context: Context) : View(context) {

    /**
     * [clip]: the capsule in window coordinates (empty = nothing to show); [magnify]: how much
     * bigger the icon looks through the glass, around the capsule's centre.
     */
    class Source(val view: View, val alpha: () -> Float, val clip: RectF, val magnify: () -> Float)

    val sources = ArrayList<Source>()
    private val loc = IntArray(2)
    private val own = IntArray(2)
    private val path = android.graphics.Path()
    private val inset = resources.displayMetrics.density * 1.5f

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
            val cl = s.clip.left - own[0] + inset
            val ct = s.clip.top - own[1] + inset
            val cr = s.clip.right - own[0] - inset
            val cb = s.clip.bottom - own[1] - inset
            if (cr <= cl || cb <= ct) continue
            val save = canvas.save()
            // inside the capsule only (its rim stays visible around it)
            path.reset()
            val r = (cb - ct) / 2f
            path.addRoundRect(cl, ct, cr, cb, r, r, android.graphics.Path.Direction.CW)
            canvas.clipPath(path)
            // magnified around the capsule's centre, as if seen through the drop
            val m = s.magnify()
            canvas.scale(m, m, (cl + cr) / 2f, (ct + cb) / 2f)
            if (a < 0.99f) canvas.saveLayerAlpha(cl, ct, cr, cb, (a * 255).toInt())
            canvas.translate((loc[0] - own[0]).toFloat(), (loc[1] - own[1]).toFloat())
            canvas.scale(sx, sy)
            // draw(Canvas) ignores the view's clipBounds (they apply when its parent draws it), so
            // the copy is whole even though the real view is cut away under the capsule
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
