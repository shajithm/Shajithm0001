package com.example.intervaltimer

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.text.format.DateFormat
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/** Works out when scheduled timers should start and sets Android alarms for them. */
object Scheduler {

    private const val EXTRA_ID = "preset_id"
    private const val EXTRA_AT = "trigger_at"
    const val EXTRA_START_ID = "start_preset_id"
    private const val EXTRA_TEST = "test_start"
    private const val TEST_REQUEST = 0x7E57
    private const val STATE = "schedule_state"
    private const val START_CHANNEL = "scheduled_start"

    /** A start that was missed (phone asleep, app blocked, restarted…) is still run if found within this window. */
    const val GRACE_MS = 15 * 60_000L

    /** True if a start armed for [armedAt] was missed but is recent enough to run now. */
    fun isMissedButRecent(armedAt: Long, now: Long): Boolean =
        armedAt in 1..now && now - armedAt <= GRACE_MS

    // ---------- When is the next start? ----------

    /** The next start strictly after [after], or null if there is none (e.g. a past one-off). */
    fun next(p: Preset, after: LocalDateTime): LocalDateTime? {
        val time = LocalTime.of(p.hour, p.minute)
        val today = after.toLocalDate()
        fun ok(d: LocalDate) = d.atTime(time).takeIf { it.isAfter(after) }

        return when (p.repeat) {
            Repeat.ONCE -> ok(p.date)

            Repeat.DAILY -> ok(today) ?: today.plusDays(1).atTime(time)

            Repeat.WEEKLY -> {
                val from = if (p.date.isAfter(today)) p.date else today
                val first = from.with(TemporalAdjusters.nextOrSame(p.date.dayOfWeek))
                ok(first) ?: first.plusWeeks(1).atTime(time)
            }

            Repeat.MONTHLY -> {
                val start = YearMonth.from(if (p.date.isAfter(today)) p.date else today)
                (0L..13L).firstNotNullOfOrNull { k ->
                    val ym = start.plusMonths(k)
                    // Day 31 in a shorter month falls on that month's last day.
                    ok(ym.atDay(minOf(p.date.dayOfMonth, ym.lengthOfMonth())))
                }
            }

            Repeat.DAYS -> if (p.days == 0) null else
                (0L..7L).firstNotNullOfOrNull { k ->
                    val d = today.plusDays(k)
                    if (hasDay(p.days, d.dayOfWeek)) ok(d) else null
                }
        }
    }

    fun nextMillis(p: Preset, afterMillis: Long = System.currentTimeMillis()): Long? {
        val zone = ZoneId.systemDefault()
        val after = LocalDateTime.ofInstant(Instant.ofEpochMilli(afterMillis), zone)
        return next(p, after)?.atZone(zone)?.toInstant()?.toEpochMilli()
    }

    fun hasDay(mask: Int, d: DayOfWeek) = mask and (1 shl (d.value - 1)) != 0

    // ---------- Android alarms ----------

