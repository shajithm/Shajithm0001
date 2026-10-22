package com.example.intervaltimer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/** How a scheduled timer repeats. */
enum class Repeat { ONCE, DAILY, WEEKLY, MONTHLY, DAYS }

/** A saved, named interval timer, optionally started automatically at a set time. */
data class Preset(
    val id: Long,
    val name: String,
    val intervalMs: Long,
    val rounds: Int,
    val scheduleOn: Boolean = false,
    val hour: Int = 7,
    val minute: Int = 0,
    val repeat: Repeat = Repeat.DAILY,
    /** Date for ONCE; first date (and weekday / day of month) for WEEKLY and MONTHLY. */
    val date: LocalDate = LocalDate.now(),
    /** Chosen weekdays for DAYS: bit 0 = Monday … bit 6 = Sunday. */
    val days: Int = WEEKDAYS,
) {
    val totalMs get() = intervalMs * rounds

    companion object {
        const val WEEKDAYS = 0b0011111
        const val ALL_DAYS = 0b1111111
        const val MAX_NAME = 100
    }
}

/** Stores the user's presets on the device as JSON in SharedPreferences. */
class PresetStore(context: Context) {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun all(): List<Preset> {
        val raw = prefs.getString(KEY, null) ?: return seed()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i -> fromJson(arr.getJSONObject(i)) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun get(id: Long): Preset? = all().firstOrNull { it.id == id }

    /** Adds a new preset or replaces the one with the same id. */
    fun save(preset: Preset) {
        val list = all().toMutableList()
        val i = list.indexOfFirst { it.id == preset.id }
        if (i >= 0) list[i] = preset else list.add(preset)
        write(list)
    }

    fun delete(id: Long) = write(all().filterNot { it.id == id })

    fun replaceAll(list: List<Preset>) = write(list)

    fun newId(): Long = System.currentTimeMillis()

    private fun write(list: List<Preset>) {
        val arr = JSONArray()
        list.forEach { arr.put(toJson(it)) }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    /** First launch: carry over the settings from the first version as the first preset. */
    private fun seed(): List<Preset> {
        val first = Preset(
            id = 1L,
            name = "My timer",
            intervalMs = prefs.getLong("interval_ms", 3 * 60_000L),
            rounds = prefs.getInt("rounds", 10),
        )
        write(listOf(first))
        return listOf(first)
    }

    companion object {
        private const val KEY = "presets"
        private const val FORMAT = "interval-timer-presets"

        fun toJson(p: Preset): JSONObject = JSONObject()
            .put("id", p.id)
            .put("name", p.name)
            .put("interval", p.intervalMs)
            .put("rounds", p.rounds)
            .put("scheduleOn", p.scheduleOn)
            .put("hour", p.hour)
            .put("minute", p.minute)
            .put("repeat", p.repeat.name)
            .put("date", p.date.toString())
            .put("days", p.days)

        /** Reads one timer, clamping values so a hand-edited file can't break the app. */
        fun fromJson(o: JSONObject) = Preset(
            id = o.optLong("id", 0L),
            name = o.optString("name").trim().take(Preset.MAX_NAME).ifEmpty { "Timer" },
            intervalMs = o.getLong("interval").coerceIn(Durations.MIN_INTERVAL_MS, Durations.MAX_INTERVAL_MS),
            rounds = o.getInt("rounds").coerceIn(1, 99),
            scheduleOn = o.optBoolean("scheduleOn", false),
            hour = o.optInt("hour", 7).coerceIn(0, 23),
            minute = o.optInt("minute", 0).coerceIn(0, 59),
            repeat = runCatching { Repeat.valueOf(o.optString("repeat")) }.getOrDefault(Repeat.DAILY),
            date = runCatching { LocalDate.parse(o.optString("date")) }.getOrDefault(LocalDate.now()),
            days = o.optInt("days", Preset.WEEKDAYS) and Preset.ALL_DAYS,
        )

        /** The file written by Export. */
        fun exportJson(list: List<Preset>): String = JSONObject()
            .put("format", FORMAT)
            .put("version", 1)
            .put("timers", JSONArray().apply { list.forEach { put(toJson(it)) } })
            .toString(2)

        /** Reads an exported file (or a bare list of timers). Throws if it isn't valid. */
        fun parseImport(text: String): List<Preset> {
            val trimmed = text.trim()
            val arr = if (trimmed.startsWith("[")) JSONArray(trimmed)
                      else JSONObject(trimmed).getJSONArray("timers")
            return (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
        }

        /**
         * Adds [incoming] to [existing], giving each a fresh id and skipping exact
         * duplicates. Returns the merged list and how many were added.
         */
        fun merge(existing: List<Preset>, incoming: List<Preset>, idBase: Long): Pair<List<Preset>, Int> {
            val result = existing.toMutableList()
            val usedIds = existing.map { it.id }.toMutableSet()
            var nextId = idBase
            var added = 0
            incoming.forEach { p ->
                if (result.none { it.copy(id = 0) == p.copy(id = 0) }) {
                    while (nextId in usedIds) nextId++
                    usedIds += nextId
                    result += p.copy(id = nextId)
                    added++
                }
            }
            return result to added
        }
    }
}
