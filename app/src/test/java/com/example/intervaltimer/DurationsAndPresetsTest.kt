package com.example.intervaltimer

import com.example.intervaltimer.Durations.DAY
import com.example.intervaltimer.Durations.HOUR
import com.example.intervaltimer.Durations.MINUTE
import com.example.intervaltimer.Durations.SECOND
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class DurationsTest {

    @Test fun clockFormats() {
        assertEquals("03:00", Durations.clock(3 * MINUTE))
        assertEquals("00:05", Durations.clock(5 * SECOND))
        assertEquals("02:59", Durations.clock(3 * MINUTE - 1500))   // rounds up
        assertEquals("1:30:00", Durations.clock(90 * MINUTE))
        assertEquals("2d 04:00:00", Durations.clock(2 * DAY + 4 * HOUR))
        assertEquals("99d 23:59:59", Durations.clock(Durations.MAX_INTERVAL_MS))
    }

    @Test fun humanFormats() {
        assertEquals("3 min", Durations.human(3 * MINUTE))
        assertEquals("30 min", Durations.human(30 * MINUTE))
        assertEquals("1 h 30 min", Durations.human(90 * MINUTE))
        assertEquals("2 h", Durations.human(2 * HOUR))
        assertEquals("1 d", Durations.human(DAY))
        assertEquals("2 d 4 h", Durations.human(2 * DAY + 4 * HOUR + 7 * MINUTE))
        assertEquals("1 min 30 s", Durations.human(90 * SECOND))
        assertEquals("45 s", Durations.human(45 * SECOND))
    }

    @Test fun stepsUp() {
        assertEquals(15 * SECOND, Durations.step(5 * SECOND, +1))
        assertEquals(3 * MINUTE + 15 * SECOND, Durations.step(3 * MINUTE, +1))
        assertEquals(6 * MINUTE, Durations.step(5 * MINUTE, +1))
        assertEquals(HOUR, Durations.step(59 * MINUTE, +1))
        assertEquals(HOUR + 15 * MINUTE, Durations.step(HOUR, +1))
        assertEquals(DAY, Durations.step(23 * HOUR, +1))
        assertEquals(DAY + 6 * HOUR, Durations.step(DAY, +1))
    }

    @Test fun stepsDown() {
        assertEquals(5 * SECOND, Durations.step(15 * SECOND, -1))        // never below minimum
        assertEquals(4 * MINUTE + 45 * SECOND, Durations.step(5 * MINUTE, -1))
        assertEquals(59 * MINUTE, Durations.step(HOUR, -1))
        assertEquals(23 * HOUR, Durations.step(DAY, -1))
        assertEquals(DAY, Durations.step(DAY + 6 * HOUR, -1))
    }

    @Test fun stepsAreReversible() {
        var ms = 3 * MINUTE
        repeat(200) { ms = Durations.step(ms, +1) }
        repeat(200) { ms = Durations.step(ms, -1) }
        assertEquals(3 * MINUTE, ms)
    }

    @Test fun neverAboveMaximum() =
        assertEquals(Durations.MAX_INTERVAL_MS, Durations.step(Durations.MAX_INTERVAL_MS, +1))
}

class PresetsJsonTest {

    private val a = Preset(1, "Morning workout", 3 * MINUTE, 10)
    private val b = Preset(2, "Medication every 2 days", 2 * DAY, 15, scheduleOn = true,
        hour = 8, minute = 30, repeat = Repeat.DAYS, date = LocalDate.of(2026, 11, 1), days = 0b0010101,
        sound = "content://media/internal/audio/media/42")

    @Test fun exportThenImportGivesSameTimers() {
        val back = PresetStore.parseImport(PresetStore.exportJson(listOf(a, b)))
        assertEquals(listOf(a, b), back)
    }

    @Test fun oldFilesWithoutSoundUseBeep() {
        val p = PresetStore.parseImport("""{"timers":[{"name":"Old","interval":180000,"rounds":10}]}""")[0]
        assertEquals("", p.sound)
    }

    @Test fun importsBareList() {
        val text = "[" + PresetStore.toJson(a).toString() + "]"
        assertEquals(listOf(a), PresetStore.parseImport(text))
    }

