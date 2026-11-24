package com.example.glasstube

import android.content.Intent
import android.graphics.Color
import android.graphics.Outline
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
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

    private lateinit var root: FrameLayout
    private lateinit var list: RecyclerView
    private lateinit var bar: LinearLayout
    private lateinit var field: EditText
    private lateinit var clear: ImageView
    private lateinit var suggestions: LinearLayout
    private lateinit var adapter: VideoAdapter
    private lateinit var history: SearchHistory

    private var lens: LensEffect? = null
    private var statusTop = 0
    private var bottomInset = 0

    private val yt = ServiceList.YouTube
    private var currentQuery: String? = null
    private var queryHandler: SearchQueryHandler? = null
    private var nextPage: Page? = null
    private var searchJob: Job? = null
    private var moreJob: Job? = null
    private var suggestJob: Job? = null

    private val density get() = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()
    private fun dpf(v: Float) = v * density

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        setContentView(R.layout.activity_main)
        val night = Glass.isNight(this)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !night
            isAppearanceLightNavigationBars = !night
        }

        root = findViewById(R.id.root)
        list = findViewById(R.id.list)
        bar = findViewById(R.id.bar)
        field = findViewById(R.id.field)
        clear = findViewById(R.id.clear)
        suggestions = findViewById(R.id.suggestions)
        history = SearchHistory(this)

        adapter = VideoAdapter(
            onVideo = { openVideo(it) },
            onHistory = { search(it) },
            onHistoryLong = {
                history.remove(it)
                if (currentQuery == null) showHome()
            }
        )
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        list.itemAnimator = null

        setupGlass()
        setupBar()
        setupInsets()

        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING && field.hasFocus()) leaveField()
            }

            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val lm = rv.layoutManager as LinearLayoutManager
                if (lm.findLastVisibleItemPosition() >= adapter.itemCount - 4 &&
                    adapter.items.lastOrNull() == Row.Loading && currentQuery != null && nextPage != null
                ) {
                    loadMore()
                }
            }
        })

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    field.hasFocus() -> leaveField()
                    currentQuery != null -> {
                        field.setText("")
                        showHome()
                    }
                    else -> finish()
                }
            }
        })

        showHome()
    }

    // ---------------------------------------------------------------- glass

    private fun setupGlass() {
        val lensOn = Glass.lensSupported() && try {
            lens = LensEffect(list).apply {
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

        bar.background = Glass.skin(this, null, lensOn)
        suggestions.background = Glass.skin(this, dpf(24f), lensOn)
        suggestions.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, dpf(24f))
            }
        }
        suggestions.clipToOutline = true

        root.viewTreeObserver.addOnPreDrawListener {
            updateLens()
            true
        }
    }

    private fun updateLens() {
        val l = lens ?: return
        val blur = dpf(2f)
        l.setSlot(0, bar.x, bar.y, bar.x + bar.width, bar.y + bar.height, bar.height / 2f, blur)
        if (suggestions.visibility == View.VISIBLE && suggestions.height > 0) {
            l.setSlot(
                1, suggestions.x, suggestions.y,
                suggestions.x + suggestions.width, suggestions.y + suggestions.height,
                dpf(24f), blur, suggestions.alpha
            )
        } else {
            l.clearSlot(1)
        }
        // content scrolled under the status bar flows into a soft blur
        val scrolled = list.computeVerticalScrollOffset() > 0
        if (statusTop > 0 && scrolled) {
            val tint = if (Glass.isNight(this)) Color.argb(90, 0, 0, 0) else Color.argb(70, 255, 255, 255)
            l.setTop(statusTop.toFloat(), dpf(18f), dpf(10f), tint, 0f, true)
        } else {
            l.setTop(0f, 0f, 0f, 0, 0f, false)
        }
        l.apply()
    }

    // ---------------------------------------------------------------- bar

    private fun setupBar() {
        val text = Glass.textColor(this)
        val muted = ColorUtils.setAlphaComponent(text, 140)
        field.setTextColor(text)
        field.setHintTextColor(muted)
        findViewById<ImageView>(R.id.searchIcon).setColorFilter(muted)
        clear.setColorFilter(muted)

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

    private fun leaveField() {
        hideKeyboard()
        field.clearFocus()
        hideSuggestions()
    }

    private fun showKeyboard() {
        getSystemService(InputMethodManager::class.java)?.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(field.windowToken, 0)
    }

    private fun setupInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            statusTop = sys.top
            bottomInset = maxOf(sys.bottom, ime.bottom)
            val barLp = bar.layoutParams as FrameLayout.LayoutParams
            barLp.bottomMargin = bottomInset + dp(12)
            bar.layoutParams = barLp
            val sugLp = suggestions.layoutParams as FrameLayout.LayoutParams
            sugLp.bottomMargin = bottomInset + dp(12) + dp(54) + dp(10)
            suggestions.layoutParams = sugLp
            list.setPadding(0, sys.top, 0, sys.bottom + dp(54) + dp(36))
            insets
        }
    }

    // ---------------------------------------------------------------- suggestions

    private fun scheduleSuggestions(text: String) {
        suggestJob?.cancel()
        if (!field.hasFocus()) return
        if (text.isBlank()) {
            val recent = history.all().take(5)
            if (recent.isEmpty()) hideSuggestions() else showSuggestions(recent, true)
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
                if (items.isEmpty()) hideSuggestions() else showSuggestions(items.take(6), false)
            }
        }
    }

    private fun showSuggestions(items: List<String>, fromHistory: Boolean) {
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
            icon.setImageResource(if (fromHistory) R.drawable.ic_history else R.drawable.ic_search)
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

            // fill the field without searching (like the arrow in YouTube)
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
            suggestions.scaleX = 0.96f
            suggestions.scaleY = 0.96f
            suggestions.pivotY = 9999f
            suggestions.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
                .setDuration(220L).setUpdateListener { root.invalidate() }.start()
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

    // ---------------------------------------------------------------- content

    private fun showHome() {
        searchJob?.cancel()
        moreJob?.cancel()
        currentQuery = null
        queryHandler = null
        nextPage = null
        val rows = ArrayList<Row>()
        rows.add(Row.Title(getString(R.string.app_name), getString(R.string.home_subtitle)))
        val recent = history.all()
        if (recent.isNotEmpty()) {
            rows.add(Row.Section(getString(R.string.recent_searches), getString(R.string.clear_history)) {
                history.clear()
                showHome()
            })
            recent.forEach { rows.add(Row.History(it)) }
        }
        adapter.submit(rows)
        list.scrollToPosition(0)
    }

    private fun search(raw: String) {
        val query = raw.trim()
        if (query.isEmpty()) return
        history.add(query)
        if (field.text.toString() != query) field.setText(query)
        leaveField()

        searchJob?.cancel()
        moreJob?.cancel()
        currentQuery = query
        queryHandler = null
        nextPage = null
        adapter.submit(listOf(Row.Title(query, null), Row.Loading))
        list.scrollToPosition(0)

        searchJob = lifecycleScope.launch {
            try {
                val (handler, info) = withContext(Dispatchers.IO) {
                    val h = yt.searchQHFactory.fromQuery(
                        query, listOf(YoutubeSearchQueryHandlerFactory.VIDEOS), ""
                    )
                    h to SearchInfo.getInfo(yt, h)
                }
                if (currentQuery != query) return@launch
                queryHandler = handler
                val videos = info.relatedItems.filterIsInstance<StreamInfoItem>()
                nextPage = info.nextPage.takeIf { Page.isValid(it) }
                val rows = ArrayList<Row>()
                rows.add(Row.Title(query, null))
                if (videos.isEmpty()) {
                    rows.add(Row.Message(getString(R.string.no_results), null, null))
                } else {
                    videos.forEach { rows.add(Row.Video(it)) }
                    if (nextPage != null) rows.add(Row.Loading)
                }
                adapter.submit(rows)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (currentQuery != query) return@launch
                adapter.submit(
                    listOf(
                        Row.Title(query, null),
                        Row.Message(Errors.text(this@MainActivity, e), getString(R.string.retry)) { search(query) }
                    )
                )
            }
        }
    }

    private fun loadMore() {
        val page = nextPage ?: return
        val handler = queryHandler ?: return
        val query = currentQuery ?: return
        if (moreJob?.isActive == true) return
        if (adapter.items.lastOrNull() is Row.Message) adapter.append(listOf(Row.Loading))
        moreJob = lifecycleScope.launch {
            try {
                val res = withContext(Dispatchers.IO) { SearchInfo.getMoreItems(yt, handler, page) }
                if (currentQuery != query) return@launch
                nextPage = res.nextPage.takeIf { Page.isValid(it) }
                val rows = ArrayList<Row>()
                res.items.filterIsInstance<StreamInfoItem>().forEach { rows.add(Row.Video(it)) }
                if (nextPage != null) rows.add(Row.Loading)
                adapter.append(rows)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (currentQuery != query) return@launch
                adapter.append(
                    listOf(Row.Message(Errors.text(this@MainActivity, e), getString(R.string.retry)) { loadMore() })
                )
            }
        }
    }

    private fun openVideo(item: StreamInfoItem) {
        startActivity(Intent(this, PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_URL, item.url))
    }

    override fun onResume() {
        super.onResume()
        // the history may have changed on the player screen
        if (currentQuery == null && !field.hasFocus()) showHome()
    }

    @Suppress("unused")
    private fun color(id: Int) = ContextCompat.getColor(this, id)
}
