package com.example.glasstube

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.dash.DefaultDashChunkSource
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubeOtfDashManifestCreator
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubeProgressiveDashManifestCreator
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.Stream
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.VideoStream
import java.io.ByteArrayInputStream
import java.io.IOException

/**
 * Turns a [StreamInfo] into something ExoPlayer can play.
 *
 * YouTube serves video and audio separately. Every resolution (one codec each) and the best
 * audio track become small generated DASH manifests, merged into one source: ExoPlayer then picks
 * the quality itself, and the player's settings menu can switch it.
 */
@OptIn(UnstableApi::class)
object StreamResolver {

    fun build(info: StreamInfo, metadata: MediaMetadata): MediaSource {
        val yt = YtDataSource.Factory()
        fun item(uri: String) = MediaItem.Builder()
            .setUri(uri)
            .setMediaId(info.url)
            .setMediaMetadata(metadata)
            .build()

        val type = info.streamType
        if (type == StreamType.LIVE_STREAM || type == StreamType.AUDIO_LIVE_STREAM) {
            val http = DefaultHttpDataSource.Factory().setUserAgent(NetDownloader.USER_AGENT)
            if (info.hlsUrl.isNotEmpty()) {
                return HlsMediaSource.Factory(http).createMediaSource(item(info.hlsUrl))
            }
            if (info.dashMpdUrl.isNotEmpty()) {
                return DashMediaSource.Factory(DefaultDashChunkSource.Factory(http), http)
                    .createMediaSource(item(info.dashMpdUrl))
            }
        }

        val duration = info.duration
        fun dash(stream: Stream): MediaSource? {
            val itag = when (stream) {
                is VideoStream -> stream.itagItem
                is AudioStream -> stream.itagItem
                else -> null
            } ?: return null
            val url = stream.content
            return try {
                val manifestText = when (stream.deliveryMethod) {
                    DeliveryMethod.PROGRESSIVE_HTTP ->
                        YoutubeProgressiveDashManifestCreator.fromProgressiveStreamingUrl(url, itag, duration)
                    DeliveryMethod.DASH ->
                        YoutubeOtfDashManifestCreator.fromOtfStreamingUrl(url, itag, duration)
                    else -> return null
                }
                val manifest = DashManifestParser().parse(
                    Uri.parse(url), ByteArrayInputStream(manifestText.toByteArray(Charsets.UTF_8))
                )
                DashMediaSource.Factory(DefaultDashChunkSource.Factory(yt), yt)
                    .createMediaSource(manifest, item(url))
            } catch (e: Exception) {
                null
            }
        }

        val audio = pickAudio(info.audioStreams)
        val videos = pickVideos(info.videoOnlyStreams).mapNotNull { dash(it) }
        val audioSource = audio?.let { dash(it) }
        if (videos.isNotEmpty() && audioSource != null) {
            return MergingMediaSource(true, true, *(videos + audioSource).toTypedArray())
        }

        // older "muxed" streams (video + audio in one file)
        val muxed = info.videoStreams
            .filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.content.isNotEmpty() }
            .maxByOrNull { it.height }
        if (muxed != null) {
            return ProgressiveMediaSource.Factory(yt).createMediaSource(item(muxed.content))
        }

        if (info.hlsUrl.isNotEmpty()) {
            val http = DefaultHttpDataSource.Factory().setUserAgent(NetDownloader.USER_AGENT)
            return HlsMediaSource.Factory(http).createMediaSource(item(info.hlsUrl))
        }

        if (audioSource != null) return audioSource
        if (videos.isNotEmpty()) return MergingMediaSource(true, true, *videos.toTypedArray())
        throw IOException("No playable stream found")
    }

    /** One stream per resolution: VP9 first (hardware decoded almost everywhere), then H.264, then AV1. */
    private fun pickVideos(streams: List<VideoStream>): List<VideoStream> {
        fun codecRank(s: VideoStream): Int {
            val c = s.codec.orEmpty().lowercase()
            return when {
                c.startsWith("vp9") || c.startsWith("vp09") -> 0
                c.startsWith("avc") -> 1
                c.startsWith("av01") -> 2
                else -> 3
            }
        }
        return streams
            .filter { it.isUrl && it.itagItem != null && it.height > 0 && it.content.isNotEmpty() }
            .groupBy { it.height }
            .mapNotNull { (_, list) ->
                list.sortedWith(
                    compareBy<VideoStream> { codecRank(it) }
                        .thenByDescending { it.fps }
                        .thenByDescending { it.bitrate }
                ).firstOrNull()
            }
            .sortedByDescending { it.height }
    }

    /** The original-language track with the highest bitrate. */
    private fun pickAudio(streams: List<AudioStream>): AudioStream? {
        val usable = streams.filter { it.isUrl && it.itagItem != null && it.content.isNotEmpty() }
        val original = usable.filter { it.audioTrackType == null || it.audioTrackType == AudioTrackType.ORIGINAL }
        val pool = original.ifEmpty { usable }
        return pool.maxByOrNull { maxOf(it.averageBitrate, it.bitrate) }
    }
}
