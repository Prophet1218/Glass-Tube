package com.example.glasstube

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import org.schabi.newpipe.extractor.ServiceList

/**
 * Receives YouTube links and "Share" from other apps and hands the video to [MainActivity],
 * where the player lives (full screen or as the mini player).
 */
class PlayerActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = extractUrl(intent)
        if (url == null) {
            Toast.makeText(this, R.string.not_a_video_link, Toast.LENGTH_SHORT).show()
        } else {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .putExtra(EXTRA_URL, url)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        }
        finish()
    }

    private fun extractUrl(intent: Intent?): String? {
        intent ?: return null
        val candidates = ArrayList<String>()
        intent.getStringExtra(EXTRA_URL)?.let { candidates.add(it) }
        intent.dataString?.let { candidates.add(it) }
        intent.getStringExtra(Intent.EXTRA_TEXT)?.let { text ->
            Regex("https?://\\S+").findAll(text).forEach { candidates.add(it.value) }
        }
        return candidates.firstOrNull { url ->
            try {
                ServiceList.YouTube.streamLHFactory.acceptUrl(url)
            } catch (e: Exception) {
                false
            }
        }
    }

    companion object {
        const val EXTRA_URL = "url"
    }
}
