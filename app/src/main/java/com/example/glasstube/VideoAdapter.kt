package com.example.glasstube

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.CircleCropTransformation

/** Rows of the lists (home, search, profile, settings, related videos). */
sealed class Row {
    data class Title(val title: String, val subtitle: String?) : Row()
    data class Section(val title: String, val action: String? = null, val onAction: (() -> Unit)? = null) : Row()
    data class History(val query: String) : Row()
    data class VideoRow(val video: Video) : Row()
    data class Message(val text: String, val action: String? = null, val onAction: (() -> Unit)? = null) : Row()
    object Loading : Row()
    /** A custom view (account header, video details). */
    data class Custom(val view: View) : Row()
    data class Chips(val labels: List<String>, val selected: Int, val onSelect: (Int) -> Unit) : Row()
    /** Horizontally scrolling videos (history on the profile page). */
    data class Strip(val videos: List<Video>) : Row()
    data class Menu(
        val icon: Int,
        val title: String,
        val subtitle: String? = null,
        val thumb: String? = null,
        val chevron: Boolean = true,
        val onClick: () -> Unit
    ) : Row()
    data class Toggle(
        val icon: Int,
        val title: String,
        val subtitle: String?,
        val checked: Boolean,
        val enabled: Boolean = true,
        val onChange: (Boolean) -> Unit
    ) : Row()
    data class Choice(
        val title: String,
        val subtitle: String?,
        val options: List<String>,
        val selected: Int,
        val onSelect: (Int) -> Unit
    ) : Row()
    /** Empty space (e.g. under a floating header). */
    data class Space(val heightPx: Int) : Row()
    /** Expandable settings group header. */
    data class Group(
        val key: String,
        val icon: Int,
        val title: String,
        val subtitle: String?,
        val expanded: Boolean,
        val onToggle: () -> Unit
    ) : Row()
    data class ChannelRow(val name: String, val avatar: String?, val subtitle: String?, val onClick: () -> Unit) : Row()
    data class Comment(
        val author: String,
        val avatar: String?,
        val text: CharSequence,
        val likes: String?,
        val date: String?,
        val replies: Int
    ) : Row()
}

/** Identity of a row (for animated list updates). */
fun Row.key(): String = when (this) {
    is Row.Title -> "title:$title"
    is Row.Section -> "section:$title"
    is Row.Menu -> "menu:$title"
    is Row.Toggle -> "toggle:$title"
    is Row.Choice -> "choice:$title"
    is Row.Group -> "group:$key"
    is Row.VideoRow -> "video:${video.id}"
    is Row.History -> "history:$query"
    Row.Loading -> "loading"
    else -> "obj:" + System.identityHashCode(this)
}

/** What a row shows (a change of it rebinds the row). */
private fun Row.content(): String = when (this) {
    is Row.Menu -> "$title|$subtitle|$thumb"
    is Row.Toggle -> "$title|$subtitle|$checked|$enabled"
    is Row.Choice -> "$title|$subtitle|$selected|$options"
    is Row.Group -> "$title|$subtitle|$expanded"
    is Row.Section -> "$title|$action"
    else -> key()
}

