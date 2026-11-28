package com.example.glasstube

import android.app.Application
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import java.util.Locale

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // the crash screen runs in its own process and needs nothing else from the app
        val proc = if (android.os.Build.VERSION.SDK_INT >= 28) getProcessName() else ""
        if (proc.endsWith(":crash")) return
        installCrashScreen()
        Settings(this).applyTheme()
        Glass.reduceTransparency = Settings(this).reduceTransparency
        Glass.dispersion = Settings(this).dispersion
        Glass.refraction = Settings(this).refraction / 100f
        val locale = Locale.getDefault()
        val country = locale.country.ifEmpty { "US" }
        // YouTube answers in the phone's language (titles, "3 days ago", etc.)
        NewPipe.init(
            NetDownloader.instance,
            Localization.fromLocale(locale),
            ContentCountry(country)
        )
    }

    /** Any crash: show what happened (to copy / send) instead of the app just closing. */
    private fun installCrashScreen() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            try {
                val sw = java.io.StringWriter()
                e.printStackTrace(java.io.PrintWriter(sw))
                val info = "Lucent " + (try { packageManager.getPackageInfo(packageName, 0).versionName } catch (x: Exception) { "?" }) +
                    "\n" + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL +
                    ", Android " + android.os.Build.VERSION.RELEASE + " (API " + android.os.Build.VERSION.SDK_INT + ")" +
                    "\nThread: " + thread.name + "\n\n" + sw.toString()
                java.io.File(filesDir, "last_crash.txt").writeText(info)
                startActivity(
                    android.content.Intent(this, CrashActivity::class.java)
                        .putExtra(CrashActivity.EXTRA_TRACE, info.take(60_000))
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
                )
                android.os.Process.killProcess(android.os.Process.myPid())
                kotlin.system.exitProcess(10)
            } catch (x: Throwable) {
                previous?.uncaughtException(thread, e)
            }
        }
    }
}
