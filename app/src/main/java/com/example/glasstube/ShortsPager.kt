package com.example.glasstube

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import coil.load
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
import org.schabi.newpipe.extractor.linkhandler.SearchQueryHandler
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem

/**
 * The Shorts tab: full-screen vertical videos, one per page, swiped up and down. A single player
 * (on a TextureView, so the glass dock can refract it) moves to the page in view.
 */
@OptIn(UnstableApi::class)
class ShortsPager(
    private val activity: AppCompatActivity,
    private val list: RecyclerView,
    /** Space to keep free at the bottom (dock + navigation bar). */
    private val bottomInset: () -> Int
) {
    private val ctx = activity
    private val yt = ServiceList.YouTube
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

    // search-based source (also used without a Google account)
    private var queries: List<String> = emptyList()
    private var queryIndex = 0
    private var handler: SearchQueryHandler? = null
    private var nextPage: Page? = null

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
    }

    /** Shorts found elsewhere (e.g. on the signed-in home feed) go to the front of the queue. */
    fun offer(videos: List<Video>) {
        val fresh = videos.filter { seen.add(it.id) }
        if (fresh.isEmpty()) return
        val start = items.size
        items.addAll(fresh)
        adapter.notifyItemRangeInserted(start, fresh.size)
        if (active && current < 0) list.post { settle() }
    }

    fun setQueries(q: List<String>) {
        if (q == queries) return
        queries = q
        queryIndex = 0
        handler = null
        nextPage = null
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
    }

    fun release() {
        progressJob?.cancel()
        player?.release()
        player = null
    }

    private fun ensurePlayer(): ExoPlayer {
        player?.let { return it }
        val p = ExoPlayer.Builder(ctx)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                true
            )
            .build()
        p.repeatMode = Player.REPEAT_MODE_ONE
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setMaxVideoSize(1920, 1920)
            .build()
        p.addListener(object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                // vertical videos fill the screen, others fit
                playerView.resizeMode = if (videoSize.height >= videoSize.width) {
                    AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                } else {
                    AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
            }
        })
        playerView.player = p
        player = p
        return p
    }

    private fun settle() {
        if (!active) return
        val view = snap.findSnapView(list.layoutManager) ?: return
        val pos = list.getChildAdapterPosition(view)
        if (pos == RecyclerView.NO_POSITION || pos == current) return
        current = pos
        playAt(pos, view)
        if (pos >= items.size - 3) loadMore()
    }

    private fun playAt(pos: Int, itemView: View) {
        val video = items.getOrNull(pos) ?: return
        val slot = itemView.findViewById<FrameLayout>(R.id.playerSlot)
        (playerView.parent as? ViewGroup)?.removeView(playerView)
        slot.addView(playerView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val p = ensurePlayer()
        p.stop()
        p.clearMediaItems()
        loadJob?.cancel()
        loadJob = activity.lifecycleScope.launch {
            try {
                val info = info(video.id).await()
                if (current != pos) return@launch
                val meta = MediaMetadata.Builder().setTitle(info.name).setArtist(info.uploaderName).build()
                p.setMediaSource(StreamResolver.build(info, meta))
                p.prepare()
                p.playWhenReady = active
                // prefetch the next one
                items.getOrNull(pos + 1)?.let { info(it.id) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // skip broken ones
                if (current == pos) list.smoothScrollToPosition(pos + 1)
            }
        }
        startProgress(itemView)
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

    private fun info(id: String): Deferred<StreamInfo> =
        infoCache.getOrPut(id) {
            activity.lifecycleScope.async(Dispatchers.IO) {
                StreamInfo.getInfo(yt, "https://www.youtube.com/shorts/$id")
            }
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
        ctx.startActivity(
            Intent(ctx, PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_URL, v.url)
        )
    }

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_short, parent, false)
            v.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            return object : RecyclerView.ViewHolder(v) {}
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val v = holder.itemView
            val video = items[position]
            val d = v.resources.displayMetrics.density
            v.findViewById<ImageView>(R.id.thumb).load(video.thumb) { crossfade(120) }
            v.findViewById<TextView>(R.id.title).text = video.title
            v.findViewById<TextView>(R.id.channel).text = video.channel ?: video.viewsText.orEmpty()
            val bottom = bottomInset()
            val info = v.findViewById<View>(R.id.info)
            info.setPadding(info.paddingLeft, 0, info.paddingRight, bottom + (22 * d).toInt())
            val seek = v.findViewById<GlassSeekBar>(R.id.seek)
            (seek.layoutParams as FrameLayout.LayoutParams).bottomMargin = bottom
            seek.sidePadding = 20 * d
            seek.restThickness = 3 * d
            // leftover player from a recycled page
            val slot = v.findViewById<FrameLayout>(R.id.playerSlot)
            if (playerView.parent === slot && position != current) slot.removeView(playerView)

            val actions = v.findViewById<LinearLayout>(R.id.actions)
            actions.removeAllViews()
            fun action(icon: Int, desc: Int, click: () -> Unit) {
                val b = FrameLayout(ctx)
                b.background = Glass.overVideo(ctx, null)
                b.contentDescription = ctx.getString(desc)
                val iv = ImageView(ctx)
                iv.setImageResource(icon)
                iv.setColorFilter(android.graphics.Color.WHITE)
                b.addView(iv, FrameLayout.LayoutParams((22 * d).toInt(), (22 * d).toInt(), android.view.Gravity.CENTER))
                b.setOnClickListener { click() }
                val lp = LinearLayout.LayoutParams((48 * d).toInt(), (48 * d).toInt())
                lp.topMargin = (12 * d).toInt()
                actions.addView(b, lp)
            }
            action(R.drawable.ic_share, R.string.action_share) { share(video) }
            action(R.drawable.ic_open, R.string.open_player) { openFull(video) }

            val paused = v.findViewById<ImageView>(R.id.paused)
            paused.background = Glass.overVideo(ctx, null)
            paused.setColorFilter(android.graphics.Color.WHITE)
            v.setOnClickListener {
                val p = player ?: return@setOnClickListener
                if (holder.bindingAdapterPosition != current) return@setOnClickListener
                if (p.isPlaying) {
                    p.pause()
                    paused.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(160).start()
                } else {
                    p.play()
                    paused.animate().alpha(0f).scaleX(1.3f).scaleY(1.3f).setDuration(200).start()
                }
            }
            paused.alpha = 0f
        }
    }

    @Suppress("unused")
    private fun open(url: String) = ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
}
