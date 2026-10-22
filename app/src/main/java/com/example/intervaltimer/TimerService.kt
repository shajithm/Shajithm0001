package com.example.intervaltimer

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Runs one continuous clock of [rounds] x [roundMs] (from seconds up to many days).
 *
 * Everything is measured from a single start time, so playing an alarm never pauses
 * or shifts the countdown. The run is saved to storage, and an exact "alarm clock"
 * alarm wakes the phone at every round boundary, so long runs keep going through
 * deep sleep, the app being closed, and even a phone restart.
 */
class TimerService : Service() {

    companion object {
        const val EXTRA_INTERVAL_MS = "interval_ms"
        const val EXTRA_ROUNDS = "rounds"
        const val EXTRA_NAME = "name"
        const val EXTRA_SCHEDULED = "scheduled"
        const val ACTION_START = "com.example.intervaltimer.START"
        const val ACTION_STOP = "com.example.intervaltimer.STOP"
        const val ACTION_TICK = "com.example.intervaltimer.TICK"
        const val ACTION_RESTORE = "com.example.intervaltimer.RESTORE"

        private const val MAX_ALARM_MS = 1500
        private const val LATE_LIMIT_MS = 60_000L   // don't sound a round alarm missed by more than this
        private const val CHANNEL_ID = "interval_timer"
        private const val NOTIFICATION_ID = 1
        private const val PREFS = "run_state"

        /** Wall-clock start of the current run (ms); 0 when idle. */
        @Volatile var startedAt = 0L
            private set
        @Volatile var finished = false
            private set
        @Volatile var roundMs = 3 * 60_000L
            private set
        @Volatile var rounds = 10
            private set
        @Volatile var name = ""
            private set
        @Volatile private var played = 0
        @Volatile var serviceAlive = false
            private set

        val isRunning get() = startedAt != 0L
        val totalMs get() = roundMs * rounds

        fun clearFinished() { finished = false }

        fun elapsed(): Long =
            if (startedAt == 0L) { if (finished) totalMs else 0L }
            else (System.currentTimeMillis() - startedAt).coerceIn(0L, totalMs)

        /** 1-based round currently counting down. */
        fun currentRound(elapsed: Long): Int =
            (elapsed / roundMs + 1).toInt().coerceAtMost(rounds)

        /** Time left in the current round. */
        fun roundRemaining(elapsed: Long): Long =
            if (elapsed >= totalMs) 0L else roundMs - elapsed % roundMs

        fun format(ms: Long): String = Durations.clock(ms)

        /** Reloads a run saved before the app was closed or the phone restarted. */
        fun load(context: Context) {
            if (isRunning) return
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (!p.getBoolean("active", false)) return
            name = p.getString("name", "") ?: ""
            roundMs = p.getLong("roundMs", roundMs)
            rounds = p.getInt("rounds", rounds)
            played = p.getInt("played", 0)
            finished = false
            startedAt = p.getLong("start", 0L)
        }

        fun hasSavedRun(context: Context) =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("active", false)

        /** Starts the service to continue a saved run (after restart / app being killed). */
        fun resume(context: Context) {
            if (hasSavedRun(context)) {
                context.startForegroundService(
                    Intent(context, TimerService::class.java).setAction(ACTION_RESTORE)
                )
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var tone: ToneGenerator? = null
    private var longWakeLock: PowerManager.WakeLock? = null
    private var armedFor = 0L

    private val tick = Runnable { check() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        serviceAlive = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopRun(); return START_NOT_STICKY }
            ACTION_START -> {
                // Always enter the foreground first, as Android requires.
                goForeground()
                if (!isRunning) begin(
                    intent?.getStringExtra(EXTRA_NAME) ?: "",
                    intent?.getLongExtra(EXTRA_INTERVAL_MS, roundMs) ?: roundMs,
                    intent?.getIntExtra(EXTRA_ROUNDS, rounds) ?: rounds,
                    intent?.getBooleanExtra(EXTRA_SCHEDULED, false) ?: false
                ) else check()
            }
            else -> {   // TICK, RESTORE, or a restart by the system (null intent)
                load(this)
                goForeground()
                if (!isRunning) { shutdown(keepNotification = false); return START_NOT_STICKY }
                check()
            }
        }
        return START_STICKY
    }

    private fun begin(presetName: String, intervalMs: Long, count: Int, scheduled: Boolean) {
        name = presetName
        roundMs = intervalMs.coerceIn(Durations.MIN_INTERVAL_MS, Durations.MAX_INTERVAL_MS)
        rounds = count.coerceIn(1, 99)
        played = 0
        finished = false
        startedAt = System.currentTimeMillis()
        save()
        if (scheduled) {
            // A scheduled timer starts by itself, so give a short signal that it has begun.
            shortWake()
            tone()?.startTone(ToneGenerator.TONE_PROP_BEEP2, 400)
        }
        if (!Scheduler.canScheduleExact(this)) {
            // Without exact alarms, keep the CPU awake instead (fine for shorter runs).
            longWakeLock = wakeLock("IntervalTimer::run").apply { acquire(totalMs + 60_000L) }
        }
        check()
    }

    /** Plays any alarm that is due, arms the next one, and refreshes the notification. */
    private fun check() {
        handler.removeCallbacks(tick)
        if (!isRunning) return
        val now = System.currentTimeMillis()
        val e = elapsed()
        val due = (e / roundMs).toInt().coerceAtMost(rounds)
        if (due > played) {
            val lateBy = now - (startedAt + due * roundMs)
            played = due
            save()
            if (lateBy < LATE_LIMIT_MS) playAlarm()
        }
        if (e >= totalMs) { finish(); return }

        val nextAt = startedAt + (played + 1) * roundMs
        armBoundary(nextAt)
        updateNotification(nextAt)
        // Backup check while the phone is awake; the exact alarm covers deep sleep.
        handler.postDelayed(tick, (nextAt - now + 20L).coerceIn(50L, 60_000L))
    }

    private fun finish() {
        finished = true
        startedAt = 0L
        clearSaved()
        cancelBoundary()
        updateNotification(0L)
        // Let the final alarm finish sounding before shutting down.
        handler.postDelayed({ shutdown(keepNotification = true) }, MAX_ALARM_MS + 500L)
    }

    private fun stopRun() {
        startedAt = 0L
        finished = false
        clearSaved()
        cancelBoundary()
        shutdown(keepNotification = false)
    }

    // ---------- Round-boundary alarm ----------

    private fun boundaryIntent(): PendingIntent = PendingIntent.getForegroundService(
        this, 100, Intent(this, TimerService::class.java).setAction(ACTION_TICK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun armBoundary(at: Long) {
        if (at == armedFor) return
        val am = getSystemService(AlarmManager::class.java)
        if (Scheduler.canScheduleExact(this)) {
            val show = PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
            )
            am.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), boundaryIntent())
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, boundaryIntent())
        }
        armedFor = at
    }

    private fun cancelBoundary() {
        getSystemService(AlarmManager::class.java).cancel(boundaryIntent())
        armedFor = 0L
    }

    // ---------- Sound ----------

    private fun tone(): ToneGenerator? {
        if (tone == null) {
            tone = runCatching { ToneGenerator(AudioManager.STREAM_ALARM, ToneGenerator.MAX_VOLUME) }.getOrNull()
        }
        return tone
    }

    private fun playAlarm() {
        shortWake()
        // Fire-and-forget: the tone plays on its own while the countdown continues.
        val length = MAX_ALARM_MS.toLong().coerceAtMost(roundMs / 3).toInt()
        tone()?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, length)
        vibrator()?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 400, 200, 400), -1))
    }

    /** Keeps the CPU awake just long enough for the alarm to sound. */
    private fun shortWake() = wakeLock("IntervalTimer::alarm").acquire(MAX_ALARM_MS + 2_000L)

    private fun wakeLock(tag: String) =
        (getSystemService(Context.POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, tag)

    private fun vibrator(): Vibrator? =
        if (Build.VERSION.SDK_INT >= 31) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

    // ---------- Saved state ----------

    private fun save() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putBoolean("active", true)
            .putString("name", name)
            .putLong("roundMs", roundMs)
            .putInt("rounds", rounds)
            .putLong("start", startedAt)
            .putInt("played", played)
            .commit()
    }

    private fun clearSaved() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().clear().commit()
    }

    // ---------- Lifecycle ----------

    private fun shutdown(keepNotification: Boolean) {
        handler.removeCallbacksAndMessages(null)
        tone?.release()
        tone = null
        longWakeLock?.takeIf { it.isHeld }?.release()
        longWakeLock = null
        stopForeground(if (keepNotification) STOP_FOREGROUND_DETACH else STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        serviceAlive = false
        handler.removeCallbacksAndMessages(null)
        tone?.release()
        longWakeLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    // ---------- Notification ----------

    private fun goForeground() {
        createChannel()
        val n = buildNotification(if (isRunning) startedAt + (played + 1) * roundMs else 0L)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW
        ).apply { setShowBadge(false) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /** [nextAt] = time of the next round alarm; the notification counts down to it by itself. */
    private fun buildNotification(nextAt: Long): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, TimerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val text = when {
            finished -> getString(R.string.done_notification, rounds)
            isRunning -> getString(R.string.notification_round,
                (played + 1).coerceAtMost(rounds), rounds, Durations.human(roundMs))
            else -> getString(R.string.app_name)
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(name.ifBlank { getString(R.string.app_name) })
            .setContentText(text)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(!finished)
            .setColor(0xFFFF6B4A.toInt())
            .setCategory(Notification.CATEGORY_ALARM)
            .apply {
                if (isRunning && nextAt > 0L) {
                    setWhen(nextAt)
                    setShowWhen(true)
                    setUsesChronometer(true)
                    setChronometerCountDown(true)
                }
                if (!finished) addAction(Notification.Action.Builder(null, getString(R.string.stop), stop).build())
            }
            .build()
    }

    private fun updateNotification(nextAt: Long) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(nextAt))
    }
}
