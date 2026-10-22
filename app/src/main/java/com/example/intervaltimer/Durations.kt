package com.example.intervaltimer

/** Time formatting and interval stepping (plain Kotlin, unit-tested). */
object Durations {

    const val SECOND = 1_000L
    const val MINUTE = 60 * SECOND
    const val HOUR = 60 * MINUTE
    const val DAY = 24 * HOUR

    const val MIN_INTERVAL_MS = 5 * SECOND
    const val MAX_DAYS = 99
    const val MAX_INTERVAL_MS = MAX_DAYS * DAY + 23 * HOUR + 59 * MINUTE + 59 * SECOND

    /** Clock style, rounded up to the second: "03:00", "1:30:00", "2d 04:00:00". */
    fun clock(ms: Long): String {
        val s = (ms.coerceAtLeast(0) + 999) / 1000
        val d = s / 86_400
        val h = (s % 86_400) / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return when {
            d > 0 -> "%dd %02d:%02d:%02d".format(d, h, m, sec)
            h > 0 -> "%d:%02d:%02d".format(h, m, sec)
            else -> "%02d:%02d".format(m, sec)
        }
    }

    /** Short words, largest two units: "3 min", "1 h 30 min", "2 d 4 h", "45 s". */
    fun human(ms: Long): String {
        val s = (ms.coerceAtLeast(0) + 999) / 1000
        val parts = listOf(
            s / 86_400 to "d", (s % 86_400) / 3600 to "h", (s % 3600) / 60 to "min", s % 60 to "s"
        )
        val first = parts.indexOfFirst { it.first > 0 }
        if (first < 0) return "0 s"
        return parts.drop(first).take(2).filter { it.first > 0 }
            .joinToString(" ") { "${it.first} ${it.second}" }
    }

    /** Step size used by − and +: finer for short intervals, coarser for long ones. */
    fun stepFor(ms: Long): Long = when {
        ms < 5 * MINUTE -> 15 * SECOND
        ms < HOUR -> MINUTE
        ms < 6 * HOUR -> 15 * MINUTE
        ms < DAY -> HOUR
        else -> 6 * HOUR
    }

    /** Next value up or down, snapped to the step grid. */
    fun step(ms: Long, direction: Int): Long {
        val next = if (direction > 0) {
            val st = stepFor(ms)
            (ms / st + 1) * st
        } else {
            val st = stepFor(ms - 1)
            ((ms + st - 1) / st - 1) * st
        }
        return next.coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS)
    }
}
