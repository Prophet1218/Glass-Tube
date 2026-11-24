package com.example.glasstube

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Binder
import android.os.IBinder
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import org.schabi.newpipe.extractor.stream.StreamInfo

/**
 * Owns the player, so playback keeps going (with a media notification) when the app is in the
 * background or the screen is off. The player screen binds to it locally and drives the player
 * directly.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    inner class LocalBinder : Binder() {
        val service: PlaybackService get() = this@PlaybackService
    }

    private val binder = LocalBinder()
    lateinit var player: ExoPlayer
        private set
    private var session: MediaSession? = null

    /** The video currently loaded (its YouTube URL), if any. */
    var currentUrl: String? = null
        private set
    var currentInfo: StreamInfo? = null
        private set

    override fun onCreate() {
        super.onCreate()
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
        // up to 1080p (also for vertical videos); the settings menu can still pick others
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setMaxVideoSize(1920, 1920)
            .build()
        val s = MediaSession.Builder(this, player).build()
        session = s
        addSession(s)
    }

    override fun onBind(intent: Intent?): IBinder? {
        if (intent?.action == ACTION_LOCAL) return binder
        return super.onBind(intent)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** Loads and starts [info]. */
    fun play(info: StreamInfo) {
        val meta = MediaMetadata.Builder()
            .setTitle(info.name)
            .setArtist(info.uploaderName)
            .setDisplayTitle(info.name)
            .apply {
                Format.pick(info.thumbnails, 720)?.let { setArtworkUri(Uri.parse(it)) }
            }
            .build()
        val source = StreamResolver.build(info, meta)
        currentUrl = info.url
        currentInfo = info
        session?.setSessionActivity(openPlayerIntent(info.url))
        player.setMediaSource(source)
        player.prepare()
        player.playWhenReady = true
    }

    /** Stops playback and removes the notification. */
    fun stopPlayback() {
        player.stop()
        player.clearMediaItems()
        currentUrl = null
        currentInfo = null
        stopSelf()
    }

    private fun openPlayerIntent(url: String): PendingIntent {
        val intent = Intent(this, PlayerActivity::class.java)
            .putExtra(PlayerActivity.EXTRA_URL, url)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // app swiped away from recents: stop instead of playing on invisibly
        stopPlayback()
    }

    override fun onDestroy() {
        session?.release()
        session = null
        player.release()
        super.onDestroy()
    }

    companion object {
        const val ACTION_LOCAL = "com.example.glasstube.LOCAL_BIND"
    }
}
