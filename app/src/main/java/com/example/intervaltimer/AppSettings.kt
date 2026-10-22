package com.example.intervaltimer

import android.content.Context
import java.time.DayOfWeek

/** App-wide preferences. */
object AppSettings {

    private const val KEY_WEEK_START = "week_start"

    /** First day of the week used for weekday buttons, day lists and the date picker. Default Sunday. */
    fun weekStart(context: Context): DayOfWeek {
        val v = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getInt(KEY_WEEK_START, DayOfWeek.SUNDAY.value)
        return DayOfWeek.of(v.coerceIn(1, 7))
    }

    fun setWeekStart(context: Context, day: DayOfWeek) {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
            .putInt(KEY_WEEK_START, day.value).apply()
    }

    /** The seven days in display order, starting from [start]. */
    fun orderedDays(start: DayOfWeek): List<DayOfWeek> = (0L until 7L).map { start.plus(it) }

    /** java.time day → java.util.Calendar day constant (Sunday = 1 … Saturday = 7). */
    fun calendarDay(day: DayOfWeek): Int = day.value % 7 + 1
}
