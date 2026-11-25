package com.example.glasstube

import android.content.Context
import android.graphics.Canvas
import android.view.View

/**
 * Copies of the tab icons / labels drawn inside the refracted content, right under where the
 * real ones sit. While a tab capsule is pressed the real icon under it fades out and this copy
 * fades in, so the icon is seen *through* the glass drop: magnified and bent like the rest of
 * the picture, with the capsule in front of it.
 */
class GhostLayer(context: Context) : View(context) {

    class Source(val view: View, val alpha: () -> Float)

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
            if (a < 0.01f) continue
            val v = s.view
            if (!v.isAttachedToWindow || v.width == 0) continue
            v.getLocationInWindow(loc)
            // total scale of the view on screen (its own and its parents')
            var sx = 1f
            var sy = 1f
            var cur: View? = v
            while (cur != null) {
                sx *= cur.scaleX
                sy *= cur.scaleY
                cur = cur.parent as? View
            }
            val save = canvas.saveLayerAlpha(
                (loc[0] - own[0]).toFloat(), (loc[1] - own[1]).toFloat(),
                loc[0] - own[0] + v.width * sx, loc[1] - own[1] + v.height * sy, (a * 255).toInt()
            )
            canvas.translate((loc[0] - own[0]).toFloat(), (loc[1] - own[1]).toFloat())
            canvas.scale(sx, sy)
            v.draw(canvas)
            canvas.restoreToCount(save)
        }
    }
}
