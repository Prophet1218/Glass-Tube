package com.example.glasstube

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.CircleCropTransformation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.linkhandler.SearchQueryHandler
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import kotlin.math.abs

/** Draws [inner] with a rounded hole cut out (the tab bar under the pressed glass capsule). */
class HoleDrawable(private val inner: Drawable) : Drawable() {
    private val path = Path()
    private val rect = RectF()
    private var hole = false

    fun setHole(r: RectF?, radius: Float) {
        if (r == null) {
            if (!hole) return
            hole = false
        } else {
            hole = true
            rect.set(r)
            path.reset()
            path.addRoundRect(rect, radius, radius, Path.Direction.CW)
        }
        invalidateSelf()
    }

    override fun onBoundsChange(bounds: Rect) {
        inner.bounds = bounds
    }

    override fun draw(canvas: Canvas) {
        if (hole && Build.VERSION.SDK_INT >= 26) {
            val save = canvas.save()
            canvas.clipOutPath(path)
            inner.draw(canvas)
            canvas.restoreToCount(save)
        } else {
            inner.draw(canvas)
        }
    }

    override fun setAlpha(alpha: Int) {
        inner.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        inner.colorFilter = colorFilter
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

class MainActivity : AppCompatActivity(), PlayerSheet.Host {

    private enum class Tab { HOME, SHORTS, YOU }

    private lateinit var root: FrameLayout
    private lateinit var content: FrameLayout
    private lateinit var homeList: RecyclerView
    private lateinit var youList: RecyclerView
    private lateinit var searchList: RecyclerView
    private lateinit var shortsList: RecyclerView
    private lateinit var pageHost: FrameLayout
    private lateinit var suggestions: LinearLayout
    private lateinit var dock: FrameLayout
    private lateinit var tabBar: FrameLayout
    private lateinit var tabIndicator: View
    private lateinit var tabs: LinearLayout
    private lateinit var collapsedIcon: ImageView
    private lateinit var searchButton: FrameLayout
    private lateinit var searchBack: FrameLayout
    private lateinit var searchBar: LinearLayout
    private lateinit var field: EditText
    private lateinit var clear: ImageView
    private lateinit var mini: LinearLayout
    private lateinit var miniTitle: TextView
    private lateinit var miniChannel: TextView
    private lateinit var miniPlay: ImageView
    private lateinit var miniClose: ImageView

    private lateinit var homeAdapter: VideoAdapter
    private lateinit var youAdapter: VideoAdapter
    private lateinit var searchAdapter: VideoAdapter
    private lateinit var history: SearchHistory
    private lateinit var store: Store
    private lateinit var settings: Settings
    private lateinit var shorts: ShortsPager
    private lateinit var sheet: PlayerSheet

    private var lens: LensEffect? = null
    private var lensOn = false
    private var statusTop = 0
    private var navBottom = 0
    private var imeBottom = 0

    private var tab = Tab.HOME
    private var searchActive = false
    private var searchAnim: ValueAnimator? = null
    private val tabViews = ArrayList<LinearLayout>()
    private var youIcon: ImageView? = null
    private var tabBarBg: HoleDrawable? = null
    private var indicatorFrost: GradientDrawable? = null
    private var indicatorGlass: GlassSkinDrawable? = null

    // collapsing tab bar
    private var barCollapse = 0f
    private var collapseTarget = 0f
    private var collapseAnim: ValueAnimator? = null

    // pressed / dragged glass capsule
    private var pressP = 0f
    private var pressAnim: ValueAnimator? = null
    private var indicatorAnim: ValueAnimator? = null
    private var touchDownX = 0f
    private var draggingTab = false

    // mini player
    private var miniShown = false
    private var sheetProgress = 0f

    private val yt = ServiceList.YouTube

    // search
    private var currentQuery: String? = null
    private var queryHandler: SearchQueryHandler? = null
    private var nextPage: Page? = null
    private var searchJob: Job? = null
    private var moreJob: Job? = null
    private var suggestJob: Job? = null

    // home
    private var homeChip = 0
    private var homeContinuation: String? = null
    private var homeJob: Job? = null
    private var homeMoreJob: Job? = null
    private var homeLoaded = false

    /** Pushed pages (history, playlists, settings...), topmost last. */
    private val pages = ArrayList<PageView>()

    private val density get() = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()
    private fun dpf(v: Float) = v * density

    private val signIn = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) onAccountChanged()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        setContentView(R.layout.activity_main)

        root = findViewById(R.id.root)
        content = findViewById(R.id.content)
        homeList = findViewById(R.id.homeList)
        youList = findViewById(R.id.youList)
        searchList = findViewById(R.id.searchList)
        shortsList = findViewById(R.id.shortsList)
        pageHost = findViewById(R.id.pageHost)
        suggestions = findViewById(R.id.suggestions)
        dock = findViewById(R.id.dock)
        tabBar = findViewById(R.id.tabBar)
        tabIndicator = findViewById(R.id.tabIndicator)
        tabs = findViewById(R.id.tabs)
        collapsedIcon = findViewById(R.id.collapsedIcon)
        searchButton = findViewById(R.id.searchButton)
        searchBack = findViewById(R.id.searchBack)
        searchBar = findViewById(R.id.searchBar)
        field = findViewById(R.id.field)
        clear = findViewById(R.id.clear)
        mini = findViewById(R.id.mini)
        miniTitle = findViewById(R.id.miniTitle)
        miniChannel = findViewById(R.id.miniChannel)
        miniPlay = findViewById(R.id.miniPlay)
        miniClose = findViewById(R.id.miniClose)
        history = SearchHistory(this)
        store = Store(this)
        settings = Settings(this)

        homeAdapter = VideoAdapter(onVideo = { openVideo(it) })
        youAdapter = VideoAdapter(onVideo = { openVideo(it) })
        searchAdapter = VideoAdapter(
            onVideo = { openVideo(it) },
            onHistory = { search(it) },
            onHistoryLong = {
                history.remove(it)
                if (currentQuery == null) showSearchIdle()
            }
        )
        for ((rv, ad) in listOf(homeList to homeAdapter, youList to youAdapter, searchList to searchAdapter)) {
            rv.layoutManager = LinearLayoutManager(this)
            rv.adapter = ad
            rv.itemAnimator = null
        }
        shorts = ShortsPager(this, shortsList, { navBottom + dp(56) + dp(12) }) { openVideo(it) }

        setupGlass()
        setupTabs()
        setupMini()
        setupSearch()
        setupInsets()
        setupScrolling()
        sheet = PlayerSheet(this, root, this)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    sheet.handleBack() -> Unit
                    field.hasFocus() -> leaveField()
                    pages.isNotEmpty() -> popPage()
                    searchActive -> collapseSearch()
                    barCollapse > 0.5f -> setBarCollapsed(false)
                    tab != Tab.HOME -> selectTab(Tab.HOME)
                    else -> finish()
                }
            }
        })

        selectTab(Tab.HOME, animate = false)
        loadHome()
        refreshAccountInBackground()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val url = intent?.getStringExtra(PlayerActivity.EXTRA_URL) ?: return
        intent.removeExtra(PlayerActivity.EXTRA_URL)
        if (sheet.currentUrl == url) sheet.expand() else sheet.open(url)
    }

    override fun onStart() {
        super.onStart()
        if (tab == Tab.SHORTS && pages.isEmpty() && !searchActive) shorts.onShown()
        sheet.onStart()
    }

    override fun onStop() {
        super.onStop()
        shorts.onHidden()
        sheet.onStop(isChangingConfigurations)
    }

    override fun onResume() {
        super.onResume()
        if (tab == Tab.YOU) buildYou()
        if (searchActive && currentQuery == null && !field.hasFocus()) showSearchIdle()
    }

    override fun onDestroy() {
        shorts.release()
        sheet.onDestroy(isFinishing)
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        sheet.onConfigurationChanged(newConfig)
        dock.post { applyDockLayout() }
    }

    // ================================================================ player sheet host

    override fun statusTop(): Int = statusTop
    override fun navBottom(): Int = navBottom

    override fun miniThumbRect(out: RectF): Boolean {
        if (!miniShown || root.height == 0) return false
        val lp = mini.layoutParams as FrameLayout.LayoutParams
        val top = root.height - lp.bottomMargin - dp(56)
        val left = lp.marginStart + dp(7)
        val t = top + (dp(56) - dp(42)) / 2f
        out.set(left.toFloat(), t, left + dpf(75f), t + dpf(42f))
        return true
    }

    override fun onSheetProgress(f: Float) {
        val wasDark = sheetProgress > 0.5f
        sheetProgress = f
        dock.alpha = 1f - f
        dock.translationY = f * dpf(90f)
        mini.alpha = (1f - f * 2.2f).coerceIn(0f, 1f)
        if (f > 0.01f && suggestions.visibility == View.VISIBLE) hideSuggestions()
        if ((f > 0.5f) != wasDark) applyBars()
        if (f > 0.5f && tab == Tab.SHORTS) shorts.onHidden()
        if (f < 0.01f && wasDark && tab == Tab.SHORTS && !searchActive && pages.isEmpty()) shorts.onShown()
        root.invalidate()
    }

    override fun onNowPlaying(info: StreamInfo?) {
        if (info == null) {
            miniShown = false
        } else {
            miniShown = true
            miniTitle.text = info.name
            miniChannel.text = info.uploaderName
        }
        placeMini()
        applyListPadding()
    }

    override fun onPlayingChanged(playing: Boolean) {
        miniPlay.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
    }

    override fun onSheetClosed() {
        miniShown = false
        placeMini()
        applyListPadding()
        applyBars()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupMini() {
        mini.background = Glass.skin(this, null, lensOn)
        val text = Glass.textColor(this)
        miniPlay.setColorFilter(text)
        miniClose.setColorFilter(ColorUtils.setAlphaComponent(text, 170))
        miniPlay.setOnClickListener { sheet.togglePlay() }
        miniClose.setOnClickListener { sheet.close() }
        mini.setOnTouchListener { _, ev -> sheet.forwardMiniTouch(ev) }
    }

    /** Mini player above the tab bar, or inline between the collapsed bar and search. */
    private fun placeMini() {
        if (!miniShown) {
            mini.visibility = View.GONE
            root.invalidate()
            return
        }
        mini.visibility = View.VISIBLE
        val c = if (searchActive) 0f else barCollapse.coerceIn(0f, 1f)
        val dockBottom = (dock.layoutParams as FrameLayout.LayoutParams).bottomMargin
        val above = dockBottom + dp(56) + dp(8)
        val lp = mini.layoutParams as FrameLayout.LayoutParams
        lp.bottomMargin = (above + (dockBottom - above) * c).toInt()
        val side = (dp(16) + (dp(16 + 56 + 8) - dp(16)) * c).toInt()
        lp.marginStart = side
        lp.marginEnd = side
        mini.layoutParams = lp
        miniClose.alpha = (1f - c * 2f).coerceIn(0f, 1f)
        miniClose.visibility = if (c > 0.5f) View.GONE else View.VISIBLE
        if (::sheet.isInitialized && sheet.progress < 0.999f) sheet.applyGeometry()
        root.invalidate()
    }

    private fun applyBars() {
        val dark = sheetProgress > 0.5f || (tab == Tab.SHORTS && !searchActive && pages.isEmpty()) || Glass.isNight(this)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !(tab == Tab.SHORTS && !searchActive && pages.isEmpty()) && !Glass.isNight(this@MainActivity)
        }
    }

    // ================================================================ glass

    private fun setupGlass() {
        lensOn = Glass.lensSupported() && try {
            lens = LensEffect(content).apply {
                // same defaults as Glass Browser's "liquid" glass
                setParams(dpf(30f), dpf(30f), 0f)
                setRim(0, 1f)
                setConvex(0f, 0f)
            }
            true
        } catch (e: Throwable) {
            lens = null
            false
        }
        applySkins()
        root.viewTreeObserver.addOnPreDrawListener {
            updateLens()
            true
        }
    }

    private fun applySkins() {
        val bg = HoleDrawable(Glass.skin(this, null, lensOn))
        tabBarBg = bg
        tabBar.background = bg
        tabBar.outlineProvider = null
        searchButton.background = Glass.skin(this, null, lensOn)
        searchBack.background = Glass.skin(this, null, lensOn)
        searchBar.background = Glass.skin(this, null, lensOn)
        suggestions.background = Glass.skin(this, dpf(24f), lensOn)
        suggestions.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, dpf(24f))
            }
        }
        suggestions.clipToOutline = true
        val night = Glass.isNight(this)
        // resting capsule: frosted; pressed: clear liquid glass (rim + sheen over the lens)
        val frost = GradientDrawable().apply {
            cornerRadius = dpf(23f)
            setColor(if (night) Color.argb(46, 255, 255, 255) else Color.argb(22, 0, 0, 0))
        }
        val glass = GlassSkinDrawable(density).apply {
            cornerRadius = null
            setTint(0.35f, null)
            setRim(true)
            setContentColor(if (night) Color.WHITE else Color.argb(200, 255, 255, 255))
            alpha = 0
        }
        indicatorFrost = frost
        indicatorGlass = glass
        tabIndicator.background = LayerDrawable(arrayOf(frost, glass))
        val text = Glass.textColor(this)
        findViewById<ImageView>(R.id.searchButtonIcon).setColorFilter(text)
        findViewById<ImageView>(R.id.searchBackIcon).setColorFilter(text)
    }

    private val tmp = RectF()
    private val loc = IntArray(2)
    private val loc2 = IntArray(2)

    private fun rectOf(v: View, out: RectF) {
        v.getLocationInWindow(loc)
        content.getLocationInWindow(loc2)
        val l = (loc[0] - loc2[0]).toFloat()
        val t = (loc[1] - loc2[1]).toFloat()
        out.set(l, t, l + v.width * v.scaleX, t + v.height * v.scaleY)
    }

    private fun slot(l: LensEffect, index: Int, v: View, radius: Float?, extra: Float = 1f) {
        if (v.visibility != View.VISIBLE || v.alpha < 0.02f || v.width == 0 || extra < 0.02f) {
            l.clearSlot(index)
            return
        }
        var a = v.alpha * extra
        var p = v.parent as? View
        while (p != null && p !== root) {
            a *= p.alpha
            p = p.parent as? View
        }
        rectOf(v, tmp)
        val r = radius ?: (tmp.height() / 2f)
        l.setSlot(index, tmp.left, tmp.top, tmp.right, tmp.bottom, r, dpf(2f), a.coerceIn(0f, 1f))
    }

    private fun updateLens() {
        val l = lens ?: return
        val dockOn = dock.visibility == View.VISIBLE && dock.alpha > 0.02f
        if (dockOn) {
            slot(l, 0, tabIndicator, null, pressP)
            slot(l, 1, tabBar, null)
            slot(l, 2, if (searchBar.visibility == View.VISIBLE) searchBar else searchButton, null)
            slot(l, 3, searchBack, null)
        } else {
            for (i in 0..3) l.clearSlot(i)
        }
        val top = pages.lastOrNull()
        if (suggestions.visibility == View.VISIBLE) slot(l, 4, suggestions, dpf(24f))
        else if (top != null) slot(l, 4, top.back, null)
        else l.clearSlot(4)
        slot(l, 5, mini, null)

        // content scrolled under the status bar (and a page's header) flows into a soft blur
        val night = Glass.isNight(this)
        val tint = if (night) Color.argb(90, 0, 0, 0) else Color.argb(70, 255, 255, 255)
        val list = visibleList()
        when {
            top != null -> {
                if (top.list.computeVerticalScrollOffset() > 0) {
                    l.setTop((statusTop + dp(58)).toFloat(), dpf(18f), dpf(14f), tint, 0f, true)
                } else {
                    l.setTop(0f, 0f, 0f, 0, 0f, false)
                }
            }
            list != null && list !== shortsList && statusTop > 0 && list.computeVerticalScrollOffset() > 0 ->
                l.setTop(statusTop.toFloat(), dpf(18f), dpf(10f), tint, 0f, true)
            else -> l.setTop(0f, 0f, 0f, 0, 0f, false)
        }
        l.apply()
    }

    private fun visibleList(): RecyclerView? = when {
        pages.isNotEmpty() -> pages.last().list
        searchActive -> searchList
        tab == Tab.HOME -> homeList
        tab == Tab.YOU -> youList
        tab == Tab.SHORTS -> shortsList
        else -> null
    }

    // ================================================================ tabs

    private val tabIcons = listOf(R.drawable.ic_home, R.drawable.ic_shorts, R.drawable.ic_person)

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTabs() {
        val labels = listOf(R.string.tab_home, R.string.tab_shorts, R.string.tab_you)
        Tab.values().forEachIndexed { i, t ->
            val item = LinearLayout(this)
            item.orientation = LinearLayout.VERTICAL
            item.gravity = Gravity.CENTER
            val iv = ImageView(this)
            iv.setImageResource(tabIcons[i])
            item.addView(iv, LinearLayout.LayoutParams(dp(23), dp(23)))
            val tv = TextView(this)
            tv.text = getString(labels[i])
            tv.textSize = 10f
            tv.gravity = Gravity.CENTER
            tv.setPadding(0, dp(1), 0, 0)
            item.addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            item.contentDescription = tv.text
            tabs.addView(item, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            tabViews.add(item)
            if (t == Tab.YOU) youIcon = iv
        }
        tabs.setOnTouchListener { _, ev -> onTabTouch(ev) }
        collapsedIcon.setOnClickListener { setBarCollapsed(false) }
        dock.addOnLayoutChangeListener { _, l, _, r, _, ol, _, or, _ ->
            if (r - l != or - ol) dock.post {
                applyDockLayout()
                placeIndicator(false)
            }
        }
        updateYouIcon()
    }

    private fun fullBarWidth(): Int = (dock.width - dp(64)).coerceAtLeast(dp(56))
    private fun tabWidth(): Float = fullBarWidth() / 3f
    private fun indicatorWidth(): Float = tabWidth() - dpf(8f)

    private fun onTabTouch(ev: MotionEvent): Boolean {
        if (barCollapse > 0.5f) {
            if (ev.actionMasked == MotionEvent.ACTION_UP) setBarCollapsed(false)
            return true
        }
        val tw = tabWidth()
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tabs.parent?.requestDisallowInterceptTouchEvent(true)
                touchDownX = ev.x
                draggingTab = false
                animatePress(1f)
                moveIndicatorCenter(ev.x, animate = true)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!draggingTab && abs(ev.x - touchDownX) > ViewConfiguration.get(this).scaledTouchSlop) draggingTab = true
                if (draggingTab) moveIndicatorCenter(ev.x, animate = false)
            }
            MotionEvent.ACTION_UP -> {
                val target = Tab.values()[(ev.x / tw).toInt().coerceIn(0, 2)]
                animatePress(0f)
                if (searchActive) collapseSearch()
                while (pages.isNotEmpty()) popPage(animate = false)
                if (target == tab && !draggingTab) {
                    placeIndicator(true)
                    onTabReselected()
                } else if (target == tab) {
                    placeIndicator(true)
                } else {
                    selectTab(target)
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                animatePress(0f)
                placeIndicator(true)
            }
        }
        return true
    }

    private fun moveIndicatorCenter(x: Float, animate: Boolean) {
        val iw = indicatorWidth()
        val target = (x - iw / 2f).coerceIn(-dpf(6f), fullBarWidth() - iw + dpf(6f))
        indicatorAnim?.cancel()
        if (animate) {
            indicatorAnim = ValueAnimator.ofFloat(tabIndicator.translationX, target).apply {
                duration = 160
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    tabIndicator.translationX = it.animatedValue as Float
                    updateTabVisuals()
                }
                start()
            }
        } else {
            tabIndicator.translationX = target
            updateTabVisuals()
        }
    }

    private fun animatePress(target: Float) {
        pressAnim?.cancel()
        pressAnim = ValueAnimator.ofFloat(pressP, target).apply {
            duration = if (target > pressP) 220L else 420L
            interpolator = OvershootInterpolator(if (target > pressP) 1.4f else 1.1f)
            addUpdateListener {
                pressP = it.animatedValue as Float
                updateTabVisuals()
            }
            start()
        }
    }

    /** Capsule size / glassiness and the tab icons under it. */
    private fun updateTabVisuals() {
        val p = pressP.coerceAtLeast(0f)
        tabIndicator.scaleX = 1f + 0.16f * p
        tabIndicator.scaleY = 1f + 0.42f * p
        indicatorFrost?.alpha = (255 * (1f - p).coerceIn(0f, 1f)).toInt()
        indicatorGlass?.alpha = (255 * p.coerceIn(0f, 1f)).toInt()
        val tw = tabWidth()
        val center = tabIndicator.translationX + indicatorWidth() / 2f
        val text = Glass.textColor(this)
        val accent = ContextCompat.getColor(this, R.color.accent)
        tabViews.forEachIndexed { i, v ->
            val tc = tw * i + tw / 2f
            val near = (1f - abs(tc - center) / tw).coerceIn(0f, 1f)
            val s = 1f + 0.16f * p.coerceIn(0f, 1.2f) * near
            v.scaleX = s
            v.scaleY = s
            val active = if (p > 0.05f) near > 0.5f else i == tab.ordinal
            val col = if (active) accent else text
            val iv = v.getChildAt(0) as ImageView
            if (!(i == Tab.YOU.ordinal && Account.isSignedIn(this) && Account.photo(this) != null)) iv.setColorFilter(col)
            (v.getChildAt(1) as TextView).setTextColor(col)
        }
        // the bar's frost gets a hole under the clear glass capsule
        if (p > 0.25f) {
            val w = tabIndicator.width
            val h = tabIndicator.height
            val sx = tabIndicator.scaleX
            val sy = tabIndicator.scaleY
            val l = tabIndicator.left + tabIndicator.translationX + w * (1f - sx) / 2f
            val t = tabIndicator.top + h * (1f - sy) / 2f
            tmp.set(l, t, l + w * sx, t + h * sy)
            tabBarBg?.setHole(tmp, tmp.height() / 2f)
        } else {
            tabBarBg?.setHole(null, 0f)
        }
        root.invalidate()
    }

    private fun placeIndicator(animate: Boolean) {
        if (dock.width == 0) return
        val tw = tabWidth()
        val lp = tabIndicator.layoutParams
        val iw = indicatorWidth().toInt()
        if (lp.width != iw) {
            lp.width = iw
            tabIndicator.layoutParams = lp
        }
        val x = tw * tab.ordinal + dpf(4f)
        indicatorAnim?.cancel()
        if (animate) {
            indicatorAnim = ValueAnimator.ofFloat(tabIndicator.translationX, x).apply {
                duration = 420L
                interpolator = OvershootInterpolator(1.3f)
                addUpdateListener {
                    tabIndicator.translationX = it.animatedValue as Float
                    updateTabVisuals()
                }
                start()
            }
        } else {
            tabIndicator.translationX = x
            updateTabVisuals()
        }
    }

    // ---- collapsing on scroll

    private fun setBarCollapsed(collapsed: Boolean) {
        val target = if (collapsed) 1f else 0f
        if (collapseTarget == target && (collapseAnim?.isRunning == true || barCollapse == target)) return
        collapseTarget = target
        collapseAnim?.cancel()
        collapseAnim = ValueAnimator.ofFloat(barCollapse, target).apply {
            duration = 420L
            interpolator = OvershootInterpolator(0.9f)
            addUpdateListener {
                barCollapse = it.animatedValue as Float
                applyDockLayout()
            }
            start()
        }
    }

    private fun applyDockLayout() {
        if (dock.width == 0) return
        val full = fullBarWidth()
        val small = dp(56)
        val c = barCollapse
        val w = (full + (small - full) * c).toInt().coerceAtLeast(small / 2)
        val lp = tabBar.layoutParams
        if (lp.width != w) {
            lp.width = w
            tabBar.layoutParams = lp
        }
        val tl = tabs.layoutParams
        if (tl.width != full) {
            tl.width = full
            tabs.layoutParams = tl
        }
        tabs.clipBounds = Rect(0, 0, w, dock.height)
        val fade = (1f - c * 1.8f).coerceIn(0f, 1f)
        tabs.alpha = fade
        tabIndicator.alpha = fade
        collapsedIcon.alpha = ((c - 0.45f) / 0.55f).coerceIn(0f, 1f)
        collapsedIcon.visibility = if (c > 0.3f) View.VISIBLE else View.GONE
        placeMini()
        root.invalidate()
    }

    private fun selectTab(t: Tab, animate: Boolean = true) {
        val old = tab
        tab = t
        placeIndicator(animate)
        collapsedIcon.setImageResource(tabIcons[t.ordinal])
        collapsedIcon.setColorFilter(ContextCompat.getColor(this, R.color.accent))
        if (old == Tab.SHORTS && t != Tab.SHORTS) shorts.onHidden()
        val from = listFor(old)
        showContent()
        if (animate && old != t && pages.isEmpty() && !searchActive) crossfade(from, listFor(t), if (t.ordinal > old.ordinal) 1 else -1)
        if (t == Tab.SHORTS) {
            shorts.setQueries(history.all().take(4))
            if (sheetProgress < 0.5f) shorts.onShown()
        }
        if (t == Tab.YOU) buildYou()
        setBarCollapsed(false)
        updateTabVisuals()
    }

    private fun listFor(t: Tab): View = when (t) {
        Tab.HOME -> homeList
        Tab.SHORTS -> shortsList
        Tab.YOU -> youList
    }

    /** Page switch: the old page slides and fades out, the new one in. */
    private fun crossfade(from: View, to: View, dir: Int) {
        val shift = dpf(36f) * dir
        from.animate().cancel()
        to.animate().cancel()
        from.visibility = View.VISIBLE
        from.alpha = 1f
        from.translationX = 0f
        from.animate().alpha(0f).translationX(-shift).setDuration(200)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                from.translationX = 0f
                from.alpha = 1f
                showContent()
            }.start()
        to.alpha = 0f
        to.translationX = shift
        to.animate().alpha(1f).translationX(0f).setDuration(300).setStartDelay(60)
            .setInterpolator(DecelerateInterpolator(1.6f))
            .setUpdateListener { root.invalidate() }
            .start()
    }

    private fun onTabReselected() {
        when (tab) {
            Tab.HOME -> {
                if (homeList.computeVerticalScrollOffset() > 0) homeList.smoothScrollToPosition(0) else loadHome()
            }
            Tab.YOU -> youList.smoothScrollToPosition(0)
            Tab.SHORTS -> Unit
        }
    }

    /** Shows the list that belongs to the current state. */
    private fun showContent() {
        val pageOn = pages.isNotEmpty()
        homeList.visibility = if (!pageOn && !searchActive && tab == Tab.HOME) View.VISIBLE else View.GONE
        youList.visibility = if (!pageOn && !searchActive && tab == Tab.YOU) View.VISIBLE else View.GONE
        shortsList.visibility = if (!searchActive && tab == Tab.SHORTS) View.VISIBLE else View.GONE
        searchList.visibility = if (!pageOn && searchActive) View.VISIBLE else View.GONE
        applyBars()
        root.invalidate()
    }

    private fun updateYouIcon() {
        val iv = youIcon ?: return
        val photo = Account.photo(this)
        if (Account.isSignedIn(this) && photo != null) {
            iv.clearColorFilter()
            iv.load(photo) { transformations(CircleCropTransformation()) }
        } else {
            iv.setImageResource(R.drawable.ic_person)
            iv.setColorFilter(
                if (tab == Tab.YOU) ContextCompat.getColor(this, R.color.accent) else Glass.textColor(this)
            )
        }
    }

    // ================================================================ insets / scrolling

    private fun setupInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            statusTop = sys.top
            navBottom = sys.bottom
            imeBottom = ime.bottom
            val bottom = maxOf(sys.bottom, ime.bottom)
            val dockLp = dock.layoutParams as FrameLayout.LayoutParams
            dockLp.bottomMargin = bottom + dp(12)
            dock.layoutParams = dockLp
            val sugLp = suggestions.layoutParams as FrameLayout.LayoutParams
            sugLp.bottomMargin = bottom + dp(12) + dp(56) + dp(10)
            suggestions.layoutParams = sugLp
            applyListPadding()
            pages.forEach { it.applyInsets() }
            placeMini()
            if (::sheet.isInitialized) root.post { sheet.applyGeometry() }
            insets
        }
    }

    private fun applyListPadding() {
        val listBottom = navBottom + dp(56) + dp(36) + if (miniShown) dp(64) else 0
        for (rv in listOf(homeList, youList, searchList)) rv.setPadding(0, statusTop, 0, listBottom)
    }

    private fun collapseOnScroll(rv: RecyclerView, dy: Int) {
        if (searchActive || pressP > 0.05f) return
        if (rv.computeVerticalScrollOffset() <= 0) setBarCollapsed(false)
        else if (dy > dp(4)) setBarCollapsed(true)
        else if (dy < -dp(4)) setBarCollapsed(false)
    }

    private fun setupScrolling() {
        homeList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                collapseOnScroll(rv, dy)
                val lm = rv.layoutManager as LinearLayoutManager
                if (lm.findLastVisibleItemPosition() >= homeAdapter.itemCount - 4 &&
                    homeAdapter.items.lastOrNull() == Row.Loading
                ) loadMoreHome()
            }
        })
        youList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) = collapseOnScroll(rv, dy)
        })
        searchList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING && field.hasFocus()) leaveField()
            }

            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val lm = rv.layoutManager as LinearLayoutManager
                if (lm.findLastVisibleItemPosition() >= searchAdapter.itemCount - 4 &&
                    searchAdapter.items.lastOrNull() == Row.Loading && currentQuery != null && nextPage != null
                ) loadMoreSearch()
            }
        })
    }

    // ================================================================ home

    private fun homeHeader(): List<Row> {
        val rows = ArrayList<Row>()
        rows.add(Row.Title(getString(R.string.app_name), null))
        if (Account.isSignedIn(this)) {
            rows.add(Row.Chips(listOf(getString(R.string.chip_for_you), getString(R.string.chip_subscriptions)), homeChip) {
                if (it != homeChip) {
                    homeChip = it
                    loadHome()
                }
            })
        }
        return rows
    }

    private fun loadHome() {
        homeJob?.cancel()
        homeMoreJob?.cancel()
        homeContinuation = null
        homeAdapter.submit(homeHeader() + Row.Loading)
        homeJob = lifecycleScope.launch {
            try {
                val videos = ArrayList<Video>()
                if (Account.isSignedIn(this@MainActivity)) {
                    val id = if (homeChip == 1) Innertube.SUBSCRIPTIONS else Innertube.HOME
                    val page = withContext(Dispatchers.IO) { Innertube.browse(this@MainActivity, id) }
                    videos.addAll(page.videos)
                    shorts.offer(page.shorts)
                    homeContinuation = page.continuation
                }
                if (videos.isEmpty()) videos.addAll(localRecommendations())
                val rows = ArrayList<Row>(homeHeader())
                if (videos.isEmpty()) {
                    rows.add(Row.Message(getString(R.string.home_empty), getString(R.string.retry)) { loadHome() })
                } else {
                    videos.forEach { rows.add(Row.VideoRow(it)) }
                    if (homeContinuation != null) rows.add(Row.Loading)
                }
                homeAdapter.submit(rows)
                homeLoaded = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                homeAdapter.submit(
                    homeHeader() + Row.Message(Errors.text(this@MainActivity, e), getString(R.string.retry)) { loadHome() }
                )
            }
        }
    }

    /** Without an account: related videos of what was watched, recent searches, then trending. */
    private suspend fun localRecommendations(): List<Video> = withContext(Dispatchers.IO) {
        val out = ArrayList<Video>(store.recommendations().take(40))
        val seen = out.map { it.id }.toHashSet()
        fun addAll(items: List<StreamInfoItem>) {
            items.mapNotNull { Video.from(this@MainActivity, it) }
                .filter { !it.isShort && seen.add(it.id) }
                .forEach { out.add(it) }
        }
        if (out.size < 20) {
            for (q in history.all().take(2)) {
                try {
                    val h = yt.searchQHFactory.fromQuery(q, listOf(YoutubeSearchQueryHandlerFactory.VIDEOS), "")
                    addAll(SearchInfo.getInfo(yt, h).relatedItems.filterIsInstance<StreamInfoItem>().take(10))
                } catch (e: Exception) {
                    // next source
                }
            }
        }
        if (out.size < 10) {
            for (kiosk in listOf("trending_music", "trending_gaming", "trending_movies_and_shows")) {
                try {
                    val ex = yt.kioskList.getExtractorById(kiosk, null)
                    ex.fetchPage()
                    addAll(ex.initialPage.items.filterIsInstance<StreamInfoItem>().take(15))
                } catch (e: Exception) {
                    // next kiosk
                }
            }
        }
        // a little variety: interleave sources instead of blocks
        out.shuffled(java.util.Random(System.currentTimeMillis() / 3_600_000))
    }

    private fun loadMoreHome() {
        val token = homeContinuation ?: return
        if (homeMoreJob?.isActive == true) return
        if (homeAdapter.items.lastOrNull() is Row.Message) homeAdapter.append(listOf(Row.Loading))
        homeMoreJob = lifecycleScope.launch {
            try {
                val page = withContext(Dispatchers.IO) { Innertube.browse(this@MainActivity, null, token) }
                homeContinuation = page.continuation
                shorts.offer(page.shorts)
                val rows = ArrayList<Row>()
                page.videos.forEach { rows.add(Row.VideoRow(it)) }
                if (homeContinuation != null) rows.add(Row.Loading)
                homeAdapter.append(rows)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                homeAdapter.append(listOf(Row.Message(Errors.text(this@MainActivity, e), getString(R.string.retry)) { loadMoreHome() }))
            }
        }
    }

    // ================================================================ you (profile)

    private var youHistoryJob: Job? = null
    private var remoteHistory: List<Video>? = null

    private fun buildYou() {
        val signed = Account.isSignedIn(this)
        val rows = ArrayList<Row>()
        rows.add(Row.Custom(accountHeader(signed)))
        val hist = if (signed) remoteHistory ?: emptyList() else store.history()
        rows.add(Row.Section(getString(R.string.history), getString(R.string.see_all)) { openHistoryPage() })
        if (hist.isEmpty()) {
            if (signed && remoteHistory == null) rows.add(Row.Loading)
            else rows.add(Row.Message(getString(R.string.history_empty)))
        } else {
            rows.add(Row.Strip(hist.take(20)))
        }
        rows.add(Row.Section(getString(R.string.library)))
        if (signed) {
            rows.add(Row.Menu(R.drawable.ic_like, getString(R.string.liked_videos)) { openFeedPage(getString(R.string.liked_videos), Innertube.LIKED) })
            rows.add(Row.Menu(R.drawable.ic_clock, getString(R.string.watch_later)) { openFeedPage(getString(R.string.watch_later), Innertube.WATCH_LATER) })
            rows.add(Row.Menu(R.drawable.ic_playlist, getString(R.string.playlists)) { openPlaylistsPage() })
        }
        rows.add(Row.Menu(R.drawable.ic_history, getString(R.string.history)) { openHistoryPage() })
        rows.add(Row.Menu(R.drawable.ic_settings, getString(R.string.settings)) { openSettingsPage() })
        youAdapter.submit(rows)

        if (signed && youHistoryJob?.isActive != true) {
            youHistoryJob = lifecycleScope.launch {
                try {
                    val page = withContext(Dispatchers.IO) { Innertube.browse(this@MainActivity, Innertube.HISTORY) }
                    remoteHistory = page.videos + page.shorts
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    remoteHistory = store.history()
                }
                if (tab == Tab.YOU) buildYouRowsOnly()
            }
        }
    }

    /** Rebuild without starting another history request. */
    private fun buildYouRowsOnly() {
        val job = youHistoryJob
        youHistoryJob = null
        buildYou()
        youHistoryJob = job
    }

    private fun accountHeader(signed: Boolean): View {
        val v = LayoutInflater.from(this).inflate(R.layout.view_account_header, youList, false)
        v.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        val avatar = v.findViewById<ImageView>(R.id.avatar)
        val name = v.findViewById<TextView>(R.id.name)
        val handle = v.findViewById<TextView>(R.id.handle)
        val buttons = v.findViewById<LinearLayout>(R.id.buttons)
        val photo = Account.photo(this)
        if (signed && photo != null) {
            avatar.load(photo) { transformations(CircleCropTransformation()) }
        } else {
            avatar.setImageResource(R.drawable.ic_person)
            avatar.setColorFilter(ContextCompat.getColor(this, R.color.text_muted))
            avatar.setPadding(dp(18), dp(18), dp(18), dp(18))
        }
        if (signed) {
            name.text = Account.name(this) ?: getString(R.string.signed_in)
            handle.text = Account.handle(this) ?: ""
            buttons.addView(headerButton(R.drawable.ic_settings, getString(R.string.settings), false) { openSettingsPage() })
        } else {
            name.text = getString(R.string.not_signed_in)
            handle.text = getString(R.string.sign_in_benefits)
            buttons.addView(headerButton(R.drawable.ic_google, getString(R.string.sign_in_google), true) {
                signIn.launch(Intent(this, SignInActivity::class.java))
            })
        }
        return v
    }

    private fun headerButton(icon: Int, label: String, primary: Boolean, click: () -> Unit): TextView {
        val tv = TextView(this)
        tv.text = label
        tv.textSize = 15f
        tv.gravity = Gravity.CENTER
        tv.setPadding(dp(18), dp(12), dp(20), dp(12))
        tv.compoundDrawablePadding = dp(10)
        val text = Glass.textColor(this)
        val fg = if (primary) (if (Glass.isNight(this)) Color.BLACK else Color.WHITE) else text
        tv.setTextColor(fg)
        val d = ContextCompat.getDrawable(this, icon)?.mutate()
        d?.setBounds(0, 0, dp(18), dp(18))
        if (icon != R.drawable.ic_google) d?.setTint(fg)
        tv.setCompoundDrawablesRelative(d, null, null, null)
        tv.background = if (primary) {
            GradientDrawable().apply {
                cornerRadius = dpf(24f)
                setColor(ColorUtils.setAlphaComponent(text, 240))
            }
        } else Glass.pill(this, dpf(24f))
        tv.setOnClickListener { click() }
        tv.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        return tv
    }

    private fun refreshAccountInBackground() {
        if (!Account.isSignedIn(this)) return
        lifecycleScope.launch {
            try {
                val (n, h, p) = withContext(Dispatchers.IO) { Innertube.accountInfo(this@MainActivity) }
                if (n != null || p != null) {
                    Account.saveProfile(this@MainActivity, n, h, p)
                    updateYouIcon()
                    if (tab == Tab.YOU) buildYouRowsOnly()
                }
            } catch (e: Exception) {
                // keep the stored profile
            }
        }
    }

    private fun onAccountChanged() {
        remoteHistory = null
        homeChip = 0
        updateYouIcon()
        selectTab(tab, animate = false)
        loadHome()
        refreshAccountInBackground()
        while (pages.isNotEmpty()) popPage(animate = false)
    }

    // ================================================================ pages

    /** A pushed full-screen page with a floating glass back button. */
    private inner class PageView(title: String) {
        val view: FrameLayout = LayoutInflater.from(this@MainActivity).inflate(R.layout.page, pageHost, false) as FrameLayout
        val list: RecyclerView = view.findViewById(R.id.list)
        val back: FrameLayout = view.findViewById(R.id.back)
        val header: View = view.findViewById(R.id.header)
        val adapter = VideoAdapter(onVideo = { openVideo(it) })
        var continuation: String? = null
        var browseId: String? = null
        var job: Job? = null

        init {
            view.findViewById<TextView>(R.id.title).text = title
            back.background = Glass.skin(this@MainActivity, null, lensOn)
            view.findViewById<ImageView>(R.id.backIcon).setColorFilter(Glass.textColor(this@MainActivity))
            back.setOnClickListener { popPage() }
            list.layoutManager = LinearLayoutManager(this@MainActivity)
            list.adapter = adapter
            list.itemAnimator = null
            list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                    val lm = rv.layoutManager as LinearLayoutManager
                    if (lm.findLastVisibleItemPosition() >= adapter.itemCount - 4 &&
                        adapter.items.lastOrNull() == Row.Loading && continuation != null
                    ) loadMore()
                }
            })
            applyInsets()
        }

        fun applyInsets() {
            header.setPadding(header.paddingLeft, statusTop + dp(6), header.paddingRight, dp(6))
            list.setPadding(0, statusTop + dp(62), 0, navBottom + dp(56) + dp(36) + if (miniShown) dp(64) else 0)
        }

        fun loadFeed(id: String) {
            browseId = id
            adapter.submit(listOf(Row.Loading))
            job = lifecycleScope.launch {
                try {
                    val page = withContext(Dispatchers.IO) { Innertube.browse(this@MainActivity, id) }
                    continuation = page.continuation
                    val rows = ArrayList<Row>()
                    if (page.videos.isEmpty() && page.shorts.isEmpty()) rows.add(Row.Message(getString(R.string.nothing_here)))
                    (page.videos + page.shorts).forEach { rows.add(Row.VideoRow(it)) }
                    if (continuation != null) rows.add(Row.Loading)
                    adapter.submit(rows)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    adapter.submit(listOf(Row.Message(Errors.text(this@MainActivity, e), getString(R.string.retry)) { loadFeed(id) }))
                }
            }
        }

        fun loadMore() {
            val token = continuation ?: return
            if (job?.isActive == true) return
            job = lifecycleScope.launch {
                try {
                    val page = withContext(Dispatchers.IO) { Innertube.browse(this@MainActivity, null, token) }
                    continuation = page.continuation
                    val rows = ArrayList<Row>()
                    (page.videos + page.shorts).forEach { rows.add(Row.VideoRow(it)) }
                    if (continuation != null) rows.add(Row.Loading)
                    adapter.append(rows)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    adapter.append(listOf(Row.Message(Errors.text(this@MainActivity, e), getString(R.string.retry)) { loadMore() }))
                }
            }
        }
    }

    private fun pushPage(page: PageView) {
        if (field.hasFocus()) leaveField()
        pages.add(page)
        pageHost.addView(page.view)
        page.view.translationX = root.width.toFloat()
        page.view.animate().translationX(0f).setDuration(320L)
            .setInterpolator(DecelerateInterpolator(2f))
            .setUpdateListener { root.invalidate() }
            .withEndAction { showContent() }
            .start()
    }

    private fun popPage(animate: Boolean = true) {
        val page = pages.removeLastOrNull() ?: return
        page.job?.cancel()
        showContent()
        if (!animate) {
            pageHost.removeView(page.view)
            return
        }
        page.view.animate().translationX(root.width.toFloat()).setDuration(260L)
            .setInterpolator(DecelerateInterpolator())
            .setUpdateListener { root.invalidate() }
            .withEndAction { pageHost.removeView(page.view) }
            .start()
        if (tab == Tab.YOU) buildYouRowsOnly()
    }

    private fun openFeedPage(title: String, browseId: String) {
        val p = PageView(title)
        pushPage(p)
        p.loadFeed(browseId)
    }

    private fun openHistoryPage() {
        if (Account.isSignedIn(this)) {
            openFeedPage(getString(R.string.history), Innertube.HISTORY)
            return
        }
        val p = PageView(getString(R.string.history))
        val list = store.history()
        p.adapter.submit(
            if (list.isEmpty()) listOf(Row.Message(getString(R.string.history_empty)))
            else list.map { Row.VideoRow(it) }
        )
        pushPage(p)
    }

    private fun openPlaylistsPage() {
        val p = PageView(getString(R.string.playlists))
        pushPage(p)
        p.adapter.submit(listOf(Row.Loading))
        p.job = lifecycleScope.launch {
            try {
                val page = withContext(Dispatchers.IO) { Innertube.browse(this@MainActivity, Innertube.PLAYLISTS) }
                val rows = ArrayList<Row>()
                if (page.playlists.isEmpty()) rows.add(Row.Message(getString(R.string.nothing_here)))
                page.playlists.forEach { pl ->
                    rows.add(Row.Menu(R.drawable.ic_playlist, pl.title, pl.subtitle, pl.thumb) {
                        openFeedPage(pl.title, if (pl.id.startsWith("VL")) pl.id else "VL" + pl.id)
                    })
                }
                p.adapter.submit(rows)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                p.adapter.submit(listOf(Row.Message(Errors.text(this@MainActivity, e))))
            }
        }
    }

    private fun openSettingsPage() {
        val p = PageView(getString(R.string.settings))
        fillSettings(p)
        pushPage(p)
    }

    private fun fillSettings(p: PageView) {
        val rows = ArrayList<Row>()
        rows.add(Row.Section(getString(R.string.settings_video)))
        val qualities = listOf(1080, 1440, 2160)
        rows.add(
            Row.Choice(
                getString(R.string.max_quality), getString(R.string.max_quality_sub),
                qualities.map { "${it}p" }, qualities.indexOf(settings.maxQuality).coerceAtLeast(0)
            ) {
                settings.maxQuality = qualities[it]
                fillSettings(p)
            }
        )
        val hdrOk = HdrStreams.deviceSupports(this)
        rows.add(
            Row.Toggle(
                R.drawable.ic_hdr, getString(R.string.hdr),
                getString(if (hdrOk) R.string.hdr_sub else R.string.hdr_unsupported),
                settings.hdr && hdrOk, hdrOk
            ) {
                settings.hdr = it
                sheet.onSettingsChanged()
            }
        )

        rows.add(Row.Section(getString(R.string.account)))
        if (Account.isSignedIn(this)) {
            rows.add(Row.Menu(R.drawable.ic_logout, getString(R.string.sign_out), Account.name(this), chevron = false) {
                Account.signOut(this)
                Toast.makeText(this, R.string.signed_out, Toast.LENGTH_SHORT).show()
                onAccountChanged()
            })
        } else {
            rows.add(Row.Menu(R.drawable.ic_google, getString(R.string.sign_in_google), getString(R.string.sign_in_benefits)) {
                signIn.launch(Intent(this, SignInActivity::class.java))
            })
        }

        rows.add(Row.Section(getString(R.string.data)))
        rows.add(Row.Menu(R.drawable.ic_trash, getString(R.string.clear_watch_history), getString(R.string.clear_watch_history_sub), chevron = false) {
            store.clearHistory()
            Toast.makeText(this, R.string.cleared, Toast.LENGTH_SHORT).show()
        })
        rows.add(Row.Menu(R.drawable.ic_trash, getString(R.string.clear_search_history), null, chevron = false) {
            history.clear()
            Toast.makeText(this, R.string.cleared, Toast.LENGTH_SHORT).show()
        })

        rows.add(Row.Section(getString(R.string.about)))
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) {
            null
        }
        rows.add(Row.Menu(R.drawable.ic_play, getString(R.string.app_name), version, chevron = false) { })
        p.adapter.submit(rows)
    }

    // ================================================================ search UI

    private fun setupSearch() {
        val text = Glass.textColor(this)
        val muted = ColorUtils.setAlphaComponent(text, 140)
        field.setTextColor(text)
        field.setHintTextColor(muted)
        findViewById<ImageView>(R.id.searchIcon).setColorFilter(muted)
        clear.setColorFilter(muted)

        searchButton.setOnClickListener { expandSearch(true) }
        searchBack.setOnClickListener { collapseSearch() }

        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val t = s?.toString().orEmpty()
                clear.visibility = if (t.isEmpty()) View.GONE else View.VISIBLE
                if (field.hasFocus()) scheduleSuggestions(t)
            }
        })
        field.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                search(field.text.toString())
                true
            } else false
        }
        field.setOnFocusChangeListener { _, focused ->
            if (focused) {
                field.post { field.selectAll() }
                scheduleSuggestions(field.text.toString())
            } else {
                hideSuggestions()
            }
        }
        clear.setOnClickListener {
            field.setText("")
            field.requestFocus()
            showKeyboard()
        }
    }

    private fun setSearchWidth(w: Int) {
        val lp = searchBar.layoutParams as FrameLayout.LayoutParams
        lp.width = w
        searchBar.layoutParams = lp
    }

    private fun expandSearch(focus: Boolean) {
        if (searchActive) {
            if (focus) {
                field.requestFocus()
                showKeyboard()
            }
            return
        }
        searchActive = true
        if (tab == Tab.SHORTS) shorts.onHidden()
        val full = dock.width - dp(64)
        val small = dp(56)
        searchAnim?.cancel()
        searchBar.visibility = View.VISIBLE
        searchButton.visibility = View.INVISIBLE
        setSearchWidth(small)
        field.alpha = 0f
        searchAnim = ValueAnimator.ofInt(small, full).apply {
            duration = 380L
            interpolator = OvershootInterpolator(0.9f)
            addUpdateListener { setSearchWidth(it.animatedValue as Int) }
            start()
        }
        field.animate().alpha(1f).setStartDelay(120).setDuration(200).start()
        tabBar.pivotX = 0f
        tabBar.pivotY = tabBar.height / 2f
        tabBar.animate().alpha(0f).scaleX(0.3f).scaleY(0.9f).setDuration(260L)
            .withEndAction { tabBar.visibility = View.INVISIBLE }.start()
        searchBack.visibility = View.VISIBLE
        searchBack.alpha = 0f
        searchBack.scaleX = 0.4f
        searchBack.scaleY = 0.4f
        searchBack.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(80).setDuration(320L)
            .setInterpolator(OvershootInterpolator(1.6f)).start()
        showSearchIdle()
        showContent()
        placeMini()
        if (focus) {
            field.requestFocus()
            showKeyboard()
        }
    }

    private fun collapseSearch() {
        if (!searchActive) return
        searchActive = false
        leaveField()
        searchJob?.cancel()
        moreJob?.cancel()
        currentQuery = null
        field.setText("")
        val full = searchBar.width
        val small = dp(56)
        searchAnim?.cancel()
        searchAnim = ValueAnimator.ofInt(full, small).apply {
            duration = 300L
            interpolator = DecelerateInterpolator()
            addUpdateListener { setSearchWidth(it.animatedValue as Int) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (!searchActive) {
                        searchBar.visibility = View.INVISIBLE
                        searchButton.visibility = View.VISIBLE
                    }
                }
            })
            start()
        }
        field.animate().alpha(0f).setStartDelay(0).setDuration(120).start()
        tabBar.visibility = View.VISIBLE
        tabBar.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(340L)
            .setInterpolator(OvershootInterpolator(1.1f)).start()
        searchBack.animate().alpha(0f).scaleX(0.4f).scaleY(0.4f).setStartDelay(0).setDuration(180L)
            .withEndAction { if (!searchActive) searchBack.visibility = View.INVISIBLE }.start()
        showContent()
        barCollapse = 0f
        collapseTarget = 0f
        applyDockLayout()
        if (tab == Tab.SHORTS && sheetProgress < 0.5f) shorts.onShown()
    }

    private fun leaveField() {
        hideKeyboard()
        field.clearFocus()
        hideSuggestions()
    }

    private fun showKeyboard() {
        field.postDelayed({
            getSystemService(InputMethodManager::class.java)?.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
        }, 80)
    }

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(field.windowToken, 0)
    }

    // ================================================================ suggestions

    private fun scheduleSuggestions(text: String) {
        suggestJob?.cancel()
        if (!field.hasFocus()) return
        if (text.isBlank()) {
            hideSuggestions()
            return
        }
        suggestJob = lifecycleScope.launch {
            delay(160)
            val items = try {
                withContext(Dispatchers.IO) { yt.suggestionExtractor.suggestionList(text) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            if (field.hasFocus() && field.text.toString() == text) {
                if (items.isEmpty()) hideSuggestions() else showSuggestions(items.take(6))
            }
        }
    }

    private fun showSuggestions(items: List<String>) {
        suggestions.removeAllViews()
        val text = Glass.textColor(this)
        val muted = ColorUtils.setAlphaComponent(text, 140)
        for (s in items) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(dp(18), 0, dp(6), 0)
            val ta = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
            row.background = ta.getDrawable(0)
            ta.recycle()

            val icon = ImageView(this)
            icon.setImageResource(R.drawable.ic_search)
            icon.setColorFilter(muted)
            row.addView(icon, LinearLayout.LayoutParams(dp(18), dp(18)))

            val tv = TextView(this)
            tv.text = s
            tv.textSize = 16f
            tv.setTextColor(text)
            tv.maxLines = 1
            tv.ellipsize = android.text.TextUtils.TruncateAt.END
            val tlp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            tlp.marginStart = dp(14)
            row.addView(tv, tlp)

            val fill = ImageView(this)
            fill.setImageResource(R.drawable.ic_arrow_up_left)
            fill.setColorFilter(muted)
            fill.setPadding(dp(11), dp(11), dp(11), dp(11))
            val tb = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackgroundBorderless))
            fill.background = tb.getDrawable(0)
            tb.recycle()
            fill.setOnClickListener {
                field.setText(s)
                field.setSelection(s.length)
            }
            row.addView(fill, LinearLayout.LayoutParams(dp(42), dp(42)))

            row.setOnClickListener { search(s) }
            suggestions.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)))
        }
        if (suggestions.visibility != View.VISIBLE) {
            suggestions.visibility = View.VISIBLE
            suggestions.alpha = 0f
            suggestions.translationY = dpf(10f)
            suggestions.animate().alpha(1f).translationY(0f).setDuration(220L)
                .setUpdateListener { root.invalidate() }.start()
        }
    }

    private fun hideSuggestions() {
        suggestJob?.cancel()
        if (suggestions.visibility != View.VISIBLE) return
        suggestions.animate().cancel()
        suggestions.animate().alpha(0f).translationY(dpf(8f)).setDuration(140L)
            .setUpdateListener { root.invalidate() }
            .withEndAction {
                suggestions.visibility = View.GONE
                root.invalidate()
            }.start()
    }

    // ================================================================ search results

    private fun showSearchIdle() {
        currentQuery = null
        val rows = ArrayList<Row>()
        rows.add(Row.Title(getString(R.string.search_title), null))
        val recent = history.all()
        if (recent.isNotEmpty()) {
            rows.add(Row.Section(getString(R.string.recent_searches), getString(R.string.clear_history)) {
                history.clear()
                showSearchIdle()
            })
            recent.forEach { rows.add(Row.History(it)) }
        } else {
            rows.add(Row.Message(getString(R.string.home_subtitle)))
        }
        searchAdapter.submit(rows)
        searchList.scrollToPosition(0)
    }

    private fun search(raw: String) {
        val query = raw.trim()
        if (query.isEmpty()) return
        if (!searchActive) expandSearch(false)
        history.add(query)
        if (field.text.toString() != query) field.setText(query)
        leaveField()

        searchJob?.cancel()
        moreJob?.cancel()
        currentQuery = query
        queryHandler = null
        nextPage = null
        searchAdapter.submit(listOf(Row.Title(query, null), Row.Loading))
        searchList.scrollToPosition(0)

        searchJob = lifecycleScope.launch {
            try {
                val (handler, info) = withContext(Dispatchers.IO) {
                    val h = yt.searchQHFactory.fromQuery(query, listOf(YoutubeSearchQueryHandlerFactory.VIDEOS), "")
                    h to SearchInfo.getInfo(yt, h)
                }
                if (currentQuery != query) return@launch
                queryHandler = handler
                val videos = info.relatedItems.filterIsInstance<StreamInfoItem>().mapNotNull { Video.from(this@MainActivity, it) }
                nextPage = info.nextPage.takeIf { Page.isValid(it) }
                val rows = ArrayList<Row>()
                rows.add(Row.Title(query, null))
                if (videos.isEmpty()) {
                    rows.add(Row.Message(getString(R.string.no_results)))
                } else {
                    videos.forEach { rows.add(Row.VideoRow(it)) }
                    if (nextPage != null) rows.add(Row.Loading)
                }
                searchAdapter.submit(rows)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (currentQuery != query) return@launch
                searchAdapter.submit(
                    listOf(
                        Row.Title(query, null),
                        Row.Message(Errors.text(this@MainActivity, e), getString(R.string.retry)) { search(query) }
                    )
                )
            }
        }
    }

    private fun loadMoreSearch() {
        val page = nextPage ?: return
        val handler = queryHandler ?: return
        val query = currentQuery ?: return
        if (moreJob?.isActive == true) return
        if (searchAdapter.items.lastOrNull() is Row.Message) searchAdapter.append(listOf(Row.Loading))
        moreJob = lifecycleScope.launch {
            try {
                val res = withContext(Dispatchers.IO) { SearchInfo.getMoreItems(yt, handler, page) }
                if (currentQuery != query) return@launch
                nextPage = res.nextPage.takeIf { Page.isValid(it) }
                val rows = ArrayList<Row>()
                res.items.filterIsInstance<StreamInfoItem>().mapNotNull { Video.from(this@MainActivity, it) }
                    .forEach { rows.add(Row.VideoRow(it)) }
                if (nextPage != null) rows.add(Row.Loading)
                searchAdapter.append(rows)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (currentQuery != query) return@launch
                searchAdapter.append(
                    listOf(Row.Message(Errors.text(this@MainActivity, e), getString(R.string.retry)) { loadMoreSearch() })
                )
            }
        }
    }

    private fun openVideo(v: Video) {
        shorts.onHidden()
        sheet.open(v.url)
    }
}
