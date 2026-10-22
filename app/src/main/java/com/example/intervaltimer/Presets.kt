package com.example.intervaltimer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** A saved, named interval timer. */
data class Preset(
    val id: Long,
    val name: String,
    val intervalMs: Long,
    val rounds: Int,
) {
    val totalMs get() = intervalMs * rounds
}

/** Stores the user's presets on the device as JSON in SharedPreferences. */
class PresetStore(context: Context) {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun all(): List<Preset> {
        val raw = prefs.getString(KEY, null) ?: return seed()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Preset(o.getLong("id"), o.getString("name"), o.getLong("interval"), o.getInt("rounds"))
            }
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

    fun newId(): Long = System.currentTimeMillis()

    private fun write(list: List<Preset>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject()
                .put("id", it.id)
                .put("name", it.name)
                .put("interval", it.intervalMs)
                .put("rounds", it.rounds))
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    /** First launch: carry over the settings from the previous version as the first preset. */
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
    }
}
