package com.example.glasstube

import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.Color
import android.graphics.Outline
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
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
import org.schabi.newpipe.extractor.stream.StreamInfoItem

class MainActivity : AppCompatActivity() {

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
    private lateinit var searchButton: FrameLayout
    private lateinit var searchBack: FrameLayout
    private lateinit var searchBar: LinearLayout
    private lateinit var field: EditText
    private lateinit var clear: ImageView

    private lateinit var homeAdapter: VideoAdapter
    private lateinit var youAdapter: VideoAdapter
    private lateinit var searchAdapter: VideoAdapter
    private lateinit var history: SearchHistory
    private lateinit var store: Store
    private lateinit var settings: Settings
    private lateinit var shorts: ShortsPager

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
        searchButton = findViewById(R.id.searchButton)
        searchBack = findViewById(R.id.searchBack)
        searchBar = findViewById(R.id.searchBar)
        field = findViewById(R.id.field)
        clear = findViewById(R.id.clear)
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
        shorts = ShortsPager(this, shortsList) { navBottom + dp(62) + dp(12) }

        setupGlass()
        setupTabs()
        setupSearch()
        setupInsets()
        setupScrolling()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    field.hasFocus() -> leaveField()
                    pages.isNotEmpty() -> popPage()
                    searchActive -> collapseSearch()
                    tab != Tab.HOME -> selectTab(Tab.HOME)
                    else -> finish()
                }
            }
        })

        selectTab(Tab.HOME, animate = false)
        loadHome()
        refreshAccountInBackground()
    }

    override fun onStart() {
        super.onStart()
        if (tab == Tab.SHORTS && pages.isEmpty() && !searchActive) shorts.onShown()
    }

    override fun onStop() {
        super.onStop()
        shorts.onHidden()
    }

    override fun onResume() {
        super.onResume()
        // the watch history may have changed on the player screen
        if (tab == Tab.YOU) buildYou()
        if (searchActive && currentQuery == null && !field.hasFocus()) showSearchIdle()
    }

    override fun onDestroy() {
        shorts.release()
        super.onDestroy()
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
        tabBar.background = Glass.skin(this, null, lensOn)
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
        tabIndicator.background = GradientDrawable().apply {
            cornerRadius = dpf(26f)
            setColor(if (night) Color.argb(46, 255, 255, 255) else Color.argb(20, 0, 0, 0))
        }
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

    private fun slot(l: LensEffect, index: Int, v: View, radius: Float?) {
        if (v.visibility != View.VISIBLE || v.alpha < 0.02f || v.width == 0) {
            l.clearSlot(index)
            return
        }
        var a = v.alpha
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
        val dockOn = dock.visibility == View.VISIBLE
        if (dockOn) {
            slot(l, 0, tabBar, null)
            slot(l, 1, if (searchBar.visibility == View.VISIBLE) searchBar else searchButton, null)
            slot(l, 2, searchBack, null)
        } else {
            l.clearSlot(0)
            l.clearSlot(1)
            l.clearSlot(2)
        }
        slot(l, 3, suggestions, dpf(24f))
        val top = pages.lastOrNull()
        if (top != null) slot(l, 4, top.back, null) else l.clearSlot(4)

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

    private fun setupTabs() {
        val labels = listOf(R.string.tab_home, R.string.tab_shorts, R.string.tab_you)
        val icons = listOf(R.drawable.ic_home, R.drawable.ic_shorts, R.drawable.ic_person)
        Tab.values().forEachIndexed { i, t ->
            val item = LinearLayout(this)
            item.orientation = LinearLayout.VERTICAL
            item.gravity = Gravity.CENTER
            val iv = ImageView(this)
            iv.setImageResource(icons[i])
            item.addView(iv, LinearLayout.LayoutParams(dp(24), dp(24)))
            val tv = TextView(this)
            tv.text = getString(labels[i])
            tv.textSize = 10.5f
            tv.gravity = Gravity.CENTER
            tv.setPadding(0, dp(2), 0, 0)
            item.addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            item.contentDescription = tv.text
            item.setOnClickListener {
                if (searchActive) collapseSearch()
                while (pages.isNotEmpty()) popPage(animate = false)
                if (tab == t) onTabReselected() else selectTab(t)
            }
            tabs.addView(item, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            tabViews.add(item)
            if (t == Tab.YOU) youIcon = iv
        }
        tabBar.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> placeIndicator(false) }
        updateYouIcon()
    }

    private fun placeIndicator(animate: Boolean) {
        val w = tabBar.width
        if (w == 0) return
        val tabW = w / 3f
        val lp = tabIndicator.layoutParams
        val iw = (tabW - dp(8)).toInt()
        if (lp.width != iw) {
            lp.width = iw
            tabIndicator.layoutParams = lp
        }
        val x = tabW * tab.ordinal + dp(4)
        if (animate) {
            tabIndicator.animate().translationX(x).setDuration(420L)
                .setInterpolator(OvershootInterpolator(1.3f))
                .setUpdateListener { root.invalidate() }.start()
            // a little squash while it travels, like a drop of glass
            tabIndicator.scaleX = 1.12f
            tabIndicator.animate().scaleX(1f).setDuration(420L).start()
        } else {
            tabIndicator.translationX = x
        }
    }

    private fun selectTab(t: Tab, animate: Boolean = true) {
        val old = tab
        tab = t
        val text = Glass.textColor(this)
        val accent = ContextCompat.getColor(this, R.color.accent)
        tabViews.forEachIndexed { i, v ->
            val selected = i == t.ordinal
            val col = if (selected) accent else text
            val iv = v.getChildAt(0) as ImageView
            if (!(i == Tab.YOU.ordinal && Account.photo(this) != null && Account.isSignedIn(this))) iv.setColorFilter(col)
            (v.getChildAt(1) as TextView).setTextColor(col)
        }
        placeIndicator(animate)
        if (old == Tab.SHORTS && t != Tab.SHORTS) shorts.onHidden()
        showContent()
        if (t == Tab.SHORTS) {
            shorts.setQueries(history.all().take(4))
            shorts.onShown()
        }
        if (t == Tab.YOU) buildYou()
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
        val dark = (tab == Tab.SHORTS && !searchActive && !pageOn) || Glass.isNight(this)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
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
            sugLp.bottomMargin = bottom + dp(12) + dp(62) + dp(10)
            suggestions.layoutParams = sugLp
            val listBottom = sys.bottom + dp(62) + dp(36)
            for (rv in listOf(homeList, youList, searchList)) rv.setPadding(0, sys.top, 0, listBottom)
            pages.forEach { it.applyInsets() }
            insets
        }
    }

    private fun setupScrolling() {
        homeList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val lm = rv.layoutManager as LinearLayoutManager
                if (lm.findLastVisibleItemPosition() >= homeAdapter.itemCount - 4 &&
                    homeAdapter.items.lastOrNull() == Row.Loading
                ) loadMoreHome()
            }
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
            list.setPadding(0, statusTop + dp(62), 0, navBottom + dp(62) + dp(36))
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
            ) { settings.hdr = it }
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
        val full = dock.width - dp(72)
        val small = dp(62)
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
        val small = dp(62)
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
        if (tab == Tab.SHORTS) shorts.onShown()
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
        startActivity(Intent(this, PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_URL, v.url))
    }
}
