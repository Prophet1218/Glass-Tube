package com.example.glasstube

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Choreographer
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * A small copy of the bottom tab bar for the tabs at the top of the home page: a glass capsule
 * with text tabs and the same capsule underneath the selected one. Pressed, the capsule grows a
 * little past the bar into a magnifying drop of glass; it follows the finger, stretches while it
 * moves and springs onto the chosen tab. The activity puts the lens under [barRect] and
 * [capRect] (see [pressAmount]).
 */
class TopTabs(context: Context, labels: List<String>, private val lensOn: Boolean) : FrameLayout(context) {

    var onSelect: (Int) -> Unit = {}
    var onReselect: (Int) -> Unit = {}
    /** Called every frame while something moves, so the activity can redraw its lens. */
    var onFrame: () -> Unit = {}

    var selected = 0
        private set

    private val d = resources.displayMetrics.density
    private fun dpf(v: Float) = v * d

    private val bg = HoleDrawable(Glass.skin(context, null, lensOn))
    private val indicator = View(context)
    private val row = LinearLayout(context)
    private val texts = ArrayList<TextView>()
    private val frost: GradientDrawable
    private val glass: GlassSkinDrawable
    private val night = Glass.isNight(context)

    private var capX = 0f
    private var capV = 0f
    private var capTarget = 0f
    private var press = 0f
    private var pressV = 0f
    private var pressTarget = 0f
    private var stretch = 0f
    private var running = false
    private var lastFrame = 0L
    private var dragging = false
    private var downX = 0f
    private var placed = false
    private var layingOut = false

    val labelViews: List<TextView> get() = texts
    /** The capsule in window coordinates (for the copies under the glass). */
    val capWin = RectF()
    private val winLoc = IntArray(2)
    private val ghost = FloatArray(labels.size)
    fun ghostAlpha(i: Int): Float = ghost.getOrElse(i) { 0f }

    /** Capsule (pressed: bigger, past the bar) in this view's coordinates. */
    val capRect = RectF()
    val pressAmount: Float get() = press.coerceAtLeast(0f)

    init {
        clipChildren = false
        clipToPadding = false
        background = bg
        frost = GradientDrawable().apply {
            cornerRadius = dpf(100f)
            setColor(if (night) Color.argb(46, 255, 255, 255) else Color.argb(22, 0, 0, 0))
        }
        glass = GlassSkinDrawable(d).apply {
            cornerRadius = null
            setTint(0.12f, null)
            setRim(true)
            setContentColor(if (night) Color.WHITE else Color.argb(200, 255, 255, 255))
            alpha = 0
        }
        indicator.background = LayerDrawable(arrayOf(frost, glass))
        addView(indicator, LayoutParams(0, 0))
        row.orientation = LinearLayout.HORIZONTAL
        labels.forEach { label ->
            val t = TextView(context)
            t.text = label
            t.textSize = 13f
            t.gravity = Gravity.CENTER
            t.maxLines = 1
            t.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            texts.add(t)
            row.addView(t, LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        }
        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        indicator.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> if (!layingOut) layoutCapsule() }
    }

    private fun tabW(): Float = width / texts.size.toFloat()
    private fun center(i: Int): Float = tabW() * i + tabW() / 2f

    fun select(i: Int, animate: Boolean) {
        selected = i.coerceIn(0, texts.size - 1)
        capTarget = center(selected)
        if (!animate || !placed) {
            capX = capTarget
            capV = 0f
            visuals()
        } else start()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        capTarget = center(selected)
        capX = capTarget
        placed = true
        post { visuals() }
    }

    // ---- spring physics (same feel as the bottom bar)

    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            val dt = if (lastFrame == 0L) 1f / 120f else ((frameTimeNanos - lastFrame) / 1e9f).coerceIn(0.001f, 1f / 30f)
            lastFrame = frameTimeNanos
            step(dt)
            if (settled()) {
                capX = capTarget
                capV = 0f
                press = pressTarget
                pressV = 0f
                stretch = 0f
                running = false
                lastFrame = 0L
                visuals()
            } else {
                visuals()
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
    }

    private fun start() {
        if (running) return
        running = true
        lastFrame = 0L
        Choreographer.getInstance().postFrameCallback(frame)
    }

    private fun settled() = abs(capX - capTarget) < 0.3f && abs(capV) < dpf(4f) &&
        abs(press - pressTarget) < 0.002f && abs(pressV) < 0.02f && stretch < 0.002f

