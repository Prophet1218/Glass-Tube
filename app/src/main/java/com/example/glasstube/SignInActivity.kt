package com.example.glasstube

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Google sign-in on Google's own page, inside a WebView that presents itself as Chrome (like the
 * Google sign-in of Glass Browser). When YouTube's session cookies appear, they are kept by
 * [Account] and the screen closes.
 */
class SignInActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private var done = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val night = Glass.isNight(this)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !night
            isAppearanceLightNavigationBars = !night
        }
        val d = resources.displayMetrics.density
        val text = Glass.textColor(this)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(if (night) Color.rgb(14, 16, 22) else Color.WHITE)

        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setPadding((10 * d).toInt(), (6 * d).toInt(), (16 * d).toInt(), (6 * d).toInt())
        val close = ImageView(this)
        close.setImageResource(R.drawable.ic_back)
        close.setColorFilter(text)
        close.setPadding((10 * d).toInt(), (10 * d).toInt(), (10 * d).toInt(), (10 * d).toInt())
        close.background = Glass.pill(this, 22 * d)
        close.contentDescription = getString(R.string.back)
        close.setOnClickListener { finish() }
        bar.addView(close, LinearLayout.LayoutParams((44 * d).toInt(), (44 * d).toInt()))
        val title = TextView(this)
        title.text = getString(R.string.sign_in_google)
        title.textSize = 18f
        title.setTypeface(title.typeface, Typeface.BOLD)
        title.setTextColor(text)
        bar.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = (14 * d).toInt()
        })
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val progressHost = FrameLayout(this)
        val progress = View(this)
        progress.setBackgroundColor(Color.rgb(10, 132, 255))
        progressHost.addView(progress, FrameLayout.LayoutParams(0, (2 * d).toInt()))
        root.addView(progressHost, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (2 * d).toInt()))

        web = WebView(this)
        root.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(0, b.top, 0, b.bottom)
            insets
        }

        // start from a clean session
        val cm = CookieManager.getInstance()
        cm.removeAllCookies(null)
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(web, true)

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // without "; wv" Google treats it as Chrome and allows the sign-in
            val base = WebSettings.getDefaultUserAgent(this@SignInActivity)
            userAgentString = base.replace("; wv", "").replace(Regex("Version/\\d+\\.\\d+\\s"), "")
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
            WebSettingsCompat.setRequestedWithHeaderOriginAllowList(web.settings, emptySet())
        }
        if (Build.VERSION.SDK_INT >= 33) web.settings.setAlgorithmicDarkeningAllowed(true)

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val u = request?.url ?: return false
                return u.scheme != "http" && u.scheme != "https"
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                checkSignedIn(url)
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progress.layoutParams = progress.layoutParams.apply {
                    width = if (newProgress >= 100) 0 else progressHost.width * newProgress / 100
                }
                progress.requestLayout()
            }
        }
        web.loadUrl(SIGN_IN_URL)
    }

    private fun checkSignedIn(url: String?) {
        if (done || url == null || !url.contains("youtube.com")) return
        CookieManager.getInstance().flush()
        if (!Account.captureFromWebView(this)) return
        done = true
        lifecycleScope.launch {
            try {
                val (name, handle, photo) = withContext(Dispatchers.IO) { Innertube.accountInfo(this@SignInActivity) }
                Account.saveProfile(this@SignInActivity, name, handle, photo)
            } catch (e: Exception) {
                // the profile is only cosmetic; it's fetched again later
            }
            setResult(RESULT_OK)
            finish()
        }
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    companion object {
        private const val SIGN_IN_URL = "https://accounts.google.com/ServiceLogin?service=youtube&uilel=3&passive=true" +
            "&continue=https%3A%2F%2Fwww.youtube.com%2Fsignin%3Faction_handle_signin%3Dtrue%26app%3Dm%26next%3Dhttps%253A%252F%252Fm.youtube.com%252F"
    }
}
