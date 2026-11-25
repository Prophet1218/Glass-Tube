package com.example.glasstube

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.VideoStream
import java.io.File
import java.io.FileOutputStream

/** A downloaded (or downloading) video. [video] and [audio] are file paths ([audio] may be null for muxed files). */
data class Download(
    val id: String,
    val title: String,
    val channel: String?,
    val thumb: String?,
    val durationSec: Long,
    val height: Int,
    val video: String?,
    val audio: String?,
    val state: String,
    val progress: Int,
    val bytes: Long
) {
    val done: Boolean get() = state == "done"

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("title", title); put("channel", channel); put("thumb", thumb)
        put("dur", durationSec); put("h", height); put("video", video); put("audio", audio)
        put("state", state); put("progress", progress); put("bytes", bytes)
    }

    fun asVideo(): Video = Video(id = id, title = title, channel = channel, thumb = thumb, durationSec = durationSec)

    companion object {
        fun fromJson(o: JSONObject) = Download(
            o.getString("id"), o.optString("title"), o.optStringOrNull("channel"), o.optStringOrNull("thumb"),
            o.optLong("dur"), o.optInt("h"), o.optStringOrNull("video"), o.optStringOrNull("audio"),
            o.optString("state"), o.optInt("progress"), o.optLong("bytes")
        )
    }
}

object Downloads {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("downloads", Context.MODE_PRIVATE)

    fun dir(ctx: Context): File = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "downloads").apply { mkdirs() }

    @Synchronized
    fun all(ctx: Context): List<Download> {
        val arr = try {
            JSONArray(prefs(ctx).getString("list", "[]"))
        } catch (e: Exception) {
            return emptyList()
        }
        val out = ArrayList<Download>()
        for (i in 0 until arr.length()) {
            try {
                out.add(Download.fromJson(arr.getJSONObject(i)))
            } catch (e: Exception) {
                // skip
            }
        }
        return out
    }

    fun get(ctx: Context, id: String): Download? = all(ctx).firstOrNull { it.id == id }

    @Synchronized
    fun put(ctx: Context, d: Download) {
        val list = all(ctx).filterNot { it.id == d.id }.toMutableList()
        list.add(0, d)
        prefs(ctx).edit().putString("list", JSONArray(list.map { it.toJson() }).toString()).apply()
    }

    @Synchronized
    fun delete(ctx: Context, id: String) {
        val d = get(ctx, id)
        listOfNotNull(d?.video, d?.audio, d?.thumb?.takeIf { it.startsWith("/") }).forEach { File(it).delete() }
        val list = all(ctx).filterNot { it.id == id }
        prefs(ctx).edit().putString("list", JSONArray(list.map { it.toJson() }).toString()).apply()
    }

    fun start(ctx: Context, videoUrl: String, quality: Int) {
        val i = Intent(ctx, DownloadService::class.java).putExtra("url", videoUrl).putExtra("quality", quality)
        androidx.core.content.ContextCompat.startForegroundService(ctx, i)
    }
}

