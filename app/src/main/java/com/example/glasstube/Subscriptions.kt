package com.example.glasstube

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** A channel the user subscribed to (kept on the phone; also used next to a Google account). */
data class ChannelRef(val url: String, val name: String, val avatar: String?) {
    fun toJson(): JSONObject = JSONObject().put("url", url).put("name", name).put("avatar", avatar)

    companion object {
        fun fromJson(o: JSONObject) = ChannelRef(o.getString("url"), o.optString("name"), o.optStringOrNull("avatar"))
    }
}

class Subscriptions(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("subscriptions", Context.MODE_PRIVATE)

    fun all(): List<ChannelRef> {
        val arr = try {
            JSONArray(prefs.getString("list", "[]"))
        } catch (e: Exception) {
            return emptyList()
        }
        val out = ArrayList<ChannelRef>()
        for (i in 0 until arr.length()) {
            try {
                out.add(ChannelRef.fromJson(arr.getJSONObject(i)))
            } catch (e: Exception) {
                // skip
            }
        }
        return out
    }

    fun isSubscribed(url: String): Boolean = all().any { same(it.url, url) }

    fun add(c: ChannelRef) {
        val list = all().filterNot { same(it.url, c.url) }.toMutableList()
        list.add(0, c)
        save(list)
    }

    fun remove(url: String) = save(all().filterNot { same(it.url, url) })

    /** Takes the YouTube account's subscriptions (keeps channels only subscribed here too). */
    fun merge(remote: List<ChannelRef>) {
        if (remote.isEmpty()) return
        val local = all().filter { l -> remote.none { same(it.url, l.url) } }
        save(remote + local)
        prefs.edit().putLong("synced", System.currentTimeMillis()).apply()
    }

    fun lastSync(): Long = prefs.getLong("synced", 0L)

    private fun save(list: List<ChannelRef>) {
        prefs.edit().putString("list", JSONArray(list.map { it.toJson() }).toString()).apply()
    }

    private fun same(a: String, b: String) = a.trimEnd('/').equals(b.trimEnd('/'), ignoreCase = true)
}


/** The subscribe pill (channel page, under the player). */
object SubscribeButton {

    private val CHANNEL_ID = Regex("/channel/(UC[A-Za-z0-9_-]{22})")

    fun channelId(url: String): String? = CHANNEL_ID.find(url)?.groupValues?.get(1)

    fun create(activity: AppCompatActivity, ref: ChannelRef): TextView {
        val d = activity.resources.displayMetrics.density
        val tv = TextView(activity)
        tv.textSize = 14f
        tv.gravity = Gravity.CENTER
        tv.setPadding((16 * d).toInt(), (8 * d).toInt(), (16 * d).toInt(), (8 * d).toInt())
        val subs = Subscriptions(activity)
        fun style(on: Boolean) {
            val text = Glass.textColor(activity)
            if (on) {
                tv.text = activity.getString(R.string.subscribed)
                tv.setTextColor(text)
                tv.typeface = Typeface.DEFAULT
                tv.background = Glass.pill(activity, 18 * d)
            } else {
                tv.text = activity.getString(R.string.subscribe)
                tv.setTextColor(if (Glass.isNight(activity)) Color.BLACK else Color.WHITE)
                tv.typeface = Typeface.DEFAULT_BOLD
                tv.background = GradientDrawable().apply {
                    cornerRadius = 18 * d
                    setColor(ColorUtils.setAlphaComponent(text, 240))
                }
            }
        }
        style(subs.isSubscribed(ref.url))
        tv.setOnClickListener {
            val on = !subs.isSubscribed(ref.url)
            if (on) subs.add(ref) else subs.remove(ref.url)
            style(on)
            tv.scaleX = 0.88f
            tv.scaleY = 0.88f
            tv.animate().scaleX(1f).scaleY(1f).setDuration(320).setInterpolator(OvershootInterpolator(3f)).start()
            val id = channelId(ref.url)
            if (Account.isSignedIn(activity) && id != null) {
                activity.lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) { Innertube.subscribe(activity, id, on) }
                    } catch (e: Exception) {
                        Toast.makeText(activity, Errors.text(activity, e), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        tv.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = (10 * d).toInt()
        }
        return tv
    }
}

/** "Save to playlist": the user's own playlists kept on the phone. */
object PlaylistPicker {
    fun show(activity: AppCompatActivity, v: Video) {
        val store = Store(activity)
        val items = ArrayList<ActionSheet.Item>()
        store.playlistNames().forEach { name ->
            items.add(ActionSheet.Item(R.drawable.ic_playlist, name) {
                store.addToPlaylist(name, v)
                Toast.makeText(activity, activity.getString(R.string.saved_to, name), Toast.LENGTH_SHORT).show()
            })
        }
        items.add(ActionSheet.Item(R.drawable.ic_add, activity.getString(R.string.new_playlist)) {
            ActionSheet.askText(activity, activity.getString(R.string.new_playlist), activity.getString(R.string.playlist_name)) { name ->
                store.addToPlaylist(name, v)
                Toast.makeText(activity, activity.getString(R.string.saved_to, name), Toast.LENGTH_SHORT).show()
            }
        })
        ActionSheet.show(activity, activity.getString(R.string.save_to_playlist), items)
    }
}
