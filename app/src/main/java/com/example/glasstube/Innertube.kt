package com.example.glasstube

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Locale

/** A page of videos (and playlists) from YouTube's web API, with the token for the next page. */
data class FeedPage(
    val videos: List<Video>,
    val shorts: List<Video>,
    val playlists: List<PlaylistRef>,
    val continuation: String?
)

data class PlaylistRef(val id: String, val title: String, val thumb: String?, val subtitle: String?)

/**
 * YouTube's own web API ("InnerTube"), used for the signed-in pages NewPipe Extractor doesn't
 * cover: the personal home feed, subscriptions, history, liked videos, watch later, playlists.
 */
object Innertube {
    private const val BASE = "https://www.youtube.com/youtubei/v1/"
    private const val CLIENT_VERSION = "2.20260805.01.00"
    private val JSON = "application/json".toMediaType()

    const val HOME = "FEwhat_to_watch"
    const val SUBSCRIPTIONS = "FEsubscriptions"
    const val HISTORY = "FEhistory"
    const val LIKED = "VLLL"
    const val WATCH_LATER = "VLWL"
    const val PLAYLISTS = "FEplaylist_aggregation"

    private fun context(): JSONObject {
        val loc = Locale.getDefault()
        return JSONObject().put(
            "client", JSONObject()
                .put("clientName", "WEB")
                .put("clientVersion", CLIENT_VERSION)
                .put("hl", loc.language.ifEmpty { "en" })
                .put("gl", loc.country.ifEmpty { "US" })
                .put("platform", "DESKTOP")
        )
    }