    private fun step(dt: Float) {
        val kx = if (dragging) 1100f else 420f
        val zx = if (dragging) 0.92f else 0.74f
        val growing = pressTarget > press
        val kp = if (growing) 750f else 440f
        val zp = if (growing) 0.55f else 0.82f
        val h = dt / 4
        repeat(4) {
            capV += (-kx * (capX - capTarget) - 2f * zx * sqrt(kx) * capV) * h
            capX += capV * h
            pressV += (-kp * (press - pressTarget) - 2f * zp * sqrt(kp) * pressV) * h
            press += pressV * h
        }
        val want = (abs(capV) / dpf(1200f)).coerceIn(0f, 1f)
        stretch += (want - stretch) * (1f - exp(-dt * 18f))
        if (stretch < 0.001f && want == 0f) stretch = 0f
    }

    // ---- visuals

    private fun layoutCapsule() {
        if (width == 0 || texts.isEmpty()) return
        val p = press.coerceAtLeast(0f)
        val iw = tabW() - dpf(6f)
        val ih = height - dpf(6f)
        val wf = 1f + 0.2f * p + 0.16f * stretch
        val hf = (1f + 0.34f * p - 0.1f * stretch).coerceAtMost(1.4f)
        var l = capX - iw * wf / 2f
        var r = capX + iw * wf / 2f
        val out = dpf(3f) - dpf(6f) * p.coerceIn(0f, 1f)
        if (l < out) l = out
        if (r > width - out) r = width - out
        if (r - l < iw * 0.8f) r = l + iw * 0.8f
        val cy = height / 2f
        val hh = ih * hf / 2f
        capRect.set(l, cy - hh, r, cy + hh)
        layingOut = true
        indicator.layout(Math.round(l), Math.round(cy - hh), Math.round(r), Math.round(cy + hh))
        layingOut = false
    }

    private fun visuals() {
        layoutCapsule()
        val p = press.coerceAtLeast(0f)
        frost.alpha = (255 * (1f - p).coerceIn(0f, 1f)).toInt()
        glass.alpha = (255 * p.coerceIn(0f, 1f)).toInt()
        if (width > 0) {
            indicator.getLocationInWindow(winLoc)
            capWin.set(winLoc[0].toFloat(), winLoc[1].toFloat(), winLoc[0] + indicator.width.toFloat(), winLoc[1] + indicator.height.toFloat())
        }
        val text = Glass.textColor(context)
        val dim = ColorUtils.setAlphaComponent(text, 150)
        val tw = tabW()
        texts.forEachIndexed { i, t ->
            val near = if (tw > 0f) (1f - abs(center(i) - capX) / tw).coerceIn(0f, 1f) else if (i == selected) 1f else 0f
            t.setTextColor(ColorUtils.blendARGB(dim, text, near))
            val s = 1f + 0.1f * p.coerceIn(0f, 1.2f) * near
            t.scaleX = s
            t.scaleY = s
            t.translationX = 0f
            // under the pressed glass the label is shown by its copy inside the refracted picture;
            // the real one is cut away exactly where the capsule covers it
            val g = if (p > 0.01f) 1f else 0f
            ghost[i] = g
            t.alpha = 1f
            GhostLayer.cutUnder(t, capWin, g > 0f)
            t.setShadowLayer(if (night) dpf(6f) * near else 0f, 0f, 0f, Color.argb((150 * near).toInt(), 255, 255, 255))
        }
        if (p > 0.01f) bg.setHole(capRect, capRect.height() / 2f, p.coerceIn(0f, 1f)) else bg.setHole(null, 0f)
        invalidate()
        onFrame()
    }

    // ---- touch

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                downX = ev.x
                dragging = false
                pressTarget = 1f
                start()
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && abs(ev.x - downX) > slop) dragging = true
                if (dragging) {
                    capTarget = ev.x.coerceIn(center(0), center(texts.size - 1))
                    start()
                }
            }
            MotionEvent.ACTION_UP -> {
                val x = if (dragging) capTarget else ev.x
                val target = (x / tabW()).toInt().coerceIn(0, texts.size - 1)
                val tapped = abs(ev.x - downX) <= slop
                dragging = false
                pressTarget = 0f
                val old = selected
                select(target, true)
                start()
                if (target != old) onSelect(target) else if (tapped) onReselect(target)
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                pressTarget = 0f
                select(selected, true)
                start()
            }
        }
        return true
    }
}
