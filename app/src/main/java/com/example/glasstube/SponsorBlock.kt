package com.example.glasstube

import android.content.Context
import okhttp3.Request
import org.json.JSONArray
import java.net.URLEncoder

/** A part of a video marked by the SponsorBlock community. */
data class Segment(val startMs: Long, val endMs: Long, val category: String)

/** SponsorBlock (sponsor.ajay.app): skips sponsored and other unwanted parts of videos. */
object SponsorBlock {

    /** Categories with their bar colour (as on the SponsorBlock site). */
    val CATEGORIES = linkedMapOf(
        "sponsor" to 0xFF00D400.toInt(),
        "selfpromo" to 0xFFFFFF00.toInt(),
        "interaction" to 0xFFCC00FF.toInt(),
        "intro" to 0xFF00FFFF.toInt(),
        "outro" to 0xFF0202ED.toInt(),
        "preview" to 0xFF008FD6.toInt(),
        "music_offtopic" to 0xFFFF9900.toInt(),
        "filler" to 0xFF7300FF.toInt()
    )

    fun label(ctx: Context, category: String): String = ctx.getString(
        when (category) {
            "sponsor" -> R.string.sb_sponsor
            "selfpromo" -> R.string.sb_selfpromo
            "interaction" -> R.string.sb_interaction
            "intro" -> R.string.sb_intro
            "outro" -> R.string.sb_outro
            "preview" -> R.string.sb_preview
            "music_offtopic" -> R.string.sb_music
            else -> R.string.sb_filler
        }
    )

    /** The segments of [videoId] in the given categories. Runs network I/O; empty on any error. */
    fun fetch(videoId: String, categories: Collection<String>): List<Segment> {
        if (categories.isEmpty()) return emptyList()
        val cats = URLEncoder.encode(JSONArray(categories.toList()).toString(), "UTF-8")
        val url = "https://sponsor.ajay.app/api/skipSegments?videoID=$videoId&categories=$cats"
        return try {
            NetDownloader.instance.client.newCall(
                Request.Builder().url(url).header("User-Agent", "Lucent").build()
            ).execute().use { r ->
                if (!r.isSuccessful) return emptyList()
                val arr = JSONArray(r.body?.string().orEmpty())
                val out = ArrayList<Segment>()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val seg = o.optJSONArray("segment") ?: continue
                    val s = (seg.optDouble(0) * 1000).toLong()
                    val e = (seg.optDouble(1) * 1000).toLong()
                    if (e > s && o.optString("actionType", "skip") == "skip") out.add(Segment(s, e, o.optString("category")))
                }
                out.sortedBy { it.startMs }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