    private fun post(ctx: Context, endpoint: String, body: JSONObject): JSONObject {
        body.put("context", context())
        val builder = Request.Builder()
            .url("$BASE$endpoint?prettyPrint=false")
            .post(body.toString().toRequestBody(JSON))
            .header("User-Agent", NetDownloader.USER_AGENT)
            .header("X-YouTube-Client-Name", "1")
            .header("X-YouTube-Client-Version", CLIENT_VERSION)
        Account.authHeaders(ctx).forEach { (k, v) -> builder.header(k, v) }
        NetDownloader.instance.client.newCall(builder.build()).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            return JSONObject(text)
        }
    }

    /** A browse page ([browseId]) or its next page ([continuation]). */
    fun browse(ctx: Context, browseId: String?, continuation: String? = null): FeedPage {
        val body = JSONObject()
        if (continuation != null) body.put("continuation", continuation) else body.put("browseId", browseId)
        return parse(post(ctx, "browse", body))
    }

    /** The signed-in user's rating of [videoId]: "LIKE", "DISLIKE" or "INDIFFERENT" (null if unknown). */
    fun likeStatus(ctx: Context, videoId: String): String? {
        val r = post(ctx, "next", JSONObject().put("videoId", videoId))
        return (find(r, "likeStatus") as? String)
    }

    /** Rates [videoId]: [status] = "LIKE", "DISLIKE" or "INDIFFERENT" (removes the rating). */
    fun rate(ctx: Context, videoId: String, status: String) {
        val endpoint = when (status) {
            "LIKE" -> "like/like"
            "DISLIKE" -> "like/dislike"
            else -> "like/removelike"
        }
        post(ctx, endpoint, JSONObject().put("target", JSONObject().put("videoId", videoId)))
    }

    /** All channels the signed-in user is subscribed to. */
    fun subscriptions(ctx: Context): List<ChannelRef> {
        val out = LinkedHashMap<String, ChannelRef>()
        fun collect(node: Any?, tokens: MutableList<String>) {
            when (node) {
                is JSONObject -> {
                    val keys = node.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        val v = node.opt(k)
                        when (k) {
                            "channelRenderer", "gridChannelRenderer" -> (v as? JSONObject)?.let { o ->
                                val id = o.optString("channelId")
                                if (id.startsWith("UC")) {
                                    val name = text(o.optJSONObject("title")) ?: return@let
                                    out[id] = ChannelRef("https://www.youtube.com/channel/$id", name, bestThumb(o.optJSONObject("thumbnail")))
                                }
                            }
                            "guideEntryRenderer" -> (v as? JSONObject)?.let { o ->
                                val id = (find(o, "guideEntryId") as? String) ?: (find(o, "browseId") as? String) ?: ""
                                if (id.startsWith("UC")) {
                                    val name = o.optString("formattedTitle").ifEmpty { text(o.optJSONObject("formattedTitle")).orEmpty() }
                                    if (name.isNotEmpty()) {
                                        out.getOrPut(id) { ChannelRef("https://www.youtube.com/channel/$id", name, bestThumb(o.optJSONObject("thumbnail"))) }
                                    }
                                }
                            }
                            "continuationItemRenderer" -> (v as? JSONObject)?.let { o ->
                                (find(o, "continuationCommand") as? JSONObject)?.optString("token")
                                    ?.takeIf { it.isNotEmpty() }?.let { tokens.add(it) }
                            }
                            else -> collect(v, tokens)
                        }
                    }
                }
                is JSONArray -> for (i in 0 until node.length()) collect(node.opt(i), tokens)
            }
        }
        // the "Manage subscriptions" page, page by page
        var tokens = ArrayList<String>()
        collect(post(ctx, "browse", JSONObject().put("browseId", "FEchannels")), tokens)
        var pages = 0
        while (tokens.isNotEmpty() && pages < 30) {
            val t = tokens.last()
            tokens = ArrayList()
            collect(post(ctx, "browse", JSONObject().put("continuation", t)), tokens)
            pages++
        }
        // the side menu also lists them (fallback / extra)
        if (out.isEmpty()) {
            try {
                collect(post(ctx, "guide", JSONObject()), ArrayList())
            } catch (e: Exception) {
                // nothing more to try
            }
        }
        return out.values.toList()
    }

    /** Subscribes to / unsubscribes from a channel ([channelId] = "UC..."). */
    fun subscribe(ctx: Context, channelId: String, on: Boolean) {
        post(ctx, if (on) "subscription/subscribe" else "subscription/unsubscribe",
            JSONObject().put("channelIds", JSONArray().put(channelId)))
    }

    /** Thrown when YouTube no longer accepts the saved sign-in. */
    class SessionExpired : IOException("Google sign-in expired")

    /** Name, handle and photo of the signed-in account. */
    fun accountInfo(ctx: Context): Triple<String?, String?, String?> {
        val r = post(ctx, "account/account_menu", JSONObject())
        val header = find(r, "activeAccountHeaderRenderer") as? JSONObject
            ?: throw SessionExpired()
        val name = text(header.optJSONObject("accountName"))
        val handle = text(header.optJSONObject("channelHandle"))
        val photo = bestThumb(header.optJSONObject("accountPhoto"))
        return Triple(name, handle, photo)
    }

    // ------------------------------------------------------------------ parsing

    fun parse(root: JSONObject): FeedPage {
        val videos = ArrayList<Video>()
        val shorts = ArrayList<Video>()
        val playlists = ArrayList<PlaylistRef>()
        val tokens = ArrayList<String>()
        walk(root, videos, shorts, playlists, tokens)
        val seen = HashSet<String>()
        return FeedPage(
            videos.filter { seen.add(it.id) },
            shorts.filter { seen.add(it.id) },
            playlists.distinctBy { it.id },
            tokens.lastOrNull()
        )
    }

    private fun walk(
        node: Any?, videos: MutableList<Video>, shorts: MutableList<Video>,
        playlists: MutableList<PlaylistRef>, tokens: MutableList<String>
    ) {
        when (node) {
            is JSONObject -> {
                val keys = node.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = node.opt(k)
                    when (k) {
                        "videoRenderer", "gridVideoRenderer", "compactVideoRenderer",
                        "playlistVideoRenderer", "videoWithContextRenderer" ->
                            (v as? JSONObject)?.let { o -> videoRenderer(o)?.let { if (it.isShort) shorts.add(it) else videos.add(it) } }
                        "reelItemRenderer" -> (v as? JSONObject)?.let { o -> reelItem(o)?.let { shorts.add(it) } }
                        "shortsLockupViewModel" -> (v as? JSONObject)?.let { o -> shortsLockup(o)?.let { shorts.add(it) } }
                        "lockupViewModel" -> (v as? JSONObject)?.let { o -> lockup(o, videos, playlists) }
                        "gridPlaylistRenderer", "playlistRenderer" -> (v as? JSONObject)?.let { o -> playlistRenderer(o)?.let { playlists.add(it) } }
                        "continuationItemRenderer" -> (v as? JSONObject)?.let { o ->
                            (find(o, "continuationCommand") as? JSONObject)?.optString("token")
                                ?.takeIf { it.isNotEmpty() }?.let { tokens.add(it) }
                        }
                        else -> walk(v, videos, shorts, playlists, tokens)
                    }
                }
            }
            is JSONArray -> for (i in 0 until node.length()) walk(node.opt(i), videos, shorts, playlists, tokens)
        }
    }

    private fun videoRenderer(o: JSONObject): Video? {
        val id = o.optString("videoId").takeIf { it.isNotEmpty() } ?: return null
        val title = text(o.optJSONObject("title")) ?: text(o.optJSONObject("headline")) ?: return null
        val byline = o.optJSONObject("ownerText") ?: o.optJSONObject("shortBylineText") ?: o.optJSONObject("longBylineText")
        val lengthText = text(o.optJSONObject("lengthText"))
        var duration = Format.parseDuration(lengthText)
        if (duration == 0L) duration = o.optString("lengthSeconds").toLongOrNull() ?: 0L
        val overlayStyle = (find(o.optJSONArray("thumbnailOverlays"), "thumbnailOverlayTimeStatusRenderer") as? JSONObject)?.optString("style")
        val live = overlayStyle == "LIVE" || o.toString().contains("BADGE_STYLE_TYPE_LIVE_NOW")
        val isShort = overlayStyle == "SHORTS" ||
            (o.optJSONObject("navigationEndpoint")?.has("reelWatchEndpoint") == true)
        var views = text(o.optJSONObject("shortViewCountText")) ?: text(o.optJSONObject("viewCountText"))
        var date = text(o.optJSONObject("publishedTimeText"))
        if (views == null && date == null) {
            // playlist rows: "1.2M views • 3 years ago"
            text(o.optJSONObject("videoInfo"))?.split("•")?.map { it.trim() }?.let { parts ->
                views = parts.getOrNull(0)
                date = parts.getOrNull(1)
            }
        }
        val avatar = (find(o.optJSONObject("channelThumbnailSupportedRenderers"), "thumbnail") as? JSONObject)
            ?.let { bestThumb(it) }
            ?: bestThumb(o.optJSONObject("channelThumbnail"))
        return Video(
            id = id,
            title = title,
            channel = text(byline),
            channelUrl = channelUrlOf(byline),
            avatar = avatar,
            thumb = bestThumb(o.optJSONObject("thumbnail")) ?: "https://i.ytimg.com/vi/$id/hqdefault.jpg",
            durationSec = duration,
            viewsText = views,
            dateText = date,
            isShort = isShort,
            isLive = live
        )
    }

    private fun reelItem(o: JSONObject): Video? {
        val id = o.optString("videoId").takeIf { it.isNotEmpty() } ?: return null
        return Video(
            id = id,
            title = text(o.optJSONObject("headline")).orEmpty(),
            thumb = bestThumb(o.optJSONObject("thumbnail")) ?: "https://i.ytimg.com/vi/$id/hq2.jpg",
            viewsText = text(o.optJSONObject("viewCountText")),
            isShort = true
        )
    }

    private fun shortsLockup(o: JSONObject): Video? {
        val id = ((find(o.optJSONObject("onTap"), "reelWatchEndpoint") as? JSONObject)?.optString("videoId"))
            ?.takeIf { it.isNotEmpty() }
            ?: o.optString("entityId").removePrefix("shorts-shelf-item-").takeIf { it.length == 11 }
            ?: return null
        val meta = o.optJSONObject("overlayMetadata")
        return Video(
            id = id,
            title = text(meta?.optJSONObject("primaryText")).orEmpty(),
            thumb = sources(o.optJSONObject("thumbnail")) ?: "https://i.ytimg.com/vi/$id/hq2.jpg",
            viewsText = text(meta?.optJSONObject("secondaryText")),
            isShort = true
        )
    }

    private fun lockup(o: JSONObject, videos: MutableList<Video>, playlists: MutableList<PlaylistRef>) {
        val id = o.optString("contentId").takeIf { it.isNotEmpty() } ?: return
        val type = o.optString("contentType")
        val meta = o.optJSONObject("metadata")?.optJSONObject("lockupMetadataViewModel")
        val title = text(meta?.optJSONObject("title")) ?: return
        val rows = ArrayList<List<String>>()
        val mrows = (find(meta?.optJSONObject("metadata"), "metadataRows") as? JSONArray)
        if (mrows != null) {
            for (i in 0 until mrows.length()) {
                val parts = mrows.optJSONObject(i)?.optJSONArray("metadataParts") ?: continue
                val row = ArrayList<String>()
                for (j in 0 until parts.length()) text(parts.optJSONObject(j)?.optJSONObject("text"))?.let { row.add(it) }
                if (row.isNotEmpty()) rows.add(row)
            }
        }
        val image = o.optJSONObject("contentImage")
        val thumb = (find(image, "image") as? JSONObject)?.let { sources(it) }
        when (type) {
            "LOCKUP_CONTENT_TYPE_VIDEO" -> {
                val badge = (find(image, "thumbnailBadgeViewModel") as? JSONObject)
                val badgeText = badge?.optString("text")
                val live = badge?.optString("badgeStyle")?.contains("LIVE") == true
                val avatar = (find(meta?.optJSONObject("image"), "avatarViewModel") as? JSONObject)
                    ?.optJSONObject("image")?.let { sources(it) }
                videos.add(
                    Video(
                        id = id,
                        title = title,
                        channelUrl = channelUrlOf(meta),
                        channel = rows.getOrNull(0)?.firstOrNull(),
                        avatar = avatar,
                        thumb = thumb ?: "https://i.ytimg.com/vi/$id/hqdefault.jpg",
                        durationSec = Format.parseDuration(badgeText),
                        viewsText = rows.getOrNull(1)?.getOrNull(0),
                        dateText = rows.getOrNull(1)?.getOrNull(1),
                        isLive = live
                    )
                )
            }
            "LOCKUP_CONTENT_TYPE_PLAYLIST", "LOCKUP_CONTENT_TYPE_PODCAST" ->
                playlists.add(PlaylistRef(id, title, thumb, rows.flatten().joinToString(" · ").ifEmpty { null }))
        }
    }

    private fun playlistRenderer(o: JSONObject): PlaylistRef? {
        val id = o.optString("playlistId").takeIf { it.isNotEmpty() } ?: return null
        val title = text(o.optJSONObject("title")) ?: return null
        val thumb = bestThumb(o.optJSONObject("thumbnail"))
            ?: (find(o.optJSONArray("thumbnails"), "thumbnails") as? JSONArray)?.let { bestOf(it) }
        val count = text(o.optJSONObject("videoCountShortText")) ?: text(o.optJSONObject("videoCountText"))
        return PlaylistRef(id, title, thumb, count)
    }

    // ------------------------------------------------------------------ helpers

    private fun channelUrlOf(node: Any?): String? {
        val be = find(node, "browseEndpoint") as? JSONObject ?: return null
        val base = be.optString("canonicalBaseUrl")
        if (base.startsWith("/")) return "https://www.youtube.com$base"
        val id = be.optString("browseId")
        return if (id.startsWith("UC")) "https://www.youtube.com/channel/$id" else null
    }

    /** Text of {simpleText}, {runs:[{text}]} or {content}. */
    fun text(o: JSONObject?): String? {
        o ?: return null
        o.optString("simpleText").takeIf { it.isNotEmpty() }?.let { return it }
        o.optString("content").takeIf { it.isNotEmpty() }?.let { return it }
        val runs = o.optJSONArray("runs") ?: return null
        val sb = StringBuilder()
        for (i in 0 until runs.length()) sb.append(runs.optJSONObject(i)?.optString("text").orEmpty())
        return sb.toString().takeIf { it.isNotEmpty() }
    }

    private fun bestThumb(o: JSONObject?): String? {
        val arr = o?.optJSONArray("thumbnails") ?: return null
        return bestOf(arr)
    }

    private fun sources(o: JSONObject?): String? {
        val arr = o?.optJSONArray("sources") ?: return null
        return bestOf(arr)
    }

    private fun bestOf(arr: JSONArray): String? {
        var best: JSONObject? = null
        for (i in 0 until arr.length()) {
            val t = arr.optJSONObject(i) ?: continue
            if (best == null || t.optInt("width") > best.optInt("width")) best = t
        }
        val url = best?.optString("url")?.takeIf { it.isNotEmpty() } ?: return null
        return if (url.startsWith("//")) "https:$url" else url
    }

    /** First value under [key], anywhere inside [node]. */
    fun find(node: Any?, key: String): Any? {
        when (node) {
            is JSONObject -> {
                if (node.has(key)) return node.opt(key)
                val keys = node.keys()
                while (keys.hasNext()) {
                    val r = find(node.opt(keys.next()), key)
                    if (r != null) return r
                }
            }
            is JSONArray -> for (i in 0 until node.length()) {
                val r = find(node.opt(i), key)
                if (r != null) return r
            }
        }
        return null
    }
}
