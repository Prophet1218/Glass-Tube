package com.example.glasstube

import android.content.Context
import android.webkit.CookieManager
import java.security.MessageDigest

/**
 * The Google account the user signed in with (in [SignInActivity]). The YouTube cookies of that
 * sign-in authorize requests to YouTube's own web API (home feed, history, playlists...).
 */
object Account {
    private const val PREFS = "account"
    private const val ORIGIN = "https://www.youtube.com"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun cookies(ctx: Context): String? = prefs(ctx).getString("cookies", null)

    fun isSignedIn(ctx: Context): Boolean = sapisid(cookies(ctx)) != null

    fun name(ctx: Context): String? = prefs(ctx).getString("name", null)
    fun handle(ctx: Context): String? = prefs(ctx).getString("handle", null)
    fun photo(ctx: Context): String? = prefs(ctx).getString("photo", null)

    /** Takes the cookies of the WebView sign-in; true if they contain a YouTube session. */
    fun captureFromWebView(ctx: Context): Boolean {
        val cm = CookieManager.getInstance()
        val c = cm.getCookie(ORIGIN) ?: return false
        if (sapisid(c) == null) return false
        prefs(ctx).edit().putString("cookies", c).putBoolean("expired", false).apply()
        return true
    }

    fun saveProfile(ctx: Context, name: String?, handle: String?, photo: String?) {
        prefs(ctx).edit().putString("name", name).putString("handle", handle).putString("photo", photo).apply()
    }

    /** YouTube stopped accepting the saved sign-in (cookies expired): ask to sign in again. */
    fun isExpired(ctx: Context): Boolean = prefs(ctx).getBoolean("expired", false)

    fun setExpired(ctx: Context, v: Boolean) {
        prefs(ctx).edit().putBoolean("expired", v).apply()
    }

    fun signOut(ctx: Context) {
        prefs(ctx).edit().clear().apply()
        val cm = CookieManager.getInstance()
        cm.removeAllCookies(null)
        cm.flush()
    }

    /** Request headers that make YouTube treat the request as the signed-in user's. */
    fun authHeaders(ctx: Context): Map<String, String> {
        val c = cookies(ctx) ?: return emptyMap()
        val sid = sapisid(c) ?: return emptyMap()
        val ts = System.currentTimeMillis() / 1000
        val hash = sha1("$ts $sid $ORIGIN")
        return mapOf(
            "Cookie" to c,
            "Authorization" to "SAPISIDHASH ${ts}_$hash",
            "X-Goog-AuthUser" to "0",
            "X-Origin" to ORIGIN,
            "Origin" to ORIGIN
        )
    }

    private fun sapisid(cookies: String?): String? {
        cookies ?: return null
        val map = cookies.split(';').mapNotNull {
            val i = it.indexOf('=')
            if (i <= 0) null else it.substring(0, i).trim() to it.substring(i + 1).trim()
        }.toMap()
        return map["SAPISID"] ?: map["__Secure-3PAPISID"]
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
