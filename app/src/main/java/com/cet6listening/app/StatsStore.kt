package com.cet6listening.app

import android.content.Context
import org.json.JSONObject
import java.time.LocalDate

class StatsStore(context: Context) {
    private val prefs = context.getSharedPreferences("cet6_stats", Context.MODE_PRIVATE)

    fun sectionListenedMs(itemId: String, sectionIndex: Int): Long =
        read().optJSONObject("sectionListened")?.optLong(key(itemId, sectionIndex), 0L) ?: 0L

    fun addSectionListenedMs(itemId: String, sectionIndex: Int, amountMs: Long) {
        if (amountMs <= 0L) return
        update { root ->
            val sections = root.optJSONObject("sectionListened") ?: JSONObject().also {
                root.put("sectionListened", it)
            }
            val k = key(itemId, sectionIndex)
            sections.put(k, sections.optLong(k, 0L) + amountMs)
        }
    }

    fun sectionPlayCount(itemId: String, sectionIndex: Int): Int =
        read().optJSONObject("sectionPlays")?.optInt(key(itemId, sectionIndex), 0) ?: 0

    fun incrementSectionPlayCount(itemId: String, sectionIndex: Int) {
        update { root ->
            val sections = root.optJSONObject("sectionPlays") ?: JSONObject().also {
                root.put("sectionPlays", it)
            }
            val k = key(itemId, sectionIndex)
            sections.put(k, sections.optInt(k, 0) + 1)
        }
    }

    fun itemPlayCount(item: ListeningItem): Int {
        val plays = read().optJSONObject("sectionPlays") ?: return 0
        return item.sections.indices.minOfOrNull { plays.optInt(key(item.id, it), 0) } ?: 0
    }

    fun completedSectionCount(item: ListeningItem): Int {
        return item.sections.indices.count { index ->
            sectionPlayCount(item.id, index) > 0
        }
    }

    fun totalCompletedSections(items: List<ListeningItem>): Int =
        items.sumOf { completedSectionCount(it) }

    fun lastPositionMs(itemId: String): Long =
        read().optJSONObject("positions")?.optLong(itemId, 0L) ?: 0L

    fun setLastPositionMs(itemId: String, positionMs: Long) {
        update { root ->
            val positions = root.optJSONObject("positions") ?: JSONObject().also {
                root.put("positions", it)
            }
            positions.put(itemId, positionMs.coerceAtLeast(0L))
        }
    }

    fun recentItemId(): String? =
        read().optJSONObject("recent")?.optString("itemId")?.takeIf { it.isNotBlank() }

    fun recentTimestamp(): Long =
        read().optJSONObject("recent")?.optLong("updatedAt", 0L) ?: 0L

    fun setRecent(itemId: String) {
        update { root ->
            root.put(
                "recent",
                JSONObject()
                    .put("itemId", itemId)
                    .put("updatedAt", System.currentTimeMillis())
            )
        }
    }

    fun addDailyListeningMs(deltaMs: Long) {
        if (deltaMs <= 0L) return
        val today = LocalDate.now().toString()
        update { root ->
            val daily = root.optJSONObject("dailyListeningMs") ?: JSONObject().also {
                root.put("dailyListeningMs", it)
            }
            daily.put(today, daily.optLong(today, 0L) + deltaMs)
        }
    }

    fun todayListeningMs(): Long {
        val today = LocalDate.now().toString()
        return read().optJSONObject("dailyListeningMs")?.optLong(today, 0L) ?: 0L
    }

    fun totalListeningMs(): Long {
        val daily = read().optJSONObject("dailyListeningMs") ?: return 0L
        var total = 0L
        val keys = daily.keys()
        while (keys.hasNext()) {
            total += daily.optLong(keys.next(), 0L)
        }
        return total
    }

    fun playbackSpeed(): Float = read().optDouble("playbackSpeed", 1.0).toFloat()

    fun setPlaybackSpeed(speed: Float) {
        update { it.put("playbackSpeed", speed.toDouble()) }
    }

    private fun key(itemId: String, sectionIndex: Int) = "$itemId::$sectionIndex"

    private fun read(): JSONObject {
        val raw = prefs.getString(KEY_DATA, null) ?: return JSONObject()
        return try {
            JSONObject(raw)
        } catch (_: Exception) {
            JSONObject()
        }
    }

    private fun update(block: (JSONObject) -> Unit) {
        synchronized(lock) {
            val root = read()
            block(root)
            prefs.edit().putString(KEY_DATA, root.toString()).commit()
        }
    }

    companion object {
        private const val KEY_DATA = "stats_json"
        private val lock = Any()
    }
}
