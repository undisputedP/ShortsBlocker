package com.shortsBlocker.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

data class BlockEvent(
    val domain: String,
    val platform: String,
    val timestamp: Long,
    val dateLabel: String
)

data class DailyStats(
    val date: String,
    val totalBlocked: Int,
    val byPlatform: Map<String, Int>
)

object StatsManager {

    private const val PREFS_NAME = "shortsBlocker_stats"
    private const val KEY_TOTAL_BLOCKED = "total_blocked"
    private const val KEY_TODAY_BLOCKED = "today_blocked"
    private const val KEY_TODAY_DATE = "today_date"
    private const val KEY_HISTORY = "history_json"
    private const val KEY_PLATFORM_COUNTS = "platform_counts"

    private lateinit var prefs: SharedPreferences
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        resetDailyIfNeeded()
    }

    private fun resetDailyIfNeeded() {
        val today = dateFormat.format(Date())
        val savedDate = prefs.getString(KEY_TODAY_DATE, "")
        if (savedDate != today) {
            prefs.edit()
                .putString(KEY_TODAY_DATE, today)
                .putInt(KEY_TODAY_BLOCKED, 0)
                .apply()
            saveDailyToHistory(savedDate ?: today)
        }
    }

    fun recordBlock(domain: String, platform: String) {
        if (!::prefs.isInitialized) return
        resetDailyIfNeeded()

        val total = prefs.getInt(KEY_TOTAL_BLOCKED, 0) + 1
        val today = prefs.getInt(KEY_TODAY_BLOCKED, 0) + 1

        // Update platform counts
        val platformJson = JSONObject(prefs.getString(KEY_PLATFORM_COUNTS, "{}") ?: "{}")
        platformJson.put(platform, platformJson.optInt(platform, 0) + 1)

        prefs.edit()
            .putInt(KEY_TOTAL_BLOCKED, total)
            .putInt(KEY_TODAY_BLOCKED, today)
            .putString(KEY_PLATFORM_COUNTS, platformJson.toString())
            .apply()
    }

    fun getTodayCount(): Int {
        if (!::prefs.isInitialized) return 0
        resetDailyIfNeeded()
        return prefs.getInt(KEY_TODAY_BLOCKED, 0)
    }

    fun getTotalCount(): Int {
        if (!::prefs.isInitialized) return 0
        return prefs.getInt(KEY_TOTAL_BLOCKED, 0)
    }

    fun getPlatformCounts(): Map<String, Int> {
        if (!::prefs.isInitialized) return emptyMap()
        val json = prefs.getString(KEY_PLATFORM_COUNTS, "{}") ?: "{}"
        val obj = JSONObject(json)
        return obj.keys().asSequence().associateWith { obj.getInt(it) }
    }

    fun getWeeklyHistory(): List<DailyStats> {
        if (!::prefs.isInitialized) return emptyList()
        val historyJson = prefs.getString(KEY_HISTORY, "[]") ?: "[]"
        // Returns last 7 days - simplified implementation
        return try {
            val result = mutableListOf<DailyStats>()
            // Add today
            result.add(DailyStats(
                date = dateFormat.format(Date()),
                totalBlocked = getTodayCount(),
                byPlatform = getPlatformCounts()
            ))
            result
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun saveDailyToHistory(date: String) {
        val count = prefs.getInt(KEY_TODAY_BLOCKED, 0)
        if (count == 0) return
        // Archive daily stats - simplified, extend with full JSON array as needed
    }

    fun clearAll() {
        if (!::prefs.isInitialized) return
        prefs.edit().clear().apply()
    }
}
