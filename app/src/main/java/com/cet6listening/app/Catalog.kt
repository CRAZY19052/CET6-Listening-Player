package com.cet6listening.app

import android.content.Context
import org.json.JSONArray

data class ListeningSection(
    val title: String,
    val questions: String,
    val startMs: Long,
    val endMs: Long
) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(1L)
}

data class ListeningItem(
    val id: String,
    val year: Int,
    val examYm: String,
    val setNo: Int,
    val title: String,
    val fileName: String,
    val durationMs: Long,
    val sections: List<ListeningSection>
) {
    fun sectionIndexAt(positionMs: Long): Int {
        if (sections.isEmpty()) return 0
        val safePosition = positionMs.coerceAtLeast(0L)
        val index = sections.indexOfLast { safePosition >= it.startMs }
        return index.coerceAtLeast(0)
    }
}

object CatalogRepository {
    @Volatile
    private var cached: List<ListeningItem>? = null

    fun load(context: Context): List<ListeningItem> {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val raw = context.assets.open("catalog.json").bufferedReader().use { it.readText() }
            val root = JSONArray(raw)
            val result = buildList {
                for (i in 0 until root.length()) {
                    val item = root.getJSONObject(i)
                    val sectionArray = item.getJSONArray("sections")
                    val sections = buildList {
                        for (j in 0 until sectionArray.length()) {
                            val section = sectionArray.getJSONObject(j)
                            add(
                                ListeningSection(
                                    title = section.getString("title"),
                                    questions = section.getString("questions"),
                                    startMs = section.getLong("startMs"),
                                    endMs = section.getLong("endMs")
                                )
                            )
                        }
                    }
                    add(
                        ListeningItem(
                            id = item.getString("id"),
                            year = item.getInt("year"),
                            examYm = item.getString("examYm"),
                            setNo = item.getInt("setNo"),
                            title = item.getString("title"),
                            fileName = item.getString("fileName"),
                            durationMs = item.getLong("durationMs"),
                            sections = sections
                        )
                    )
                }
            }.sortedWith(compareByDescending<ListeningItem> { it.examYm }.thenBy { it.setNo })
            cached = result
            return result
        }
    }
}
