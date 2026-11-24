package com.example.glasstube

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.text.Html
import android.view.Gravity
import android.view.LayoutInflater
import android.view.OrientationEventListener
import android.view.View
import android.view.ViewGroup
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
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.CircleCropTransformation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.Description
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType

@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {

    private lateinit var root: LinearLayout
    private lateinit var statusPad: View
    private lateinit var playerBox: RatioFrameLayout
    private lateinit var playerView: PlayerView
    private lateinit var loading: ProgressBar
    private lateinit var list: RecyclerView
    private lateinit var adapter: VideoAdapter

    private var service: PlaybackService? = null
    private var bound = false
    private var pendingInfo: StreamInfo? = null
    private var currentUrl: String? = null
    private var loadJob: Job? = null

    private var fullscreen = false
    /** Fullscreen was entered by turning the phone (leaves again when turned back). */
    private var fullscreenByRotation = false
    private var statusTop = 0
    private var navBottom = 0
    private var orientationListener: OrientationEventListener? = null

    private val yt = ServiceList.YouTube
    private val density get() = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val playerListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            loading.visibility = View.GONE
            val cause = error.cause
            val text = if (cause != null) Errors.text(this@PlayerActivity, cause) else error.errorCodeName
            Toast.makeText(this@PlayerActivity, getString(R.string.playback_error, text), Toast.LENGTH_LONG).show()
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_READY) loading.visibility = View.GONE
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val svc = (binder as? PlaybackService.LocalBinder)?.service ?: return
            service = svc
            svc.player.addListener(playerListener)
            playerView.player = svc.player
            setVideoEnabled(true)
            val pending = pendingInfo
            if (pending != null) {
                pendingInfo = null
                startPlayback(pending)
            } else {
                val url = currentUrl
                // the activity came back while this video kept playing in the background
                if (url != null && svc.currentUrl == url) {
                    loadJob?.cancel()
                    svc.currentInfo?.let { showDetails(it) }
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

        root = findViewById(R.id.root)
        statusPad = findViewById(R.id.statusPad)
        playerBox = findViewById(R.id.playerBox)
        playerView = findViewById(R.id.playerView)
        loading = findViewById(R.id.loading)
        list = findViewById(R.id.list)

        adapter = VideoAdapter(onVideo = { load(it.url) })
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        list.itemAnimator = null

        playerView.setShowNextButton(false)
        playerView.setShowPreviousButton(false)
        playerView.setFullscreenButtonClickListener { goFull ->
            if (goFull) enterFullscreen(rotate = true) else exitFullscreen()
        }

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            statusTop = sys.top
            navBottom = sys.bottom
            applyInsets()
            insets
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (fullscreen) exitFullscreen() else finish()
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

    // ---------------------------------------------------------------- loading

    private fun load(url: String) {
        if (url == currentUrl) return
        currentUrl = url
        list.scrollToPosition(0)
        val svc = service
        val info = svc?.currentInfo
        if (svc != null && svc.currentUrl == url && info != null) {
            showDetails(info)
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
                showDetails(result)
                startPlayback(result)
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

    private fun startPlayback(info: StreamInfo) {
        val svc = service
        if (svc == null) {
            pendingInfo = info
            return
        }
        askNotificationPermission()
        try {
            svc.play(info)
            setVideoEnabled(true)
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

    // ---------------------------------------------------------------- details

    private fun showDetails(info: StreamInfo) {
        val v = LayoutInflater.from(this).inflate(R.layout.item_details, list, false)
        v.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        v.findViewById<TextView>(R.id.title).text = info.name
        val live = info.streamType == StreamType.LIVE_STREAM || info.streamType == StreamType.AUDIO_LIVE_STREAM
        val views = if (info.viewCount >= 0) {
            getString(if (live) R.string.watching else R.string.views, Format.count(this, info.viewCount))
        } else null
        val likes = if (info.likeCount > 0) getString(R.string.likes, Format.count(this, info.likeCount)) else null
        v.findViewById<TextView>(R.id.meta).text = Format.joinDot(views, info.textualUploadDate, likes)

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
            desc.setOnClickListener {
                expanded = !expanded
                desc.maxLines = if (expanded) Int.MAX_VALUE else 3
            }
        }

        val related = info.relatedItems.filterIsInstance<StreamInfoItem>()
        v.findViewById<View>(R.id.related).visibility = if (related.isEmpty()) View.GONE else View.VISIBLE

        val rows = ArrayList<Row>()
        rows.add(Row.Custom(v))
        related.forEach { rows.add(Row.Video(it)) }
        adapter.submit(rows)
        list.scrollToPosition(0)
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

    // ---------------------------------------------------------------- background / video track

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
    }

    override fun onStop() {
        super.onStop()
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

    // ---------------------------------------------------------------- fullscreen

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
        playerView.setFullscreenButtonState(true)
        list.visibility = View.GONE
        playerBox.locked = false
        val lp = playerBox.layoutParams as LinearLayout.LayoutParams
        lp.height = 0
        lp.weight = 1f
        playerBox.layoutParams = lp
        applyInsets()
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
        playerView.setFullscreenButtonState(false)
        list.visibility = View.VISIBLE
        playerBox.locked = true
        val lp = playerBox.layoutParams as LinearLayout.LayoutParams
        lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
        lp.weight = 0f
        playerBox.layoutParams = lp
        applyInsets()
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
