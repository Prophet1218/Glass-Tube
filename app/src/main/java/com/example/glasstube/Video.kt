package com.example.glasstube

import android.content.Context
import org.json.JSONObject
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType

/** A video as the lists show it, from NewPipe Extractor or from YouTube's own (signed-in) pages. */
data class Video(
    val id: String,
    val title: String,
    val channel: String? = null,
    val avatar: String? = null,
    val thumb: String? = null,
    val durationSec: Long = 0,
    val viewsText: String? = null,
    val dateText: String? = null,
    val isShort: Boolean = false,
    val isLive: Boolean = false,
    val channelUrl: String? = null
) {
    val url: String get() = "https://www.youtube.com/watch?v=$id"

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("channel", channel)
        put("avatar", avatar)
        put("thumb", thumb)
        put("dur", durationSec)
        put("views", viewsText)
        put("date", dateText)
        put("short", isShort)
        put("live", isLive)
        put("curl", channelUrl)
    }

    companion object {
        private val ID = Regex("(?:v=|/shorts/|youtu\\.be/|/live/|/embed/)([A-Za-z0-9_-]{11})")

        fun idOf(url: String): String? = ID.find(url)?.groupValues?.get(1)

        fun fromJson(o: JSONObject): Video = Video(
            id = o.getString("id"),
            title = o.optString("title"),
            channel = o.optStringOrNull("channel"),
            avatar = o.optStringOrNull("avatar"),
            thumb = o.optStringOrNull("thumb"),
            durationSec = o.optLong("dur"),
            viewsText = o.optStringOrNull("views"),
            dateText = o.optStringOrNull("date"),
            isShort = o.optBoolean("short"),
            isLive = o.optBoolean("live"),
            channelUrl = o.optStringOrNull("curl")
        )

        fun from(ctx: Context, item: StreamInfoItem): Video? {
            val id = idOf(item.url) ?: return null
            val live = item.streamType == StreamType.LIVE_STREAM || item.streamType == StreamType.AUDIO_LIVE_STREAM
            val views = if (item.viewCount >= 0) {
                ctx.getString(if (live) R.string.watching else R.string.views, Format.count(ctx, item.viewCount))
            } else null
            return Video(
                id = id,
                title = item.name.orEmpty(),
                channel = item.uploaderName,
                avatar = Format.pick(item.uploaderAvatars, 96),
                thumb = Format.pick(item.thumbnails, 720),
                durationSec = item.duration,
                viewsText = views,
                dateText = Format.date(item.uploadDate, item.textualUploadDate),
                isShort = item.isShortFormContent || item.url.contains("/shorts/"),
                isLive = live,
                channelUrl = item.uploaderUrl
            )
        }

        fun from(ctx: Context, info: StreamInfo): Video? {
            val id = idOf(info.url) ?: info.id ?: return null
            return Video(
                id = id,
                title = info.name.orEmpty(),
                channel = info.uploaderName,
                avatar = Format.pick(info.uploaderAvatars, 96),
                thumb = Format.pick(info.thumbnails, 720),
                durationSec = info.duration,
                viewsText = if (info.viewCount >= 0) ctx.getString(R.string.views, Format.count(ctx, info.viewCount)) else null,
                dateText = Format.date(info.uploadDate, info.textualUploadDate),
                channelUrl = info.uploaderUrl
            )
        }
    }
}

fun JSONObject.optStringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotEmpty() } else null
