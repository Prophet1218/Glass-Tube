package com.example.glasstube

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import com.grack.nanojson.JsonWriter
import org.json.JSONObject
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.services.youtube.YoutubeJavaScriptPlayerManager
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import org.schabi.newpipe.extractor.services.youtube.YoutubeStreamHelper

/** One HDR video stream (VP9 profile 2 or AV1 10-bit with HDR10 / HLG). */
data class HdrStream(
    val itag: Int,
    val url: String,
    val mime: String,
    val codecs: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrate: Int,
    val initRange: String,
    val indexRange: String,
    val durationMs: Long
) {
    val isAv1: Boolean get() = codecs.startsWith("av01")

    /** A minimal DASH manifest for this single stream. */
    fun manifest(): String {
        val sec = durationMs / 1000.0
        val url = url.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        return """<?xml version="1.0" encoding="UTF-8"?>
<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" profiles="urn:mpeg:dash:profile:full:2011" type="static" mediaPresentationDuration="PT${sec}S" minBufferTime="PT1.500S">
<Period start="PT0S" duration="PT${sec}S">
<AdaptationSet id="0" contentType="video" mimeType="$mime" subsegmentAlignment="true" startWithSAP="1">
<Representation id="$itag" codecs="$codecs" bandwidth="$bitrate" width="$width" height="$height" frameRate="$fps">
<BaseURL>$url</BaseURL>
<SegmentBase indexRange="$indexRange"><Initialization range="$initRange"/></SegmentBase>
</Representation>
</AdaptationSet>
</Period>
</MPD>"""
    }
}

/**
 * NewPipe Extractor drops YouTube's HDR formats (it doesn't know their itags), so they are asked
 * for separately here, with the same player request the extractor uses.
 */
object HdrStreams {

    private val HDR_ITAGS = (330..337).toSet() + (694..702).toSet()

    /** True if the screen shows HDR and a decoder can play YouTube's HDR formats. */
    fun deviceSupports(ctx: Context): Boolean {
        if (!ctx.resources.configuration.isScreenHdr) return false
        return decoderSupports(false) || decoderSupports(true)
    }

    private fun decoderSupports(av1: Boolean): Boolean {
        val mime = if (av1) "video/av01" else "video/x-vnd.on2.vp9"
        return try {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
                !info.isEncoder && info.supportedTypes.any { it.equals(mime, true) } &&
                    info.getCapabilitiesForType(mime).profileLevels.any { pl ->
                        if (av1) {
                            pl.profile == MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10HDR10 ||
                                pl.profile == MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10
                        } else {
                            pl.profile == MediaCodecInfo.CodecProfileLevel.VP9Profile2HDR ||
                                pl.profile == MediaCodecInfo.CodecProfileLevel.VP9Profile2
                        }
                    }
            }
        } catch (e: Exception) {
            false
        }
    }

    /** The HDR streams of [videoId] the phone can decode (empty if none). Runs network I/O. */
    fun fetch(videoId: String): List<HdrStream> {
        val cpn = YoutubeParsingHelper.generateContentPlaybackNonce()
        val response = YoutubeStreamHelper.getVisionOsPlayerResponse(
            NewPipe.getPreferredContentCountry(), NewPipe.getPreferredLocalization(), videoId, cpn
        )
        val root = JSONObject(JsonWriter.string(response))
        val formats = root.optJSONObject("streamingData")?.optJSONArray("adaptiveFormats") ?: return emptyList()
        val vp9 = decoderSupports(false)
        val av1 = decoderSupports(true)
        val out = ArrayList<HdrStream>()
        for (i in 0 until formats.length()) {
            val f = formats.optJSONObject(i) ?: continue
            val itag = f.optInt("itag")
            val transfer = f.optJSONObject("colorInfo")?.optString("transferCharacteristics").orEmpty()
            val hdr = itag in HDR_ITAGS || transfer.contains("SMPTEST2084") || transfer.contains("ARIB_STD_B67")
            if (!hdr) continue
            val rawUrl = f.optString("url").takeIf { it.isNotEmpty() } ?: continue
            val mimeType = f.optString("mimeType")
            val mime = mimeType.substringBefore(';').trim()
            val codecs = mimeType.substringAfter("codecs=\"", "").substringBefore('"')
            if (!mime.startsWith("video/") || codecs.isEmpty()) continue
            val isAv1 = codecs.startsWith("av01")
            if ((isAv1 && !av1) || (!isAv1 && !vp9)) continue
            val init = f.optJSONObject("initRange") ?: continue
            val index = f.optJSONObject("indexRange") ?: continue
            val url = try {
                YoutubeJavaScriptPlayerManager.getUrlWithThrottlingParameterDeobfuscated(videoId, rawUrl)
            } catch (e: Exception) {
                rawUrl
            } + "&cpn=" + cpn
            out.add(
                HdrStream(
                    itag = itag,
                    url = url,
                    mime = mime,
                    codecs = codecs,
                    width = f.optInt("width"),
                    height = f.optInt("height"),
                    fps = f.optInt("fps", 30),
                    bitrate = f.optInt("bitrate"),
                    initRange = init.optString("start") + "-" + init.optString("end"),
                    indexRange = index.optString("start") + "-" + index.optString("end"),
                    durationMs = f.optString("approxDurationMs").toLongOrNull() ?: 0L
                )
            )
        }
        // one per resolution: VP9 first (widest hardware support), then the higher frame rate
        return out.filter { it.durationMs > 0 && it.height > 0 }
            .groupBy { minOf(it.width, it.height) }
            .mapNotNull { (_, list) -> list.sortedWith(compareBy<HdrStream> { it.isAv1 }.thenByDescending { it.fps }).firstOrNull() }
    }
}
