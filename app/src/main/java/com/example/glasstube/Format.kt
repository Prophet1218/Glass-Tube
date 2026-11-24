package com.example.glasstube

import android.content.Context
import android.text.format.DateUtils
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.localization.DateWrapper
import java.time.OffsetDateTime
import java.util.Locale

object Format {

    /** 1234 -> "1.2K" (or "1,2 E" in Hungarian). */
    fun count(ctx: Context, n: Long): String {
        if (n < 0) return ""
        if (n < 1000) return n.toString()
        val (value, unit) = when {
            n < 1_000_000 -> n / 1_000.0 to ctx.getString(R.string.unit_k)
            n < 1_000_000_000 -> n / 1_000_000.0 to ctx.getString(R.string.unit_m)
            else -> n / 1_000_000_000.0 to ctx.getString(R.string.unit_b)
        }
        val num = if (value < 10) String.format(Locale.getDefault(), "%.1f", value).replace(Regex("[.,]0$"), "")
        else value.toLong().toString()
        val hu = Locale.getDefault().language == "hu"
        return if (hu) "$num $unit" else "$num$unit"
    }

    /** Seconds -> "4:05" or "1:02:33". */
    fun duration(sec: Long): String {
        if (sec <= 0) return ""
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.ROOT, "%d:%02d", m, s)
    }

    /** The image closest to [targetWidth] pixels wide (but not smaller, when possible). */
    fun pick(images: List<Image>?, targetWidth: Int): String? {
        if (images.isNullOrEmpty()) return null
        val known = images.filter { it.width > 0 }
        if (known.isEmpty()) return images.last().url
        val bigEnough = known.filter { it.width >= targetWidth }
        return (bigEnough.minByOrNull { it.width } ?: known.maxByOrNull { it.width })?.url
    }

    fun joinDot(vararg parts: String?): String =
        parts.filter { !it.isNullOrBlank() }.joinToString("  ·  ")

    private val ISO = Regex("^\\d{4}-\\d{2}-\\d{2}")

    /** "3 days ago" in the phone's language (YouTube's own text when it isn't a raw date). */
    fun date(d: DateWrapper?, textual: String?): String? {
        val t = textual?.trim()
        if (!t.isNullOrEmpty() && !ISO.containsMatchIn(t)) return t
        val millis = try {
            d?.instant?.toEpochMilli() ?: t?.let { OffsetDateTime.parse(it).toInstant().toEpochMilli() }
        } catch (e: Exception) {
            null
        } ?: return null
        val now = System.currentTimeMillis()
        return DateUtils.getRelativeTimeSpanString(minOf(millis, now), now, 60_000L).toString()
    }

    /** "12:34" or "1:02:33" -> seconds. */
    fun parseDuration(text: String?): Long {
        if (text.isNullOrBlank()) return 0
        val parts = text.trim().split(':')
        if (parts.size !in 2..3) return 0
        var total = 0L
        for (p in parts) total = total * 60 + (p.trim().toLongOrNull() ?: return 0)
        return total
    }
}