    @Test fun longNamesKeptUpTo100() {
        val name = "x".repeat(100)
        val back = PresetStore.parseImport(PresetStore.exportJson(listOf(a.copy(name = name))))
        assertEquals(name, back[0].name)
        val tooLong = PresetStore.parseImport(PresetStore.exportJson(listOf(a.copy(name = "y".repeat(150)))))
        assertEquals(100, tooLong[0].name.length)
    }

    @Test fun badValuesAreClamped() {
        val text = """{"timers":[{"name":"  ","interval":1,"rounds":500,"hour":30,"repeat":"NOPE"}]}"""
        val p = PresetStore.parseImport(text)[0]
        assertEquals("Timer", p.name)
        assertEquals(Durations.MIN_INTERVAL_MS, p.intervalMs)
        assertEquals(99, p.rounds)
        assertEquals(23, p.hour)
        assertEquals(Repeat.DAILY, p.repeat)
    }

    @Test(expected = Exception::class)
    fun rejectsNonTimerFile() { PresetStore.parseImport("hello") }

    @Test fun mergeSkipsDuplicatesAndGivesNewIds() {
        val (merged, added) = PresetStore.merge(
            existing = listOf(a),
            incoming = listOf(a.copy(id = 99), b.copy(id = 1)),
            idBase = 1
        )
        assertEquals(1, added)
        assertEquals(2, merged.size)
        assertEquals(merged.size, merged.map { it.id }.toSet().size)   // ids unique
        assertTrue(merged.any { it.name == b.name })
    }
}

class WeekStartTest {
    @Test fun sundayFirst() = assertEquals(
        listOf(java.time.DayOfWeek.SUNDAY, java.time.DayOfWeek.MONDAY, java.time.DayOfWeek.TUESDAY,
            java.time.DayOfWeek.WEDNESDAY, java.time.DayOfWeek.THURSDAY, java.time.DayOfWeek.FRIDAY,
            java.time.DayOfWeek.SATURDAY),
        AppSettings.orderedDays(java.time.DayOfWeek.SUNDAY))

    @Test fun saturdayFirst() {
        val d = AppSettings.orderedDays(java.time.DayOfWeek.SATURDAY)
        assertEquals(java.time.DayOfWeek.SATURDAY, d.first())
        assertEquals(java.time.DayOfWeek.FRIDAY, d.last())
    }

    @Test fun calendarConstants() {
        assertEquals(java.util.Calendar.SUNDAY, AppSettings.calendarDay(java.time.DayOfWeek.SUNDAY))
        assertEquals(java.util.Calendar.MONDAY, AppSettings.calendarDay(java.time.DayOfWeek.MONDAY))
        assertEquals(java.util.Calendar.SATURDAY, AppSettings.calendarDay(java.time.DayOfWeek.SATURDAY))
    }
}

class AnnouncementsTest {
    @Test fun spokenDurations() {
        assertEquals("21 minutes", Announcements.spoken(21 * MINUTE))
        assertEquals("1 minute", Announcements.spoken(MINUTE))
        assertEquals("1 hour 30 minutes", Announcements.spoken(90 * MINUTE))
        assertEquals("2 days 4 hours", Announcements.spoken(2 * DAY + 4 * HOUR))
        assertEquals("1 day", Announcements.spoken(DAY))
        assertEquals("45 seconds", Announcements.spoken(45 * SECOND))
        assertEquals("1 minute 1 second", Announcements.spoken(61 * SECOND))
    }

    @Test fun startText() {
        assertEquals("Starting Morning workout. 10 rounds of 3 minutes.",
            Announcements.start("Morning workout", 10, 3 * MINUTE))
        assertEquals("Starting. 1 round of 2 hours.", Announcements.start("", 1, 2 * HOUR))
        assertEquals("Starting. 5 rounds of 1 minute.", Announcements.start("n".repeat(60), 5, MINUTE))
    }

    @Test fun roundText() {
        assertEquals("Round 3 of 10 complete. 7 rounds, 21 minutes left.",
            Announcements.round(3, 10, 21 * MINUTE))
        assertEquals("Round 9 of 10 complete. 1 round, 3 minutes left.",
            Announcements.round(9, 10, 3 * MINUTE))
        assertEquals("All 10 rounds complete. Well done.", Announcements.round(10, 10, 0))
        assertEquals("Round complete. Well done.", Announcements.round(1, 1, 0))
    }
}
