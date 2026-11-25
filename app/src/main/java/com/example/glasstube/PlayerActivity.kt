package com.example.glasstube

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import org.schabi.newpipe.extractor.ServiceList

/**
 * Receives YouTube links and "Share" from other apps and hands them to [MainActivity]: videos go
 * to the player, channels and playlists open their page, searches run, anything else opens the app.
 */
class PlayerActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val target = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        route(candidates(intent), target)
        startActivity(target)
        finish()
    }

    private fun candidates(intent: Intent?): List<String> {
        intent ?: return emptyList()
        val list = ArrayList<String>()
        intent.getStringExtra(EXTRA_URL)?.let { list.add(it) }
        intent.dataString?.let { d ->
            // vnd.youtube:VIDEO_ID or vnd.youtube://VIDEO_ID
            if (d.startsWith("vnd.youtube")) {
                val id = d.substringAfter(':').trimStart('/').substringBefore('?')
                if (id.isNotEmpty()) list.add("https://www.youtube.com/watch?v=$id")
            } else list.add(d)
        }
        intent.getStringExtra(Intent.EXTRA_TEXT)?.let { text ->
            Regex("https?://\\S+").findAll(text).forEach { list.add(it.value) }
        }
        return list
    }

    private fun accepts(f: () -> Boolean) = try { f() } catch (e: Exception) { false }

    private fun route(urls: List<String>, target: Intent) {
        val yt = ServiceList.YouTube
        for (url in urls) {
            when {
                accepts { yt.streamLHFactory.acceptUrl(url) } -> {
                    target.putExtra(EXTRA_URL, url)
                    return
                }
                accepts { yt.playlistLHFactory.acceptUrl(url) } -> {
                    target.putExtra(EXTRA_PLAYLIST, url)
                    return
                }
                accepts { yt.channelLHFactory.acceptUrl(url) } -> {
                    target.putExtra(EXTRA_CHANNEL, url)
                    return
                }
            }
            val uri = try { Uri.parse(url) } catch (e: Exception) { null } ?: continue
            val q = uri.getQueryParameter("search_query") ?: uri.getQueryParameter("q")
            if (uri.path?.startsWith("/results") == true && !q.isNullOrBlank()) {
                target.putExtra(EXTRA_SEARCH, q)
                return
            }
        }
        // some other YouTube page (home, feed...): just open the app
    }

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_CHANNEL = "channel"
        const val EXTRA_PLAYLIST = "playlist"
        const val EXTRA_SEARCH = "search"
    }
}
