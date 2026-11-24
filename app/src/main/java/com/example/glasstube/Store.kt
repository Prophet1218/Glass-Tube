package com.example.glasstube

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Things kept on the phone: the local watch history and, for the home page without a Google
 * account, the related videos of the last few watched videos.
 */
class Store(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("store", Context.MODE_PRIVATE)

    fun history(): List<Video> = readList("history")

    fun addToHistory(v: Video) {
        val list = history().filterNot { it.id == v.id }.toMutableList()
        list.add(0, v)
        writeList("history", list.take(MAX_HISTORY))
    }

    fun clearHistory() {
        prefs.edit().remove("history").remove("related").apply()
    }

    /** Remembers the related videos of [sourceId] (the newest few sources are kept). */
    fun saveRelated(sourceId: String, related: List<Video>) {
        if (related.isEmpty()) return
        val arr = try {
            JSONArray(prefs.getString("related", "[]"))
        } catch (e: Exception) {
            JSONArray()
        }
        val out = JSONArray()
        out.put(JSONObject().put("src", sourceId).put("items", JSONArray(related.take(20).map { it.toJson() })))
        for (i in 0 until arr.length()) {
            if (out.length() >= MAX_SOURCES) break
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("src") != sourceId) out.put(o)
        }
        prefs.edit().putString("related", out.toString()).apply()
    }

    /** Related videos of the recently watched ones, interleaved, without already watched ones. */
    fun recommendations(): List<Video> {
        val arr = try {
            JSONArray(prefs.getString("related", "[]"))
        } catch (e: Exception) {
            return emptyList()
        }
        val pools = ArrayList<List<Video>>()
        for (i in 0 until arr.length()) {
            val items = arr.optJSONObject(i)?.optJSONArray("items") ?: continue
            val pool = ArrayList<Video>()
            for (j in 0 until items.length()) {
                try {
                    pool.add(Video.fromJson(items.getJSONObject(j)))
                } catch (e: Exception) {
                    // skip broken entry
                }
            }
            pools.add(pool)
        }
        val watched = history().take(50).map { it.id }.toHashSet()
        val seen = HashSet<String>()
        val out = ArrayList<Video>()
        var index = 0
        var added = true
        while (added) {
            added = false
            for (pool in pools) {
                if (index < pool.size) {
                    added = true
                    val v = pool[index]
                    if (v.id !in watched && seen.add(v.id)) out.add(v)
                }
            }
            index++
        }
        return out
    }

    private fun readList(key: String): List<Video> {
        val arr = try {
            JSONArray(prefs.getString(key, "[]"))
        } catch (e: Exception) {
            return emptyList()
        }
        val out = ArrayList<Video>()
        for (i in 0 until arr.length()) {
            try {
                out.add(Video.fromJson(arr.getJSONObject(i)))
            } catch (e: Exception) {
                // skip broken entry
            }
        }
        return out
    }

    private fun writeList(key: String, list: List<Video>) {
        prefs.edit().putString(key, JSONArray(list.map { it.toJson() }).toString()).apply()
    }

    companion object {
        private const val MAX_HISTORY = 200
        private const val MAX_SOURCES = 8
    }
}
