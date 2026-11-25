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
import kotlinx.coroutines.async
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
    private var strength = 1f

    /** [strength]: how much of the frost is cut away inside the hole (fades in with the press). */
    fun setHole(r: RectF?, radius: Float, strength: Float = 1f) {
        if (r == null) {
            if (!hole) return
            hole = false
        } else {
            hole = true
            this.strength = strength
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
            if (strength < 0.995f) {
                val save2 = canvas.save()
                canvas.clipPath(path)
                val a = inner.alpha
                inner.alpha = (a * (1f - strength)).toInt()
                inner.draw(canvas)
                inner.alpha = a
                canvas.restoreToCount(save2)
            }
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

private const val TOP_TABS_SPACE = 120

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
    private lateinit var miniPlay: PlayPauseView
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
    private lateinit var topTabs: TopTabs
    /** Copies of the tab icons inside the refracted content (seen through the pressed capsule). */
    private lateinit var ghosts: GhostLayer
    private val tabGhost = FloatArray(3)
    private val capWin = RectF()
    private val capLocTmp = IntArray(2)
    /** "Glass Tube" + the tabs: attached to the top of the home page, scrolls away and comes back. */
    private lateinit var homeHeaderView: LinearLayout
    private var headerShift = 0f
    private var headerSnap: ValueAnimator? = null
    /** Home page tab at the top: 0 home, 1 subscriptions, 2 live. */
    private var homeTop = 0
    private val homeRowsCache = arrayOfNulls<List<Row>>(3)
    private val homeScrollCache = arrayOfNulls<android.os.Parcelable>(3)
    private val homeContCache = arrayOfNulls<String>(3)
    private var kioskEx: org.schabi.newpipe.extractor.kiosk.KioskExtractor<*>? = null
    private var kioskNext: Page? = null
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

        homeAdapter = VideoAdapter(onVideo = { openVideo(it) }, onVideoLong = { videoActions(it) }, onChannel = { openChannel(it) })
        youAdapter = VideoAdapter(onVideo = { openVideo(it) }, onVideoLong = { videoActions(it) }, onChannel = { openChannel(it) })
        searchAdapter = VideoAdapter(
            onVideo = { openVideo(it) },
            onHistory = { search(it) },
            onHistoryLong = {
                history.remove(it)
                if (currentQuery == null) showSearchIdle()
            },
            onVideoLong = { videoActions(it) },
            onChannel = { openChannel(it) }
        )
        for ((rv, ad) in listOf(homeList to homeAdapter, youList to youAdapter, searchList to searchAdapter)) {
            rv.layoutManager = LinearLayoutManager(this)
            rv.adapter = ad
            rv.itemAnimator = null
        }
        shorts = ShortsPager(this, shortsList, { navBottom + dp(56) + dp(12) }) { openVideo(it) }

        setupGlass()
        setupTabs()
        setupGhosts()
        setupMini()
        setupSearch()
        setupInsets()
        setupScrolling()
        sheet = PlayerSheet(this, root, this)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    sheet.handleBack() -> Unit
                    tab == Tab.SHORTS && !searchActive && pages.isEmpty() && shorts.handleBack() -> Unit
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

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (settings.pip && Build.VERSION.SDK_INT >= 26 && sheet.isOpen && sheet.isPlaying) {
            try {
                enterPictureInPictureMode(
                    android.app.PictureInPictureParams.Builder().setAspectRatio(android.util.Rational(16, 9)).build()
                )
            } catch (e: Exception) {
                // not allowed on this device
            }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        sheet.setPipMode(isInPictureInPictureMode)
        mini.visibility = if (isInPictureInPictureMode) View.INVISIBLE else if (miniShown) View.VISIBLE else View.GONE
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

    override fun onNowPlaying(title: String?, channel: String?) {
        if (title == null) {
            miniShown = false
        } else {
            miniShown = true
            miniTitle.text = title
            miniChannel.text = channel
        }
        placeMini()
        applyListPadding()
    }

    override fun onPlayingChanged(playing: Boolean) {
        miniPlay.setPause(playing)
    }

    override fun onMiniOffset(dy: Float, alpha: Float) {
        mini.translationY = dy
        mini.alpha = alpha * (1f - sheetProgress * 2.2f).coerceIn(0f, 1f)
        mini.scaleX = 1f - 0.12f * (1f - alpha)
        mini.scaleY = mini.scaleX
        root.invalidate()
    }

    override fun onOpenChannel(url: String) {
        if (searchActive) collapseSearch()
        openChannel(url)
    }

    override fun onVideoActions(video: Video) = videoActions(video)

    /** Long press on a video: play next, save, download, share, channel. */
    private fun videoActions(v: Video) {
        val items = ArrayList<ActionSheet.Item>()
        items.add(ActionSheet.Item(R.drawable.ic_playlist, getString(R.string.play_next)) {
            if (sheet.isOpen) sheet.addToQueue(v) else openVideo(v)
        })
        items.add(ActionSheet.Item(R.drawable.ic_add, getString(R.string.save_to_playlist)) { PlaylistPicker.show(this, v) })
        items.add(ActionSheet.Item(R.drawable.ic_download, getString(R.string.download) + " (${settings.downloadQuality}p)") {
            Downloads.start(this, v.url, settings.downloadQuality)
            Toast.makeText(this, R.string.download_started, Toast.LENGTH_SHORT).show()
        })
        items.add(ActionSheet.Item(R.drawable.ic_share, getString(R.string.action_share)) {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, v.url), v.title))
        })
        v.channelUrl?.let { c ->
            items.add(ActionSheet.Item(R.drawable.ic_person, getString(R.string.go_to_channel)) { openChannel(c) })
        }
        ActionSheet.show(this, v.title, items)
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
        miniPlay.setColor(text)
        miniClose.setColorFilter(ColorUtils.setAlphaComponent(text, 170))
        miniPlay.setOnClickListener { sheet.togglePlay() }
        miniClose.setOnClickListener { sheet.dismiss() }
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
                setParams(dpf(30f), dpf(30f), 0.06f)
                setRim(0, 1f)
                setConvex(0f, 0f)
            }
            true
        } catch (e: Throwable) {
            lens = null
            false
        }
        applySkins()
        setupTopTabs()
        root.viewTreeObserver.addOnPreDrawListener {
            if (dock.width > 0) layoutCapsule()
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
            cornerRadius = dpf(100f)
            setColor(if (night) Color.argb(46, 255, 255, 255) else Color.argb(22, 0, 0, 0))
        }
        val glass = GlassSkinDrawable(density).apply {
            cornerRadius = null
            setTint(0.12f, null)
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

    private fun slot(l: LensEffect, index: Int, v: View, radius: Float?, extra: Float = 1f, refr: Float = 1f) {
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
        // refr < 1: edge-only refraction (icon buttons), sharp inside so the icon stays crisp
        l.setSlot(index, tmp.left, tmp.top, tmp.right, tmp.bottom, r, if (refr < 1f) 0f else dpf(2f), a.coerceIn(0f, 1f), if (refr < 1f) -refr else 0f)
    }

    private fun updateLens() {
        val l = lens ?: return
        val dockOn = dock.visibility == View.VISIBLE && dock.alpha > 0.02f
        if (dockOn) {
            // the pressed capsule is a magnifying drop of glass, through a hole in the bar's frost
            if (pressP > 0.01f && tabIndicator.width > 0) {
                // fading out: the refraction relaxes and the frosted bar comes back underneath,
                // so there is no moment of bare, unblurred picture
                val st = (pressP * 1.4f).coerceIn(0f, 1f)
                l.setSlot0Lens(st)
                rectOf(tabIndicator, tmp)
                l.setSlot(0, tmp.left, tmp.top, tmp.right, tmp.bottom, tmp.height() / 2f, dpf(2f) * (1f - st),
                    st * dock.alpha)
            } else {
                l.clearSlot(0)
            }
            slot(l, 2, tabBar, null)
            slot(l, 3, if (searchBar.visibility == View.VISIBLE) searchBar else searchButton, null)
            slot(l, 4, searchBack, null, refr = 0.8f)
        } else {
            for (i in listOf(0, 2, 3, 4)) l.clearSlot(i)
        }
        val top = pages.lastOrNull()
        if (suggestions.visibility == View.VISIBLE) slot(l, 5, suggestions, dpf(24f))
        else if (top != null) slot(l, 5, top.back, null, refr = 0.8f)
        else l.clearSlot(5)
        slot(l, 6, mini, null)
        // the tabs at the top of the home page: the bar, and its capsule as a drop of glass when pressed
        if (homeHeaderView.visibility == View.VISIBLE && topTabs.width > 0 && headerShift < homeHeaderView.height - dp(2)) {
            slot(l, 7, topTabs, null)
            val tp = topTabs.pressAmount
            if (tp > 0.01f) {
                val st = (tp * 1.4f).coerceIn(0f, 1f)
                rectOf(topTabs, tmp)
                val r = topTabs.capRect
                l.setSlot(1, tmp.left + r.left, tmp.top + r.top, tmp.left + r.right, tmp.top + r.bottom, r.height() / 2f,
                    dpf(2f) * (1f - st), st, lens = st)
            } else {
                l.clearSlot(1)
            }
        } else {
            l.clearSlot(7)
            l.clearSlot(1)
        }

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
            list === homeList && homeList.computeVerticalScrollOffset() > 0 -> {
                val visible = (homeHeaderView.height - headerShift).coerceAtLeast(0f)
                l.setTop(statusTop + visible, dpf(18f), dpf(14f), tint, 0f, true)
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
    private val tabHalos = ArrayList<ImageView>()
    private val tabIconViews = ArrayList<ImageView>()

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTabs() {
        val labels = listOf(R.string.tab_home, R.string.tab_shorts, R.string.tab_you)
        Tab.values().forEachIndexed { i, t ->
            val item = LinearLayout(this)
            item.orientation = LinearLayout.VERTICAL
            item.gravity = Gravity.CENTER
            // the icon over a blurred copy of itself: the white glow of the active tab
            val frame = FrameLayout(this)
            val halo = ImageView(this)
            halo.setImageResource(tabIcons[i])
            halo.alpha = 0f
            if (Build.VERSION.SDK_INT >= 31) {
                halo.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(dpf(5f), dpf(5f), android.graphics.Shader.TileMode.DECAL))
            }
            frame.addView(halo, FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
            val iv = ImageView(this)
            iv.setImageResource(tabIcons[i])
            frame.addView(iv, FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
            frame.clipChildren = false
            item.clipChildren = false
            item.addView(frame, LinearLayout.LayoutParams(dp(40), dp(32)))
            tabHalos.add(halo)
            tabIconViews.add(iv)
            item.contentDescription = getString(labels[i])
            tabs.addView(item, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            tabViews.add(item)
            if (t == Tab.YOU) youIcon = iv
        }
        tabs.setOnTouchListener { _, ev -> onTabTouch(ev) }
        // the capsule runs in front of the icons (it doesn't take touches, so they reach the tabs)
        tabIndicator.bringToFront()
        collapsedIcon.bringToFront()
        // a layout pass of the bar puts the capsule back to its xml size: re-apply ours in the
        // same frame (posting it would show the wrong size for one frame: the capsule jumped)
        tabIndicator.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (!capLayout) layoutCapsule()
        }
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

    // ---- the capsule: spring physics (position, press and a little stretch while it moves)

    /** Centre of the capsule in the tab bar, its velocity and where it is heading. */
    private var capX = 0f
    private var capV = 0f
    private var capTarget = 0f
    private var pressV = 0f
    private var pressTarget = 0f
    private var stretch = 0f
    private var capRunning = false
    private var capLastFrame = 0L
    private val capRect = RectF()
    private var capLayout = false

    private val capFrame = object : android.view.Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            val dt = if (capLastFrame == 0L) 1f / 120f
            else ((frameTimeNanos - capLastFrame) / 1e9f).coerceIn(0.001f, 1f / 30f)
            capLastFrame = frameTimeNanos
            stepCapsule(dt)
            updateTabVisuals()
            if (capsuleSettled()) {
                capX = capTarget
                capV = 0f
                pressP = pressTarget
                pressV = 0f
                stretch = 0f
                capRunning = false
                capLastFrame = 0L
                updateTabVisuals()
            } else {
                android.view.Choreographer.getInstance().postFrameCallback(this)
            }
        }
    }

    private fun startCapsule() {
        if (capRunning) return
        capRunning = true
        capLastFrame = 0L
        android.view.Choreographer.getInstance().postFrameCallback(capFrame)
    }

    private fun capsuleSettled(): Boolean =
        abs(capX - capTarget) < 0.3f && abs(capV) < dpf(4f) &&
            abs(pressP - pressTarget) < 0.002f && abs(pressV) < 0.02f && stretch < 0.002f

    private fun stepCapsule(dt: Float) {
        // stiffer and almost without bounce while following the finger, softer when settling
        val kx = if (draggingTab) 1100f else 420f
        val zx = if (draggingTab) 0.92f else 0.74f
        val growing = pressTarget > pressP
        val kp = if (growing) 750f else 440f
        val zp = if (growing) 0.55f else 0.82f
        val n = 4
        val h = dt / n
        repeat(n) {
            val ax = -kx * (capX - capTarget) - 2f * zx * kotlin.math.sqrt(kx) * capV
            capV += ax * h
            capX += capV * h
            val ap = -kp * (pressP - pressTarget) - 2f * zp * kotlin.math.sqrt(kp) * pressV
            pressV += ap * h
            pressP += pressV * h
        }
        // stretch follows the speed, smoothed so it doesn't flicker
        val want = (abs(capV) / dpf(1400f)).coerceIn(0f, 1f)
        stretch += (want - stretch) * (1f - kotlin.math.exp(-dt * 18f))
        if (stretch < 0.001f && want == 0f) stretch = 0f
    }

    private fun tabCenter(i: Int): Float = tabWidth() * i + tabWidth() / 2f

    private fun clampCenter(x: Float): Float = x.coerceIn(tabCenter(0), tabCenter(2))

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
                // grows in place; it only travels once the finger moves
                pressTarget = 1f
                startCapsule()
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - touchDownX
                if (!draggingTab && abs(dx) > ViewConfiguration.get(this).scaledTouchSlop) draggingTab = true
                if (draggingTab) {
                    capTarget = clampCenter(ev.x)
                    startCapsule()
                }
            }
            MotionEvent.ACTION_UP -> {
                val x = if (draggingTab) capTarget else ev.x
                val target = Tab.values()[(x / tw).toInt().coerceIn(0, 2)]
                draggingTab = false
                pressTarget = 0f
                if (searchActive) collapseSearch()
                while (pages.isNotEmpty()) popPage(animate = false)
                when {
                    target != tab -> selectTab(target)
                    else -> {
                        placeIndicator(true)
                        if (abs(ev.x - touchDownX) <= ViewConfiguration.get(this).scaledTouchSlop) onTabReselected()
                    }
                }
                startCapsule()
            }
            MotionEvent.ACTION_CANCEL -> {
                draggingTab = false
                pressTarget = 0f
                placeIndicator(true)
            }
        }
        return true
    }

    /** Puts the capsule where the spring says; also run before every frame is drawn, because a
     *  layout pass of the bar (e.g. while it collapses) would put it back to its xml place. */
    private fun layoutCapsule() {
        val p = pressP.coerceAtLeast(0f)
        val iw = indicatorWidth()
        val ih = dpf(46f)
        val barW = fullBarWidth()
        if (iw > 0f && ih > 0f) {
            // pressed: a bit bigger; moving fast: longer and a little flatter
            // it grows a little past the bar, like a drop of glass lifted off it
            val wf = 1f + 0.2f * p + 0.16f * stretch
            val hf = (1f + 0.34f * p - 0.1f * stretch).coerceAtMost(1.4f)
            var l = capX - iw * wf / 2f
            var r = capX + iw * wf / 2f
            // against the ends of the bar it squashes instead of poking out
            val out = dpf(2.5f) - dpf(7f) * p.coerceIn(0f, 1f)
            val minL = out
            val maxR = barW - out
            if (l < minL) l = minL
            if (r > maxR) r = maxR
            if (r - l < iw * 0.8f) r = l + iw * 0.8f
            // real bounds instead of scaling, so the rounded ends stay perfectly round
            val bar = tabIndicator.parent as View
            val cy = bar.height / 2f
            val hh = ih * hf / 2f
            capRect.set(l, cy - hh, r, cy + hh)
            tabIndicator.translationX = 0f
            tabIndicator.scaleX = 1f
            tabIndicator.scaleY = 1f
            capLayout = true
            tabIndicator.layout(Math.round(l), Math.round(cy - hh), Math.round(r), Math.round(cy + hh))
            capLayout = false
        }
    }

    /** Capsule size / glassiness and the tab icons under it. */
    private fun updateTabVisuals() {
        val p = pressP.coerceAtLeast(0f)
        layoutCapsule()
        val center = capX
        if (tabIndicator.width > 0) {
            tabIndicator.getLocationInWindow(capLocTmp)
            capWin.set(capLocTmp[0].toFloat(), capLocTmp[1].toFloat(),
                capLocTmp[0] + tabIndicator.width * tabIndicator.scaleX, capLocTmp[1] + tabIndicator.height * tabIndicator.scaleY)
        } else capWin.setEmpty()
        indicatorFrost?.alpha = (255 * (1f - p).coerceIn(0f, 1f)).toInt()
        indicatorGlass?.alpha = (255 * p.coerceIn(0f, 1f)).toInt()
        val tw = tabWidth()
        val text = Glass.textColor(this)
        val night = Glass.isNight(this)
        val glowColor = if (night) Color.WHITE else Color.argb(255, 40, 44, 52)
        val dim = ColorUtils.setAlphaComponent(text, 150)
        val avatarTab = Account.isSignedIn(this) && Account.photo(this) != null
        tabViews.forEachIndexed { i, v ->
            val tc = tw * i + tw / 2f
            val near = (1f - abs(tc - center) / tw).coerceIn(0f, 1f)
            val s = 1f + 0.16f * p.coerceIn(0f, 1.2f) * near
            v.scaleX = s
            v.scaleY = s
            v.translationX = 0f
            // glow follows the capsule (at rest that is exactly the selected tab)
            val glow = near
            val col = ColorUtils.blendARGB(dim, glowColor, glow)
            val iv = tabIconViews[i]
            val halo = tabHalos[i]
            // under the pressed glass the icon is shown by its copy inside the refracted picture:
            // the real one is cut away exactly where the capsule covers it
            val g = ghostAmount(p)
            tabGhost[i] = g
            iv.alpha = 1f
            GhostLayer.cutUnder(iv, capWin, g > 0f)
            // the glow is soft: never cut it (a straight cut through it showed as a hard edge)
            if (halo.clipBounds != null) halo.clipBounds = null
            if (!(i == Tab.YOU.ordinal && avatarTab)) {
                iv.setColorFilter(col)
                halo.setColorFilter(glowColor)
                halo.alpha = glow * (if (night) 0.95f else 0.45f)
            } else {
                halo.alpha = glow * 0.8f
            }
            // the glow grows and moves together with the icon seen through the glass (same
            // magnification around the capsule's centre), so it stays right behind it
            val m = if (g > 0f) 1f + 0.1f * pressP.coerceIn(0f, 1.2f) else 1f
            halo.scaleX = 1.12f * m
            halo.scaleY = 1.12f * m
            halo.translationX = (tc - center) * (m - 1f) / s
        }
        // the bar's frost gets a hole under the clear glass capsule
        if (p > 0.01f) {
            tabBarBg?.setHole(capRect, capRect.height() / 2f, p.coerceIn(0f, 1f))
        } else {
            tabBarBg?.setHole(null, 0f)
        }
        if (::ghosts.isInitialized) ghosts.invalidate()
        root.invalidate()
    }

    private fun placeIndicator(animate: Boolean) {
        if (dock.width == 0) return
        val lp = tabIndicator.layoutParams
        val iw = indicatorWidth().toInt()
        if (lp.width != iw) {
            lp.width = iw
            tabIndicator.layoutParams = lp
        }
        capTarget = tabCenter(tab.ordinal)
        if (!animate || capX == 0f) {
            capX = capTarget
            capV = 0f
            if (!capRunning) {
                updateTabVisuals()
                return
            }
        }
        startCapsule()
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
        collapsedIcon.setColorFilter(Glass.textColor(this))
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
        if (::homeHeaderView.isInitialized) {
            if (from === homeList) {
                homeHeaderView.animate().cancel()
                homeHeaderView.visibility = View.VISIBLE
                homeHeaderView.alpha = 1f
                homeHeaderView.animate().alpha(0f).setDuration(200).withEndAction {
                    homeHeaderView.alpha = 1f
                    homeHeaderView.visibility = homeList.visibility
                }.start()
            } else if (to === homeList) {
                homeHeaderView.animate().cancel()
                homeHeaderView.alpha = 0f
                homeHeaderView.animate().alpha(1f).setDuration(300).setStartDelay(60).start()
            }
        }
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
        if (::homeHeaderView.isInitialized) homeHeaderView.visibility = homeList.visibility
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
            iv.setColorFilter(Glass.textColor(this))
        }
        // the halo of the You tab: the avatar's round glow or the person icon
        tabHalos.getOrNull(Tab.YOU.ordinal)?.let { h ->
            if (Account.isSignedIn(this) && photo != null) {
                h.setImageDrawable(GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.WHITE)
                })
                h.clearColorFilter()
            } else {
                h.setImageResource(R.drawable.ic_person)
            }
        }
        updateTabVisuals()
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
        for (rv in listOf(youList, searchList)) rv.setPadding(0, statusTop, 0, listBottom)
        // the home list starts under the floating tabs
        val headerH = if (::homeHeaderView.isInitialized && homeHeaderView.height > 0) homeHeaderView.height else dp(TOP_TABS_SPACE)
        homeList.setPadding(0, statusTop + headerH, 0, listBottom)
        if (::homeHeaderView.isInitialized) {
            val lp = homeHeaderView.layoutParams as FrameLayout.LayoutParams
            if (lp.topMargin != statusTop) {
                lp.topMargin = statusTop
                homeHeaderView.layoutParams = lp
            }
        }
    }

    private fun collapseOnScroll(rv: RecyclerView, dy: Int) {
        if (searchActive || pressP > 0.05f || !settings.collapseBar) return
        if (rv.computeVerticalScrollOffset() <= 0) setBarCollapsed(false)
        else if (dy > dp(4)) setBarCollapsed(true)
        else if (dy < -dp(4)) setBarCollapsed(false)
    }

    private fun setupScrolling() {
        homeList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                collapseOnScroll(rv, dy)
                onHomeScrolled(dy)
                val lm = rv.layoutManager as LinearLayoutManager
                if (lm.findLastVisibleItemPosition() >= homeAdapter.itemCount - 4 &&
                    homeAdapter.items.lastOrNull() == Row.Loading
                ) loadMoreHome()
            }

            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) settleHeader()
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
        if (Account.isSignedIn(this) && Account.isExpired(this)) {
            rows.add(Row.Message(getString(R.string.session_expired), getString(R.string.sign_in_again)) {
                signIn.launch(Intent(this, SignInActivity::class.java))
            })
        }
        return rows
    }

    private fun loadHome() {
        homeJob?.cancel()
        homeMoreJob?.cancel()
        homeContinuation = null
        kioskEx = null
        kioskNext = null
        homeRowsCache[homeTop] = null
        if (homeTop == 2) {
            loadKiosk(listOf("live"), "live")
            return
        }
        homeChip = if (homeTop == 1) 1 else 0
        homeAdapter.submit(homeHeader() + Row.Loading)
        homeJob = lifecycleScope.launch {
            try {
                val videos = ArrayList<Video>()
                val signedOk = Account.isSignedIn(this@MainActivity) && !Account.isExpired(this@MainActivity)
                if (signedOk) {
                    val id = if (homeChip == 1) Innertube.SUBSCRIPTIONS else Innertube.HOME
                    try {
                        val page = withContext(Dispatchers.IO) { Innertube.browse(this@MainActivity, id) }
                        videos.addAll(page.videos)
                        shorts.offer(page.shorts)
                        homeContinuation = page.continuation
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // fall back to the local feed below
                    }
                }
                if (homeChip == 1 && videos.isEmpty()) videos.addAll(localSubscriptionFeed())
                if (homeChip == 0 && videos.isEmpty()) videos.addAll(localRecommendations())
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

    // ---- tabs at the top of the home page

    /** Pressed at all: the icons under the capsule are shown through it. */
    private fun ghostAmount(p: Float): Float = if (p > 0.01f) 1f else 0f

    private fun setupGhosts() {
        ghosts = GhostLayer(this)
        // over the bars (not refracted), under the player
        root.addView(ghosts, root.indexOfChild(dock) + 1,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        tabIconViews.forEachIndexed { i, iv ->
            ghosts.sources.add(GhostLayer.Source(iv, { tabGhost[i] * dock.alpha }, capWin, { 1f + 0.1f * pressP.coerceIn(0f, 1.2f) }))
        }
        topTabs.labelViews.forEachIndexed { i, t ->
            ghosts.sources.add(GhostLayer.Source(t, { topTabs.ghostAlpha(i) * homeHeaderView.alpha }, topTabs.capWin, { 1f + 0.08f * topTabs.pressAmount.coerceIn(0f, 1.2f) }))
        }
    }

    private fun setupTopTabs() {
        val labels = listOf(R.string.top_home, R.string.top_subs, R.string.top_live).map { getString(it) }
        topTabs = TopTabs(this, labels, lensOn)
        topTabs.onFrame = {
            if (::ghosts.isInitialized) ghosts.invalidate()
            root.invalidate()
        }
        topTabs.onSelect = { switchHomeTop(it) }
        topTabs.onReselect = {
            if (homeList.computeVerticalScrollOffset() > 0) homeList.smoothScrollToPosition(0) else loadHome()
        }
        homeHeaderView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        val title = TextView(this).apply {
            text = getString(R.string.app_name)
            setTextColor(Glass.textColor(this@MainActivity))
            textSize = 32f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(20), dp(14), dp(20), dp(8))
        }
        homeHeaderView.addView(title)
        homeHeaderView.addView(topTabs, LinearLayout.LayoutParams(dp(258), dp(38)).apply {
            marginStart = dp(16)
            bottomMargin = dp(10)
        })
        val lp = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP)
        lp.topMargin = statusTop
        // above the content (so the glass refracts the list), below the dock and the player
        root.addView(homeHeaderView, root.indexOfChild(content) + 1, lp)
        homeHeaderView.visibility = homeList.visibility
        homeHeaderView.addOnLayoutChangeListener { _, _, t, _, b, _, ot, _, ob ->
            if (b - t != ob - ot) root.post { applyListPadding() }
        }
    }

    /** The header is fixed to the top of the page: it moves exactly with the list. */
    private fun onHomeScrolled(@Suppress("UNUSED_PARAMETER") dy: Int) {
        if (!::homeHeaderView.isInitialized) return
        syncHeader()
    }

    private fun syncHeader() {
        val offset = homeList.computeVerticalScrollOffset().toFloat()
        val max = homeHeaderView.height.toFloat() + statusTop
        headerShift = offset.coerceIn(0f, max)
        applyHeaderShift()
    }

    private fun settleHeader() = Unit

    private fun showHeader() {
        headerSnap?.cancel()
        if (::homeHeaderView.isInitialized) homeList.post { syncHeader() }
    }

    private fun applyHeaderShift() {
        homeHeaderView.translationY = -headerShift
        root.invalidate()
    }

    /** Switching top tabs: the same slide-and-fade as between the bottom tabs. */
    private fun switchHomeTop(i: Int) {
        if (i == homeTop) return
        val dir = if (i > homeTop) 1 else -1
        // keep what the old tab showed, so coming back is instant
        val oldLoading = homeAdapter.items.any { it == Row.Loading } && homeAdapter.items.none { it is Row.VideoRow }
        homeRowsCache[homeTop] = if (oldLoading) null else homeAdapter.items.toList()
        homeScrollCache[homeTop] = homeList.layoutManager?.onSaveInstanceState()
        homeContCache[homeTop] = homeContinuation
        homeJob?.cancel()
        homeMoreJob?.cancel()
        homeTop = i
        showHeader()
        val shift = dpf(36f) * dir
        homeList.animate().cancel()
        homeList.animate().alpha(0f).translationX(-shift).setDuration(110)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                val cached = homeRowsCache[i]
                if (cached != null && i != 2) {
                    homeContinuation = homeContCache[i]
                    homeAdapter.submit(cached)
                    homeScrollCache[i]?.let { homeList.layoutManager?.onRestoreInstanceState(it) }
                } else {
                    loadHome()
                    homeList.scrollToPosition(0)
                }
                homeList.post { syncHeader() }
                homeList.translationX = shift
                homeList.animate().alpha(1f).translationX(0f).setDuration(220)
                    .setInterpolator(DecelerateInterpolator(1.6f)).start()
            }.start()
        setBarCollapsed(false)
    }

    private fun kioskVideos(items: List<*>): List<Video> =
        items.filterIsInstance<StreamInfoItem>().mapNotNull { Video.from(this, it) }

    /** Music / live: a YouTube kiosk (the first of [ids] that works), or a search as a fallback. */
    private fun loadKiosk(ids: List<String>, fallbackQuery: String?) {
        val tabNow = homeTop
        homeAdapter.submit(homeHeader() + Row.Loading)
        homeJob = lifecycleScope.launch {
            try {
                var videos: List<Video> = emptyList()
                for (id in ids) {
                    try {
                        val (ex, list) = withContext(Dispatchers.IO) {
                            val ex = yt.kioskList.getExtractorById(id, null)
                            ex.fetchPage()
                            ex to ex.initialPage
                        }
                        videos = kioskVideos(list.items)
                        if (videos.isNotEmpty()) {
                            kioskEx = ex
                            kioskNext = list.nextPage
                            break
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // next source
                    }
                }
                if (videos.isEmpty() && fallbackQuery != null) {
                    videos = withContext(Dispatchers.IO) {
                        val h = yt.searchQHFactory.fromQuery(fallbackQuery, listOf(YoutubeSearchQueryHandlerFactory.VIDEOS), "")
                        SearchInfo.getInfo(yt, h).relatedItems.filterIsInstance<StreamInfoItem>()
                            .filter { it.streamType == org.schabi.newpipe.extractor.stream.StreamType.LIVE_STREAM }
                            .mapNotNull { Video.from(this@MainActivity, it) }
                    }
                }
                if (homeTop != tabNow) return@launch
                val rows = ArrayList<Row>(homeHeader())
                if (videos.isEmpty()) rows.add(Row.Message(getString(R.string.nothing_here), getString(R.string.retry)) { loadHome() })
                else {
                    videos.forEach { rows.add(Row.VideoRow(it)) }
                    if (Page.isValid(kioskNext)) rows.add(Row.Loading)
                }
                homeAdapter.submit(rows)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (homeTop == tabNow) homeAdapter.submit(
                    homeHeader() + Row.Message(Errors.text(this@MainActivity, e), getString(R.string.retry)) { loadHome() }
                )
            }
        }
    }

    private fun loadMoreKiosk() {
        val ex = kioskEx ?: return
        val next = kioskNext ?: return
        if (!Page.isValid(next) || homeMoreJob?.isActive == true) return
        val tabNow = homeTop
        homeMoreJob = lifecycleScope.launch {
            try {
                val page = withContext(Dispatchers.IO) { ex.getPage(next) }
                if (homeTop != tabNow) return@launch
                kioskNext = page.nextPage
                val rows = ArrayList<Row>()
                kioskVideos(page.items).forEach { rows.add(Row.VideoRow(it)) }
                if (Page.isValid(kioskNext)) rows.add(Row.Loading)
                homeAdapter.append(rows)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                homeAdapter.append(listOf(Row.Message(Errors.text(this@MainActivity, e), getString(R.string.retry)) { loadMoreKiosk() }))
            }
        }
    }

    /** Newest videos of the channels subscribed to on the phone. */
    private suspend fun localSubscriptionFeed(): List<Video> = withContext(Dispatchers.IO) {
        val subs = Subscriptions(this@MainActivity).all().take(15)
        val jobs = subs.map { c ->
            async {
                try {
                    val info = org.schabi.newpipe.extractor.channel.ChannelInfo.getInfo(yt, c.url)
                    val tab = info.tabs.firstOrNull { it.contentFilters.contains(org.schabi.newpipe.extractor.channel.tabs.ChannelTabs.VIDEOS) }
                        ?: return@async emptyList<Pair<Long, Video>>()
                    val tabInfo = org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo.getInfo(yt, tab)
                    tabInfo.relatedItems.filterIsInstance<StreamInfoItem>().take(8).mapNotNull { item ->
                        val v = Video.from(this@MainActivity, item)?.copy(avatar = c.avatar, channelUrl = c.url) ?: return@mapNotNull null
                        val t = try {
                            item.uploadDate?.instant?.toEpochMilli() ?: 0L
                        } catch (e: Exception) {
                            0L
                        }
                        t to v
                    }
                } catch (e: Exception) {
                    emptyList()
                }
            }
        }
        jobs.flatMap { it.await() }.sortedByDescending { it.first }.map { it.second }.distinctBy { it.id }
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
        if (homeTop == 2) {
            loadMoreKiosk()
            return
        }
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
        val dls = Downloads.all(this)
        rows.add(Row.Menu(R.drawable.ic_download, getString(R.string.downloads),
            if (dls.isEmpty()) null else getString(R.string.n_videos, dls.size)) { openDownloadsPage() })
        val subs = Subscriptions(this).all()
        rows.add(Row.Menu(R.drawable.ic_person, getString(R.string.subscriptions_here),
            if (subs.isEmpty()) null else getString(R.string.n_channels, subs.size)) { openSubscriptionsPage() })
        val lists = store.playlistNames()
        if (lists.isNotEmpty()) {
            rows.add(Row.Section(getString(R.string.my_playlists)))
            lists.forEach { name ->
                val items = store.playlist(name)
                rows.add(Row.Menu(R.drawable.ic_playlist, name, getString(R.string.n_videos, items.size), items.firstOrNull()?.thumb) {
                    openLocalPlaylist(name)
                })
            }
        }
        rows.add(Row.Section(getString(R.string.settings)))
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
                if (System.currentTimeMillis() - Subscriptions(this@MainActivity).lastSync() > 6 * 3_600_000L) syncSubscriptions(null)
            } catch (e: Innertube.SessionExpired) {
                if (!Account.isExpired(this@MainActivity)) {
                    Account.setExpired(this@MainActivity, true)
                    loadHome()
                }
            } catch (e: Exception) {
                // keep the stored profile
            }
        }
    }

    /** Copies the YouTube account's subscriptions to the phone. */
    private fun syncSubscriptions(done: ((Boolean) -> Unit)?) {
        if (!Account.isSignedIn(this)) {
            done?.invoke(false)
            return
        }
        lifecycleScope.launch {
            val ok = try {
                val remote = withContext(Dispatchers.IO) { Innertube.subscriptions(this@MainActivity) }
                Subscriptions(this@MainActivity).merge(remote)
                remote.isNotEmpty()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            if (tab == Tab.YOU && pages.isEmpty()) buildYouRowsOnly()
            done?.invoke(ok)
        }
    }

    private fun onAccountChanged() {
        remoteHistory = null
        homeChip = 0
        for (i in homeRowsCache.indices) homeRowsCache[i] = null
        updateYouIcon()
        selectTab(tab, animate = false)
        loadHome()
        refreshAccountInBackground()
        syncSubscriptions(null)
        while (pages.isNotEmpty()) popPage(animate = false)
    }

    // ================================================================ pages

    /** A pushed full-screen page with a floating glass back button. */
    private inner class PageView(title: String) {
        val view: FrameLayout = LayoutInflater.from(this@MainActivity).inflate(R.layout.page, pageHost, false) as FrameLayout
        val list: RecyclerView = view.findViewById(R.id.list)
        val back: FrameLayout = view.findViewById(R.id.back)
        val header: View = view.findViewById(R.id.header)
        val adapter = VideoAdapter(onVideo = { openVideo(it) }, onVideoLong = { videoActions(it) }, onChannel = { openChannel(it) })
        var continuation: String? = null
        /** Custom "next page" (channel tabs, NewPipe playlists). */
        var moreFn: (() -> Unit)? = null
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
                        adapter.items.lastOrNull() == Row.Loading
                    ) {
                        val f = moreFn
                        if (f != null) f() else if (continuation != null) loadMore()
                    }
                }
            })
            applyInsets()
        }

        fun setTitle(t: String) {
            view.findViewById<TextView>(R.id.title).text = t
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

    // ---- channel page

    private fun openChannel(url: String) {
        val p = PageView("")
        pushPage(p)
        p.adapter.submit(listOf(Row.Loading))
        p.job = lifecycleScope.launch {
            try {
                val info = withContext(Dispatchers.IO) { org.schabi.newpipe.extractor.channel.ChannelInfo.getInfo(yt, url) }
                p.setTitle(info.name)
                val header = LayoutInflater.from(this@MainActivity).inflate(R.layout.view_channel_header, p.list, false)
                header.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                val banner = Format.pick(info.banners, 1280)
                val bannerBox = header.findViewById<RatioFrameLayout>(R.id.bannerBox)
                bannerBox.ratio = 4f
                if (banner != null) header.findViewById<ImageView>(R.id.banner).load(banner) { crossfade(150) } else bannerBox.visibility = View.GONE
                header.findViewById<ImageView>(R.id.avatar).load(Format.pick(info.avatars, 200)) { transformations(CircleCropTransformation()) }
                header.findViewById<TextView>(R.id.name).text = info.name
                header.findViewById<TextView>(R.id.subs).text =
                    if (info.subscriberCount > 0) getString(R.string.subscribers, Format.count(this@MainActivity, info.subscriberCount)) else ""
                val about = header.findViewById<TextView>(R.id.about)
                about.text = info.description
                about.visibility = if (info.description.isNullOrBlank()) View.GONE else View.VISIBLE
                about.setOnClickListener { about.maxLines = if (about.maxLines == 2) Int.MAX_VALUE else 2 }
                val ref = ChannelRef(info.url ?: url, info.name, Format.pick(info.avatars, 200))
                header.findViewById<LinearLayout>(R.id.buttons).addView(SubscribeButton.create(this@MainActivity, ref))

                val wanted = listOf(
                    org.schabi.newpipe.extractor.channel.tabs.ChannelTabs.VIDEOS to R.string.ch_videos,
                    org.schabi.newpipe.extractor.channel.tabs.ChannelTabs.SHORTS to R.string.tab_shorts,
                    org.schabi.newpipe.extractor.channel.tabs.ChannelTabs.LIVESTREAMS to R.string.ch_live,
                    org.schabi.newpipe.extractor.channel.tabs.ChannelTabs.PLAYLISTS to R.string.playlists
                )
                val tabs = wanted.mapNotNull { (f, label) ->
                    info.tabs.firstOrNull { it.contentFilters.contains(f) }?.let { it to getString(label) }
                }
                val tabBox = header.findViewById<LinearLayout>(R.id.tabs)
                fun showTab(index: Int) {
                    tabBox.removeAllViews()
                    tabs.forEachIndexed { i, (_, label) ->
                        val chip = VideoAdapter.pill(this@MainActivity, label, i == index)
                        chip.setOnClickListener { if (i != index) showTab(i) }
                        tabBox.addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                            marginStart = dp(4)
                            marginEnd = dp(4)
                        })
                    }
                    val handler = tabs.getOrNull(index)?.first
                    p.adapter.submit(listOf(Row.Custom(header), Row.Loading))
                    if (handler == null) {
                        p.adapter.submit(listOf(Row.Custom(header), Row.Message(getString(R.string.nothing_here))))
                        return
                    }
                    var next: Page? = null
                    var loading = false
                    fun rowsOf(items: List<org.schabi.newpipe.extractor.InfoItem>): List<Row> = items.mapNotNull { item ->
                        when (item) {
                            is StreamInfoItem -> Video.from(this@MainActivity, item)?.let { Row.VideoRow(it.copy(avatar = null)) }
                            is org.schabi.newpipe.extractor.playlist.PlaylistInfoItem -> Row.Menu(
                                R.drawable.ic_playlist, item.name, if (item.streamCount > 0) getString(R.string.n_videos, item.streamCount.toInt()) else null,
                                Format.pick(item.thumbnails, 480)
                            ) { openNpPlaylist(item.url, item.name) }
                            else -> null
                        }
                    }
                    p.moreFn = {
                        val pg = next
                        if (pg != null && !loading && Page.isValid(pg)) {
                            loading = true
                            lifecycleScope.launch {
                                try {
                                    val res = withContext(Dispatchers.IO) {
                                        org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo.getMoreItems(yt, handler, pg)
                                    }
                                    next = res.nextPage
                                    p.adapter.append(rowsOf(res.items) + if (Page.isValid(next)) listOf(Row.Loading) else emptyList())
                                } catch (e: Exception) {
                                    p.adapter.append(listOf(Row.Message(Errors.text(this@MainActivity, e))))
                                } finally {
                                    loading = false
                                }
                            }
                        }
                    }
                    p.job = lifecycleScope.launch {
                        try {
                            val tabInfo = withContext(Dispatchers.IO) {
                                org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo.getInfo(yt, handler)
                            }
                            next = tabInfo.nextPage
                            val rows = ArrayList<Row>()
                            rows.add(Row.Custom(header))
                            val items = rowsOf(tabInfo.relatedItems)
                            if (items.isEmpty()) rows.add(Row.Message(getString(R.string.nothing_here)))
                            rows.addAll(items)
                            if (Page.isValid(next)) rows.add(Row.Loading)
                            p.adapter.submit(rows)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            p.adapter.submit(listOf(Row.Custom(header), Row.Message(Errors.text(this@MainActivity, e))))
                        }
                    }
                }
                showTab(0)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                p.adapter.submit(listOf(Row.Message(Errors.text(this@MainActivity, e), getString(R.string.retry)) {
                    popPage(animate = false)
                    openChannel(url)
                }))
            }
        }
    }

    /** A YouTube playlist through NewPipe (from search or a channel). */
    private fun openNpPlaylist(url: String, title: String) {
        val p = PageView(title)
        pushPage(p)
        p.adapter.submit(listOf(Row.Loading))
        var next: Page? = null
        var loading = false
        p.moreFn = {
            val pg = next
            if (pg != null && !loading && Page.isValid(pg)) {
                loading = true
                lifecycleScope.launch {
                    try {
                        val res = withContext(Dispatchers.IO) { org.schabi.newpipe.extractor.playlist.PlaylistInfo.getMoreItems(yt, url, pg) }
                        next = res.nextPage
                        p.adapter.append(res.items.mapNotNull { Video.from(this@MainActivity, it)?.let { v -> Row.VideoRow(v) } } +
                            if (Page.isValid(next)) listOf(Row.Loading) else emptyList())
                    } catch (e: Exception) {
                        p.adapter.append(listOf(Row.Message(Errors.text(this@MainActivity, e))))
                    } finally {
                        loading = false
                    }
                }
            }
        }
        p.job = lifecycleScope.launch {
            try {
                val info = withContext(Dispatchers.IO) { org.schabi.newpipe.extractor.playlist.PlaylistInfo.getInfo(yt, url) }
                next = info.nextPage
                val rows = ArrayList<Row>()
                info.relatedItems.mapNotNull { Video.from(this@MainActivity, it) }.forEach { rows.add(Row.VideoRow(it)) }
                if (rows.isEmpty()) rows.add(Row.Message(getString(R.string.nothing_here)))
                if (Page.isValid(next)) rows.add(Row.Loading)
                p.adapter.submit(rows)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                p.adapter.submit(listOf(Row.Message(Errors.text(this@MainActivity, e))))
            }
        }
    }

    private fun openLocalPlaylist(name: String) {
        val p = PageView(name)
        fun fill() {
            val items = store.playlist(name)
            val rows = ArrayList<Row>()
            if (items.isEmpty()) rows.add(Row.Message(getString(R.string.nothing_here)))
            items.forEach { rows.add(Row.VideoRow(it)) }
            rows.add(Row.Menu(R.drawable.ic_trash, getString(R.string.delete_playlist), null, chevron = false) {
                store.deletePlaylist(name)
                popPage()
            })
            p.adapter.submit(rows)
        }
        fill()
        pushPage(p)
    }

    private fun openDownloadsPage() {
        val p = PageView(getString(R.string.downloads))
        fun fill() {
            val list = Downloads.all(this)
            val rows = ArrayList<Row>()
            if (list.isEmpty()) rows.add(Row.Message(getString(R.string.downloads_empty)))
            list.forEach { d ->
                val sub = when (d.state) {
                    "done" -> Format.joinDot(d.channel, if (d.height > 0) "${d.height}p" else null,
                        android.text.format.Formatter.formatShortFileSize(this, d.bytes))
                    "failed" -> getString(R.string.download_failed)
                    else -> getString(R.string.downloading) + " ${d.progress}%"
                }
                rows.add(Row.Menu(R.drawable.ic_download, d.title, sub, d.thumb?.let { if (it.startsWith("/")) "file://$it" else it }, chevron = false) {
                    when {
                        d.done -> {
                            popPage(animate = false)
                            sheet.openDownload(d)
                        }
                        d.state == "failed" -> ActionSheet.show(this, d.title, listOf(
                            ActionSheet.Item(R.drawable.ic_download, getString(R.string.retry)) {
                                Downloads.delete(this, d.id)
                                Downloads.start(this, "https://www.youtube.com/watch?v=${d.id}", settings.downloadQuality)
                            },
                            ActionSheet.Item(R.drawable.ic_trash, getString(R.string.delete)) {
                                Downloads.delete(this, d.id)
                                fill()
                            }
                        ))
                    }
                })
            }
            p.adapter.submit(rows)
        }
        fill()
        pushPage(p)
        // refresh while something is downloading
        p.job = lifecycleScope.launch {
            while (true) {
                delay(1500)
                fill()
                if (Downloads.all(this@MainActivity).none { it.state == "downloading" }) break
            }
        }
    }

    private fun openSubscriptionsPage() {
        val p = PageView(getString(R.string.subscriptions_here))
        fun fill(syncing: Boolean) {
            val list = Subscriptions(this).all()
            val rows = ArrayList<Row>()
            if (syncing) rows.add(Row.Loading)
            if (list.isEmpty() && !syncing) rows.add(Row.Message(getString(R.string.subscriptions_empty)))
            list.forEach { c -> rows.add(Row.ChannelRow(c.name, c.avatar, null) { openChannel(c.url) }) }
            p.adapter.submit(rows)
        }
        val signed = Account.isSignedIn(this)
        fill(signed)
        pushPage(p)
        // always fetch the latest list from YouTube when signed in
        if (signed) syncSubscriptions { ok ->
            fill(false)
            if (!ok) Toast.makeText(this, R.string.sync_failed, Toast.LENGTH_SHORT).show()
        }
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

    private val openGroups = HashSet<String>()
    private var iconPicker: View? = null

    private fun openSettingsPage() {
        val p = PageView(getString(R.string.settings))
        p.list.itemAnimator = androidx.recyclerview.widget.DefaultItemAnimator().apply {
            addDuration = 240
            removeDuration = 160
            moveDuration = 280
            changeDuration = 0
        }
        fillSettings(p, animate = false)
        pushPage(p)
    }

    private fun fillSettings(p: PageView, animate: Boolean = true) {
        val rows = ArrayList<Row>()
        fun group(key: String, icon: Int, title: Int, subtitle: String?, children: () -> List<Row>) {
            val open = key in openGroups
            rows.add(Row.Group(key, icon, getString(title), subtitle, open) {
                if (!openGroups.add(key)) openGroups.remove(key)
                fillSettings(p)
            })
            if (open) rows.addAll(children())
        }
        val refresh = { fillSettings(p) }

        // ---- playback
        val qualities = listOf(720, 1080, 1440, 2160)
        val mobile = listOf(0, 480, 720, 1080)
        val speeds = listOf(75, 100, 125, 150, 200)
        val steps = listOf(5, 10, 15, 30)
        val dlq = listOf(360, 480, 720, 1080)
        group("playback", R.drawable.ic_play, R.string.settings_playback,
            "${settings.maxQuality}p · " + speedText(settings.speedPercent)) {
            listOf(
                Row.Choice(getString(R.string.max_quality), getString(R.string.max_quality_sub),
                    qualities.map { "${it}p" }, qualities.indexOf(settings.maxQuality).coerceAtLeast(0)) {
                    settings.maxQuality = qualities[it]
                    sheet.onSettingsChanged()
                    refresh()
                },
                Row.Choice(getString(R.string.mobile_quality), getString(R.string.mobile_quality_sub),
                    mobile.map { if (it == 0) getString(R.string.same) else "${it}p" },
                    mobile.indexOf(settings.mobileQuality).coerceAtLeast(0)) {
                    settings.mobileQuality = mobile[it]
                    sheet.onSettingsChanged()
                    refresh()
                },
                Row.Choice(getString(R.string.default_speed), null,
                    speeds.map { speedText(it) }, speeds.indexOf(settings.speedPercent).coerceAtLeast(1)) {
                    settings.speedPercent = speeds[it]
                    refresh()
                },
                Row.Choice(getString(R.string.seek_step), getString(R.string.seek_step_sub),
                    steps.map { getString(R.string.seconds_short, it) }, steps.indexOf(settings.seekStep).coerceAtLeast(1)) {
                    settings.seekStep = steps[it]
                    refresh()
                },
                Row.Toggle(R.drawable.ic_play, getString(R.string.autoplay), getString(R.string.autoplay_sub), settings.autoplay) {
                    settings.autoplay = it
                },
                Row.Toggle(R.drawable.ic_headphones, getString(R.string.background_play), getString(R.string.background_play_sub), settings.backgroundPlay) {
                    settings.backgroundPlay = it
                },
                Row.Toggle(R.drawable.ic_history, getString(R.string.resume), getString(R.string.resume_sub), settings.resume) {
                    settings.resume = it
                },
                Row.Toggle(R.drawable.ic_fullscreen_exit, getString(R.string.pip), getString(R.string.pip_sub), settings.pip) {
                    settings.pip = it
                },
                Row.Toggle(R.drawable.ic_fullscreen, getString(R.string.swipe_controls), getString(R.string.swipe_controls_sub), settings.swipeControls) {
                    settings.swipeControls = it
                },
                Row.Choice(getString(R.string.download_quality), null, dlq.map { "${it}p" },
                    dlq.indexOf(settings.downloadQuality).coerceAtLeast(2)) {
                    settings.downloadQuality = dlq[it]
                    refresh()
                }
            )
        }

        // ---- SponsorBlock
        group("sponsorblock", R.drawable.ic_sponsor, R.string.sponsorblock,
            getString(if (settings.sponsorBlock) R.string.on else R.string.off)) {
            val list = ArrayList<Row>()
            list.add(Row.Toggle(R.drawable.ic_sponsor, getString(R.string.sponsorblock), getString(R.string.sponsorblock_sub), settings.sponsorBlock) {
                settings.sponsorBlock = it
                refresh()
            })
            if (settings.sponsorBlock) {
                val cats = settings.sbCategories
                SponsorBlock.CATEGORIES.keys.forEach { c ->
                    list.add(Row.Toggle(R.drawable.ic_sponsor, SponsorBlock.label(this, c), null, c in cats) { on ->
                        settings.sbCategories = if (on) settings.sbCategories + c else settings.sbCategories - c
                    })
                }
            }
            list
        }

        // ---- shorts
        group("shorts", R.drawable.ic_shorts, R.string.tab_shorts, null) {
            listOf(
                Row.Toggle(R.drawable.ic_replay10, getString(R.string.shorts_loop), getString(R.string.shorts_loop_sub), settings.shortsLoop) {
                    settings.shortsLoop = it
                    shorts.applySettings()
                }
            )
        }

        // ---- appearance
        val themes = listOf(R.string.theme_system, R.string.theme_light, R.string.theme_dark)
        val refrSteps = listOf(0, 50, 100, 150, 200)
        group("appearance", R.drawable.ic_palette, R.string.settings_appearance, getString(themes[settings.theme.coerceIn(0, 2)])) {
            listOf(
                Row.Choice(getString(R.string.theme), null, themes.map { getString(it) }, settings.theme.coerceIn(0, 2)) {
                    settings.theme = it
                    settings.applyTheme()
                },
                Row.Toggle(R.drawable.ic_home, getString(R.string.collapse_bar), getString(R.string.collapse_bar_sub), settings.collapseBar) {
                    settings.collapseBar = it
                    if (!it) setBarCollapsed(false)
                },
                Row.Choice(getString(R.string.refraction), getString(R.string.refraction_sub),
                    refrSteps.map { if (it == 0) getString(R.string.off) else "$it%" },
                    refrSteps.indexOf(settings.refraction).let { if (it < 0) 2 else it }) {
                    settings.refraction = refrSteps[it]
                    Glass.refraction = refrSteps[it] / 100f
                    lens?.apply()
                    root.invalidate()
                    refresh()
                },
                Row.Toggle(R.drawable.ic_palette, getString(R.string.dispersion), getString(R.string.dispersion_sub), settings.dispersion, enabled = !settings.reduceTransparency) {
                    settings.dispersion = it
                    Glass.dispersion = it
                    lens?.apply()
                    root.invalidate()
                },
                Row.Toggle(R.drawable.ic_palette, getString(R.string.reduce_transparency), getString(R.string.reduce_transparency_sub), settings.reduceTransparency) {
                    settings.reduceTransparency = it
                    Glass.reduceTransparency = it
                    root.postDelayed({ recreate() }, 350)
                }
            )
        }

        // ---- app icon
        group("app_icon", R.drawable.ic_palette, R.string.app_icon, AppIcons.label(this, AppIcons.current(this))) {
            val v = iconPicker ?: AppIcons.picker(this) { fillSettings(p) }.also { iconPicker = it }
            listOf(Row.Custom(v))
        }

        // ---- history & privacy
        group("privacy", R.drawable.ic_history, R.string.settings_privacy, null) {
            listOf(
                Row.Toggle(R.drawable.ic_history, getString(R.string.save_watch_history), null, settings.saveWatchHistory) {
                    settings.saveWatchHistory = it
                },
                Row.Toggle(R.drawable.ic_search, getString(R.string.save_search_history), null, settings.saveSearchHistory) {
                    settings.saveSearchHistory = it
                },
                Row.Menu(R.drawable.ic_trash, getString(R.string.clear_watch_history), getString(R.string.clear_watch_history_sub), chevron = false) {
                    store.clearHistory()
                    Toast.makeText(this, R.string.cleared, Toast.LENGTH_SHORT).show()
                },
                Row.Menu(R.drawable.ic_trash, getString(R.string.clear_search_history), null, chevron = false) {
                    history.clear()
                    Toast.makeText(this, R.string.cleared, Toast.LENGTH_SHORT).show()
                }
            )
        }

        // ---- account
        val signed = Account.isSignedIn(this)
        group("account", R.drawable.ic_person, R.string.account,
            if (signed) Account.name(this) ?: getString(R.string.signed_in) else getString(R.string.not_signed_in)) {
            if (signed) listOf(
                Row.Menu(R.drawable.ic_logout, getString(R.string.sign_out), Account.name(this), chevron = false) {
                    Account.signOut(this)
                    Toast.makeText(this, R.string.signed_out, Toast.LENGTH_SHORT).show()
                    onAccountChanged()
                }
            ) else listOf(
                Row.Menu(R.drawable.ic_google, getString(R.string.sign_in_google), getString(R.string.sign_in_benefits)) {
                    signIn.launch(Intent(this, SignInActivity::class.java))
                }
            )
        }

        // ---- about
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) {
            null
        }
        group("about", R.drawable.ic_info, R.string.about, version) {
            listOf(
                Row.Menu(R.drawable.ic_play, getString(R.string.app_name), version, chevron = false) { },
                Row.Menu(R.drawable.ic_open, getString(R.string.source_code), "github.com/Prophet1218/Glass-Tube") {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/Prophet1218/Glass-Tube")))
                    } catch (e: Exception) {
                        // no browser
                    }
                }
            )
        }
        if (animate) p.adapter.submitAnimated(rows) else p.adapter.submit(rows)
    }

    private fun speedText(percent: Int): String {
        val v = percent / 100f
        return if (v == v.toInt().toFloat()) "${v.toInt()}×" else "${v}×"
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
        val fromList: View = if (pages.isNotEmpty()) pageHost else listFor(tab)
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
        // the same slide-and-fade as between the tabs
        if (fromList !== pageHost) crossfade(fromList, searchList, 1)
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
        if (pages.isEmpty()) crossfade(searchList, listFor(tab), -1)
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

    private var searchFilter = 0

    private fun searchFilters(): List<String> = listOf(
        YoutubeSearchQueryHandlerFactory.VIDEOS,
        YoutubeSearchQueryHandlerFactory.CHANNELS,
        YoutubeSearchQueryHandlerFactory.PLAYLISTS
    )

    private fun searchHeader(query: String): List<Row> = listOf(
        Row.Title(query, null),
        Row.Chips(listOf(getString(R.string.ch_videos), getString(R.string.channels), getString(R.string.playlists)), searchFilter) {
            if (it != searchFilter) {
                searchFilter = it
                currentQuery = null
                search(query)
            }
        }
    )

    /** Search results of any kind as rows. */
    private fun resultRows(items: List<org.schabi.newpipe.extractor.InfoItem>): List<Row> = items.mapNotNull { item ->
        when (item) {
            is StreamInfoItem -> Video.from(this, item)?.let { Row.VideoRow(it) }
            is org.schabi.newpipe.extractor.channel.ChannelInfoItem -> Row.ChannelRow(
                item.name, Format.pick(item.thumbnails, 160),
                Format.joinDot(
                    if (item.subscriberCount > 0) getString(R.string.subscribers, Format.count(this, item.subscriberCount)) else null,
                    item.description?.takeIf { it.isNotBlank() }
                )
            ) { openChannel(item.url) }
            is org.schabi.newpipe.extractor.playlist.PlaylistInfoItem -> Row.Menu(
                R.drawable.ic_playlist, item.name,
                Format.joinDot(item.uploaderName, if (item.streamCount > 0) getString(R.string.n_videos, item.streamCount.toInt()) else null),
                Format.pick(item.thumbnails, 480)
            ) { openNpPlaylist(item.url, item.name) }
            else -> null
        }
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
        searchAdapter.submit(searchHeader(query) + Row.Loading)
        searchList.scrollToPosition(0)

        searchJob = lifecycleScope.launch {
            try {
                val (handler, info) = withContext(Dispatchers.IO) {
                    val h = yt.searchQHFactory.fromQuery(query, listOf(searchFilters()[searchFilter]), "")
                    h to SearchInfo.getInfo(yt, h)
                }
                if (currentQuery != query) return@launch
                queryHandler = handler
                val found = resultRows(info.relatedItems)
                nextPage = info.nextPage.takeIf { Page.isValid(it) }
                val rows = ArrayList<Row>(searchHeader(query))
                if (found.isEmpty()) {
                    rows.add(Row.Message(getString(R.string.no_results)))
                } else {
                    rows.addAll(found)
                    if (nextPage != null) rows.add(Row.Loading)
                }
                searchAdapter.submit(rows)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (currentQuery != query) return@launch
                searchAdapter.submit(
                    searchHeader(query) + Row.Message(Errors.text(this@MainActivity, e), getString(R.string.retry)) {
                        currentQuery = null
                        search(query)
                    }
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
                rows.addAll(resultRows(res.items))
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
