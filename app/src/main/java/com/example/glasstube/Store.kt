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
    private val positions = ctx.getSharedPreferences("positions", Context.MODE_PRIVATE)
    private val settings = Settings(ctx)

    /** Where the user stopped watching [id] (0 if unknown or finished). */
    fun position(id: String): Long = positions.getLong(id, 0L)

    fun savePosition(id: String, ms: Long, durationMs: Long) {
        val e = positions.edit()
        // near the start or the end: nothing to resume
        if (ms < 15_000 || (durationMs > 0 && ms > durationMs - 20_000)) e.remove(id) else e.putLong(id, ms)
        e.apply()
        if (positions.all.size > 300) positions.edit().clear().apply()
    }

    fun history(): List<Video> = readList("history")

    fun addToHistory(v: Video) {
        if (!settings.saveWatchHistory) return
        val list = history().filterNot { it.id == v.id }.toMutableList()
        list.add(0, v)
        writeList("history", list.take(MAX_HISTORY))
    }

    fun clearHistory() {
        prefs.edit().remove("history").remove("related").apply()
        positions.edit().clear().apply()
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

    // ---- own playlists (kept on the phone)

    fun playlistNames(): List<String> =
        try {
            val o = JSONObject(prefs.getString("playlists", "{}"))
            o.keys().asSequence().toList()
        } catch (e: Exception) {
            emptyList()
        }

    fun playlist(name: String): List<Video> {
        val o = try {
            JSONObject(prefs.getString("playlists", "{}"))
        } catch (e: Exception) {
            return emptyList()
        }
        val arr = o.optJSONArray(name) ?: return emptyList()
        val out = ArrayList<Video>()
        for (i in 0 until arr.length()) {
            try {
                out.add(Video.fromJson(arr.getJSONObject(i)))
            } catch (e: Exception) {
                // skip
            }
        }
        return out
    }

    fun addToPlaylist(name: String, v: Video) {
        val o = try {
            JSONObject(prefs.getString("playlists", "{}"))
        } catch (e: Exception) {
            JSONObject()
        }
        val list = playlist(name).filterNot { it.id == v.id } + v
        o.put(name, JSONArray(list.map { it.toJson() }))
        prefs.edit().putString("playlists", o.toString()).apply()
    }

    fun removeFromPlaylist(name: String, id: String) {
        val o = try {
            JSONObject(prefs.getString("playlists", "{}"))
        } catch (e: Exception) {
            return
        }
        o.put(name, JSONArray(playlist(name).filterNot { it.id == id }.map { it.toJson() }))
        prefs.edit().putString("playlists", o.toString()).apply()
    }

    fun deletePlaylist(name: String) {
        val o = try {
            JSONObject(prefs.getString("playlists", "{}"))
        } catch (e: Exception) {
            return
        }
        o.remove(name)
        prefs.edit().putString("playlists", o.toString()).apply()
    }

    companion object {
        private const val MAX_HISTORY = 200
        private const val MAX_SOURCES = 8
    }
}
