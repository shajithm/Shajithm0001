package com.example.intervaltimer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class SchedulerTest {

    // Thursday 22 Oct 2026, 10:00
    private val now = LocalDateTime.of(2026, 10, 22, 10, 0)

    private fun preset(
        repeat: Repeat, hour: Int, minute: Int = 0,
        date: LocalDate = now.toLocalDate(), days: Int = Preset.WEEKDAYS,
    ) = Preset(1, "t", 180_000, 10, true, hour, minute, repeat, date, days)

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0) = LocalDateTime.of(y, mo, d, h, mi)

    @Test fun onceLaterToday() =
        assertEquals(at(2026, 10, 22, 18, 30), Scheduler.next(preset(Repeat.ONCE, 18, 30), now))

    @Test fun onceAlreadyPassed() =
        assertNull(Scheduler.next(preset(Repeat.ONCE, 9), now))

    @Test fun onceFutureDate() = assertEquals(at(2026, 12, 1, 6),
        Scheduler.next(preset(Repeat.ONCE, 6, date = LocalDate.of(2026, 12, 1)), now))

    @Test fun dailyLaterToday() =
        assertEquals(at(2026, 10, 22, 11), Scheduler.next(preset(Repeat.DAILY, 11), now))

    @Test fun dailyPassedGoesTomorrow() =
        assertEquals(at(2026, 10, 23, 6), Scheduler.next(preset(Repeat.DAILY, 6), now))

    @Test fun dailyExactlyNowGoesTomorrow() =
        assertEquals(at(2026, 10, 23, 10), Scheduler.next(preset(Repeat.DAILY, 10), now))

    @Test fun weeklySameDayLater() =
        assertEquals(at(2026, 10, 22, 20), Scheduler.next(preset(Repeat.WEEKLY, 20), now))

    @Test fun weeklySameDayPassed() =
        assertEquals(at(2026, 10, 29, 6), Scheduler.next(preset(Repeat.WEEKLY, 6), now))

    @Test fun weeklyFromFutureMonday() = assertEquals(at(2026, 11, 2, 6),
        Scheduler.next(preset(Repeat.WEEKLY, 6, date = LocalDate.of(2026, 11, 2)), now))

    @Test fun weeklyAnchorInPast() = assertEquals(at(2026, 10, 26, 6),   // anchored on a Monday
        Scheduler.next(preset(Repeat.WEEKLY, 6, date = LocalDate.of(2026, 10, 5)), now))

    @Test fun monthlyThisMonth() = assertEquals(at(2026, 10, 25, 6),
        Scheduler.next(preset(Repeat.MONTHLY, 6, date = LocalDate.of(2026, 10, 25)), now))

    @Test fun monthlyPassedGoesNextMonth() = assertEquals(at(2026, 11, 22, 6),
        Scheduler.next(preset(Repeat.MONTHLY, 6), now))

    @Test fun monthly31stInShortMonth() = assertEquals(at(2026, 11, 30, 6),
        Scheduler.next(preset(Repeat.MONTHLY, 6, date = LocalDate.of(2026, 10, 31)),
            at(2026, 11, 1, 0)))

    @Test fun monthly31stInFebruary() = assertEquals(at(2027, 2, 28, 6),
        Scheduler.next(preset(Repeat.MONTHLY, 6, date = LocalDate.of(2026, 10, 31)),
            at(2027, 2, 1, 0)))

    @Test fun weekdaysOnThursdayLater() =
        assertEquals(at(2026, 10, 22, 12), Scheduler.next(preset(Repeat.DAYS, 12), now))

    @Test fun weekdaysFridayPassedSkipsWeekend() = assertEquals(at(2026, 10, 26, 6),
        Scheduler.next(preset(Repeat.DAYS, 6), at(2026, 10, 23, 7)))

    @Test fun mondayWednesdayFriday() = assertEquals(at(2026, 10, 23, 6),   // Fri
        Scheduler.next(preset(Repeat.DAYS, 6, days = 0b0010101), now))

    @Test fun onlyThursdayPassedGoesNextWeek() = assertEquals(at(2026, 10, 29, 6),
        Scheduler.next(preset(Repeat.DAYS, 6, days = 0b0001000), now))

    @Test fun noDaysChosen() =
        assertNull(Scheduler.next(preset(Repeat.DAYS, 6, days = 0), now))

    @Test fun ordinals() {
        assertEquals("1st", Scheduler.ordinal(1)); assertEquals("2nd", Scheduler.ordinal(2))
        assertEquals("3rd", Scheduler.ordinal(3)); assertEquals("11th", Scheduler.ordinal(11))
        assertEquals("22nd", Scheduler.ordinal(22)); assertEquals("31st", Scheduler.ordinal(31))
    }
}
