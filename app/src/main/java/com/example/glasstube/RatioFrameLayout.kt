package com.example.glasstube

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout

/** A FrameLayout that keeps a fixed aspect ratio (16:9 by default) unless [locked] is off. */
class RatioFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    /** Width / height. */
    var ratio: Float = 16f / 9f
        set(value) {
            if (field != value) {
                field = value
                requestLayout()
            }
        }

    /** When false the view takes whatever height its parent gives it (fullscreen player). */
    var locked: Boolean = true
        set(value) {
            if (field != value) {
                field = value
                requestLayout()
            }
        }

    init {
        clipToOutline = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (!locked) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        val w = View.MeasureSpec.getSize(widthMeasureSpec)
        val h = (w / ratio).toInt()
        super.onMeasure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
        )
    }
}
