package com.example.glasstube

import android.Manifest
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Outline
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.text.Html
import android.view.GestureDetector
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.CircleCropTransformation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.comments.CommentsInfo
import org.schabi.newpipe.extractor.comments.CommentsInfoItem
import org.schabi.newpipe.extractor.stream.Description
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import kotlin.math.abs
import kotlin.math.pow

/**
 * The player as a sheet over the app: expanded it is the full player screen; collapsed, the
 * video shrinks into the mini player above the tab bar (and can be dragged between the two).
 * The buttons and the settings menu over the video are real liquid glass: the lens refracts the
 * video under them.
 */
@OptIn(UnstableApi::class)
class PlayerSheet(
    private val activity: AppCompatActivity,
    parent: FrameLayout,
    private val host: Host
) {
    interface Host {
        /** Where the mini player's video slot is (in the parent's coordinates); false if hidden. */
        fun miniThumbRect(out: RectF): Boolean
        fun onSheetProgress(f: Float)
        fun onNowPlaying(title: String?, channel: String?)
        fun onOpenChannel(url: String)
        fun onVideoActions(video: Video)
        fun onPlayingChanged(playing: Boolean)
        fun onSheetClosed()
        /** The mini player being swiped down: move it by [dy] and fade it to [alpha]. */
        fun onMiniOffset(dy: Float, alpha: Float)
        fun statusTop(): Int
        fun navBottom(): Int
    }

    val view: FrameLayout = LayoutInflater.from(activity).inflate(R.layout.view_player_sheet, parent, false) as FrameLayout
    private val sheetBg: View = view.findViewById(R.id.sheetBg)
    private val sheetStatus: View = view.findViewById(R.id.sheetStatus)
    private val details: RecyclerView = view.findViewById(R.id.details)
    private val videoBox: FrameLayout = view.findViewById(R.id.videoBox)
    private val playerHolder: FrameLayout = view.findViewById(R.id.playerHolder)
    private val controls: FrameLayout = view.findViewById(R.id.controls)
    private val loading: ProgressBar = view.findViewById(R.id.loading)
    private val timeRow: View = view.findViewById(R.id.timeRow)
    private val timeNow: TextView = view.findViewById(R.id.timeNow)
    private val timeTotal: TextView = view.findViewById(R.id.timeTotal)
    private val chapterChip: TextView = view.findViewById(R.id.chapterChip)
    private val seek: GlassSeekBar = view.findViewById(R.id.seek)
    private val icPlay: PlayPauseView = view.findViewById(R.id.icPlay)
    private val icFull: ImageView = view.findViewById(R.id.icFull)
    private val menuScrim: FrameLayout = view.findViewById(R.id.menuScrim)
    private val menuScroll: ScrollView = view.findViewById(R.id.menuScroll)
    private val menu: LinearLayout = view.findViewById(R.id.menu)
    private val commentsPanel: LinearLayout = view.findViewById(R.id.commentsPanel)
    private val commentsTitle: TextView = view.findViewById(R.id.commentsTitle)
    private val commentsList: RecyclerView = view.findViewById(R.id.commentsList)
    private val buttons: List<View> = listOf(R.id.btnDown, R.id.btnSettings, R.id.btnBack10, R.id.btnPlay, R.id.btnFwd10, R.id.btnFull)
        .map { view.findViewById<View>(it) }

    private val adapter = VideoAdapter(onVideo = { open(it.url) }, onVideoLong = { host.onVideoActions(it) }, onChannel = { openChannel(it) })
    private val commentsAdapter = VideoAdapter(onVideo = { })
    private val settings = Settings(activity)
    private val store = Store(activity)
    private val yt = ServiceList.YouTube
    private val density = activity.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()
    private fun dpf(v: Float) = v * density

    private var playerView: PlayerView? = null
    private var lens: LensEffect? = null

    private var service: PlaybackService? = null
    private var bound = false
    private var pendingInfo: StreamInfo? = null
    private var pendingStart = 0L
    var currentUrl: String? = null
        private set
    private var currentId: String? = null
    private var nextUp: Video? = null
    private var loadJob: Job? = null
    private var progressJob: Job? = null
    private var hideJob: Job? = null
    private var loadingInfo = false
    private var buffering = false

    /** 0 = mini player, 1 = full player. */
    var progress = 0f
        private set
    private var progressAnim: ValueAnimator? = null
    private var controlsShown = false
    var fullscreen = false
        private set
    private var fullscreenByRotation = false
    private var orientationListener: OrientationEventListener? = null

    /** Videos queued with "Play next" (before autoplay). */
    val queue = ArrayList<Video>()
    private var detailHeader: View? = null
    private var related: List<Video> = emptyList()
    private var pip = false
    private var frameset: org.schabi.newpipe.extractor.stream.Frameset? = null
    private var chapters: List<org.schabi.newpipe.extractor.stream.StreamSegment> = emptyList()
    private val storyboards = HashMap<Int, android.graphics.Bitmap>()
    private val storyboardLoading = HashSet<Int>()
    private var videoThumb: String? = null
    private var chapterIndex = -1
    private val chaptersPanel: DismissPanel = DismissPanel(activity).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.panel_bg)
        isClickable = true
        isFocusable = true
        visibility = View.GONE
    }
    private val chaptersList = RecyclerView(activity)

    private val chaptersAdapter = object : RecyclerView.Adapter<ChapterHolder>() {
        override fun getItemCount() = chapters.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChapterHolder {
            val row = LinearLayout(activity)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(dp(16), dp(9), dp(16), dp(9))
            row.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            val img = ImageView(activity)
            img.scaleType = ImageView.ScaleType.CENTER_CROP
            img.setBackgroundColor(Color.argb(40, 128, 128, 128))
            img.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, outline: Outline) {
                    outline.setRoundRect(0, 0, v.width, v.height, dpf(10f))
                }
            }
            img.clipToOutline = true
            row.addView(img, LinearLayout.LayoutParams(dp(136), dp(76)))
            val col = LinearLayout(activity)
            col.orientation = LinearLayout.VERTICAL
            val t = TextView(activity)
            t.setTextColor(Glass.textColor(activity))
            t.textSize = 15f
            t.maxLines = 2
            t.ellipsize = android.text.TextUtils.TruncateAt.END
            col.addView(t)
            val time = TextView(activity)
            time.textSize = 12f
            time.setPadding(dp(7), dp(2), dp(7), dp(2))
            col.addView(time, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(6)
            })
            row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(14) })
            return ChapterHolder(row)
        }

        override fun onBindViewHolder(h: ChapterHolder, position: Int) {
            val c = chapters[position]
            val current = position == chapterIndex
            val text = Glass.textColor(activity)
            h.title.text = c.title
            h.title.typeface = if (current) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            h.time.text = clock(c.startTimeSeconds * 1000L)
            h.time.setTextColor(if (current) text else androidx.core.graphics.ColorUtils.setAlphaComponent(text, 200))
            h.time.background = GradientDrawable().apply {
                cornerRadius = dpf(7f)
                setColor(androidx.core.graphics.ColorUtils.setAlphaComponent(text, if (current) 46 else 24))
            }
            h.itemView.background = if (current) GradientDrawable().apply {
                setColor(androidx.core.graphics.ColorUtils.setAlphaComponent(text, 18))
            } else null
            val preview = c.previewUrl
            when {
                !preview.isNullOrEmpty() -> h.thumb.load(preview) { crossfade(150) }
                else -> {
                    val frame = storyboardFrame(c.startTimeSeconds * 1000L) { notifyItemChanged(position) }
                    if (frame != null) h.thumb.setImageBitmap(frame)
                    else h.thumb.load(videoThumb) { crossfade(150) }
                }
            }
            h.itemView.setOnClickListener {
                val p = service?.player ?: return@setOnClickListener
                p.seekTo(c.startTimeSeconds * 1000L)
                updateChapterChip(c.startTimeSeconds * 1000L)
                if (!p.isPlaying) p.play()
            }
        }
    }

    private val previewBox: LinearLayout
    private val previewImage: ImageView
    private val previewText: TextView
    private val hud: TextView

    // comments
    private var commentsInfo: CommentsInfo? = null
    private var commentsNext: Page? = null
    private var commentsJob: Job? = null
    private var commentsMoreJob: Job? = null

    // rating
    private var likeStatus = "INDIFFERENT"
    private var baseLikes = -1L
    private var likePill: TextView? = null
    private var dislikePill: TextView? = null

    val isOpen: Boolean get() = view.visibility == View.VISIBLE
    val isPlaying: Boolean get() = service?.player?.isPlaying == true

    private val notificationPermission =
        activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val playerListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            buffering = false
            updateLoading()
            val cause = error.cause
            val text = if (cause != null) Errors.text(activity, cause) else error.errorCodeName
            Toast.makeText(activity, activity.getString(R.string.playback_error, text), Toast.LENGTH_LONG).show()
            showControls(autoHide = false)
        }

        override fun onPlaybackStateChanged(state: Int) {
            buffering = state == Player.STATE_BUFFERING
            updateLoading()
            if (state == Player.STATE_ENDED) {
                savePosition()
                val queued = queue.firstOrNull()
                val next = nextUp
                when {
                    queued != null -> {
                        queue.removeAt(0)
                        load(queued.url)
                    }
                    settings.autoplay && next != null -> load(next.url)
                    else -> showControls(autoHide = false)
                }
            }
            updatePlayIcon()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            playerView?.keepScreenOn = isPlaying
            updatePlayIcon()
            host.onPlayingChanged(isPlaying)
            if (!isPlaying) savePosition()
            if (isPlaying && controlsShown) scheduleHide()
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val svc = (binder as? PlaybackService.LocalBinder)?.service ?: return
            service = svc
            svc.player.addListener(playerListener)
            svc.onSkipped = { seg ->
                Toast.makeText(activity, activity.getString(R.string.sb_skipped, SponsorBlock.label(activity, seg.category)), Toast.LENGTH_SHORT).show()
            }
            playerView?.player = svc.player
            setVideoEnabled(true)
            updatePlayIcon(animate = false)
            val pending = pendingInfo
            if (pending != null) {
                pendingInfo = null
                startPlayback(pending, pendingStart)
            } else if (currentUrl == null && svc.currentDownload != null) {
                val d = svc.currentDownload!!
                currentUrl = svc.currentUrl
                currentId = d.id
                view.visibility = View.VISIBLE
                ensurePlayerView()
                showOfflineDetails(d)
                host.onNowPlaying(d.title, d.channel)
                view.post { setProgress(0f) }
            } else if (currentUrl == null && svc.currentInfo != null) {
                // the app was reopened while a video kept playing: show it in the mini player
                val info = svc.currentInfo!!
                currentUrl = svc.currentUrl
                currentId = Video.idOf(info.url)
                view.visibility = View.VISIBLE
                ensurePlayerView()
                showDetails(info, record = false)
                host.onNowPlaying(info.name, info.uploaderName)
                view.post { setProgress(0f) }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    init {
        parent.addView(view)
        details.layoutManager = LinearLayoutManager(activity)
        details.adapter = adapter
        details.itemAnimator = null
        commentsList.layoutManager = LinearLayoutManager(activity)
        commentsList.adapter = commentsAdapter
        setupChapters()
        commentsList.itemAnimator = null
        commentsList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val lm = rv.layoutManager as LinearLayoutManager
                if (lm.findLastVisibleItemPosition() >= commentsAdapter.itemCount - 4 &&
                    commentsAdapter.items.lastOrNull() == Row.Loading
                ) loadMoreComments()
            }
        })
        view.findViewById<ImageView>(R.id.commentsClose).apply {
            setColorFilter(Glass.textColor(activity))
            setOnClickListener { closeComments() }
        }
        videoBox.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: Outline) {
                outline.setRoundRect(0, 0, v.width, v.height, corner)
            }
        }
        videoBox.clipToOutline = true
        previewBox = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            visibility = View.GONE
        }
        previewImage = ImageView(activity).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, outline: Outline) {
                    outline.setRoundRect(0, 0, v.width, v.height, dpf(10f))
                }
            }
            clipToOutline = true
            background = Glass.overVideo(activity, dpf(10f))
        }
        previewBox.addView(previewImage, LinearLayout.LayoutParams(dp(160), dp(90)))
        previewText = TextView(activity).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            gravity = Gravity.CENTER
            maxLines = 2
            setShadowLayer(5f, 0f, 1f, Color.argb(170, 0, 0, 0))
        }
        previewBox.addView(previewText, LinearLayout.LayoutParams(dp(180), ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        videoBox.addView(previewBox, FrameLayout.LayoutParams(dp(180), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START))
        hud = TextView(activity).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(18), dp(10))
            compoundDrawablePadding = dp(10)
            background = Glass.overVideo(activity, dpf(22f))
            visibility = View.GONE
        }
        videoBox.addView(hud, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(40) })
        setupControls()
        controls.alpha = 0f
        controls.visibility = View.INVISIBLE
        view.viewTreeObserver.addOnPreDrawListener {
            updateLens()
            true
        }
        view.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (r - l != or - ol || b - t != ob - ot) view.post { applyGeometry() }
        }
        activity.bindService(
            Intent(activity, PlaybackService::class.java).setAction(PlaybackService.ACTION_LOCAL),
            connection, Context.BIND_AUTO_CREATE
        )
        bound = true
    }

    private var corner = 0f

    // ================================================================ player view + lens

    private fun ensurePlayerView() {
        if (playerView != null) return
        val pv = LayoutInflater.from(activity).inflate(R.layout.view_player_texture, playerHolder, false) as PlayerView
        playerHolder.addView(pv)
        pv.player = service?.player
        playerView = pv
        lens = if (Glass.lensSupported()) {
            try {
                LensEffect(pv).apply {
                    setParams(dpf(22f), dpf(24f), 0.15f)
                    setRim(0, 1f)
                    setConvex(0f, 0f)
                }
            } catch (e: Throwable) {
                null
            }
        } else null
        val lensOn = lens != null
        for (b in buttons) b.background = Glass.overVideo(activity, null, lensOn)
    }

    private val tmp = RectF()
    private val loc = IntArray(2)
    private val loc2 = IntArray(2)

    /** Position of [v] inside [ancestor] in the ancestor's own (unscaled) units. */
    private fun offsetIn(v: View, ancestor: View, out: FloatArray) {
        var x = 0f
        var y = 0f
        var cur: View? = v
        while (cur != null && cur !== ancestor) {
            x += cur.left + cur.translationX
            y += cur.top + cur.translationY
            cur = cur.parent as? View
        }
        out[0] = x
        out[1] = y
    }

    private val offA = FloatArray(2)
    private val offB = FloatArray(2)

    /**
     * The button's rect in the player view's own coordinates. Measured inside the video box, so a
     * scale on the box (the springy grow when turning to fullscreen) can't shift the glass away
     * from the button: on screen positions the scale would be counted twice.
     */
    private fun lensRect(v: View) {
        val pv = playerView ?: return
        offsetIn(pv, videoBox, offA)
        offsetIn(v, videoBox, offB)
        // a button scaled around its centre (the pop-in) is drawn shifted from its layout place
        val left = offB[0] - offA[0] + v.pivotX * (1f - v.scaleX)
        val top = offB[1] - offA[1] + v.pivotY * (1f - v.scaleY)
        tmp.set(left, top, left + v.width * v.scaleX, top + v.height * v.scaleY)
    }

    private fun updateLens() {
        val l = lens ?: return
        if (playerView == null) return
        val menuOn = menuScrim.visibility == View.VISIBLE && menuScroll.alpha > 0.02f
        val buttonsOn = progress >= 0.999f && controls.visibility == View.VISIBLE && controls.alpha > 0.02f
        if (!menuOn && !buttonsOn) {
            l.clear()
            return
        }
        if (menuOn) {
            lensRect(menuScroll)
            l.setSlot(0, tmp.left, tmp.top, tmp.right, tmp.bottom, dpf(22f), dpf(6f), menuScroll.alpha)
            for (i in 1 until LensEffect.SLOTS) l.clearSlot(i)
        } else {
            buttons.forEachIndexed { i, b ->
                lensRect(b)
                l.setSlot(i, tmp.left, tmp.top, tmp.right, tmp.bottom, tmp.height() / 2f, dpf(1.5f), controls.alpha * b.alpha)
            }
        }
        l.setTop(0f, 0f, 0f, 0, 0f, false)
        l.apply()
    }

    // ================================================================ open / expand / collapse

    fun open(url: String) {
        val wasOpen = isOpen
        view.visibility = View.VISIBLE
        videoBox.translationY = 0f
        videoBox.alpha = 1f
        host.onMiniOffset(0f, 1f)
        ensurePlayerView()
        if (!wasOpen) {
            progress = 0f
            view.post {
                applyGeometry()
                animateTo(1f)
            }
        } else {
            animateTo(1f)
        }
        load(url)
    }

    fun expand() = animateTo(1f)

    fun collapse() {
        if (fullscreen) exitFullscreen()
        hideMenuNow()
        if (commentsPanel.visibility == View.VISIBLE) closeComments()
        closeChapters(animate = false)
        hideControls(fast = true)
        animateTo(0f)
    }

    fun close() {
        savePosition()
        loadJob?.cancel()
        service?.stopPlayback()
        currentUrl = null
        currentId = null
        progressAnim?.cancel()
        view.visibility = View.GONE
        videoBox.translationY = 0f
        videoBox.alpha = 1f
        videoBox.scaleX = 1f
        videoBox.scaleY = 1f
        progress = 0f
        host.onMiniOffset(0f, 1f)
        host.onSheetProgress(0f)
        host.onNowPlaying(null, null)
        host.onSheetClosed()
    }

    /** Back: menu, comments, fullscreen, then collapse. False if there was nothing to do. */
    fun handleBack(): Boolean {
        if (!isOpen) return false
        return when {
            menuScrim.visibility == View.VISIBLE -> {
                hideMenu()
                true
            }
            chaptersPanel.visibility == View.VISIBLE && !chaptersClosing -> {
                closeChapters()
                true
            }
            commentsPanel.visibility == View.VISIBLE -> {
                closeComments()
                true
            }
            fullscreen -> {
                exitFullscreen()
                true
            }
            progress > 0.5f -> {
                collapse()
                true
            }
            else -> false
        }
    }

    /** Emphasized easing: quick start, long gentle stop at both ends of the travel. */
    private val sheetEase = PathInterpolator(0.2f, 0f, 0f, 1f)

    private fun animateTo(target: Float) {
        progressAnim?.cancel()
        val start = progress
        if (start == target) {
            setProgress(target)
            if (target >= 1f) revealControls()
            return
        }
        if (target < 1f) hideControls(fast = true)
        progressAnim = ValueAnimator.ofFloat(start, target).apply {
            duration = (300 + 220 * abs(target - start)).toLong()
            interpolator = sheetEase
            addUpdateListener { setProgress(it.animatedValue as Float) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (progress >= 0.999f) revealControls()
                }
            })
            start()
        }
    }

    private fun setProgress(f: Float) {
        progress = f.coerceIn(0f, 1f)
        applyGeometry()
        host.onSheetProgress(progress)
    }

    private val miniRect = RectF()
    private val fullRect = RectF()

    fun applyGeometry() {
        val w = view.width.toFloat()
        val h = view.height.toFloat()
        if (w <= 0f || h <= 0f) return
        val f = progress
        val statusTop = host.statusTop()
        if (fullscreen || pip) fullRect.set(0f, 0f, w, h)
        else fullRect.set(0f, statusTop.toFloat(), w, statusTop + w * 9f / 16f)
        if (!host.miniThumbRect(miniRect)) {
            val mh = dpf(42f)
            val bottom = h - host.navBottom() - dpf(12f + 56f + 8f + 7f)
            miniRect.set(dpf(23f), bottom - mh, dpf(23f) + mh * 16f / 9f, bottom)
        }
        // size follows the progress, the centre moves on an arc (sideways first)
        val fx = 1f - (1f - f).pow(1.7f)
        val fy = f.pow(1.25f)
        val nwF = lerp(miniRect.width(), fullRect.width(), f)
        val nhF = lerp(miniRect.height(), fullRect.height(), f)
        val cx = lerp(miniRect.centerX(), fullRect.centerX(), fx)
        val cy = lerp(miniRect.centerY(), fullRect.centerY(), fy)
        val l = cx - nwF / 2f
        val t = cy - nhF / 2f
        val lp = videoBox.layoutParams as FrameLayout.LayoutParams
        val nw = nwF.toInt()
        val nh = nhF.toInt()
        if (lp.width != nw || lp.height != nh || lp.leftMargin != l.toInt() || lp.topMargin != t.toInt()) {
            lp.width = nw
            lp.height = nh
            lp.leftMargin = l.toInt()
            lp.topMargin = t.toInt()
            videoBox.layoutParams = lp
        }
        // in the mini player the video's corners follow the pill around it (concentric), and
        // they straighten out smoothly while it grows into the full player
        val miniCorner = miniRect.height() / 2f
        val e = 1f - (1f - f) * (1f - f)
        corner = lerp(miniCorner, 0f, e).coerceAtMost(nhF / 2f)
        videoBox.invalidateOutline()

        val shown = f > 0.001f
        sheetBg.alpha = f
        sheetBg.visibility = if (shown) View.VISIBLE else View.INVISIBLE
        val sl = sheetStatus.layoutParams
        val sh = if (fullscreen || pip) 0 else statusTop
        if (sl.height != sh) {
            sl.height = sh
            sheetStatus.layoutParams = sl
        }
        sheetStatus.alpha = f
        details.visibility = if (shown && !fullscreen && !pip) View.VISIBLE else View.INVISIBLE
        // the page under the video (title, comments, up next) only shows near the full player:
        // while the player shrinks or grows it blurs away quickly instead of sliding over the app
        val dv = ((f - 0.62f) / 0.38f).coerceIn(0f, 1f)
        val dEase = dv * dv * (3f - 2f * dv)
        details.alpha = dEase
        details.translationY = (1f - f) * dpf(120f)
        applyDetailsBlur(if (shown) (1f - dEase) * dpf(22f) else 0f)
        val top = fullRect.bottom.toInt()
        if (details.paddingTop != top) details.setPadding(0, top, 0, host.navBottom() + dp(16))
        val cp = commentsPanel.layoutParams as FrameLayout.LayoutParams
        if (cp.topMargin != top) {
            cp.topMargin = top
            commentsPanel.layoutParams = cp
        }
        val late = if (pip) 0f else ((f - 0.8f) / 0.2f).coerceIn(0f, 1f)
        if (fullscreen && !controlsShown && !seek.scrubbing) {
            seek.animate().cancel()
            seek.alpha = 0f
            seek.visibility = View.INVISIBLE
        } else {
            seek.animate().cancel()
            seek.alpha = late
            seek.visibility = if (late > 0f) View.VISIBLE else View.INVISIBLE
        }
        layoutChapters()
        if (f < 0.999f && controls.visibility == View.VISIBLE && !controlsShown) controls.visibility = View.INVISIBLE
        updateLoading()
    }

    private fun lerp(a: Float, b: Float, f: Float) = a + (b - a) * f

    private var detailsBlur = -1

    private fun applyDetailsBlur(radius: Float) {
        if (Build.VERSION.SDK_INT < 31) return
        val r = radius.toInt()
        if (r == detailsBlur) return
        detailsBlur = r
        details.setRenderEffect(
            if (r < 1 || details.alpha <= 0f) null
            else android.graphics.RenderEffect.createBlurEffect(r.toFloat(), r.toFloat(), android.graphics.Shader.TileMode.CLAMP)
        )
    }

    // ================================================================ touch on the video

    private enum class Drag { NONE, SHEET, DISMISS, FULL_UP, FULL_DOWN, VOLUME, BRIGHTNESS }

    private var startLevel = 0f

    private val slop = ViewConfiguration.get(activity).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var startProgress = 0f
    private var drag = Drag.NONE
    private var downXLocal = 0f
    private var velocity: VelocityTracker? = null

    private val gestures = GestureDetector(activity, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (progress < 0.5f) {
                expand()
                return true
            }
            return false
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (progress < 0.999f) return true
            if (controlsShown) hideControls() else showControls(autoHide = true)
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (progress < 0.999f) return true
            val third = videoBox.width / 3f
            val step = settings.seekStep * 1000L
            when {
                e.x < third -> seekBy(-step)
                e.x > third * 2 -> seekBy(step)
                else -> togglePlay()
            }
            return true
        }
    })

    private fun travel(): Float = (miniRect.top - fullRect.top).coerceAtLeast(dpf(200f))

    @SuppressLint("ClickableViewAccessibility")
    private fun onVideoTouch(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX
                downY = ev.rawY
                downXLocal = ev.x
                startProgress = progress
                startLevel = if (downXLocal < videoBox.width / 2f) currentBrightness() else currentVolume()
                drag = Drag.NONE
                velocity?.recycle()
                velocity = VelocityTracker.obtain()
                trackVelocity(ev)
                gestures.onTouchEvent(ev)
            }
            MotionEvent.ACTION_MOVE -> {
                trackVelocity(ev)
                val dx = ev.rawX - downX
                val dy = ev.rawY - downY
                if (drag == Drag.NONE && abs(dy) > slop && abs(dy) > abs(dx)) {
                    drag = when {
                        // the outer strips (about a fifth of the width each) set brightness / volume,
                        // the middle leaves fullscreen
                        fullscreen && settings.swipeControls && downXLocal < videoBox.width * 0.21f -> Drag.BRIGHTNESS
                        fullscreen && settings.swipeControls && downXLocal > videoBox.width * 0.82f -> Drag.VOLUME
                        fullscreen -> if (dy > 0) Drag.FULL_DOWN else Drag.NONE
                        startProgress < 0.01f -> if (dy > 0) Drag.DISMISS else Drag.SHEET
                        startProgress > 0.99f && dy < 0 -> Drag.FULL_UP
                        else -> Drag.SHEET
                    }
                    if (drag != Drag.NONE) {
                        progressAnim?.cancel()
                        val cancel = MotionEvent.obtain(ev)
                        cancel.action = MotionEvent.ACTION_CANCEL
                        gestures.onTouchEvent(cancel)
                        cancel.recycle()
                        if (drag == Drag.SHEET && controlsShown) hideControls(fast = true)
                    }
                }
                when (drag) {
                    Drag.SHEET -> setProgress(startProgress - dy / travel())
                    Drag.DISMISS -> {
                        val off = dy.coerceAtLeast(0f)
                        val a = (1f - off / dpf(160f)).coerceIn(0f, 1f)
                        videoBox.translationY = off
                        videoBox.alpha = a
                        host.onMiniOffset(off, a)
                    }
                    Drag.FULL_UP -> {
                        // a hint of the turn to come: the video grows a little
                        val s = 1f + (-dy / dpf(900f)).coerceIn(0f, 0.08f)
                        videoBox.pivotY = videoBox.height.toFloat()
                        videoBox.scaleX = s
                        videoBox.scaleY = s
                    }
                    Drag.FULL_DOWN -> {
                        videoBox.translationY = dy.coerceAtLeast(0f) * 0.35f
                        videoBox.scaleX = 1f - (dy / dpf(3000f)).coerceIn(0f, 0.08f)
                        videoBox.scaleY = videoBox.scaleX
                    }
                    Drag.BRIGHTNESS -> setBrightness(startLevel - dy / (videoBox.height * 0.75f))
                    Drag.VOLUME -> setVolume(startLevel - dy / (videoBox.height * 0.75f))
                    Drag.NONE -> gestures.onTouchEvent(ev)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                trackVelocity(ev)
                velocity?.computeCurrentVelocity(1000)
                val vy = velocity?.yVelocity ?: 0f
                val dy = ev.rawY - downY
                when (drag) {
                    Drag.SHEET -> {
                        // started pulling down from the full player: it goes to the mini player,
                        // however little it moved (only a clear fling back up keeps it open);
                        // from the mini player the same the other way round
                        val target = if (startProgress > 0.5f) {
                            if (vy < -1200 && dy < 0) 1f else 0f
                        } else {
                            if (vy > 1200 && dy > 0) 0f else 1f
                        }
                        animateTo(target)
                    }
                    Drag.DISMISS -> {
                        if (dy > dpf(56f) || vy > 800) dismissMini() else springBackMini()
                    }
                    Drag.FULL_UP -> {
                        springBackScale()
                        if (dy < -dpf(50f) || vy < -900) enterFullscreen(rotate = true)
                    }
                    Drag.FULL_DOWN -> {
                        springBackScale()
                        if (dy > dpf(50f) || vy > 900) exitFullscreen()
                    }
                    Drag.VOLUME, Drag.BRIGHTNESS -> hideHud()
                    Drag.NONE -> gestures.onTouchEvent(ev)
                }
                drag = Drag.NONE
                velocity?.recycle()
                velocity = null
            }
        }
        return true
    }

    // ---- volume / brightness (fullscreen, left and right side)

    private val audio = activity.getSystemService(android.media.AudioManager::class.java)

    private fun currentVolume(): Float {
        val a = audio ?: return 0f
        val max = a.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return a.getStreamVolume(android.media.AudioManager.STREAM_MUSIC).toFloat() / max
    }

    private fun setVolume(v: Float) {
        val a = audio ?: return
        val max = a.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
        val level = v.coerceIn(0f, 1f)
        a.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, Math.round(level * max), 0)
        showHud(R.drawable.ic_volume, level)
    }

    private fun currentBrightness(): Float {
        val b = activity.window.attributes.screenBrightness
        if (b >= 0f) return b
        return try {
            android.provider.Settings.System.getInt(activity.contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS) / 255f
        } catch (e: Exception) {
            0.5f
        }
    }

    private fun setBrightness(v: Float) {
        val level = v.coerceIn(0.01f, 1f)
        activity.window.attributes = activity.window.attributes.apply { screenBrightness = level }
        showHud(R.drawable.ic_brightness, level)
    }

    private fun showHud(icon: Int, level: Float) {
        val d = ContextCompat.getDrawable(activity, icon)?.mutate()
        d?.setBounds(0, 0, dp(20), dp(20))
        d?.setTint(Color.WHITE)
        hud.setCompoundDrawablesRelative(d, null, null, null)
        hud.text = "${Math.round(level * 100)}%"
        if (hud.visibility != View.VISIBLE) {
            hud.visibility = View.VISIBLE
            hud.alpha = 0f
            hud.animate().alpha(1f).setDuration(120).start()
        }
    }

    private fun hideHud() {
        hud.animate().alpha(0f).setDuration(260).withEndAction { hud.visibility = View.GONE }.start()
    }

    /** Velocity in screen coordinates (the video moves under the finger while dragging). */
    private fun trackVelocity(ev: MotionEvent) {
        val e = MotionEvent.obtain(ev)
        e.setLocation(ev.rawX, ev.rawY)
        velocity?.addMovement(e)
        e.recycle()
    }

    private fun springBackScale() {
        videoBox.animate().scaleX(1f).scaleY(1f).translationY(0f).setDuration(320)
            .setInterpolator(OvershootInterpolator(1.4f)).start()
    }

    private fun springBackMini() {
        val start = videoBox.translationY
        ValueAnimator.ofFloat(start, 0f).apply {
            duration = 300
            interpolator = OvershootInterpolator(1.2f)
            addUpdateListener {
                val off = it.animatedValue as Float
                val a = (1f - off / dpf(160f)).coerceIn(0f, 1f)
                videoBox.translationY = off
                videoBox.alpha = a
                host.onMiniOffset(off, a)
            }
            start()
        }
    }

    /** Closes the mini player with its disappearing animation (X button). */
    fun dismiss() {
        if (!isOpen) return
        if (progress > 0.01f) {
            close()
            return
        }
        dismissMini()
    }

    /** Mini player swiped away: it sinks, shrinks and fades out, then playback stops. */
    private fun dismissMini() {
        val start = videoBox.translationY
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 280
            interpolator = DecelerateInterpolator(1.5f)
            addUpdateListener {
                val t = it.animatedValue as Float
                val off = start + (dpf(140f) - start) * t
                val a = (1f - t) * (1f - start / dpf(160f)).coerceIn(0f, 1f)
                videoBox.translationY = off
                videoBox.alpha = a
                videoBox.scaleX = 1f - 0.15f * t
                videoBox.scaleY = 1f - 0.15f * t
                host.onMiniOffset(off, a)
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    close()
                }
            })
            start()
        }
    }

    /** The mini player's own touches (outside the video): tap, swipe up or swipe away. */
    fun forwardMiniTouch(ev: MotionEvent): Boolean = onVideoTouch(ev)

    // ================================================================ controls

    @SuppressLint("ClickableViewAccessibility")
    private fun setupControls() {
        for (id in listOf(R.id.icDown, R.id.icSettings, R.id.icBack10, R.id.icFwd10, R.id.icFull)) {
            view.findViewById<ImageView>(id).setColorFilter(Color.WHITE)
        }
        icPlay.setColor(Color.WHITE)
        press(view.findViewById(R.id.btnPlay)) { togglePlay() }
        press(view.findViewById(R.id.btnBack10)) { seekBy(-settings.seekStep * 1000L) }
        press(view.findViewById(R.id.btnFwd10)) { seekBy(settings.seekStep * 1000L) }
        press(view.findViewById(R.id.btnFull)) { if (fullscreen) exitFullscreen() else enterFullscreen(rotate = true) }
        press(view.findViewById(R.id.btnSettings)) { showMenu() }
        press(view.findViewById(R.id.btnDown)) { collapse() }

        seek.onScrubStart = { showControls(autoHide = false) }
        seek.onScrub = { pos ->
            timeNow.text = clock(pos)
            updatePreview(pos)
        }
        seek.onSeek = { pos ->
            service?.player?.seekTo(pos)
            previewBox.visibility = View.GONE
            showControls(autoHide = true)
        }
        videoBox.setOnTouchListener { _, ev -> onVideoTouch(ev) }
        menuScrim.setOnClickListener { hideMenu() }
        applyControlsLayout()
    }

    /** Click with a springy press, like glass being pushed. */
    @SuppressLint("ClickableViewAccessibility")
    private fun press(v: View, action: () -> Unit) {
        v.setOnTouchListener { b, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> b.animate().scaleX(1.14f).scaleY(1.14f).setDuration(120).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    b.animate().scaleX(1f).scaleY(1f).setDuration(340).setInterpolator(OvershootInterpolator(2.5f)).start()
            }
            false
        }
        v.setOnClickListener {
            action()
            if (controlsShown) scheduleHide()
        }
    }

    fun togglePlay() {
        val p = service?.player ?: return
        if (p.playbackState == Player.STATE_ENDED) {
            p.seekTo(0)
            p.play()
        } else if (p.isPlaying) p.pause() else p.play()
        updatePlayIcon()
    }

    private fun seekBy(delta: Long) {
        val p = service?.player ?: return
        val dur = p.duration
        var target = p.currentPosition + delta
        if (dur > 0) target = target.coerceAtMost(dur)
        p.seekTo(target.coerceAtLeast(0))
    }

    private fun updatePlayIcon(animate: Boolean = true) {
        val p = service?.player
        // shows "pause" while playing or about to play
        val pause = p != null && p.playWhenReady && p.playbackState != Player.STATE_ENDED && p.playbackState != Player.STATE_IDLE
        icPlay.setPause(pause, animate)
    }

    /** The spinner only when the controls are hidden (never inside the play button). */
    private fun updateLoading() {
        val show = (buffering || loadingInfo) && (!controlsShown || progress < 0.999f) && progress > 0.5f
        loading.visibility = if (show) View.VISIBLE else View.GONE
    }

    /** Controls appear once the player is fully open: the buttons pop in one after the other. */
    private fun revealControls() {
        if (progress < 0.999f || pip) return
        controlsShown = true
        controls.visibility = View.VISIBLE
        controls.alpha = 0f
        controls.animate().alpha(1f).setDuration(220).start()
        buttons.forEachIndexed { i, b ->
            b.scaleX = 0.6f
            b.scaleY = 0.6f
            b.alpha = 0f
            b.animate().scaleX(1f).scaleY(1f).alpha(1f).setStartDelay(30L * i).setDuration(360)
                .setInterpolator(OvershootInterpolator(1.8f))
                .withEndAction { b.animate().setStartDelay(0) }
                .start()
        }
        timeRow.alpha = 0f
        timeRow.animate().alpha(1f).setDuration(260).start()
        showFullscreenSeek(260)
        seek.restThickness = dpf(4f)
        seek.restGlow = 0.55f
        updateLoading()
        hideJob?.cancel()
        if (isPlaying) scheduleHide()
    }

    private fun showControls(autoHide: Boolean) {
        if (progress < 0.999f || pip) return
        controlsShown = true
        controls.visibility = View.VISIBLE
        controls.animate().alpha(1f).setDuration(160).start()
        for (b in buttons) if (b.alpha < 1f && b.animation == null) b.alpha = 1f
        timeRow.animate().alpha(1f).setDuration(160).start()
        showFullscreenSeek(160)
        seek.restThickness = dpf(4f)
        seek.restGlow = 0.55f
        updateLoading()
        hideJob?.cancel()
        if (autoHide) scheduleHide()
    }

    private fun hideControls(fast: Boolean = false) {
        if (seek.scrubbing) return
        controlsShown = false
        hideJob?.cancel()
        controls.animate().alpha(0f).setDuration(if (fast) 90 else 220).withEndAction {
            if (!controlsShown) controls.visibility = View.INVISIBLE
        }.start()
        timeRow.animate().alpha(0f).setDuration(if (fast) 90 else 220).start()
        if (fullscreen) {
            // in fullscreen the seek bar goes away together with the buttons
            seek.animate().alpha(0f).setDuration(if (fast) 90 else 220).withEndAction {
                if (!controlsShown && fullscreen) seek.visibility = View.INVISIBLE
            }.start()
        }
        seek.restThickness = dpf(3f)
        seek.restGlow = 0f
        updateLoading()
    }

    private fun scheduleHide() {
        hideJob?.cancel()
        hideJob = activity.lifecycleScope.launch {
            delay(3000)
            if (isPlaying && menuScrim.visibility != View.VISIBLE) hideControls()
        }
    }

    private var lastSave = 0L

    private fun startProgressLoop() {
        progressJob?.cancel()
        progressJob = activity.lifecycleScope.launch {
            while (isActive) {
                val p = service?.player
                if (p != null && !seek.scrubbing) {
                    val dur = p.duration.coerceAtLeast(0)
                    seek.durationMs = dur
                    seek.positionMs = p.currentPosition
                    seek.bufferedMs = p.bufferedPosition
                    if (controlsShown) {
                        timeNow.text = clock(p.currentPosition)
                        timeTotal.text = if (dur > 0) clock(dur) else ""
                    }
                    updateChapterChip(p.currentPosition)
                    val now = System.currentTimeMillis()
                    if (p.isPlaying && now - lastSave > 5000) {
                        lastSave = now
                        savePosition()
                    }
                }
                delay(200)
            }
        }
    }

    private fun savePosition() {
        val id = currentId ?: return
        val p = service?.player ?: return
        if (service?.currentUrl != currentUrl) return
        if (!settings.resume) return
        store.savePosition(id, p.currentPosition, p.duration)
    }

    /** Storyboard frame and chapter title above the finger while scrubbing. */
    private fun updatePreview(pos: Long) {
        val dur = seek.durationMs
        val fs = frameset
        val chapter = chapters.lastOrNull { it.startTimeSeconds * 1000L <= pos }?.title
        if ((fs == null && chapter == null) || dur <= 0) {
            previewBox.visibility = View.GONE
            return
        }
        previewBox.visibility = View.VISIBLE
        previewImage.visibility = if (fs != null) View.VISIBLE else View.GONE
        previewText.text = if (chapter != null) "$chapter\n${clock(pos)}" else clock(pos)
        val w = videoBox.width
        val x = seek.sidePadding + (w - seek.sidePadding * 2) * (pos.toFloat() / dur)
        previewBox.translationX = (x - dp(90)).coerceIn(dpf(8f), w - dpf(188f))
        previewBox.translationY = -(seek.height + dpf(if (fullscreen) 30f else 14f))
        if (fs == null) return
        val b = fs.getFrameBoundsAt(pos)
        val sheetIndex = b[0]
        val bmp = storyboards[sheetIndex]
        if (bmp != null) {
            val l = b[1].coerceIn(0, bmp.width - 1)
            val t = b[2].coerceIn(0, bmp.height - 1)
            val fw = (b[3] - b[1]).coerceAtMost(bmp.width - l).coerceAtLeast(1)
            val fh = (b[4] - b[2]).coerceAtMost(bmp.height - t).coerceAtLeast(1)
            try {
                previewImage.setImageBitmap(android.graphics.Bitmap.createBitmap(bmp, l, t, fw, fh))
            } catch (e: Exception) {
                // bad bounds
            }
        } else if (storyboardLoading.add(sheetIndex)) {
            val url = fs.urls.getOrNull(sheetIndex) ?: return
            activity.lifecycleScope.launch {
                try {
                    val req = coil.request.ImageRequest.Builder(activity).data(url).allowHardware(false).build()
                    val res = coil.Coil.imageLoader(activity).execute(req)
                    val drawable = (res as? coil.request.SuccessResult)?.drawable as? android.graphics.drawable.BitmapDrawable
                    drawable?.bitmap?.let { storyboards[sheetIndex] = it }
                } finally {
                    storyboardLoading.remove(sheetIndex)
                }
            }
        }
    }

    private fun clock(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s == 0L) "0:00" else Format.duration(s)
    }

    private fun applyControlsLayout() {
        seek.sidePadding = if (fullscreen) dpf(28f) else 0f
        val lp = seek.layoutParams as FrameLayout.LayoutParams
        lp.bottomMargin = if (fullscreen) dp(18) else 0
        seek.layoutParams = lp
        val tl = timeRow.layoutParams as FrameLayout.LayoutParams
        tl.bottomMargin = if (fullscreen) dp(44) else dp(22)
        tl.marginStart = if (fullscreen) dp(16) else 0
        tl.marginEnd = if (fullscreen) dp(16) else 0
        timeRow.layoutParams = tl
        val full = view.findViewById<View>(R.id.btnFull)
        val fl = full.layoutParams as FrameLayout.LayoutParams
        fl.bottomMargin = if (fullscreen) dp(70) else dp(44)
        fl.marginEnd = if (fullscreen) dp(24) else dp(10)
        full.layoutParams = fl
        icFull.setImageResource(if (fullscreen) R.drawable.ic_fullscreen_exit else R.drawable.ic_fullscreen)
    }

    // ================================================================ settings menu (liquid glass over the video)

    private fun menuCard() {
        val lensOn = lens != null
        val fill = GradientDrawable().apply {
            cornerRadius = dpf(22f)
            setColor(if (lensOn) Color.argb(120, 22, 24, 30) else Color.argb(215, 28, 30, 36))
        }
        val skin = GlassSkinDrawable(density)
        skin.cornerRadius = dpf(22f)
        skin.setTint(if (lensOn) 0.4f else 0.5f, null)
        skin.setRim(true)
        skin.setContentColor(Color.WHITE)
        menuScroll.background = LayerDrawable(arrayOf(fill, skin))
        menuScroll.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: Outline) {
                outline.setRoundRect(0, 0, v.width, v.height, dpf(22f))
            }
        }
        menuScroll.clipToOutline = true
    }

    private fun menuRow(icon: Int, title: String, value: String?, check: Boolean = false, click: () -> Unit): View {
        val row = LinearLayout(activity)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(16), dp(11), dp(16), dp(11))
        val ta = activity.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
        row.background = ta.getDrawable(0)
        ta.recycle()
        if (icon != 0) {
            val iv = ImageView(activity)
            iv.setImageResource(icon)
            iv.setColorFilter(Color.WHITE)
            row.addView(iv, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(14) })
        }
        val tv = TextView(activity)
        tv.text = title
        tv.textSize = 15f
        tv.setTextColor(Color.WHITE)
        tv.setShadowLayer(4f, 0f, 1f, Color.argb(120, 0, 0, 0))
        row.addView(tv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (value != null) {
            val v = TextView(activity)
            v.text = value
            v.textSize = 14f
            v.setTextColor(Color.argb(200, 255, 255, 255))
            v.setShadowLayer(4f, 0f, 1f, Color.argb(120, 0, 0, 0))
            row.addView(v)
        }
        if (check) {
            val c = ImageView(activity)
            c.setImageResource(R.drawable.ic_check)
            c.setColorFilter(ContextCompat.getColor(activity, R.color.accent))
            row.addView(c, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginStart = dp(10) })
        }
        row.setOnClickListener { click() }
        return row
    }

    /** Fits the menu into the video (scrolls when longer) with a small resize animation. */
    private fun fitMenu() {
        menuScroll.post {
            val max = (videoBox.height - dp(20)).coerceAtLeast(dp(120))
            menu.measure(
                View.MeasureSpec.makeMeasureSpec(menuScroll.width.coerceAtLeast(dp(250)), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            val target = menu.measuredHeight.coerceAtMost(max)
            val start = menuScroll.height
            val lp = menuScroll.layoutParams
            if (start <= 0) {
                lp.height = target
                menuScroll.layoutParams = lp
                return@post
            }
            ValueAnimator.ofInt(start, target).apply {
                duration = 220
                interpolator = DecelerateInterpolator(1.5f)
                addUpdateListener {
                    lp.height = it.animatedValue as Int
                    menuScroll.layoutParams = lp
                }
                start()
            }
        }
    }

    private fun showMenu() {
        menuCard()
        fillMainMenu()
        menuScrim.visibility = View.VISIBLE
        menuScroll.pivotX = dpf(240f)
        menuScroll.pivotY = 0f
        menuScroll.alpha = 0f
        menuScroll.scaleX = 0.6f
        menuScroll.scaleY = 0.6f
        menuScroll.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(320)
            .setInterpolator(OvershootInterpolator(1.2f)).start()
        // the menu replaces the buttons while it is open
        controls.animate().alpha(0f).setDuration(120).start()
        hideJob?.cancel()
    }

    private fun hideMenu() {
        menuScroll.animate().alpha(0f).scaleX(0.8f).scaleY(0.8f).setDuration(160).withEndAction {
            menuScrim.visibility = View.GONE
            val lp = menuScroll.layoutParams
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
            menuScroll.layoutParams = lp
        }.start()
        showControls(autoHide = isPlaying)
    }

    private fun hideMenuNow() {
        menuScrim.visibility = View.GONE
    }

    private data class Quality(val label: String, val side: Int, val group: Tracks.Group, val selected: Boolean)

    private fun qualities(): List<Quality> {
        val p = service?.player ?: return emptyList()
        val out = ArrayList<Quality>()
        for (g in p.currentTracks.groups) {
            if (g.type != C.TRACK_TYPE_VIDEO || g.length == 0) continue
            val f = g.getTrackFormat(0)
            if (f.width <= 0 || f.height <= 0) continue
            if (!g.isTrackSupported(0)) continue
            val side = minOf(f.width, f.height)
            val fps = if (f.frameRate > 40f) "60" else ""
            out.add(Quality("${side}p$fps", side, g, g.isSelected))
        }
        return out.sortedByDescending { it.side }
    }

    private fun manualQuality(): Boolean =
        service?.player?.trackSelectionParameters?.overrides?.values?.any { it.type == C.TRACK_TYPE_VIDEO } == true

    private fun fillMainMenu() {
        menu.removeAllViews()
        val qs = qualities()
        val current = qs.firstOrNull { it.selected }?.label
        val qValue = if (manualQuality()) current else activity.getString(R.string.auto) + (current?.let { " ($it)" } ?: "")
        menu.addView(menuRow(R.drawable.ic_settings, activity.getString(R.string.quality), qValue) { fillQualityMenu() })
        val subs = textTracks()
        if (subs.isNotEmpty()) {
            val cur = subs.firstOrNull { it.third }?.first
            menu.addView(menuRow(R.drawable.ic_subtitles, activity.getString(R.string.subtitles), cur ?: activity.getString(R.string.off)) { fillSubtitleMenu() })
        }
        val speed = service?.player?.playbackParameters?.speed ?: 1f
        menu.addView(menuRow(R.drawable.ic_speed, activity.getString(R.string.speed), speedLabel(speed)) { fillSpeedMenu() })
        val loop = service?.player?.repeatMode == Player.REPEAT_MODE_ONE
        menu.addView(menuRow(R.drawable.ic_replay10, activity.getString(R.string.loop_video), activity.getString(if (loop) R.string.on else R.string.off)) {
            service?.player?.repeatMode = if (loop) Player.REPEAT_MODE_OFF else Player.REPEAT_MODE_ONE
            fillMainMenu()
        })
        fitMenu()
    }

    private fun speedLabel(s: Float): String = if (s == s.toInt().toFloat()) "${s.toInt()}×" else "${s}×"

    private fun fillQualityMenu() {
        menu.removeAllViews()
        menu.addView(menuRow(R.drawable.ic_back, activity.getString(R.string.quality), null) { fillMainMenu() })
        val manual = manualQuality()
        menu.addView(menuRow(0, activity.getString(R.string.auto) + " (≤ ${settings.effectiveMaxQuality()}p)", null, check = !manual) {
            service?.applyQualityLimit()
            hideMenu()
        })
        for (q in qualities()) {
            menu.addView(menuRow(0, q.label, null, check = manual && q.selected) {
                val p = service?.player ?: return@menuRow
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                    .clearViewportSizeConstraints()
                    .setMaxVideoSize(Int.MAX_VALUE, Int.MAX_VALUE)
                    .setOverrideForType(TrackSelectionOverride(q.group.mediaTrackGroup, 0))
                    .build()
                hideMenu()
            })
        }
        fitMenu()
    }

    /** Subtitle tracks: label, group, selected. */
    private fun textTracks(): List<Triple<String, Tracks.Group, Boolean>> {
        val p = service?.player ?: return emptyList()
        val off = p.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
        return p.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT && it.length > 0 }.map { g ->
            val f = g.getTrackFormat(0)
            Triple(f.label ?: f.language ?: "?", g, !off && g.isSelected)
        }
    }

    private fun fillSubtitleMenu() {
        menu.removeAllViews()
        menu.addView(menuRow(R.drawable.ic_back, activity.getString(R.string.subtitles), null) { fillMainMenu() })
        val tracks = textTracks()
        menu.addView(menuRow(0, activity.getString(R.string.off), null, check = tracks.none { it.third }) {
            settings.subtitleLang = ""
            applySubtitlePrefs()
            hideMenu()
        })
        for ((label, group, selected) in tracks) {
            menu.addView(menuRow(0, label, null, check = selected) {
                val p = service?.player ?: return@menuRow
                settings.subtitleLang = group.getTrackFormat(0).language ?: ""
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
                    .build()
                hideMenu()
            })
        }
        fitMenu()
    }

    /** Subtitles off, or the remembered language. */
    private fun applySubtitlePrefs() {
        val p = service?.player ?: return
        val lang = settings.subtitleLang
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, lang.isEmpty())
            .setPreferredTextLanguage(lang.ifEmpty { null })
            .build()
    }

    private fun fillSpeedMenu() {
        menu.removeAllViews()
        menu.addView(menuRow(R.drawable.ic_back, activity.getString(R.string.speed), null) { fillMainMenu() })
        val now = service?.player?.playbackParameters?.speed ?: 1f
        for (s in listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)) {
            menu.addView(menuRow(0, speedLabel(s), null, check = s == now) {
                service?.player?.playbackParameters = PlaybackParameters(s)
                hideMenu()
            })
        }
        fitMenu()
    }

    /** Settings changed on the settings page. */
    fun onSettingsChanged() {
        service?.let { if (!manualQuality()) it.applyQualityLimit() }
    }

    // ================================================================ loading

    private fun load(url: String) {
        if (url == currentUrl) return
        savePosition()
        currentUrl = url
        currentId = Video.idOf(url)
        nextUp = null
        resetComments()
        details.scrollToPosition(0)
        val svc = service
        val info = svc?.currentInfo
        if (svc != null && svc.currentUrl == url && info != null) {
            showDetails(info, record = false)
            host.onNowPlaying(info.name, info.uploaderName)
            return
        }
        loadingInfo = true
        updateLoading()
        adapter.submit(listOf(Row.Loading))
        loadJob?.cancel()
        loadJob = activity.lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { StreamInfo.getInfo(yt, url) }
                if (currentUrl != url) return@launch
                showDetails(result, record = true)
                host.onNowPlaying(result.name, result.uploaderName)
                val id = Video.idOf(result.url) ?: currentId
                val start = if (settings.resume && id != null) store.position(id) else 0L
                startPlayback(result, start)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (currentUrl != url) return@launch
                adapter.submit(
                    listOf(Row.Message(Errors.text(activity, e), activity.getString(R.string.retry)) {
                        currentUrl = null
                        load(url)
                    })
                )
            } finally {
                if (currentUrl == url) {
                    loadingInfo = false
                    updateLoading()
                }
            }
        }
    }

    private fun startPlayback(info: StreamInfo, startMs: Long) {
        val svc = service
        if (svc == null) {
            pendingInfo = info
            pendingStart = startMs
            return
        }
        askNotificationPermission()
        try {
            svc.play(info, startMs)
            applySubtitlePrefs()
            loadSegments(info)
            setVideoEnabled(true)
            if (progress >= 0.999f) showControls(autoHide = true)
        } catch (e: Exception) {
            Toast.makeText(activity, activity.getString(R.string.playback_error, Errors.text(activity, e)), Toast.LENGTH_LONG).show()
        }
    }

    private fun loadSegments(info: StreamInfo) {
        seek.marks = emptyList()
        if (!settings.sponsorBlock) return
        val id = Video.idOf(info.url) ?: return
        val cats = settings.sbCategories
        activity.lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) { SponsorBlock.fetch(id, cats) }
            if (currentId != id) return@launch
            service?.updateSegments(list)
            seek.marks = list.map { Triple(it.startMs, it.endMs, SponsorBlock.CATEGORIES[it.category] ?: Color.GREEN) }
        }
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            val prefs = activity.getSharedPreferences("app", Context.MODE_PRIVATE)
            if (!prefs.getBoolean("asked_notifications", false)) {
                prefs.edit().putBoolean("asked_notifications", true).apply()
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    // ================================================================ details

    private var commentsCard: View? = null

    private fun showDetails(info: StreamInfo, record: Boolean) {
        val v = LayoutInflater.from(activity).inflate(R.layout.item_details, details, false)
        v.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        v.findViewById<TextView>(R.id.title).text = info.name
        val live = info.streamType == StreamType.LIVE_STREAM || info.streamType == StreamType.AUDIO_LIVE_STREAM
        val views = if (info.viewCount >= 0) {
            activity.getString(if (live) R.string.watching else R.string.views, Format.count(activity, info.viewCount))
        } else null
        v.findViewById<TextView>(R.id.meta).text = Format.joinDot(views, Format.date(info.uploadDate, info.textualUploadDate))

        v.findViewById<TextView>(R.id.channel).text = info.uploaderName
        val subs = v.findViewById<TextView>(R.id.subs)
        if (info.uploaderSubscriberCount > 0) {
            subs.text = activity.getString(R.string.subscribers, Format.count(activity, info.uploaderSubscriberCount))
            subs.visibility = View.VISIBLE
        } else {
            subs.visibility = View.GONE
        }
        v.findViewById<ImageView>(R.id.avatar).load(Format.pick(info.uploaderAvatars, 120)) {
            transformations(CircleCropTransformation())
        }
        val curl = info.uploaderUrl
        val channelRow = v.findViewById<LinearLayout>(R.id.channelRow)
        if (!curl.isNullOrEmpty()) {
            channelRow.setOnClickListener { openChannel(curl) }
            val ref = ChannelRef(curl, info.uploaderName.orEmpty(), Format.pick(info.uploaderAvatars, 120))
            channelRow.addView(SubscribeButton.create(activity, ref))
        }
        chapters = info.streamSegments ?: emptyList()
        seek.chapters = chapters.map { it.startTimeSeconds * 1000L }
        videoThumb = Format.pick(info.thumbnails, 480)
        chapterIndex = -1
        closeChapters(animate = false)
        updateChapterChip(service?.player?.currentPosition ?: 0L)
        frameset = info.previewFrames?.filter { it.urls.isNotEmpty() }?.minByOrNull { abs(it.frameWidth - 160) }
        storyboards.clear()

        val actions = v.findViewById<LinearLayout>(R.id.actions)
        baseLikes = info.likeCount
        likeStatus = "INDIFFERENT"
        likePill = addAction(actions, R.drawable.ic_like, null) { rate(if (likeStatus == "LIKE") "INDIFFERENT" else "LIKE") }
        dislikePill = addAction(actions, R.drawable.ic_dislike, null) { rate(if (likeStatus == "DISLIKE") "INDIFFERENT" else "DISLIKE") }
        updateRatingUi(animate = false)
        addAction(actions, R.drawable.ic_headphones, activity.getString(R.string.action_background)) { activity.moveTaskToBack(true) }
        addAction(actions, R.drawable.ic_share, activity.getString(R.string.action_share)) { share(info) }
        addAction(actions, R.drawable.ic_download, activity.getString(R.string.download)) { askDownload(info) }
        addAction(actions, R.drawable.ic_playlist, activity.getString(R.string.save)) {
            Video.from(activity, info)?.let { PlaylistPicker.show(activity, it) }
        }
        addAction(actions, R.drawable.ic_open, activity.getString(R.string.action_open_youtube)) { openInYoutube(info.url) }

        val desc = v.findViewById<TextView>(R.id.description)
        val text = descriptionText(info.description)
        if (text.isBlank()) {
            desc.visibility = View.GONE
        } else {
            desc.text = text
            desc.background = Glass.pill(activity, 18f * density)
            var expanded = false
            var anim: ValueAnimator? = null
            desc.setOnClickListener {
                expanded = !expanded
                anim?.cancel()
                val start = desc.height
                val width = desc.width
                desc.maxLines = if (expanded) Int.MAX_VALUE else 3
                desc.measure(
                    View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                )
                val end = desc.measuredHeight
                if (!expanded) desc.maxLines = Int.MAX_VALUE
                anim = ValueAnimator.ofInt(start, end).apply {
                    duration = (240 + abs(end - start) / density * 0.6f).toLong().coerceAtMost(540)
                    interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
                    addUpdateListener {
                        desc.layoutParams = desc.layoutParams.apply { height = it.animatedValue as Int }
                    }
                    addListener(object : android.animation.AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: android.animation.Animator) {
                            if (!expanded) desc.maxLines = 3
                            desc.layoutParams = desc.layoutParams.apply { height = ViewGroup.LayoutParams.WRAP_CONTENT }
                        }
                    })
                    start()
                }
            }
        }

        val card = v.findViewById<View>(R.id.commentsCard)
        card.background = Glass.pill(activity, 18f * density)
        card.setOnClickListener { openComments() }
        commentsCard = card
        v.findViewById<TextView>(R.id.commentPreview).text = activity.getString(R.string.loading_comments)

        val rel = info.relatedItems.filterIsInstance<StreamInfoItem>().mapNotNull { Video.from(activity, it) }
        related = rel
        nextUp = rel.firstOrNull { !it.isShort && !it.isLive }
        v.findViewById<View>(R.id.related).visibility = View.GONE
        detailHeader = v
        refreshDetailRows()
        details.scrollToPosition(0)

        if (record) {
            Video.from(activity, info)?.let { video ->
                store.addToHistory(video)
                store.saveRelated(video.id, rel.filter { !it.isShort })
            }
        }
        loadCommentsPreview(info.url)
        loadRating(info)
    }

    private fun refreshDetailRows() {
        val header = detailHeader ?: return
        val rows = ArrayList<Row>()
        rows.add(Row.Custom(header))
        if (queue.isNotEmpty()) {
            rows.add(Row.Section(activity.getString(R.string.queue) + " · ${queue.size}", activity.getString(R.string.clear_history)) {
                queue.clear()
                refreshDetailRows()
            })
            queue.forEach { rows.add(Row.VideoRow(it)) }
        }
        if (related.isNotEmpty()) {
            rows.add(Row.Section(activity.getString(R.string.related)))
            related.forEach { rows.add(Row.VideoRow(it)) }
        }
        adapter.submit(rows)
    }

    /** "Play next": queued videos play before autoplay picks one. */
    fun addToQueue(v: Video) {
        queue.removeAll { it.id == v.id }
        queue.add(v)
        refreshDetailRows()
        Toast.makeText(activity, R.string.queued, Toast.LENGTH_SHORT).show()
    }

    private fun openChannel(url: String) {
        collapse()
        host.onOpenChannel(url)
    }

    private fun askDownload(info: StreamInfo) {
        val heights = info.videoOnlyStreams
            .filter { it.isUrl && it.height > 0 }
            .map { if (it.width > 0) minOf(it.width, it.height) else it.height }
            .distinct().sortedDescending()
            .ifEmpty { info.videoStreams.map { it.height }.distinct().sortedDescending() }
        if (heights.isEmpty()) {
            Toast.makeText(activity, R.string.not_supported, Toast.LENGTH_SHORT).show()
            return
        }
        ActionSheet.show(activity, activity.getString(R.string.download_quality), heights.map { h ->
            ActionSheet.Item(R.drawable.ic_download, "${h}p") {
                Downloads.start(activity, info.url, h)
                Toast.makeText(activity, R.string.download_started, Toast.LENGTH_SHORT).show()
            }
        })
    }

    // ================================================================ downloaded videos

    fun openDownload(d: Download) {
        val svc = service
        if (svc == null || d.video == null) return
        savePosition()
        view.visibility = View.VISIBLE
        videoBox.translationY = 0f
        videoBox.alpha = 1f
        host.onMiniOffset(0f, 1f)
        ensurePlayerView()
        loadJob?.cancel()
        currentUrl = "local:" + d.id
        currentId = d.id
        nextUp = null
        resetComments()
        seek.marks = emptyList()
        seek.chapters = emptyList()
        chapterChip.visibility = View.GONE
        closeChapters(animate = false)
        frameset = null
        chapters = emptyList()
        showOfflineDetails(d)
        host.onNowPlaying(d.title, d.channel)
        val start = if (settings.resume) store.position(d.id) else 0L
        try {
            svc.playLocal(d, start)
        } catch (e: Exception) {
            Toast.makeText(activity, Errors.text(activity, e), Toast.LENGTH_LONG).show()
        }
        view.post {
            applyGeometry()
            animateTo(1f)
        }
    }

    private fun showOfflineDetails(d: Download) {
        val v = LayoutInflater.from(activity).inflate(R.layout.item_details, details, false)
        v.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        v.findViewById<TextView>(R.id.title).text = d.title
        v.findViewById<TextView>(R.id.meta).text = Format.joinDot(
            activity.getString(R.string.downloaded), if (d.height > 0) "${d.height}p" else null,
            android.text.format.Formatter.formatShortFileSize(activity, d.bytes)
        )
        v.findViewById<TextView>(R.id.channel).text = d.channel
        v.findViewById<View>(R.id.subs).visibility = View.GONE
        v.findViewById<View>(R.id.avatar).visibility = View.GONE
        v.findViewById<View>(R.id.description).visibility = View.GONE
        v.findViewById<View>(R.id.commentsCard).visibility = View.GONE
        v.findViewById<View>(R.id.related).visibility = View.GONE
        val actions = v.findViewById<LinearLayout>(R.id.actions)
        likePill = null
        dislikePill = null
        addAction(actions, R.drawable.ic_open, activity.getString(R.string.action_open_youtube)) { openInYoutube("https://www.youtube.com/watch?v=${d.id}") }
        addAction(actions, R.drawable.ic_trash, activity.getString(R.string.delete)) {
            Downloads.delete(activity, d.id)
            Toast.makeText(activity, R.string.deleted, Toast.LENGTH_SHORT).show()
            close()
        }
        detailHeader = v
        related = emptyList()
        refreshDetailRows()
    }

    // ================================================================ picture-in-picture

    private var progressBeforePip = 1f

    fun setPipMode(on: Boolean) {
        if (pip == on) return
        pip = on
        if (on) {
            progressBeforePip = progress
            hideMenuNow()
            commentsPanel.visibility = View.GONE
            closeChapters(animate = false)
            hideControls(fast = true)
            progressAnim?.cancel()
            progress = 1f
        } else {
            progress = progressBeforePip
        }
        applyGeometry()
        host.onSheetProgress(progress)
    }

    private fun addAction(parent: LinearLayout, icon: Int, label: String?, onClick: () -> Unit): TextView {
        val text = Glass.textColor(activity)
        val tv = TextView(activity)
        tv.text = label
        tv.textSize = 14f
        tv.setTextColor(text)
        tv.gravity = Gravity.CENTER_VERTICAL
        tv.setPadding(dp(14), dp(9), if (label.isNullOrEmpty()) dp(14) else dp(16), dp(9))
        tv.compoundDrawablePadding = if (label.isNullOrEmpty()) 0 else dp(8)
        val d = ContextCompat.getDrawable(activity, icon)?.mutate()
        d?.setBounds(0, 0, dp(18), dp(18))
        d?.setTint(text)
        tv.setCompoundDrawablesRelative(d, null, null, null)
        tv.background = Glass.pill(activity, 20f * density)
        tv.setOnClickListener { onClick() }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.marginStart = dp(4)
        lp.marginEnd = dp(4)
        parent.addView(tv, lp)
        return tv
    }

    // ================================================================ like / dislike

    private fun loadRating(info: StreamInfo) {
        if (!Account.isSignedIn(activity)) return
        val id = Video.idOf(info.url) ?: return
        activity.lifecycleScope.launch {
            val status = try {
                withContext(Dispatchers.IO) { Innertube.likeStatus(activity, id) }
            } catch (e: Exception) {
                null
            }
            if (status != null && currentId == id) {
                likeStatus = status
                updateRatingUi(animate = false)
            }
        }
    }

    private fun rate(status: String) {
        if (!Account.isSignedIn(activity)) {
            Toast.makeText(activity, R.string.sign_in_to_rate, Toast.LENGTH_SHORT).show()
            return
        }
        val id = currentId ?: return
        val old = likeStatus
        likeStatus = status
        updateRatingUi(animate = true)
        activity.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { Innertube.rate(activity, id, status) }
            } catch (e: Exception) {
                if (currentId == id) {
                    likeStatus = old
                    updateRatingUi(animate = false)
                }
                Toast.makeText(activity, Errors.text(activity, e), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateRatingUi(animate: Boolean) {
        val text = Glass.textColor(activity)
        val accent = ContextCompat.getColor(activity, R.color.accent)
        likePill?.let { p ->
            val liked = likeStatus == "LIKE"
            val count = if (baseLikes >= 0) baseLikes + (if (liked) 1 else 0) else -1
            p.text = if (count > 0) Format.count(activity, count) else activity.getString(R.string.like)
            p.compoundDrawablePadding = dp(8)
            p.setPadding(dp(14), dp(9), dp(16), dp(9))
            p.compoundDrawablesRelative[0]?.setTint(if (liked) accent else text)
            p.setTextColor(if (liked) accent else text)
            p.setTypeface(null, if (liked) Typeface.BOLD else Typeface.NORMAL)
            if (animate && liked) pop(p)
        }
        dislikePill?.let { p ->
            val disliked = likeStatus == "DISLIKE"
            p.compoundDrawablesRelative[0]?.setTint(if (disliked) accent else text)
            if (animate && disliked) pop(p)
        }
    }

    private fun pop(v: View) {
        v.animate().cancel()
        v.scaleX = 0.85f
        v.scaleY = 0.85f
        v.animate().scaleX(1f).scaleY(1f).setDuration(380).setInterpolator(OvershootInterpolator(3f)).start()
    }

    // ================================================================ chapters

    private fun showFullscreenSeek(duration: Long) {
        if (!fullscreen) return
        seek.animate().cancel()
        if (seek.visibility != View.VISIBLE) {
            seek.alpha = 0f
            seek.visibility = View.VISIBLE
        }
        seek.animate().alpha(1f).setDuration(duration).start()
    }

    private fun chapterAt(pos: Long): Int = chapters.indexOfLast { it.startTimeSeconds * 1000L <= pos }

    private fun updateChapterChip(pos: Long) {
        if (chapters.isEmpty()) {
            chapterChip.visibility = View.GONE
            return
        }
        val i = chapterAt(pos).coerceAtLeast(0)
        if (chapterChip.visibility != View.VISIBLE) chapterChip.visibility = View.VISIBLE
        if (i != chapterIndex) {
            chapterIndex = i
            chapterChip.text = chapters[i].title
            chaptersAdapter.notifyDataSetChanged()
            if (chaptersPanel.visibility == View.VISIBLE) {
                (chaptersList.layoutManager as LinearLayoutManager).let { lm ->
                    if (i < lm.findFirstCompletelyVisibleItemPosition() || i > lm.findLastCompletelyVisibleItemPosition()) {
                        chaptersList.smoothScrollToPosition(i)
                    }
                }
            }
        }
        val mw = (videoBox.width * 0.55f).toInt()
        if (mw > 0 && chapterChip.maxWidth != mw) chapterChip.maxWidth = mw
    }

    private inner class ChapterHolder(v: View) : RecyclerView.ViewHolder(v) {
        val thumb: ImageView = (v as ViewGroup).getChildAt(0) as ImageView
        val title: TextView = (((v as ViewGroup).getChildAt(1) as ViewGroup).getChildAt(0)) as TextView
        val time: TextView = (((v as ViewGroup).getChildAt(1) as ViewGroup).getChildAt(1)) as TextView
    }

    /** A storyboard frame at [pos], or null (then the sheet is loaded and [loaded] is called). */
    private fun storyboardFrame(pos: Long, loaded: () -> Unit): android.graphics.Bitmap? {
        val fs = frameset ?: return null
        val b = fs.getFrameBoundsAt(pos)
        val sheetIndex = b[0]
        val bmp = storyboards[sheetIndex]
        if (bmp != null) {
            val l = b[1].coerceIn(0, bmp.width - 1)
            val t = b[2].coerceIn(0, bmp.height - 1)
            val fw = (b[3] - b[1]).coerceAtMost(bmp.width - l).coerceAtLeast(1)
            val fh = (b[4] - b[2]).coerceAtMost(bmp.height - t).coerceAtLeast(1)
            return try {
                android.graphics.Bitmap.createBitmap(bmp, l, t, fw, fh)
            } catch (e: Exception) {
                null
            }
        }
        if (storyboardLoading.add(sheetIndex)) {
            val url = fs.urls.getOrNull(sheetIndex) ?: return null
            activity.lifecycleScope.launch {
                try {
                    val req = coil.request.ImageRequest.Builder(activity).data(url).allowHardware(false).build()
                    val res = coil.Coil.imageLoader(activity).execute(req)
                    val drawable = (res as? coil.request.SuccessResult)?.drawable as? android.graphics.drawable.BitmapDrawable
                    drawable?.bitmap?.let {
                        storyboards[sheetIndex] = it
                        loaded()
                    }
                } finally {
                    storyboardLoading.remove(sheetIndex)
                }
            }
        }
        return null
    }

    private fun setupChapters() {
        val header = LinearLayout(activity)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        header.setPadding(dp(16), dp(10), dp(8), dp(6))
        val title = TextView(activity)
        title.setText(R.string.chapters)
        title.setTextColor(Glass.textColor(activity))
        title.textSize = 19f
        title.setTypeface(title.typeface, Typeface.BOLD)
        header.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val close = ImageView(activity)
        close.setImageResource(R.drawable.ic_x)
        close.setColorFilter(Glass.textColor(activity))
        close.setPadding(dp(11), dp(11), dp(11), dp(11))
        close.contentDescription = activity.getString(R.string.close)
        close.setOnClickListener { closeChapters() }
        header.addView(close, LinearLayout.LayoutParams(dp(42), dp(42)))
        chaptersPanel.addView(header)
        chaptersList.layoutManager = LinearLayoutManager(activity)
        chaptersList.adapter = chaptersAdapter
        chaptersPanel.sideways = { chaptersSide() }
        chaptersPanel.canScrollUp = { chaptersList.canScrollVertically(-1) }
        chaptersPanel.onDismiss = { closeChapters() }
        chaptersList.overScrollMode = View.OVER_SCROLL_NEVER
        chaptersList.clipToPadding = false
        chaptersPanel.addView(chaptersList, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        view.addView(chaptersPanel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        chapterChip.background = Glass.overVideo(activity, dpf(13f))
        val chevron = ContextCompat.getDrawable(activity, R.drawable.ic_chevron_right)?.mutate()
        chevron?.setTint(Color.WHITE)
        chevron?.setBounds(0, 0, dp(14), dp(14))
        chapterChip.setCompoundDrawablesRelative(null, null, chevron, null)
        chapterChip.setOnClickListener {
            if (!controlsShown) {
                showControls(autoHide = isPlaying)
                return@setOnClickListener
            }
            // the capsule opens and closes the list
            if (chaptersPanel.visibility == View.VISIBLE && !chaptersClosing) closeChapters() else openChapters()
        }
    }

    /** Below the video when upright, a side panel on the right in fullscreen (like YouTube). */
    private fun layoutChapters() {
        val lp = chaptersPanel.layoutParams as? FrameLayout.LayoutParams ?: return
        val side = fullscreen && view.width > view.height
        val w = if (side) (view.width * 0.4f).toInt().coerceIn(dp(300), dp(440)) else ViewGroup.LayoutParams.MATCH_PARENT
        val top = if (fullscreen) 0 else fullRect.bottom.toInt()
        val gravity = if (side) Gravity.END or Gravity.TOP else Gravity.TOP
        val bottomPad = if (fullscreen) dp(16) else host.navBottom() + dp(16)
        if (chaptersList.paddingBottom != bottomPad) chaptersList.setPadding(0, 0, 0, bottomPad)
        if (lp.width != w || lp.topMargin != top || lp.gravity != gravity) {
            lp.width = w
            lp.height = ViewGroup.LayoutParams.MATCH_PARENT
            lp.topMargin = top
            lp.gravity = gravity
            chaptersPanel.layoutParams = lp
        }
    }

    private fun chaptersSide() = fullscreen && view.width > view.height

    private var chaptersClosing = false

    private fun openChapters() {
        if (chapters.isEmpty()) return
        val wasVisible = chaptersPanel.visibility == View.VISIBLE
        if (wasVisible && !chaptersClosing) return
        chaptersClosing = false
        chaptersPanel.animate().cancel()
        hideJob?.cancel()
        layoutChapters()
        chaptersAdapter.notifyDataSetChanged()
        if (wasVisible) {
            // reopened while it was sliding away: turn around from where it is
            chaptersPanel.animate().translationX(0f).translationY(0f).setDuration(320).setInterpolator(sheetEase).start()
            return
        }
        chaptersPanel.visibility = View.VISIBLE
        // off screen until it is measured, so it doesn't flash in place for a frame
        chaptersPanel.translationX = if (chaptersSide()) view.width.toFloat() else 0f
        chaptersPanel.translationY = if (chaptersSide()) 0f else view.height.toFloat()
        chaptersPanel.post {
            val side = chaptersSide()
            chaptersPanel.translationX = if (side) chaptersPanel.width.toFloat() else 0f
            chaptersPanel.translationY = if (side) 0f else chaptersPanel.height.toFloat()
            chaptersPanel.animate().translationX(0f).translationY(0f).setDuration(380).setInterpolator(sheetEase).start()
            (chaptersList.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(chapterIndex.coerceAtLeast(0), dp(40))
        }
    }

    private fun closeChapters(animate: Boolean = true) {
        if (chaptersPanel.visibility != View.VISIBLE) return
        if (!animate) {
            chaptersPanel.animate().cancel()
            chaptersPanel.visibility = View.GONE
            chaptersClosing = false
            return
        }
        val side = chaptersSide()
        chaptersClosing = true
        chaptersPanel.animate()
            .translationX(if (side) chaptersPanel.width.toFloat() else 0f)
            .translationY(if (side) 0f else chaptersPanel.height.toFloat())
            .setDuration(260).setInterpolator(DecelerateInterpolator())
            .withEndAction {
                chaptersPanel.visibility = View.GONE
                chaptersPanel.translationX = 0f
                chaptersPanel.translationY = 0f
                chaptersClosing = false
            }.start()
        if (isPlaying && controlsShown) scheduleHide()
    }

    // ================================================================ comments

    private fun resetComments() {
        commentsJob?.cancel()
        commentsMoreJob?.cancel()
        commentsInfo = null
        commentsNext = null
        commentsPanel.visibility = View.GONE
    }

    private fun commentRow(c: CommentsInfoItem): Row.Comment {
        val d = c.commentText
        val text: CharSequence = when {
            d == null -> ""
            d.type() == Description.Type.HTML -> Html.fromHtml(d.content(), Html.FROM_HTML_MODE_COMPACT).toString().trim()
            else -> d.content().trim()
        }
        val likes = c.textualLikeCount?.takeIf { it.isNotBlank() && it != "0" }
            ?: if (c.likeCount > 0) Format.count(activity, c.likeCount.toLong()) else null
        return Row.Comment(
            author = c.uploaderName.orEmpty(),
            avatar = Format.pick(c.uploaderAvatars, 96),
            text = text,
            likes = likes,
            date = Format.date(c.uploadDate, c.textualUploadDate),
            replies = c.replyCount.coerceAtLeast(0)
        )
    }

    private fun loadCommentsPreview(url: String) {
        commentsJob?.cancel()
        commentsJob = activity.lifecycleScope.launch {
            val card = commentsCard ?: return@launch
            try {
                val info = withContext(Dispatchers.IO) { CommentsInfo.getInfo(yt, url) }
                if (currentUrl != url) return@launch
                commentsInfo = info
                commentsNext = info.nextPage
                val header = card.findViewById<TextView>(R.id.commentsHeader)
                val count = info.commentsCount
                header.text = if (count > 0) activity.getString(R.string.comments) + "  " + Format.count(activity, count.toLong())
                else activity.getString(R.string.comments)
                val preview = card.findViewById<TextView>(R.id.commentPreview)
                val first = info.relatedItems.firstOrNull()
                if (info.isCommentsDisabled || first == null) {
                    preview.text = activity.getString(R.string.comments_off)
                    card.findViewById<View>(R.id.commentAvatar).visibility = View.GONE
                } else {
                    preview.text = commentRow(first).text
                    card.findViewById<ImageView>(R.id.commentAvatar).load(Format.pick(first.uploaderAvatars, 64)) {
                        transformations(CircleCropTransformation())
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                card.findViewById<TextView>(R.id.commentPreview).text = Errors.text(activity, e)
            }
        }
    }

    private fun openComments() {
        val info = commentsInfo ?: return
        if (info.isCommentsDisabled) return
        val count = info.commentsCount
        commentsTitle.text = if (count > 0) activity.getString(R.string.comments) + "  " + Format.count(activity, count.toLong())
        else activity.getString(R.string.comments)
        val rows = ArrayList<Row>()
        info.relatedItems.forEach { rows.add(commentRow(it)) }
        if (rows.isEmpty()) rows.add(Row.Message(activity.getString(R.string.nothing_here)))
        if (Page.isValid(commentsNext)) rows.add(Row.Loading)
        commentsAdapter.submit(rows)
        commentsList.setPadding(0, 0, 0, host.navBottom() + dp(16))
        commentsPanel.visibility = View.VISIBLE
        commentsPanel.post {
            commentsPanel.translationY = commentsPanel.height.toFloat()
            commentsPanel.animate().translationY(0f).setDuration(380).setInterpolator(sheetEase).start()
        }
    }

    private fun closeComments() {
        commentsPanel.animate().translationY(commentsPanel.height.toFloat()).setDuration(260)
            .setInterpolator(DecelerateInterpolator()).withEndAction { commentsPanel.visibility = View.GONE }.start()
    }

    private fun loadMoreComments() {
        val info = commentsInfo ?: return
        val page = commentsNext ?: return
        if (!Page.isValid(page) || commentsMoreJob?.isActive == true) return
        val url = currentUrl
        commentsMoreJob = activity.lifecycleScope.launch {
            try {
                val res = withContext(Dispatchers.IO) { CommentsInfo.getMoreItems(yt, info, page) }
                if (currentUrl != url) return@launch
                commentsNext = res.nextPage
                val rows = ArrayList<Row>()
                res.items.forEach { rows.add(commentRow(it)) }
                if (Page.isValid(commentsNext)) rows.add(Row.Loading)
                commentsAdapter.append(rows)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                commentsAdapter.append(listOf(Row.Message(Errors.text(activity, e))))
            }
        }
    }

    private fun descriptionText(d: Description?): CharSequence {
        d ?: return ""
        val content = d.content()
        return when (d.type()) {
            Description.Type.HTML -> Html.fromHtml(content, Html.FROM_HTML_MODE_COMPACT).toString().trim()
            else -> content.trim()
        }
    }

    private fun share(info: StreamInfo) {
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, info.name)
            .putExtra(Intent.EXTRA_TEXT, info.url)
        activity.startActivity(Intent.createChooser(send, info.name))
    }

    private fun openInYoutube(url: String) {
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        try {
            activity.startActivity(Intent(view).setPackage("com.google.android.youtube"))
        } catch (e: ActivityNotFoundException) {
            try {
                activity.startActivity(Intent.createChooser(view, null))
            } catch (e2: ActivityNotFoundException) {
                // nothing can open it
            }
        }
    }

    // ================================================================ lifecycle

    /** Off in the background: only the audio is downloaded and decoded then. */
    private fun setVideoEnabled(on: Boolean) {
        val p = service?.player ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, !on)
            .build()
    }

    fun onStart() {
        service?.let {
            playerView?.player = it.player
            setVideoEnabled(true)
        }
        startProgressLoop()
    }

    fun onStop(changingConfigurations: Boolean) {
        progressJob?.cancel()
        savePosition()
        if (!changingConfigurations) {
            if (!settings.backgroundPlay) service?.player?.pause()
            setVideoEnabled(false)
            playerView?.player = null
        }
    }

    fun onDestroy(finishing: Boolean) {
        loadJob?.cancel()
        orientationListener?.disable()
        service?.player?.removeListener(playerListener)
        if (finishing) service?.stopPlayback()
        if (bound) {
            activity.unbindService(connection)
            bound = false
        }
    }

    // ================================================================ fullscreen

    private fun videoIsPortrait(): Boolean {
        val size = service?.player?.videoSize ?: return false
        return size.width > 0 && size.height > size.width
    }

    /** While the screen turns the buttons go away, and come back once everything has settled,
     *  so the glass behind them can't lag behind the moving buttons. */
    private fun hideControlsForSwitch() {
        val wasShown = controlsShown
        hideControls(fast = true)
        controls.animate().cancel()
        controls.alpha = 0f
        controls.visibility = View.INVISIBLE
        lens?.clear()
        view.postDelayed({
            if (isOpen && progress >= 0.999f && !pip && wasShown) showControls(autoHide = isPlaying)
        }, 300)
    }

    private fun enterFullscreen(rotate: Boolean) {
        if (fullscreen) return
        hideControlsForSwitch()
        fullscreen = true
        applyControlsLayout()
        WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        if (Build.VERSION.SDK_INT >= 28) {
            activity.window.attributes = activity.window.attributes.apply {
                layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        if (rotate && !videoIsPortrait()) {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        view.post { applyGeometry() }
    }

    private fun exitFullscreen() {
        if (!fullscreen) return
        hideControlsForSwitch()
        fullscreen = false
        val byRotation = fullscreenByRotation
        fullscreenByRotation = false
        applyControlsLayout()
        WindowCompat.getInsetsController(activity.window, activity.window.decorView).show(WindowInsetsCompat.Type.systemBars())
        if (!byRotation && activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            unlockWhenUpright()
        } else {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        view.post { applyGeometry() }
    }

    private fun unlockWhenUpright() {
        orientationListener?.disable()
        val l = object : OrientationEventListener(activity) {
            override fun onOrientationChanged(deg: Int) {
                if (deg == ORIENTATION_UNKNOWN) return
                if (deg <= 20 || deg >= 340) {
                    activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    disable()
                }
            }
        }
        orientationListener = l
        if (l.canDetectOrientation()) l.enable() else activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    fun onConfigurationChanged(newConfig: Configuration) {
        val landscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (landscape && !fullscreen && isOpen && progress > 0.99f &&
            activity.requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        ) {
            fullscreenByRotation = true
            enterFullscreen(rotate = false)
        } else if (!landscape && fullscreen && fullscreenByRotation) {
            exitFullscreen()
        }
        view.post { applyGeometry() }
    }
}
