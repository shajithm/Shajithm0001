package com.example.intervaltimer

import android.content.Context
import java.time.DayOfWeek

/** App-wide preferences. */
object AppSettings {

    private const val KEY_WEEK_START = "week_start"
    private const val KEY_DEFAULT_INTERVAL = "default_interval_ms"
    private const val KEY_DEFAULT_ROUNDS = "default_rounds"
    private const val KEY_FLOATING = "floating_window"
    private const val KEY_FLOAT_X = "float_x"
    private const val KEY_FLOAT_Y = "float_y"
    private const val KEY_VOICE = "voice"

    /** Show the floating mini window while the app is minimized. Off by default. */
    fun floatingWindow(context: Context) = prefs(context).getBoolean(KEY_FLOATING, false)
    fun setFloatingWindow(context: Context, on: Boolean) =
        prefs(context).edit().putBoolean(KEY_FLOATING, on).apply()

    fun floatX(context: Context, default: Int) = prefs(context).getInt(KEY_FLOAT_X, default)
    fun floatY(context: Context, default: Int) = prefs(context).getInt(KEY_FLOAT_Y, default)
    fun setFloatPosition(context: Context, x: Int, y: Int) =
        prefs(context).edit().putInt(KEY_FLOAT_X, x).putInt(KEY_FLOAT_Y, y).apply()

    /** Voice for spoken announcements. Off by default. */
    fun voice(context: Context): VoiceChoice =
        runCatching { VoiceChoice.valueOf(prefs(context).getString(KEY_VOICE, "OFF")!!) }
            .getOrDefault(VoiceChoice.OFF)
    fun setVoice(context: Context, v: VoiceChoice) =
        prefs(context).edit().putString(KEY_VOICE, v.name).apply()

    private fun prefs(context: Context) = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Interval a new timer starts with (0 = must be set each time). */
    fun defaultIntervalMs(context: Context): Long = prefs(context).getLong(KEY_DEFAULT_INTERVAL, 0L)
    fun setDefaultIntervalMs(context: Context, ms: Long) =
        prefs(context).edit().putLong(KEY_DEFAULT_INTERVAL, ms).apply()

    /** Rounds a new timer starts with (0 = must be set each time). */
    fun defaultRounds(context: Context): Int = prefs(context).getInt(KEY_DEFAULT_ROUNDS, 0)
    fun setDefaultRounds(context: Context, n: Int) =
        prefs(context).edit().putInt(KEY_DEFAULT_ROUNDS, n).apply()

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
