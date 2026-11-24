package com.example.glasstube

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.CircleCropTransformation
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType

/** Rows of the lists (home, search results, related videos). */
sealed class Row {
    data class Title(val title: String, val subtitle: String?) : Row()
    data class Section(val title: String, val action: String?, val onAction: (() -> Unit)?) : Row()
    data class History(val query: String) : Row()
    data class Video(val item: StreamInfoItem) : Row()
    data class Message(val text: String, val action: String?, val onAction: (() -> Unit)?) : Row()
    object Loading : Row()
    /** A custom view (the video details on the player screen). */
    data class Custom(val view: View) : Row()
}

class VideoAdapter(
    private val onVideo: (StreamInfoItem) -> Unit,
    private val onHistory: (String) -> Unit = {},
    private val onHistoryLong: (String) -> Unit = {}
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val rows = ArrayList<Row>()

    val items: List<Row> get() = rows

    @SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<Row>) {
        rows.clear()
        rows.addAll(list)
        notifyDataSetChanged()
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
        is Row.Video -> 3
        is Row.Message -> 4
        Row.Loading -> 5
        is Row.Custom -> 6
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
            else -> 0
        }
        if (layout == 0) {
            val frame = android.widget.FrameLayout(parent.context)
            frame.layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            return object : RecyclerView.ViewHolder(frame) {}
        }
        return object : RecyclerView.ViewHolder(inf.inflate(layout, parent, false)) {}
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val v = holder.itemView
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
                val icon = ContextCompat.getDrawable(v.context, R.drawable.ic_history)?.mutate()
                val size = (20 * v.resources.displayMetrics.density).toInt()
                icon?.setBounds(0, 0, size, size)
                icon?.setTint(ContextCompat.getColor(v.context, R.color.text_muted))
                tv.setCompoundDrawablesRelative(icon, null, null, null)
                tv.setOnClickListener { onHistory(row.query) }
                tv.setOnLongClickListener {
                    onHistoryLong(row.query)
                    true
                }
            }
            is Row.Video -> bindVideo(v, row.item)
            is Row.Message -> {
                v.findViewById<TextView>(R.id.text).text = row.text
                val a = v.findViewById<TextView>(R.id.action)
                a.text = row.action
                a.visibility = if (row.action == null) View.GONE else View.VISIBLE
                a.setOnClickListener { row.onAction?.invoke() }
            }
            Row.Loading -> Unit
            is Row.Custom -> {
                val frame = v as android.widget.FrameLayout
                if (row.view.parent !== frame) {
                    (row.view.parent as? ViewGroup)?.removeView(row.view)
                    frame.removeAllViews()
                    frame.addView(row.view)
                }
            }
        }
    }

    private fun bindVideo(v: View, item: StreamInfoItem) {
        val ctx = v.context
        val thumb = v.findViewById<ImageView>(R.id.thumb)
        val width = ctx.resources.displayMetrics.widthPixels
        thumb.load(Format.pick(item.thumbnails, width)) { crossfade(180) }

        val badge = v.findViewById<TextView>(R.id.badge)
        val live = item.streamType == StreamType.LIVE_STREAM || item.streamType == StreamType.AUDIO_LIVE_STREAM
        when {
            live -> {
                badge.text = ctx.getString(R.string.live)
                badge.setBackgroundResource(R.drawable.badge_live_bg)
                badge.visibility = View.VISIBLE
            }
            item.duration > 0 -> {
                badge.text = Format.duration(item.duration)
                badge.setBackgroundResource(R.drawable.badge_bg)
                badge.visibility = View.VISIBLE
            }
            item.isShortFormContent -> {
                badge.text = ctx.getString(R.string.short_label)
                badge.setBackgroundResource(R.drawable.badge_bg)
                badge.visibility = View.VISIBLE
            }
            else -> badge.visibility = View.GONE
        }

        v.findViewById<TextView>(R.id.title).text = item.name
        val views = if (item.viewCount >= 0) {
            ctx.getString(if (live) R.string.watching else R.string.views, Format.count(ctx, item.viewCount))
        } else null
        v.findViewById<TextView>(R.id.meta).text =
            Format.joinDot(item.uploaderName, views, item.textualUploadDate)

        val avatar = v.findViewById<ImageView>(R.id.avatar)
        val avatarUrl = Format.pick(item.uploaderAvatars, 96)
        if (avatarUrl != null) {
            avatar.visibility = View.VISIBLE
            avatar.load(avatarUrl) { transformations(CircleCropTransformation()) }
        } else {
            avatar.visibility = View.GONE
        }
        v.setOnClickListener { onVideo(item) }
    }
}
