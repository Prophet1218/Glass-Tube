package com.example.glasstube

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.text.Html
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.CircleCropTransformation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.comments.CommentsInfo
import org.schabi.newpipe.extractor.comments.CommentsInfoItem
import org.schabi.newpipe.extractor.linkhandler.SearchQueryHandler
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.Description
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem

/**
 * The Shorts tab: full-screen vertical videos, one per page, swiped up and down, with the
 * YouTube Shorts layout (actions on the right, channel and title at the bottom). A single player
 * (on a TextureView, so the glass dock can refract it) moves to the page in view; the next pages
 * are fetched ahead so they start quickly.
 */
@OptIn(UnstableApi::class)
class ShortsPager(
    private val activity: AppCompatActivity,
    private val list: RecyclerView,
    /** Space to keep free at the bottom (dock + navigation bar). */
    private val bottomInset: () -> Int,
    private val onOpenVideo: (Video) -> Unit
) {
    private val ctx = activity
    private val yt = ServiceList.YouTube
    private val settings = Settings(activity)
    private val items = ArrayList<Video>()
    private val seen = HashSet<String>()
    private val adapter = Adapter()
    private val snap = PagerSnapHelper()
    private var player: ExoPlayer? = null
    private val playerView: PlayerView =
        LayoutInflater.from(ctx).inflate(R.layout.view_shorts_player, null, false) as PlayerView
    private var current = -1
    private var active = false
    private var loadJob: Job? = null
    private var moreJob: Job? = null
    private var progressJob: Job? = null
    private val infoCache = HashMap<String, Deferred<StreamInfo>>()
    private val density = ctx.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    // search-based source (also used without a Google account)
    private var queries: List<String> = emptyList()
    private var queryIndex = 0
    private var handler: SearchQueryHandler? = null
    private var nextPage: Page? = null

    // comments panel (one for the whole tab)
    private val host = list.parent as FrameLayout
    private val commentsPanel: View = LayoutInflater.from(ctx).inflate(R.layout.view_comments, host, false)
    private val commentsAdapter = VideoAdapter(onVideo = { })
    private var commentsInfo: CommentsInfo? = null
    private var commentsNext: Page? = null
    private var commentsJob: Job? = null

    init {
        list.layoutManager = LinearLayoutManager(ctx)
        list.adapter = adapter
        list.itemAnimator = null
        snap.attachToRecyclerView(list)
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) settle()
            }
        })
        commentsPanel.visibility = View.GONE
        host.addView(commentsPanel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, Gravity.BOTTOM))
        val cl = commentsPanel.findViewById<RecyclerView>(R.id.commentsList)
        cl.layoutManager = LinearLayoutManager(ctx)
        cl.adapter = commentsAdapter
        cl.itemAnimator = null
        cl.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val lm = rv.layoutManager as LinearLayoutManager
                if (lm.findLastVisibleItemPosition() >= commentsAdapter.itemCount - 4 &&
                    commentsAdapter.items.lastOrNull() == Row.Loading
                ) moreComments()
            }
        })
        commentsPanel.findViewById<ImageView>(R.id.commentsClose).apply {
            setColorFilter(Glass.textColor(ctx))
            setOnClickListener { closeComments() }
        }
    }

    /** Shorts found elsewhere (e.g. on the signed-in home feed) go to the queue. */
    fun offer(videos: List<Video>) {
        val fresh = videos.filter { seen.add(it.id) }
        if (fresh.isEmpty()) return
        val start = items.size
        items.addAll(fresh)
        adapter.notifyItemRangeInserted(start, fresh.size)
        fresh.take(2).forEach { info(it.id) }
        if (active && current < 0) list.post { settle() }
    }

    fun setQueries(q: List<String>) {
        if (q == queries) return
        queries = q
        queryIndex = 0
        handler = null
        nextPage = null
    }

    fun applySettings() {
        player?.repeatMode = if (settings.shortsLoop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
    }

    fun onShown() {
        active = true
        if (items.isEmpty()) loadMore() else {
            if (current < 0) list.post { settle() } else player?.play()
        }
    }

    fun onHidden() {
        active = false
        player?.pause()
        if (commentsPanel.visibility == View.VISIBLE) commentsPanel.visibility = View.GONE
    }

    /** Back closes the comments first. */
    fun handleBack(): Boolean {
        if (commentsPanel.visibility != View.VISIBLE) return false
        closeComments()
        return true
    }

    fun release() {
        progressJob?.cancel()
        player?.release()
        player = null
    }

    private fun ensurePlayer(): ExoPlayer {
        player?.let { return it }
        // start fast: little buffer before playback begins
        val load = DefaultLoadControl.Builder()
            .setBufferDurationsMs(2_000, 20_000, 350, 1_000)
            .build()
        val p = ExoPlayer.Builder(ctx)
            .setLoadControl(load)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                true
            )
            .build()
        p.repeatMode = if (settings.shortsLoop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        // 720p is plenty for a phone-sized vertical video and starts much sooner
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setMaxVideoSize(1280, 1280)
            .build()
        p.addListener(object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                playerView.resizeMode = if (videoSize.height >= videoSize.width) {
                    AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                } else {
                    AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                currentView()?.findViewById<View>(R.id.spinner)?.visibility =
                    if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
                if (state == Player.STATE_ENDED && !settings.shortsLoop && active) {
                    list.smoothScrollToPosition(current + 1)
                }
            }

            override fun onRenderedFirstFrame() {
                currentView()?.findViewById<View>(R.id.thumb)?.animate()?.alpha(0f)?.setDuration(150)?.start()
            }
        })
        playerView.player = p
        player = p
        return p
    }

    private fun currentView(): View? = list.findViewHolderForAdapterPosition(current)?.itemView

    private fun settle() {
        if (!active) return
        val view = snap.findSnapView(list.layoutManager) ?: return
        val pos = list.getChildAdapterPosition(view)
        if (pos == RecyclerView.NO_POSITION || pos == current) return
        current = pos
        closeCommentsNow()
        playAt(pos, view)
        if (pos >= items.size - 4) loadMore()
    }

    private fun playAt(pos: Int, itemView: View) {
        val video = items.getOrNull(pos) ?: return
        val slot = itemView.findViewById<FrameLayout>(R.id.playerSlot)
        (playerView.parent as? ViewGroup)?.removeView(playerView)
        slot.addView(playerView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        itemView.findViewById<View>(R.id.thumb).alpha = 1f
        val p = ensurePlayer()
        p.stop()
        p.clearMediaItems()
        // fetch the next ones while this one loads
        for (i in 1..3) items.getOrNull(pos + i)?.let { info(it.id) }
        itemView.findViewById<View>(R.id.spinner).visibility = View.VISIBLE
        loadJob?.cancel()
        loadJob = activity.lifecycleScope.launch {
            try {
                val info = info(video.id).await()
                if (current != pos) return@launch
                val meta = MediaMetadata.Builder().setTitle(info.name).setArtist(info.uploaderName).build()
                p.setMediaSource(StreamResolver.build(info, meta))
                p.prepare()
                p.playWhenReady = active
                bindInfo(itemView, video, info)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // skip broken ones
                if (current == pos) list.smoothScrollToPosition(pos + 1)
            }
        }
        startProgress(itemView)
    }

    /** Fills in what only the full video info has (avatar, likes, comment count). */
    private fun bindInfo(v: View, video: Video, info: StreamInfo) {
        v.findViewById<ImageView>(R.id.avatar).load(Format.pick(info.uploaderAvatars, 96)) {
            transformations(CircleCropTransformation())
        }
        v.findViewById<TextView>(R.id.channel).text = info.uploaderName ?: video.channel
        if (video.title.isEmpty()) v.findViewById<TextView>(R.id.title).text = info.name
        v.findViewWithTag<TextView>("likes")?.text =
            if (info.likeCount > 0) Format.count(ctx, info.likeCount) else ctx.getString(R.string.like)
    }

    private fun startProgress(itemView: View) {
        progressJob?.cancel()
        val seek = itemView.findViewById<GlassSeekBar>(R.id.seek)
        seek.onSeek = { player?.seekTo(it) }
        progressJob = activity.lifecycleScope.launch {
            while (isActive) {
                val p = player
                if (p != null && !seek.scrubbing) {
                    seek.durationMs = p.duration.coerceAtLeast(0)
                    seek.positionMs = p.currentPosition
                    seek.bufferedMs = p.bufferedPosition
                }
                delay(100)
            }
        }
    }

    private fun info(id: String): Deferred<StreamInfo> {
        val existing = infoCache[id]
        if (existing != null && !(existing.isCompleted && existing.getCompletionExceptionOrNull() != null)) return existing
        val d = activity.lifecycleScope.async(Dispatchers.IO) {
            StreamInfo.getInfo(yt, "https://www.youtube.com/shorts/$id")
        }
        infoCache[id] = d
        return d
    }

    private fun loadMore() {
        if (moreJob?.isActive == true) return
        moreJob = activity.lifecycleScope.launch {
            try {
                var tries = 0
                var added = 0
                while (added < 4 && tries < 4) {
                    tries++
                    val found = withContext(Dispatchers.IO) { nextSearchPage() }
                    val fresh = found.filter { seen.add(it.id) }
                    if (fresh.isNotEmpty()) {
                        val start = items.size
                        items.addAll(fresh)
                        adapter.notifyItemRangeInserted(start, fresh.size)
                        added += fresh.size
                        if (current < 0) fresh.take(2).forEach { info(it.id) }
                    }
                }
                if (active && current < 0) list.post { settle() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // try again on the next swipe
            }
        }
    }

    /** One page of search results, keeping only Shorts. */
    private fun nextSearchPage(): List<Video> {
        val qs = queries.ifEmpty { listOf("") }
        val h = handler
        val page = nextPage
        val result: List<StreamInfoItem>
        if (h != null && page != null && Page.isValid(page)) {
            val res = SearchInfo.getMoreItems(yt, h, page)
            nextPage = res.nextPage
            result = res.items.filterIsInstance<StreamInfoItem>()
        } else {
            val q = (qs[queryIndex % qs.size] + " #shorts").trim()
            queryIndex++
            val nh = yt.searchQHFactory.fromQuery(q, listOf(YoutubeSearchQueryHandlerFactory.VIDEOS), "")
            val info = SearchInfo.getInfo(yt, nh)
            handler = nh
            nextPage = info.nextPage
            result = info.relatedItems.filterIsInstance<StreamInfoItem>()
        }
        return result.mapNotNull { Video.from(ctx, it) }
            .filter { it.isShort || (it.durationSec in 1..60) }
            .map { it.copy(isShort = true) }
    }

    private fun share(v: Video) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "https://www.youtube.com/shorts/${v.id}")
        ctx.startActivity(Intent.createChooser(send, v.title))
    }

    private fun openFull(v: Video) {
        player?.pause()
        onOpenVideo(v)
    }

    // ---- like

    private var likeStatus = "INDIFFERENT"

    private fun like(v: Video, view: View) {
        if (!Account.isSignedIn(ctx)) {
            Toast.makeText(ctx, R.string.sign_in_to_rate, Toast.LENGTH_SHORT).show()
            return
        }
        val target = if (likeStatus == "LIKE") "INDIFFERENT" else "LIKE"
        likeStatus = target
        tintLike(view, target == "LIKE")
        activity.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { Innertube.rate(ctx, v.id, target) }
            } catch (e: Exception) {
                Toast.makeText(ctx, Errors.text(ctx, e), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun tintLike(view: View, liked: Boolean) {
        val icon = view.findViewWithTag<ImageView>("likeIcon") ?: return
        icon.setColorFilter(if (liked) 0xFF3EA6FF.toInt() else Color.WHITE)
        icon.scaleX = 0.7f
        icon.scaleY = 0.7f
        icon.animate().scaleX(1f).scaleY(1f).setDuration(380)
            .setInterpolator(android.view.animation.OvershootInterpolator(3f)).start()
    }

    // ---- comments

    private fun commentRow(c: CommentsInfoItem): Row.Comment {
        val d = c.commentText
        val text: CharSequence = when {
            d == null -> ""
            d.type() == Description.Type.HTML -> Html.fromHtml(d.content(), Html.FROM_HTML_MODE_COMPACT).toString().trim()
            else -> d.content().trim()
        }
        val likes = c.textualLikeCount?.takeIf { it.isNotBlank() && it != "0" }
            ?: if (c.likeCount > 0) Format.count(ctx, c.likeCount.toLong()) else null
        return Row.Comment(c.uploaderName.orEmpty(), Format.pick(c.uploaderAvatars, 96), text, likes,
            Format.date(c.uploadDate, c.textualUploadDate), c.replyCount.coerceAtLeast(0))
    }

    private fun openComments(v: Video) {
        val lp = commentsPanel.layoutParams
        lp.height = (host.height * 0.66f).toInt()
        commentsPanel.layoutParams = lp
        commentsPanel.findViewById<RecyclerView>(R.id.commentsList).setPadding(0, 0, 0, bottomInset())
        commentsPanel.visibility = View.VISIBLE
        commentsPanel.translationY = lp.height.toFloat()
        commentsPanel.animate().translationY(0f).setDuration(380).setInterpolator(PathInterpolator(0.2f, 0f, 0f, 1f)).start()
        commentsAdapter.submit(listOf(Row.Loading))
        commentsJob?.cancel()
        commentsJob = activity.lifecycleScope.launch {
            try {
                val info = withContext(Dispatchers.IO) { CommentsInfo.getInfo(yt, "https://www.youtube.com/watch?v=${v.id}") }
                commentsInfo = info
                commentsNext = info.nextPage
                val count = info.commentsCount
                commentsPanel.findViewById<TextView>(R.id.commentsTitle).text =
                    if (count > 0) ctx.getString(R.string.comments) + "  " + Format.count(ctx, count.toLong())
                    else ctx.getString(R.string.comments)
                val rows = ArrayList<Row>()
                if (info.isCommentsDisabled) rows.add(Row.Message(ctx.getString(R.string.comments_off)))
                info.relatedItems.forEach { rows.add(commentRow(it)) }
                if (rows.isEmpty()) rows.add(Row.Message(ctx.getString(R.string.nothing_here)))
                if (Page.isValid(commentsNext)) rows.add(Row.Loading)
                commentsAdapter.submit(rows)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                commentsAdapter.submit(listOf(Row.Message(Errors.text(ctx, e))))
            }
        }
    }

    private fun moreComments() {
        val info = commentsInfo ?: return
        val page = commentsNext ?: return
        if (!Page.isValid(page) || commentsJob?.isActive == true) return
        commentsJob = activity.lifecycleScope.launch {
            try {
                val res = withContext(Dispatchers.IO) { CommentsInfo.getMoreItems(yt, info, page) }
                commentsNext = res.nextPage
                val rows = ArrayList<Row>()
                res.items.forEach { rows.add(commentRow(it)) }
                if (Page.isValid(commentsNext)) rows.add(Row.Loading)
                commentsAdapter.append(rows)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                commentsAdapter.append(listOf(Row.Message(Errors.text(ctx, e))))
            }
        }
    }

    private fun closeComments() {
        commentsPanel.animate().translationY(commentsPanel.height.toFloat()).setDuration(260)
            .setInterpolator(DecelerateInterpolator()).withEndAction { commentsPanel.visibility = View.GONE }.start()
    }

    private fun closeCommentsNow() {
        commentsJob?.cancel()
        commentsPanel.visibility = View.GONE
    }

    // ---- pages

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_short, parent, false)
            v.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            return object : RecyclerView.ViewHolder(v) {}
        }

        /** A YouTube Shorts style action: white icon with a soft shadow and a label under it. */
        private fun action(parent: LinearLayout, icon: Int, label: String, tag: String?, click: () -> Unit) {
            val box = LinearLayout(ctx)
            box.orientation = LinearLayout.VERTICAL
            box.gravity = Gravity.CENTER_HORIZONTAL
            val iv = ImageView(ctx)
            iv.setImageResource(icon)
            iv.setColorFilter(Color.WHITE)
            iv.elevation = 0f
            if (tag != null) iv.tag = "${tag}Icon"
            box.addView(iv, LinearLayout.LayoutParams(dp(30), dp(30)))
            val tv = TextView(ctx)
            tv.text = label
            tv.textSize = 12f
            tv.setTextColor(Color.WHITE)
            tv.setShadowLayer(5f, 0f, 1f, Color.argb(150, 0, 0, 0))
            tv.gravity = Gravity.CENTER
            if (tag != null) tv.tag = tag
            box.addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
            val ta = ctx.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackgroundBorderless))
            box.background = ta.getDrawable(0)
            ta.recycle()
            box.setOnClickListener { click() }
            parent.addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(18) })
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val v = holder.itemView
            val video = items[position]
            val thumb = v.findViewById<ImageView>(R.id.thumb)
            thumb.alpha = 1f
            thumb.load(video.thumb) { crossfade(100) }
            v.findViewById<TextView>(R.id.title).text = video.title
            v.findViewById<TextView>(R.id.channel).text = video.channel ?: ""
            v.findViewById<ImageView>(R.id.avatar).setImageDrawable(null)
            v.findViewById<View>(R.id.spinner).visibility = View.GONE
            val bottom = bottomInset()
            val info = v.findViewById<View>(R.id.info)
            info.setPadding(info.paddingLeft, 0, info.paddingRight, bottom + dp(20))
            val seek = v.findViewById<GlassSeekBar>(R.id.seek)
            (seek.layoutParams as FrameLayout.LayoutParams).bottomMargin = bottom - dp(6)
            seek.sidePadding = 16 * density
            seek.restThickness = 3 * density
            val slot = v.findViewById<FrameLayout>(R.id.playerSlot)
            if (playerView.parent === slot && position != current) slot.removeView(playerView)

            val actions = v.findViewById<LinearLayout>(R.id.actions)
            actions.removeAllViews()
            likeStatus = "INDIFFERENT"
            action(actions, R.drawable.ic_like, ctx.getString(R.string.like), "likes") { like(video, v) }
            action(actions, R.drawable.ic_comment, ctx.getString(R.string.comments), null) { openComments(video) }
            action(actions, R.drawable.ic_share, ctx.getString(R.string.action_share), null) { share(video) }
            action(actions, R.drawable.ic_open, ctx.getString(R.string.player_short), null) { openFull(video) }

            val paused = v.findViewById<PlayPauseView>(R.id.paused)
            paused.background = Glass.overVideo(ctx, null)
            paused.setColor(Color.WHITE)
            paused.setPause(false, animate = false)
            paused.alpha = 0f
            v.setOnClickListener {
                val p = player ?: return@setOnClickListener
                if (holder.bindingAdapterPosition != current) return@setOnClickListener
                if (p.isPlaying) {
                    p.pause()
                    paused.setPause(false, animate = false)
                    paused.scaleX = 0.7f
                    paused.scaleY = 0.7f
                    paused.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220).start()
                } else {
                    p.play()
                    paused.setPause(true)
                    paused.animate().alpha(0f).scaleX(1.25f).scaleY(1.25f).setStartDelay(120).setDuration(220)
                        .withEndAction { paused.animate().setStartDelay(0) }.start()
                }
            }
        }
    }
}
