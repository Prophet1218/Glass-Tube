package com.example.glasstube

import android.content.Context

/** The last few searches, newest first. */
class SearchHistory(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("history", Context.MODE_PRIVATE)

    fun all(): List<String> =
        prefs.getString(KEY, "").orEmpty().split('\n').filter { it.isNotBlank() }

    fun add(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        val list = all().filterNot { it.equals(q, ignoreCase = true) }.toMutableList()
        list.add(0, q)
        save(list.take(MAX))
    }

    fun remove(query: String) = save(all().filterNot { it == query })

    fun clear() = save(emptyList())

    private fun save(list: List<String>) {
        prefs.edit().putString(KEY, list.joinToString("\n")).apply()
    }

    companion object {
        private const val KEY = "queries"
        private const val MAX = 12
    }
}
