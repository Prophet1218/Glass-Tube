package com.example.glasstube

import android.content.Context
import android.net.ConnectivityManager
import androidx.appcompat.app.AppCompatDelegate

/** App settings. */
class Settings(ctx: Context) {
    private val app = ctx.applicationContext
    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private fun int(key: String, def: Int) = prefs.getInt(key, def)
    private fun bool(key: String, def: Boolean) = prefs.getBoolean(key, def)
    private fun put(key: String, v: Int) = prefs.edit().putInt(key, v).apply()
    private fun put(key: String, v: Boolean) = prefs.edit().putBoolean(key, v).apply()

    // ---- playback
    /** Highest automatic resolution (short side of the video): 720, 1080, 1440 or 2160. */
    var maxQuality: Int
        get() = int("max_quality", 1080)
        set(v) = put("max_quality", v)

    /** On mobile data: 0 = same as on Wi-Fi, otherwise the limit (480, 720, 1080). */
    var mobileQuality: Int
        get() = int("mobile_quality", 720)
        set(v) = put("mobile_quality", v)

    /** Default playback speed in percent (100 = 1x). */
    var speedPercent: Int
        get() = int("speed", 100)
        set(v) = put("speed", v)

    var autoplay: Boolean
        get() = bool("autoplay", true)
        set(v) = put("autoplay", v)

    var backgroundPlay: Boolean
        get() = bool("background_play", true)
        set(v) = put("background_play", v)

    var resume: Boolean
        get() = bool("resume", true)
        set(v) = put("resume", v)

    /** Seconds for double-tap and the skip buttons. */
    var seekStep: Int
        get() = int("seek_step", 10)
        set(v) = put("seek_step", v)

    /** Picture-in-picture when leaving the app during a video. */
    var pip: Boolean
        get() = bool("pip", true)
        set(v) = put("pip", v)

    /** Remembered subtitle language ("" = off). */
    var subtitleLang: String
        get() = prefs.getString("subtitle_lang", "") ?: ""
        set(v) = prefs.edit().putString("subtitle_lang", v).apply()

    /** Resolution for downloads (short side). */
    var downloadQuality: Int
        get() = int("download_quality", 720)
        set(v) = put("download_quality", v)

    // ---- SponsorBlock
    var sponsorBlock: Boolean
        get() = bool("sponsorblock", true)
        set(v) = put("sponsorblock", v)

    var sbCategories: Set<String>
        get() = prefs.getStringSet("sb_categories", setOf("sponsor", "selfpromo", "interaction")) ?: emptySet()
        set(v) = prefs.edit().putStringSet("sb_categories", HashSet(v)).apply()

    // ---- shorts
    var shortsLoop: Boolean
        get() = bool("shorts_loop", true)
        set(v) = put("shorts_loop", v)

    // ---- appearance
    /** 0 = follow the phone, 1 = light, 2 = dark. */
    var theme: Int
        get() = int("theme", 0)
        set(v) = put("theme", v)

    var collapseBar: Boolean
        get() = bool("collapse_bar", true)
        set(v) = put("collapse_bar", v)

    /** Frosted surfaces without the refracting lens. */
    var reduceTransparency: Boolean
        get() = bool("reduce_transparency", false)
        set(v) = put("reduce_transparency", v)

    // ---- history
    var saveWatchHistory: Boolean
        get() = bool("save_watch_history", true)
        set(v) = put("save_watch_history", v)

    var saveSearchHistory: Boolean
        get() = bool("save_search_history", true)
        set(v) = put("save_search_history", v)

    /** The automatic quality limit that applies right now (mobile data may lower it). */
    fun effectiveMaxQuality(): Int {
        val mobile = mobileQuality
        if (mobile <= 0) return maxQuality
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val metered = try {
            cm?.isActiveNetworkMetered == true
        } catch (e: Exception) {
            false
        }
        return if (metered) minOf(maxQuality, mobile) else maxQuality
    }

    fun applyTheme() {
        AppCompatDelegate.setDefaultNightMode(
            when (theme) {
                1 -> AppCompatDelegate.MODE_NIGHT_NO
                2 -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }
}