/** Downloads videos in the background (with a progress notification). */
@OptIn(UnstableApi::class)
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var running = 0
    private lateinit var nm: NotificationManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.downloads), NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun notification(title: String, progress: Int, done: Boolean = false): Notification {
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(getString(if (done) R.string.download_done else R.string.downloading))
            .setOnlyAlertOnce(true)
            .setOngoing(!done)
            .setContentIntent(open)
            .apply { if (!done) setProgress(100, progress, progress <= 0) }
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val url = intent?.getStringExtra("url")
        val quality = intent?.getIntExtra("quality", 720) ?: 720
        ServiceCompat.startForeground(
            this, NOTIF_ID, notification(getString(R.string.downloading), 0),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        )
        if (url == null) {
            if (running == 0) stopSelf()
            return START_NOT_STICKY
        }
        running++
        scope.launch {
            mutex.withLock {
                try {
                    download(url, quality)
                } catch (e: Exception) {
                    // the entry is already marked failed
                }
            }
            synchronized(this@DownloadService) {
                running--
                if (running <= 0) {
                    ServiceCompat.stopForeground(this@DownloadService, ServiceCompat.STOP_FOREGROUND_DETACH)
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun download(url: String, quality: Int) {
        val info = StreamInfo.getInfo(ServiceList.YouTube, url)
        val id = Video.idOf(info.url) ?: info.id
        val dir = Downloads.dir(this)
        var d = Download(id, info.name, info.uploaderName, Format.pick(info.thumbnails, 720), info.duration,
            0, null, null, "downloading", 0, 0)
        Downloads.put(this, d)
        try {
            // thumbnail for offline use
            d.thumb?.let { t ->
                val f = File(dir, "$id.jpg")
                NetDownloader.instance.client.newCall(Request.Builder().url(t).build()).execute().use { r ->
                    r.body?.byteStream()?.use { input -> FileOutputStream(f).use { input.copyTo(it) } }
                }
                d = d.copy(thumb = f.absolutePath)
            }

            val video = pickVideo(info.videoOnlyStreams, quality)
            val audio = pickAudio(info.audioStreams)
            if (video != null && audio != null) {
                val vExt = if (video.format == MediaFormat.WEBM) "webm" else "mp4"
                val aExt = if (audio.format == MediaFormat.WEBMA || audio.format == MediaFormat.WEBMA_OPUS) "webm" else "m4a"
                val vf = File(dir, "$id.v.$vExt")
                val af = File(dir, "$id.a.$aExt")
                d = d.copy(height = minOf(video.width, video.height).takeIf { it > 0 } ?: video.height,
                    video = vf.absolutePath, audio = af.absolutePath)
                Downloads.put(this, d)
                val vLen = video.itagItem?.contentLength ?: -1L
                val aLen = audio.itagItem?.contentLength ?: -1L
                val total = if (vLen > 0 && aLen > 0) vLen + aLen else -1L
                var doneBytes = 0L
                fetch(video.content, vf) { n ->
                    doneBytes += n
                    report(d, doneBytes, total)
                }
                fetch(audio.content, af) { n ->
                    doneBytes += n
                    report(d, doneBytes, total)
                }
                d = d.copy(bytes = vf.length() + af.length())
            } else {
                // only muxed streams
                val muxed = info.videoStreams
                    .filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
                    .maxByOrNull { it.height } ?: throw IllegalStateException("No downloadable stream")
                val f = File(dir, "$id.mp4")
                d = d.copy(height = muxed.height, video = f.absolutePath, audio = null)
                var doneBytes = 0L
                fetch(muxed.content, f) { n ->
                    doneBytes += n
                    report(d, doneBytes, -1)
                }
                d = d.copy(bytes = f.length())
            }
            d = d.copy(state = "done", progress = 100)
            Downloads.put(this, d)
            nm.notify(id.hashCode(), notification(d.title, 100, done = true))
        } catch (e: Exception) {
            Downloads.put(this, d.copy(state = "failed"))
            throw e
        }
    }

    private var lastReport = 0L

    private fun report(d: Download, bytes: Long, total: Long) {
        val now = System.currentTimeMillis()
        if (now - lastReport < 700) return
        lastReport = now
        val p = if (total > 0) ((bytes * 100) / total).toInt().coerceIn(0, 99) else 0
        Downloads.put(this, d.copy(progress = p, bytes = bytes))
        nm.notify(NOTIF_ID, notification(d.title, p))
    }

    /** Downloads [url] to [file] in 1 MB pieces, the way YouTube serves them without throttling. */
    private fun fetch(url: String, file: File, onBytes: (Long) -> Unit) {
        val buf = ByteArray(64 * 1024)
        FileOutputStream(file).use { out ->
            var pos = 0L
            while (true) {
                val ds = YtDataSource()
                var got = 0L
                try {
                    ds.open(DataSpec.Builder().setUri(Uri.parse(url)).setPosition(pos).setLength(CHUNK).build())
                    while (true) {
                        val n = ds.read(buf, 0, buf.size)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        got += n
                        onBytes(n.toLong())
                    }
                } catch (e: HttpDataSource.InvalidResponseCodeException) {
                    if (e.responseCode == 416 && pos > 0) break else throw e
                } finally {
                    ds.close()
                }
                pos += got
                if (got < CHUNK) break
            }
        }
    }

    private fun pickVideo(streams: List<VideoStream>, quality: Int): VideoStream? {
        val usable = streams.filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.content.isNotEmpty() }
        fun side(s: VideoStream) = if (s.width > 0) minOf(s.width, s.height) else s.height
        val fit = usable.filter { side(it) <= quality }.ifEmpty { usable }
        val best = fit.maxOfOrNull { side(it) } ?: return null
        // same resolution: H.264 (mp4) plays everywhere, then VP9
        return fit.filter { side(it) == best }.sortedWith(
            compareBy<VideoStream> { if (it.format == MediaFormat.MPEG_4) 0 else 1 }.thenByDescending { it.fps }
        ).firstOrNull()
    }

    private fun pickAudio(streams: List<AudioStream>): AudioStream? {
        val usable = streams.filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.content.isNotEmpty() }
        val original = usable.filter { it.audioTrackType == null || it.audioTrackType == AudioTrackType.ORIGINAL }.ifEmpty { usable }
        return original.sortedWith(
            compareBy<AudioStream> { if (it.format == MediaFormat.M4A) 0 else 1 }.thenByDescending { it.averageBitrate }
        ).firstOrNull()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "downloads"
        private const val NOTIF_ID = 4711
        private const val CHUNK = 1L * 1024 * 1024
    }
}
