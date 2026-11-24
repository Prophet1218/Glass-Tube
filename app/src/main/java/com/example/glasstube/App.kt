package com.example.glasstube

import android.app.Application
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import java.util.Locale

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val locale = Locale.getDefault()
        val country = locale.country.ifEmpty { "US" }
        // YouTube answers in the phone's language (titles, "3 days ago", etc.)
        NewPipe.init(
            NetDownloader.instance,
            Localization.fromLocale(locale),
            ContentCountry(country)
        )
    }
}