    /** Sets (or clears) the alarm for one preset. Returns the start time set, if any. */
    fun schedule(context: Context, p: Preset, afterMillis: Long = System.currentTimeMillis()): Long? {
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = pending(context, p.id)
        am.cancel(pi)
        if (!p.scheduleOn) return null
        val at = nextMillis(p, afterMillis) ?: return null

        val show = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        if (canScheduleExact(context)) {
            // Alarm-clock alarms fire on time even in deep sleep / Doze.
            am.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), pending(context, p.id, at))
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(context, p.id, at))
        }
        if (armed(context, p.id) != at) ScheduleLog.add(context, "Set “${p.name}” for ${whenText(at)}")
        setArmed(context, p.id, at)
        return at
    }

    /** The soonest scheduled start among all timers, or null if nothing is scheduled. */
    fun nextStart(context: Context): Pair<Preset, Long>? =
        PresetStore(context).all()
            .filter { it.scheduleOn }
            .mapNotNull { p -> nextMillis(p)?.let { p to it } }
            .minByOrNull { it.second }

    fun whenText(millis: Long): String =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("EEE d MMM, HH:mm"))

    /** A one-off 3 × 10-second test run that starts in one minute, exactly like a scheduled timer. */
    fun scheduleTest(context: Context): Long {
        val at = System.currentTimeMillis() + 60_000L
        val pi = PendingIntent.getBroadcast(
            context, TEST_REQUEST,
            Intent(context, AlarmReceiver::class.java)
                .setAction("com.example.intervaltimer.TEST_START")
                .putExtra(EXTRA_TEST, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val am = context.getSystemService(AlarmManager::class.java)
        val show = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        if (canScheduleExact(context)) am.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        ScheduleLog.add(context, "Test set for ${whenText(at).substringAfter(", ")}")
        return at
    }

    private fun testPreset(context: Context) = Preset(
        id = -1L, name = context.getString(R.string.test_timer_name),
        intervalMs = 10_000L, rounds = 3,
    )

    fun cancel(context: Context, id: Long) {
        context.getSystemService(AlarmManager::class.java).cancel(pending(context, id))
        setArmed(context, id, 0L)
    }

    /**
     * Runs any start that was missed in the last [GRACE_MS], then re-arms every schedule.
     * Called when the app opens, after a reboot / app update, and when the clock changes.
     */
    fun scheduleAll(context: Context) {
        val store = PresetStore(context)
        val now = System.currentTimeMillis()
        store.all().forEach { p ->
            if (!p.scheduleOn) { setArmed(context, p.id, 0L); return@forEach }
            val armedAt = armed(context, p.id)
            if (isMissedButRecent(armedAt, now)) {
                ScheduleLog.add(context, "Missed start found for “${p.name}” (due ${whenText(armedAt)}) – starting now")
                startNow(context, p)
                afterStart(context, store, p, armedAt)
            } else if (schedule(context, p) == null && p.repeat == Repeat.ONCE) {
                store.save(p.copy(scheduleOn = false))   // one-off whose time has passed
                setArmed(context, p.id, 0L)
            }
        }
    }

    // ---------- Starting a scheduled timer ----------

    /**
     * Starts [p] in the background. If Android (or the phone maker's battery manager)
     * refuses a background start, falls back to a full-screen alarm notification that
     * opens the app and starts the timer from there.
     */
    fun startNow(context: Context, p: Preset) {
        TimerService.load(context)
        if (TimerService.isRunning) {
            ScheduleLog.add(context, "Skipped “${p.name}”: another timer is running")
            return
        }
        try {
            context.startForegroundService(TimerService.startIntent(context, p, scheduled = true))
            ScheduleLog.add(context, "Started “${p.name}”")
        } catch (e: Exception) {
            ScheduleLog.add(context, "Phone blocked background start (${e.javaClass.simpleName}) – showed alert")
            showStartNotification(context, p)
        }
    }

    /** After a start: a one-off is switched off, a repeating one is armed for its next time. */
    private fun afterStart(context: Context, store: PresetStore, p: Preset, firedFor: Long) {
        if (p.repeat == Repeat.ONCE) {
            store.save(p.copy(scheduleOn = false))
            setArmed(context, p.id, 0L)
        } else {
            // Look for the next start after this one, even if this alarm fired a little early.
            schedule(context, p, maxOf(System.currentTimeMillis(), firedFor + 1000L))
        }
    }

    private fun showStartNotification(context: Context, p: Preset) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(START_CHANNEL, context.getString(R.string.start_channel),
                NotificationManager.IMPORTANCE_HIGH).apply { setBypassDnd(true) }
        )
        val open = PendingIntent.getActivity(
            context, requestCode(p.id),
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_START_ID, p.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(context, START_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(p.name)
            .setContentText(context.getString(R.string.start_notification_text))
            .setCategory(Notification.CATEGORY_ALARM)
            .setColor(0xFFFF6B4A.toInt())
            .setContentIntent(open)
            .setFullScreenIntent(open, true)   // opens the app (even over the lock screen) and starts the timer
            .setAutoCancel(true)
            .build()
        nm.notify(startNotificationId(p.id), n)
    }

    fun startNotificationId(id: Long) = 1000 + (requestCode(id) and 0xFFFF)

    // ---------- Armed start times (to detect missed starts) ----------

    private fun armed(context: Context, id: Long) =
        context.getSharedPreferences(STATE, Context.MODE_PRIVATE).getLong("armed_$id", 0L)

    private fun setArmed(context: Context, id: Long, at: Long) {
        context.getSharedPreferences(STATE, Context.MODE_PRIVATE).edit().apply {
            if (at > 0L) putLong("armed_$id", at) else remove("armed_$id")
        }.commit()
    }

    fun canScheduleExact(context: Context): Boolean =
        Build.VERSION.SDK_INT < 31 ||
            context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    private fun pending(context: Context, id: Long, at: Long = 0L): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java)
            .setAction("com.example.intervaltimer.SCHEDULED_START")
            .putExtra(EXTRA_ID, id)
            .putExtra(EXTRA_AT, at)
        return PendingIntent.getBroadcast(
            context, requestCode(id), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun requestCode(id: Long) = (id xor (id ushr 32)).toInt()

    // ---------- Text for the screens ----------

    fun timeText(context: Context, hour: Int, minute: Int): String =
        LocalTime.of(hour, minute).format(
            DateTimeFormatter.ofPattern(if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a")
        )

    fun dateText(d: LocalDate): String =
        d.format(DateTimeFormatter.ofPattern("EEE, d MMM yyyy"))

    fun repeatText(context: Context, r: Repeat): String = context.getString(
        when (r) {
            Repeat.ONCE -> R.string.repeat_once
            Repeat.DAILY -> R.string.repeat_daily
            Repeat.WEEKLY -> R.string.repeat_weekly
            Repeat.MONTHLY -> R.string.repeat_monthly
            Repeat.DAYS -> R.string.repeat_days
        }
    )

    fun daysText(context: Context, mask: Int): String = when (mask) {
        Preset.ALL_DAYS -> context.getString(R.string.repeat_daily)
        Preset.WEEKDAYS -> context.getString(R.string.weekdays)
        0b1100000 -> context.getString(R.string.weekends)
        else -> AppSettings.orderedDays(AppSettings.weekStart(context)).filter { hasDay(mask, it) }
            .joinToString(", ") { it.getDisplayName(TextStyle.SHORT, Locale.getDefault()) }
    }

    /** e.g. "Every day at 06:30", "Every Thursday at 06:30", "Mon, Wed, Fri at 06:30". */
    fun describe(context: Context, p: Preset): String {
        val t = timeText(context, p.hour, p.minute)
        return when (p.repeat) {
            Repeat.ONCE -> context.getString(R.string.desc_once, dateText(p.date), t)
            Repeat.DAILY -> context.getString(R.string.desc_daily, t)
            Repeat.WEEKLY -> context.getString(R.string.desc_weekly,
                p.date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault()), t)
            Repeat.MONTHLY -> context.getString(R.string.desc_monthly, ordinal(p.date.dayOfMonth), t)
            Repeat.DAYS -> context.getString(R.string.desc_days, daysText(context, p.days), t)
        }
    }

    /** e.g. "Today at 06:30", "Tomorrow at 06:30", "Mon, 26 Oct at 06:30". */
    fun nextText(context: Context, p: Preset): String? {
        val millis = nextMillis(p) ?: return null
        val dt = LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault())
        val today = LocalDate.now()
        val day = when (dt.toLocalDate()) {
            today -> context.getString(R.string.today)
            today.plusDays(1) -> context.getString(R.string.tomorrow)
            else -> dt.format(DateTimeFormatter.ofPattern(
                if (dt.year == today.year) "EEE, d MMM" else "EEE, d MMM yyyy"))
        }
        return context.getString(R.string.next_at, day, timeText(context, dt.hour, dt.minute))
    }

    fun ordinal(n: Int): String {
        val suffix = if (n % 100 in 11..13) "th" else when (n % 10) {
            1 -> "st"; 2 -> "nd"; 3 -> "rd"; else -> "th"
        }
        return "$n$suffix"
    }

    // ---------- Receivers ----------

    /** Fires at the scheduled time: starts the timer and sets the next alarm. */
    class AlarmReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.getBooleanExtra(EXTRA_TEST, false)) {
                ScheduleLog.add(context, "Test alarm fired")
                startNow(context, testPreset(context))
                return
            }
            val store = PresetStore(context)
            val p = store.get(intent.getLongExtra(EXTRA_ID, -1L)) ?: return
            if (!p.scheduleOn) return
            ScheduleLog.add(context, "Alarm fired for “${p.name}”")
            startNow(context, p)
            afterStart(context, store, p, intent.getLongExtra(EXTRA_AT, 0L))
        }
    }

    /** Re-arms schedules after a reboot, app update, or time / time-zone change. */
    class RescheduleReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_BOOT_COMPLETED) ScheduleLog.add(context, "Phone restarted – schedules re-armed")
            scheduleAll(context)
            TimerService.resume(context)   // continue a timer that was running before the restart
            TimerService.refreshStandby(context)
        }
    }
}
