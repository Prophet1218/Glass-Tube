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
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.text.Html
import android.view.GestureDetector
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
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
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.Description
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType

@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {

    private lateinit var root: FrameLayout
    private lateinit var statusPad: View
    private lateinit var playerBox: RatioFrameLayout
    private lateinit var playerView: PlayerView
    private lateinit var controls: FrameLayout
    private lateinit var loading: ProgressBar
    private lateinit var seek: GlassSeekBar
    private lateinit var timeText: TextView
    private lateinit var icPlay: ImageView
    private lateinit var icFull: ImageView
    private lateinit var bottomRow: View
    private lateinit var list: RecyclerView
    private lateinit var menuScrim: FrameLayout
    private lateinit var menu: LinearLayout
    private lateinit var adapter: VideoAdapter
    private lateinit var settings: Settings
    private lateinit var store: Store

    private var service: PlaybackService? = null
    private var bound = false
    private var pendingInfo: StreamInfo? = null
    private var pendingHdr: List<HdrStream> = emptyList()
    private var currentUrl: String? = null
    private var loadJob: Job? = null
    private var progressJob: Job? = null
    private var hideJob: Job? = null

    private var controlsShown = true
    private var fullscreen = false
    /** Fullscreen was entered by turning the phone (leaves again when turned back). */
    private var fullscreenByRotation = false
    private var statusTop = 0
    private var navBottom = 0
    private var orientationListener: OrientationEventListener? = null

    private val yt = ServiceList.YouTube
    private val density get() = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()
    private fun dpf(v: Float) = v * density

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val playerListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            loading.visibility = View.GONE
            val cause = error.cause
            val text = if (cause != null) Errors.text(this@PlayerActivity, cause) else error.errorCodeName
            Toast.makeText(this@PlayerActivity, getString(R.string.playback_error, text), Toast.LENGTH_LONG).show()
            showControls(autoHide = false)
        }

        override fun onPlaybackStateChanged(state: Int) {
            loading.visibility = if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
            if (state == Player.STATE_ENDED) showControls(autoHide = false)
            updatePlayIcon()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            playerView.keepScreenOn = isPlaying
            updatePlayIcon()
            if (isPlaying && controlsShown) scheduleHide()
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val svc = (binder as? PlaybackService.LocalBinder)?.service ?: return
            service = svc
            svc.player.addListener(playerListener)
            playerView.player = svc.player
            setVideoEnabled(true)
            updatePlayIcon()
            val pending = pendingInfo
            if (pending != null) {
                pendingInfo = null
                startPlayback(pending, pendingHdr)
            } else {
                val url = currentUrl
                // the activity came back while this video kept playing in the background
                if (url != null && svc.currentUrl == url) {
                    loadJob?.cancel()
                    svc.currentInfo?.let { showDetails(it, record = false) }
                    loading.visibility = View.GONE
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        setContentView(R.layout.activity_player)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            // the status bar sits on the black strip above the video
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = !Glass.isNight(this@PlayerActivity)
        }
        settings = Settings(this)
        store = Store(this)

        root = findViewById(R.id.root)
        statusPad = findViewById(R.id.statusPad)
        playerBox = findViewById(R.id.playerBox)
        playerView = findViewById(R.id.playerView)
        controls = findViewById(R.id.controls)
        loading = findViewById(R.id.loading)
        seek = findViewById(R.id.seek)
        timeText = findViewById(R.id.time)
        icPlay = findViewById(R.id.icPlay)
        icFull = findViewById(R.id.icFull)
        bottomRow = findViewById(R.id.bottomRow)
        list = findViewById(R.id.list)
        menuScrim = findViewById(R.id.menuScrim)
        menu = findViewById(R.id.menu)

        adapter = VideoAdapter(onVideo = { load(it.url) })
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        list.itemAnimator = null

        setupControls()

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            statusTop = sys.top
            navBottom = sys.bottom
            applyInsets()
            insets
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    menuScrim.visibility == View.VISIBLE -> hideMenu()
                    fullscreen -> exitFullscreen()
                    else -> finish()
                }
            }
        })

        bindService(
            Intent(this, PlaybackService::class.java).setAction(PlaybackService.ACTION_LOCAL),
            connection, Context.BIND_AUTO_CREATE
        )
        bound = true

        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val url = extractUrl(intent)
        if (url == null) {
            if (currentUrl == null) {
                Toast.makeText(this, R.string.not_a_video_link, Toast.LENGTH_SHORT).show()
                finish()
            }
            return
        }
        load(url)
    }

    /** The video link from our own extra, an opened link or shared text. */
    private fun extractUrl(intent: Intent?): String? {
        intent ?: return null
        val candidates = ArrayList<String>()
        intent.getStringExtra(EXTRA_URL)?.let { candidates.add(it) }
        intent.dataString?.let { candidates.add(it) }
        intent.getStringExtra(Intent.EXTRA_TEXT)?.let { text ->
            Regex("https?://\\S+").findAll(text).forEach { candidates.add(it.value) }
        }
        return candidates.firstOrNull { url ->
            try {
                yt.streamLHFactory.acceptUrl(url)
            } catch (e: Exception) {
                false
            }
        }
    }

    // ================================================================ controls

    @SuppressLint("ClickableViewAccessibility")
    private fun setupControls() {
        for ((id, size) in listOf(
            R.id.btnDown to null, R.id.btnSettings to null, R.id.btnBack10 to null,
            R.id.btnPlay to null, R.id.btnFwd10 to null, R.id.btnFull to null
        )) {
            findViewById<View>(id).background = Glass.overVideo(this, size)
        }
        for (id in listOf(R.id.icDown, R.id.icSettings, R.id.icBack10, R.id.icPlay, R.id.icFwd10, R.id.icFull)) {
            findViewById<ImageView>(id).setColorFilter(Color.WHITE)
        }
        press(findViewById(R.id.btnPlay)) { togglePlay() }
        press(findViewById(R.id.btnBack10)) { seekBy(-10_000) }
        press(findViewById(R.id.btnFwd10)) { seekBy(10_000) }
        press(findViewById(R.id.btnFull)) { if (fullscreen) exitFullscreen() else enterFullscreen(rotate = true) }
        press(findViewById(R.id.btnSettings)) { showMenu() }
        press(findViewById(R.id.btnDown)) { onBackPressedDispatcher.onBackPressed() }

        seek.onScrubStart = { showControls(autoHide = false) }
        seek.onScrub = { pos -> timeText.text = timeLabel(pos, service?.player?.duration ?: 0) }
        seek.onSeek = { pos ->
            service?.player?.seekTo(pos)
            showControls(autoHide = true)
        }

        val gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (controlsShown) hideControls() else showControls(autoHide = true)
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val third = playerBox.width / 3f
                when {
                    e.x < third -> seekBy(-10_000)
                    e.x > third * 2 -> seekBy(10_000)
                    else -> togglePlay()
                }
                return true
            }
        })
        controls.setOnTouchListener { _, ev -> gestures.onTouchEvent(ev) }
        playerView.setOnTouchListener { _, ev -> gestures.onTouchEvent(ev) }
        menuScrim.setOnClickListener { hideMenu() }
        applyControlsLayout()
    }

    /** Click with a springy press, like glass being pushed. */
    @SuppressLint("ClickableViewAccessibility")
    private fun press(v: View, action: () -> Unit) {
        v.setOnTouchListener { view, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> view.animate().scaleX(1.12f).scaleY(1.12f).setDuration(120).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    view.animate().scaleX(1f).scaleY(1f).setDuration(320).setInterpolator(OvershootInterpolator(2.5f)).start()
            }
            false
        }
        v.setOnClickListener {
            action()
            if (controlsShown) scheduleHide()
        }
    }

    private fun togglePlay() {
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

    private fun updatePlayIcon() {
        val playing = service?.player?.isPlaying == true
        icPlay.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
    }

    private fun showControls(autoHide: Boolean) {
        controlsShown = true
        controls.visibility = View.VISIBLE
        controls.animate().alpha(1f).setDuration(160).start()
        seek.restThickness = dpf(4.5f)
        hideJob?.cancel()
        if (autoHide) scheduleHide()
    }

    private fun hideControls() {
        if (seek.scrubbing) return
        controlsShown = false
        hideJob?.cancel()
        controls.animate().alpha(0f).setDuration(220).withEndAction {
            if (!controlsShown) controls.visibility = View.INVISIBLE
        }.start()
        seek.restThickness = dpf(3f)
    }

    private fun scheduleHide() {
        hideJob?.cancel()
        hideJob = lifecycleScope.launch {
            delay(3000)
            if (service?.player?.isPlaying == true && menuScrim.visibility != View.VISIBLE) hideControls()
        }
    }

    private fun startProgressLoop() {
        progressJob?.cancel()
        progressJob = lifecycleScope.launch {
            while (isActive) {
                val p = service?.player
                if (p != null && !seek.scrubbing) {
                    val dur = p.duration.coerceAtLeast(0)
                    seek.durationMs = dur
                    seek.positionMs = p.currentPosition
                    seek.bufferedMs = p.bufferedPosition
                    if (controlsShown) timeText.text = timeLabel(p.currentPosition, dur)
                }
                delay(200)
            }
        }
    }

    private fun timeLabel(pos: Long, dur: Long): String {
        fun f(ms: Long): String {
            val s = (ms / 1000).coerceAtLeast(0)
            return if (s == 0L) "0:00" else Format.duration(s)
        }
        return if (dur > 0) "${f(pos)} / ${f(dur)}" else f(pos)
    }

    private fun applyControlsLayout() {
        seek.sidePadding = if (fullscreen) dpf(28f) else 0f
        val lp = seek.layoutParams as FrameLayout.LayoutParams
        lp.bottomMargin = if (fullscreen) dp(18) else 0
        seek.layoutParams = lp
        val rl = bottomRow.layoutParams as FrameLayout.LayoutParams
        rl.bottomMargin = if (fullscreen) dp(48) else dp(18)
        rl.marginStart = if (fullscreen) dp(18) else 0
        rl.marginEnd = if (fullscreen) dp(18) else 0
        bottomRow.layoutParams = rl
        icFull.setImageResource(if (fullscreen) R.drawable.ic_fullscreen_exit else R.drawable.ic_fullscreen)
    }

    // ================================================================ settings menu

    private fun menuCard() {
        val night = true // the menu sits over the video: always dark glass
        val fill = GradientDrawable().apply {
            cornerRadius = dpf(22f)
            setColor(if (night) Color.argb(215, 28, 30, 36) else Color.WHITE)
        }
        val skin = GlassSkinDrawable(density)
        skin.cornerRadius = dpf(22f)
        skin.setTint(0.5f, null)
        skin.setRim(true)
        skin.setContentColor(Color.WHITE)
        menu.background = LayerDrawable(arrayOf(fill, skin))
        menu.clipToOutline = true
        menu.elevation = 0f
    }

    private fun menuRow(icon: Int, title: String, value: String?, check: Boolean = false, click: () -> Unit): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(16), dp(12), dp(16), dp(12))
        val ta = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
        row.background = ta.getDrawable(0)
        ta.recycle()
        if (icon != 0) {
            val iv = ImageView(this)
            iv.setImageResource(icon)
            iv.setColorFilter(Color.WHITE)
            row.addView(iv, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(14) })
        }
        val tv = TextView(this)
        tv.text = title
        tv.textSize = 15f
        tv.setTextColor(Color.WHITE)
        row.addView(tv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (value != null) {
            val v = TextView(this)
            v.text = value
            v.textSize = 14f
            v.setTextColor(Color.argb(170, 255, 255, 255))
            row.addView(v)
        }
        if (check) {
            val c = ImageView(this)
            c.setImageResource(R.drawable.ic_check)
            c.setColorFilter(ContextCompat.getColor(this, R.color.accent))
            row.addView(c, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginStart = dp(10) })
        }
        row.setOnClickListener { click() }
        return row
    }

    private fun showMenu() {
        menuCard()
        fillMainMenu()
        val boxPos = IntArray(2)
        playerBox.getLocationInWindow(boxPos)
        val rootPos = IntArray(2)
        root.getLocationInWindow(rootPos)
        val lp = menu.layoutParams as FrameLayout.LayoutParams
        lp.topMargin = boxPos[1] - rootPos[1] + dp(56)
        lp.marginEnd = dp(12)
        menu.layoutParams = lp
        menuScrim.visibility = View.VISIBLE
        menu.pivotX = dpf(250f)
        menu.pivotY = 0f
        menu.alpha = 0f
        menu.scaleX = 0.7f
        menu.scaleY = 0.7f
        menu.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(280).setInterpolator(OvershootInterpolator(1.1f)).start()
        hideJob?.cancel()
    }

    private fun hideMenu() {
        menu.animate().alpha(0f).scaleX(0.85f).scaleY(0.85f).setDuration(150).withEndAction {
            menuScrim.visibility = View.GONE
        }.start()
        if (service?.player?.isPlaying == true) scheduleHide()
    }

    private data class Quality(val label: String, val group: Tracks.Group, val selected: Boolean)

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
            val transfer = f.colorInfo?.colorTransfer
            val hdr = transfer == C.COLOR_TRANSFER_ST2084 || transfer == C.COLOR_TRANSFER_HLG
            out.add(Quality("${side}p$fps" + if (hdr) " HDR" else "", g, g.isSelected))
        }
        return out.sortedByDescending { it.label.takeWhile { c -> c.isDigit() }.toIntOrNull() ?: 0 }
    }

    private fun manualQuality(): Boolean =
        service?.player?.trackSelectionParameters?.overrides?.values?.any { it.type == C.TRACK_TYPE_VIDEO } == true

    private fun fillMainMenu() {
        menu.removeAllViews()
        val p = service?.player
        val qs = qualities()
        val current = qs.firstOrNull { it.selected }?.label
        val qValue = if (manualQuality()) current else getString(R.string.auto) + (current?.let { " ($it)" } ?: "")
        menu.addView(menuRow(R.drawable.ic_settings, getString(R.string.quality), qValue) { fillQualityMenu() })

        val hdrOk = HdrStreams.deviceSupports(this)
        val hdrValue = when {
            !hdrOk -> getString(R.string.not_supported)
            settings.hdr && service?.currentHdr?.isEmpty() == true -> getString(R.string.hdr_none_for_video)
            settings.hdr -> getString(R.string.on)
            else -> getString(R.string.off)
        }
        menu.addView(menuRow(R.drawable.ic_hdr, getString(R.string.hdr), hdrValue) {
            if (!hdrOk) return@menuRow
            setHdr(!settings.hdr)
            fillMainMenu()
        })
        val speed = p?.playbackParameters?.speed ?: 1f
        menu.addView(menuRow(R.drawable.ic_speed, getString(R.string.speed), speedLabel(speed)) { fillSpeedMenu() })
    }

    private fun speedLabel(s: Float): String =
        if (s == s.toInt().toFloat()) "${s.toInt()}×" else "${s}×"

    private fun fillQualityMenu() {
        menu.removeAllViews()
        menu.addView(menuRow(R.drawable.ic_back, getString(R.string.quality), null) { fillMainMenu() })
        val manual = manualQuality()
        menu.addView(menuRow(0, getString(R.string.auto) + " (≤ ${settings.maxQuality}p)", null, check = !manual) {
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
    }

    private fun fillSpeedMenu() {
        menu.removeAllViews()
        menu.addView(menuRow(R.drawable.ic_back, getString(R.string.speed), null) { fillMainMenu() })
        val now = service?.player?.playbackParameters?.speed ?: 1f
        for (s in listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)) {
            menu.addView(menuRow(0, speedLabel(s), null, check = s == now) {
                service?.player?.playbackParameters = PlaybackParameters(s)
                hideMenu()
            })
        }
    }

    private fun setHdr(on: Boolean) {
        settings.hdr = on
        val svc = service ?: return
        val info = svc.currentInfo ?: return
        if (!on) {
            svc.reload(emptyList())
            return
        }
        lifecycleScope.launch {
            val hdr = withContext(Dispatchers.IO) { fetchHdr(info) }
            if (service?.currentInfo === info) {
                svc.reload(hdr)
                if (hdr.isEmpty()) Toast.makeText(this@PlayerActivity, R.string.hdr_none_for_video, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun fetchHdr(info: StreamInfo): List<HdrStream> {
        if (!settings.hdr || !HdrStreams.deviceSupports(this)) return emptyList()
        val id = Video.idOf(info.url) ?: info.id ?: return emptyList()
        return try {
            HdrStreams.fetch(id)
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ================================================================ loading

    private fun load(url: String) {
        if (url == currentUrl) return
        currentUrl = url
        list.scrollToPosition(0)
        val svc = service
        val info = svc?.currentInfo
        if (svc != null && svc.currentUrl == url && info != null) {
            showDetails(info, record = false)
            loading.visibility = View.GONE
            return
        }
        loading.visibility = View.VISIBLE
        adapter.submit(listOf(Row.Loading))
        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { StreamInfo.getInfo(yt, url) }
                if (currentUrl != url) return@launch
                showDetails(result, record = true)
                val hdr = withContext(Dispatchers.IO) { fetchHdr(result) }
                if (currentUrl != url) return@launch
                startPlayback(result, hdr)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (currentUrl != url) return@launch
                loading.visibility = View.GONE
                adapter.submit(
                    listOf(Row.Message(Errors.text(this@PlayerActivity, e), getString(R.string.retry)) {
                        currentUrl = null
                        load(url)
                    })
                )
            }
        }
    }

    private fun startPlayback(info: StreamInfo, hdr: List<HdrStream>) {
        val svc = service
        if (svc == null) {
            pendingInfo = info
            pendingHdr = hdr
            return
        }
        askNotificationPermission()
        try {
            svc.play(info, hdr)
            setVideoEnabled(true)
            showControls(autoHide = true)
        } catch (e: Exception) {
            loading.visibility = View.GONE
            Toast.makeText(this, getString(R.string.playback_error, Errors.text(this, e)), Toast.LENGTH_LONG).show()
        }
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            val prefs = getSharedPreferences("app", MODE_PRIVATE)
            if (!prefs.getBoolean("asked_notifications", false)) {
                prefs.edit().putBoolean("asked_notifications", true).apply()
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    // ================================================================ details

    private fun showDetails(info: StreamInfo, record: Boolean) {
        val v = LayoutInflater.from(this).inflate(R.layout.item_details, list, false)
        v.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        v.findViewById<TextView>(R.id.title).text = info.name
        val live = info.streamType == StreamType.LIVE_STREAM || info.streamType == StreamType.AUDIO_LIVE_STREAM
        val views = if (info.viewCount >= 0) {
            getString(if (live) R.string.watching else R.string.views, Format.count(this, info.viewCount))
        } else null
        val likes = if (info.likeCount > 0) getString(R.string.likes, Format.count(this, info.likeCount)) else null
        v.findViewById<TextView>(R.id.meta).text =
            Format.joinDot(views, Format.date(info.uploadDate, info.textualUploadDate), likes)

        v.findViewById<TextView>(R.id.channel).text = info.uploaderName
        val subs = v.findViewById<TextView>(R.id.subs)
        if (info.uploaderSubscriberCount > 0) {
            subs.text = getString(R.string.subscribers, Format.count(this, info.uploaderSubscriberCount))
            subs.visibility = View.VISIBLE
        } else {
            subs.visibility = View.GONE
        }
        val avatar = v.findViewById<ImageView>(R.id.avatar)
        avatar.load(Format.pick(info.uploaderAvatars, 120)) { transformations(CircleCropTransformation()) }

        val actions = v.findViewById<LinearLayout>(R.id.actions)
        addAction(actions, R.drawable.ic_headphones, R.string.action_background) { moveTaskToBack(true) }
        addAction(actions, R.drawable.ic_share, R.string.action_share) { share(info) }
        addAction(actions, R.drawable.ic_open, R.string.action_open_youtube) { openInYoutube(info.url) }

        val desc = v.findViewById<TextView>(R.id.description)
        val text = descriptionText(info.description)
        if (text.isBlank()) {
            desc.visibility = View.GONE
        } else {
            desc.text = text
            desc.background = Glass.pill(this, 18f * density)
            var expanded = false
            var anim: ValueAnimator? = null
            desc.setOnClickListener {
                expanded = !expanded
                anim?.cancel()
                val start = desc.height
                val width = desc.width
                // measure the target height
                desc.maxLines = if (expanded) Int.MAX_VALUE else 3
                desc.measure(
                    View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                )
                val end = desc.measuredHeight
                if (!expanded) desc.maxLines = Int.MAX_VALUE // keep the text while it shrinks
                anim = ValueAnimator.ofInt(start, end).apply {
                    duration = (220 + kotlin.math.abs(end - start) / density * 0.6f).toLong().coerceAtMost(520)
                    interpolator = DecelerateInterpolator(1.6f)
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

        val related = info.relatedItems.filterIsInstance<StreamInfoItem>().mapNotNull { Video.from(this, it) }
        v.findViewById<View>(R.id.related).visibility = if (related.isEmpty()) View.GONE else View.VISIBLE

        val rows = ArrayList<Row>()
        rows.add(Row.Custom(v))
        related.forEach { rows.add(Row.VideoRow(it)) }
        adapter.submit(rows)
        list.scrollToPosition(0)

        if (record) {
            Video.from(this, info)?.let { video ->
                store.addToHistory(video)
                store.saveRelated(video.id, related.filter { !it.isShort })
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

    private fun addAction(parent: LinearLayout, icon: Int, label: Int, onClick: () -> Unit) {
        val text = Glass.textColor(this)
        val tv = TextView(this)
        tv.text = getString(label)
        tv.textSize = 14f
        tv.setTextColor(text)
        tv.gravity = Gravity.CENTER_VERTICAL
        tv.setPadding(dp(14), dp(9), dp(16), dp(9))
        tv.compoundDrawablePadding = dp(8)
        val d = ContextCompat.getDrawable(this, icon)?.mutate()
        d?.setBounds(0, 0, dp(18), dp(18))
        d?.setTint(text)
        tv.setCompoundDrawablesRelative(d, null, null, null)
        tv.background = Glass.pill(this, 20f * density)
        tv.setOnClickListener { onClick() }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.marginStart = dp(4)
        lp.marginEnd = dp(4)
        parent.addView(tv, lp)
    }

    private fun share(info: StreamInfo) {
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, info.name)
            .putExtra(Intent.EXTRA_TEXT, info.url)
        startActivity(Intent.createChooser(send, info.name))
    }

    private fun openInYoutube(url: String) {
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        try {
            startActivity(Intent(view).setPackage("com.google.android.youtube"))
        } catch (e: ActivityNotFoundException) {
            try {
                startActivity(Intent.createChooser(view, null))
            } catch (e2: ActivityNotFoundException) {
                // nothing can open it
            }
        }
    }

    // ================================================================ background / video track

    /** Off in the background: only the audio is downloaded and decoded then. */
    private fun setVideoEnabled(on: Boolean) {
        val p = service?.player ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, !on)
            .build()
    }

    override fun onStart() {
        super.onStart()
        service?.let {
            playerView.player = it.player
            setVideoEnabled(true)
        }
        startProgressLoop()
    }

    override fun onStop() {
        super.onStop()
        progressJob?.cancel()
        if (!isChangingConfigurations) {
            setVideoEnabled(false)
            playerView.player = null
        }
    }

    override fun onDestroy() {
        loadJob?.cancel()
        orientationListener?.disable()
        service?.player?.removeListener(playerListener)
        if (isFinishing) service?.stopPlayback()
        if (bound) {
            unbindService(connection)
            bound = false
        }
        super.onDestroy()
    }

    // ================================================================ fullscreen

    private fun applyInsets() {
        val lp = statusPad.layoutParams
        lp.height = if (fullscreen) 0 else statusTop
        statusPad.layoutParams = lp
        list.setPadding(0, 0, 0, navBottom + dp(16))
    }

    private fun videoIsPortrait(): Boolean {
        val size = service?.player?.videoSize ?: return false
        return size.width > 0 && size.height > size.width
    }

    private fun enterFullscreen(rotate: Boolean) {
        if (fullscreen) return
        fullscreen = true
        list.visibility = View.GONE
        playerBox.locked = false
        val lp = playerBox.layoutParams as LinearLayout.LayoutParams
        lp.height = 0
        lp.weight = 1f
        playerBox.layoutParams = lp
        applyInsets()
        applyControlsLayout()
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        if (rotate && !videoIsPortrait()) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }

    private fun exitFullscreen() {
        if (!fullscreen) return
        fullscreen = false
        val byRotation = fullscreenByRotation
        fullscreenByRotation = false
        list.visibility = View.VISIBLE
        playerBox.locked = true
        val lp = playerBox.layoutParams as LinearLayout.LayoutParams
        lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
        lp.weight = 0f
        playerBox.layoutParams = lp
        applyInsets()
        applyControlsLayout()
        WindowCompat.getInsetsController(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
        if (!byRotation && resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            // back to portrait now; unlock rotation again once the phone is held upright
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            unlockWhenUpright()
        } else {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    private fun unlockWhenUpright() {
        orientationListener?.disable()
        val l = object : OrientationEventListener(this) {
            override fun onOrientationChanged(deg: Int) {
                if (deg == ORIENTATION_UNKNOWN) return
                if (deg <= 20 || deg >= 340) {
                    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    disable()
                }
            }
        }
        orientationListener = l
        if (l.canDetectOrientation()) l.enable() else requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val landscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (landscape && !fullscreen && currentUrl != null &&
            requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        ) {
            fullscreenByRotation = true
            enterFullscreen(rotate = false)
        } else if (!landscape && fullscreen && fullscreenByRotation) {
            exitFullscreen()
        }
    }

    companion object {
        const val EXTRA_URL = "url"
    }
}
