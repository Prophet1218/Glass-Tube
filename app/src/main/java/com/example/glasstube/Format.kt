package com.example.glasstube

import android.content.Context
import org.schabi.newpipe.extractor.Image
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
}