class VideoAdapter(
    private val onVideo: (Video) -> Unit,
    private val onHistory: (String) -> Unit = {},
    private val onHistoryLong: (String) -> Unit = {},
    private val onVideoLong: ((Video) -> Unit)? = null,
    private val onChannel: ((String) -> Unit)? = null
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val rows = ArrayList<Row>()

    val items: List<Row> get() = rows

    @SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<Row>) {
        rows.clear()
        rows.addAll(list)
        notifyDataSetChanged()
    }

    /** Replaces the rows with animated inserts / removals (settings groups opening). */
    fun submitAnimated(list: List<Row>) {
        val old = ArrayList(rows)
        val diff = androidx.recyclerview.widget.DiffUtil.calculateDiff(object : androidx.recyclerview.widget.DiffUtil.Callback() {
            override fun getOldListSize() = old.size
            override fun getNewListSize() = list.size
            override fun areItemsTheSame(o: Int, n: Int) = old[o].key() == list[n].key()
            override fun areContentsTheSame(o: Int, n: Int) = old[o].content() == list[n].content()
            override fun getChangePayload(o: Int, n: Int): Any = "state"
        })
        rows.clear()
        rows.addAll(list)
        diff.dispatchUpdatesTo(this)
    }

    /** Appends rows, replacing a trailing loading / message row. */
    fun append(list: List<Row>) {
        val last = rows.lastOrNull()
        if (last is Row.Loading || last is Row.Message) {
            rows.removeAt(rows.size - 1)
            notifyItemRemoved(rows.size)
        }
        val start = rows.size
        rows.addAll(list)
        notifyItemRangeInserted(start, list.size)
    }

    override fun getItemCount(): Int = rows.size

    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        is Row.Title -> 0
        is Row.Section -> 1
        is Row.History -> 2
        is Row.VideoRow -> 3
        is Row.Message -> 4
        Row.Loading -> 5
        is Row.Custom -> 6
        is Row.Chips -> 7
        is Row.Strip -> 8
        is Row.Menu, is Row.Toggle -> 9
        is Row.Choice -> 10
        is Row.Space -> 11
        is Row.Group -> 12
        is Row.Comment -> 13
        is Row.ChannelRow -> 14
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        val layout = when (viewType) {
            0 -> R.layout.item_title
            1 -> R.layout.item_section
            2 -> R.layout.item_history
            3 -> R.layout.item_video
            4 -> R.layout.item_message
            5 -> R.layout.item_loading
            7 -> R.layout.item_chips
            8 -> R.layout.item_strip
            9 -> R.layout.item_menu
            10 -> R.layout.item_choice
            12 -> R.layout.item_group
            13 -> R.layout.item_comment
            14 -> R.layout.item_channel
            else -> 0
        }
        if (layout == 0) {
            val frame = FrameLayout(parent.context)
            frame.layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            return object : RecyclerView.ViewHolder(frame) {}
        }
        val v = inf.inflate(layout, parent, false)
        if (viewType == 8) {
            val rv = v as RecyclerView
            rv.layoutManager = LinearLayoutManager(parent.context, LinearLayoutManager.HORIZONTAL, false)
            rv.adapter = StripAdapter(onVideo, onVideoLong)
        }
        return object : RecyclerView.ViewHolder(v) {}
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val v = holder.itemView
        val ctx = v.context
        when (val row = rows[position]) {
            is Row.Title -> {
                v.findViewById<TextView>(R.id.title).text = row.title
                val sub = v.findViewById<TextView>(R.id.subtitle)
                sub.text = row.subtitle
                sub.visibility = if (row.subtitle.isNullOrEmpty()) View.GONE else View.VISIBLE
            }
            is Row.Section -> {
                v.findViewById<TextView>(R.id.title).text = row.title
                val a = v.findViewById<TextView>(R.id.action)
                a.text = row.action
                a.visibility = if (row.action == null) View.GONE else View.VISIBLE
                a.setOnClickListener { row.onAction?.invoke() }
            }
            is Row.History -> {
                val tv = v as TextView
                tv.text = row.query
                val icon = ContextCompat.getDrawable(ctx, R.drawable.ic_history)?.mutate()
                val size = (20 * v.resources.displayMetrics.density).toInt()
                icon?.setBounds(0, 0, size, size)
                icon?.setTint(ContextCompat.getColor(ctx, R.color.text_muted))
                tv.setCompoundDrawablesRelative(icon, null, null, null)
                tv.setOnClickListener { onHistory(row.query) }
                tv.setOnLongClickListener {
                    onHistoryLong(row.query)
                    true
                }
            }
            is Row.VideoRow -> bindVideo(v, row.video)
            is Row.Message -> {
                v.findViewById<TextView>(R.id.text).text = row.text
                val a = v.findViewById<TextView>(R.id.action)
                a.text = row.action
                a.visibility = if (row.action == null) View.GONE else View.VISIBLE
                a.setOnClickListener { row.onAction?.invoke() }
            }
            Row.Loading -> Unit
            is Row.Custom -> {
                val frame = v as FrameLayout
                if (row.view.parent !== frame) {
                    (row.view.parent as? ViewGroup)?.removeView(row.view)
                    frame.removeAllViews()
                    frame.addView(row.view)
                }
            }
            is Row.Chips -> {
                val box = v.findViewById<LinearLayout>(R.id.chips)
                box.removeAllViews()
                row.labels.forEachIndexed { i, label ->
                    val chip = pill(ctx, label, i == row.selected)
                    chip.setOnClickListener { row.onSelect(i) }
                    val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    lp.marginStart = dp(ctx, 4)
                    lp.marginEnd = dp(ctx, 4)
                    box.addView(chip, lp)
                }
            }
            is Row.Strip -> ((v as RecyclerView).adapter as StripAdapter).submit(row.videos)
            is Row.Menu -> bindMenu(v, row.icon, row.title, row.subtitle, row.thumb, row.chevron)
                .also {
                    v.findViewById<GlassSwitch>(R.id.toggle).visibility = View.GONE
                    v.isClickable = true
                    v.alpha = 1f
                    v.setOnClickListener { row.onClick() }
                }
            is Row.Toggle -> {
                bindMenu(v, row.icon, row.title, row.subtitle, null, false)
                val sw = v.findViewById<GlassSwitch>(R.id.toggle)
                sw.visibility = View.VISIBLE
                sw.setChecked(row.checked)
                sw.isEnabled = row.enabled
                v.alpha = if (row.enabled) 1f else 0.5f
                sw.onChange = { row.onChange(it) }
                v.setOnClickListener { if (row.enabled) sw.toggleByUser() }
            }
            is Row.Choice -> {
                v.findViewById<TextView>(R.id.title).text = row.title
                val sub = v.findViewById<TextView>(R.id.subtitle)
                sub.text = row.subtitle
                sub.visibility = if (row.subtitle.isNullOrEmpty()) View.GONE else View.VISIBLE
                val seg = v.findViewById<GlassSegmented>(R.id.segmented)
                val same = seg.tag == row.title
                seg.tag = row.title
                seg.options = row.options
                seg.setSelected(row.selected, animate = same)
                seg.onSelect = { row.onSelect(it) }
            }
            is Row.Space -> {
                v.layoutParams = v.layoutParams.apply { height = row.heightPx }
            }
            is Row.Group -> {
                v.findViewById<TextView>(R.id.title).text = row.title
                val sub = v.findViewById<TextView>(R.id.subtitle)
                sub.text = row.subtitle
                sub.visibility = if (row.subtitle.isNullOrEmpty()) View.GONE else View.VISIBLE
                v.background = Glass.pill(ctx, dp(ctx, 20).toFloat())
                val box = v.findViewById<View>(R.id.iconBox)
                box.background = Glass.iconTile(ctx, dp(ctx, 12).toFloat())
                v.findViewById<ImageView>(R.id.icon).apply {
                    setImageResource(row.icon)
                    setColorFilter(Glass.textColor(ctx))
                }
                val ch = v.findViewById<ImageView>(R.id.chevron)
                ch.setColorFilter(ContextCompat.getColor(ctx, R.color.text_muted))
                val target = if (row.expanded) 180f else 0f
                if (v.tag == row.key) {
                    ch.animate().rotation(target).setDuration(320)
                        .setInterpolator(android.view.animation.OvershootInterpolator(1.4f)).start()
                } else {
                    ch.animate().cancel()
                    ch.rotation = target
                }
                v.tag = row.key
                v.setOnClickListener { row.onToggle() }
            }
            is Row.ChannelRow -> {
                v.findViewById<ImageView>(R.id.avatar).load(row.avatar) { transformations(CircleCropTransformation()) }
                v.findViewById<TextView>(R.id.title).text = row.name
                val sub = v.findViewById<TextView>(R.id.subtitle)
                sub.text = row.subtitle
                sub.visibility = if (row.subtitle.isNullOrEmpty()) View.GONE else View.VISIBLE
                v.setOnClickListener { row.onClick() }
            }
            is Row.Comment -> {
                v.findViewById<ImageView>(R.id.avatar).load(row.avatar) { transformations(CircleCropTransformation()) }
                v.findViewById<TextView>(R.id.meta).text = Format.joinDot(row.author, row.date)
                val t = v.findViewById<TextView>(R.id.text)
                t.text = row.text
                t.maxLines = 6
                t.setOnClickListener { t.maxLines = if (t.maxLines == 6) Int.MAX_VALUE else 6 }
                val likes = v.findViewById<TextView>(R.id.likes)
                val parts = listOfNotNull(
                    row.likes,
                    if (row.replies > 0) ctx.getString(R.string.replies, row.replies) else null
                )
                likes.text = parts.joinToString("   ")
                val icon = ContextCompat.getDrawable(ctx, R.drawable.ic_like)?.mutate()
                val sz = dp(ctx, 14)
                icon?.setBounds(0, 0, sz, sz)
                icon?.setTint(ContextCompat.getColor(ctx, R.color.text_muted))
                likes.setCompoundDrawablesRelative(if (row.likes != null) icon else null, null, null, null)
                likes.visibility = if (parts.isEmpty()) View.GONE else View.VISIBLE
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
        onBindViewHolder(holder, position)
    }

    private fun bindMenu(v: View, icon: Int, title: String, subtitle: String?, thumb: String?, chevron: Boolean) {
        val ctx = v.context
        v.findViewById<TextView>(R.id.title).text = title
        val sub = v.findViewById<TextView>(R.id.subtitle)
        sub.text = subtitle
        sub.visibility = if (subtitle.isNullOrEmpty()) View.GONE else View.VISIBLE
        val iconBox = v.findViewById<View>(R.id.iconBox)
        val thumbBox = v.findViewById<View>(R.id.thumbBox)
        if (thumb != null) {
            iconBox.visibility = View.GONE
            thumbBox.visibility = View.VISIBLE
            v.findViewById<ImageView>(R.id.thumb).load(thumb) { crossfade(150) }
        } else {
            thumbBox.visibility = View.GONE
            iconBox.visibility = View.VISIBLE
            iconBox.background = Glass.pill(ctx, dp(ctx, 14).toFloat())
            val iv = v.findViewById<ImageView>(R.id.icon)
            iv.setImageResource(icon)
            iv.setColorFilter(Glass.textColor(ctx))
        }
        val ch = v.findViewById<ImageView>(R.id.chevron)
        ch.visibility = if (chevron) View.VISIBLE else View.GONE
        ch.setColorFilter(ContextCompat.getColor(ctx, R.color.text_muted))
    }

    private fun bindVideo(v: View, video: Video) {
        val ctx = v.context
        v.findViewById<ImageView>(R.id.thumb).load(video.thumb) { crossfade(180) }
        bindBadge(v.findViewById(R.id.badge), video)
        v.findViewById<TextView>(R.id.title).text = video.title
        v.findViewById<TextView>(R.id.meta).text = Format.joinDot(video.channel, video.viewsText, video.dateText)
        val avatar = v.findViewById<ImageView>(R.id.avatar)
        if (video.avatar != null) {
            avatar.visibility = View.VISIBLE
            avatar.load(video.avatar) { transformations(CircleCropTransformation()) }
        } else {
            avatar.visibility = View.GONE
        }
        val curl = video.channelUrl
        if (curl != null && onChannel != null) avatar.setOnClickListener { onChannel.invoke(curl) } else avatar.setOnClickListener(null)
        v.setOnClickListener { onVideo(video) }
        if (onVideoLong != null) v.setOnLongClickListener {
            onVideoLong.invoke(video)
            true
        } else v.setOnLongClickListener(null)
    }

    companion object {
        fun dp(ctx: android.content.Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

        fun bindBadge(badge: TextView, video: Video) {
            val ctx = badge.context
            when {
                video.isLive -> {
                    badge.text = ctx.getString(R.string.live)
                    badge.setBackgroundResource(R.drawable.badge_live_bg)
                    badge.visibility = View.VISIBLE
                }
                video.durationSec > 0 -> {
                    badge.text = Format.duration(video.durationSec)
                    badge.setBackgroundResource(R.drawable.badge_bg)
                    badge.visibility = View.VISIBLE
                }
                video.isShort -> {
                    badge.text = ctx.getString(R.string.short_label)
                    badge.setBackgroundResource(R.drawable.badge_bg)
                    badge.visibility = View.VISIBLE
                }
                else -> badge.visibility = View.GONE
            }
        }

        /** A glass chip; the selected one is filled with the text color. */
        fun pill(ctx: android.content.Context, label: String, selected: Boolean): TextView {
            val tv = TextView(ctx)
            tv.text = label
            tv.textSize = 14f
            tv.setPadding(dp(ctx, 16), dp(ctx, 8), dp(ctx, 16), dp(ctx, 8))
            val text = Glass.textColor(ctx)
            if (selected) {
                tv.setTextColor(if (Glass.isNight(ctx)) Color.BLACK else Color.WHITE)
                tv.background = GradientDrawable().apply {
                    cornerRadius = dp(ctx, 18).toFloat()
                    setColor(ColorUtils.setAlphaComponent(text, 235))
                }
                tv.setTypeface(tv.typeface, android.graphics.Typeface.BOLD)
            } else {
                tv.setTextColor(text)
                tv.background = Glass.pill(ctx, dp(ctx, 18).toFloat())
            }
            return tv
        }
    }
}

/** The horizontal history strip. */
class StripAdapter(
    private val onVideo: (Video) -> Unit,
    private val onLong: ((Video) -> Unit)? = null
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private val list = ArrayList<Video>()

    @SuppressLint("NotifyDataSetChanged")
    fun submit(videos: List<Video>) {
        list.clear()
        list.addAll(videos)
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = list.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
        object : RecyclerView.ViewHolder(
            LayoutInflater.from(parent.context).inflate(R.layout.item_strip_video, parent, false)
        ) {}

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val v = holder.itemView
        val video = list[position]
        v.findViewById<ImageView>(R.id.thumb).load(video.thumb) { crossfade(150) }
        VideoAdapter.bindBadge(v.findViewById(R.id.badge), video)
        v.findViewById<TextView>(R.id.title).text = video.title
        v.findViewById<TextView>(R.id.meta).text = video.channel.orEmpty()
        v.setOnClickListener { onVideo(video) }
        if (onLong != null) v.setOnLongClickListener {
            onLong.invoke(video)
            true
        }
    }
}
