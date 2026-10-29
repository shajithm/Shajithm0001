package com.example.intervaltimer

import android.content.Context
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * A short history of what happened with scheduled starts (armed, fired, started,
 * blocked…), shown in Settings so problems on a particular phone can be seen.
 */
object ScheduleLog {

    private const val PREFS = "schedule_log"
    private const val KEY = "lines"
    const val MAX_LINES = 40
    private val stamp = DateTimeFormatter.ofPattern("dd MMM HH:mm:ss")

    fun add(context: Context, message: String) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val line = LocalDateTime.now().format(stamp) + "  " + message
        p.edit().putString(KEY, append(p.getString(KEY, "") ?: "", line)).commit()
    }

    fun read(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "") ?: ""

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }

    /** Newest line first, keeping at most [MAX_LINES]. */
    fun append(existing: String, line: String): String =
        (listOf(line) + existing.lines().filter { it.isNotBlank() }).take(MAX_LINES).joinToString("\n")
}
