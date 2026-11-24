package com.example.glasstube

import android.content.Context

/** App settings. */
class Settings(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Highest resolution chosen automatically (the short side of the video): 1080, 1440 or 2160. */
    var maxQuality: Int
        get() = prefs.getInt("max_quality", 1080)
        set(v) = prefs.edit().putInt("max_quality", v).apply()

    var hdr: Boolean
        get() = prefs.getBoolean("hdr", false)
        set(v) = prefs.edit().putBoolean("hdr", v).apply()
}
