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
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
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
 * Runs one continuous clock through a list of rounds, each with its own length
 * (from seconds up to many days).
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
        const val EXTRA_LENGTHS = "lengths"
        const val EXTRA_NAME = "name"
        const val EXTRA_SCHEDULED = "scheduled"
        const val EXTRA_SOUND = "sound"
        const val EXTRA_FLOATING = "floating"
        const val EXTRA_VOICE = "voice"
        const val ACTION_START = "com.example.intervaltimer.START"
        const val ACTION_STOP = "com.example.intervaltimer.STOP"
        const val ACTION_PAUSE = "com.example.intervaltimer.PAUSE"
        const val ACTION_RESUME = "com.example.intervaltimer.RESUME"
        const val ACTION_TICK = "com.example.intervaltimer.TICK"
        const val ACTION_RESTORE = "com.example.intervaltimer.RESTORE"
        /** Keep the app alive and ready while a scheduled start is waiting. */
        const val ACTION_STANDBY = "com.example.intervaltimer.STANDBY"
        private const val STANDBY_CHANNEL = "standby"
        private const val DONE_NOTIFICATION_ID = 2

        private const val BEEP_MS = 1500L       // built-in beep length
        private const val RINGTONE_MS = 3000L   // how long a chosen ringtone plays each round
        private const val LATE_LIMIT_MS = 60_000L   // don't sound a round alarm missed by more than this
        private const val CHANNEL_ID = "interval_timer"
        private const val NOTIFICATION_ID = 1
        private const val PREFS = "run_state"

        /** Wall-clock start of the current run (ms); 0 when idle. */
        @Volatile var startedAt = 0L
            private set
        @Volatile var finished = false
            private set
        /** The rounds of the current (or last) run. */
        @Volatile var plan = RoundPlan(listOf(3 * 60_000L))
            private set
        val rounds get() = plan.rounds
        @Volatile var name = ""
            private set
        @Volatile var sound = ""
            private set
        @Volatile var floating = false
            private set
        @Volatile var voice = VoiceChoice.OFF
            private set

        /** Intent that starts [p] (from the app, or from a scheduled alarm). */
        fun startIntent(context: Context, p: Preset, scheduled: Boolean = false): Intent =
            Intent(context, TimerService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_NAME, p.name)
                .putExtra(EXTRA_SOUND, p.sound)
                .putExtra(EXTRA_LENGTHS, p.lengths.toLongArray())
                .putExtra(EXTRA_FLOATING, p.floating)
                .putExtra(EXTRA_VOICE, p.voice.name)
                .putExtra(EXTRA_SCHEDULED, scheduled)
        @Volatile private var played = 0
        @Volatile var serviceAlive = false
            private set
        @Volatile var inStandby = false
            private set

        /**
         * Starts (or stops) "stay ready" mode to match the current schedules and setting.
         * Call from the app, after a reboot, or whenever schedules change.
         */
        fun refreshStandby(context: Context) {
            val needed = AppSettings.stayReady(context) && Scheduler.nextStart(context) != null
            if (needed || inStandby) {
                runCatching {
                    context.startForegroundService(Intent(context, TimerService::class.java).setAction(ACTION_STANDBY))
                }.onFailure { ScheduleLog.add(context, "Couldn’t start ready mode (${it.javaClass.simpleName})") }
            }
        }

        val isRunning get() = startedAt != 0L

        /** Wall-clock time the run was paused at; 0 when not paused. */
        @Volatile var pausedAt = 0L
            private set
        val isPaused get() = isRunning && pausedAt != 0L

        /** Pause or resume the running timer. */
        fun togglePause(context: Context) {
            if (!isRunning) return
            context.startService(Intent(context, TimerService::class.java)
                .setAction(if (isPaused) ACTION_RESUME else ACTION_PAUSE))
        }
        val totalMs get() = plan.totalMs

        fun clearFinished() { finished = false }

        fun elapsed(): Long =
            if (startedAt == 0L) { if (finished) totalMs else 0L }
            else ((if (pausedAt != 0L) pausedAt else System.currentTimeMillis()) - startedAt).coerceIn(0L, totalMs)

        /** 1-based round currently counting down. */
        fun currentRound(elapsed: Long): Int = plan.current(elapsed)

        /** Length of the round counting down at [elapsed]. */
        fun currentRoundLength(elapsed: Long): Long = plan.lengthOf(plan.current(elapsed))

        /** Time left in the current round. */
        fun roundRemaining(elapsed: Long): Long = plan.remaining(elapsed)

        fun format(ms: Long): String = Durations.clock(ms)

        /** Reloads a run saved before the app was closed or the phone restarted. */
        fun load(context: Context) {
            if (isRunning) return
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (!p.getBoolean("active", false)) return
            name = p.getString("name", "") ?: ""
            sound = p.getString("sound", "") ?: ""
            floating = p.getBoolean("floating", false)
            voice = runCatching { VoiceChoice.valueOf(p.getString("voice", "OFF")!!) }.getOrDefault(VoiceChoice.OFF)
            val saved = RoundPlan.decode(p.getString("lengths", null))
            plan = RoundPlan(saved.ifEmpty {
                // run saved by an older version
                List(p.getInt("rounds", 10).coerceAtLeast(1)) { p.getLong("roundMs", 3 * 60_000L) }
            })
            played = p.getInt("played", 0)
            finished = false
            pausedAt = p.getLong("pausedAt", 0L)
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
            ACTION_STOP -> { stopRun(); return if (inStandby) START_STICKY else START_NOT_STICKY }
            ACTION_PAUSE -> { load(this); pause(); return START_STICKY }
            ACTION_RESUME -> { load(this); resume(); return START_STICKY }
            ACTION_STANDBY -> {
                load(this)
                if (isRunning) { goForeground(); check(); return START_STICKY }
                return enterStandbyOrStop()
            }
            ACTION_START -> {
                // Always enter the foreground first, as Android requires.
                goForeground()
                if (!isRunning) begin(
                    intent?.getStringExtra(EXTRA_NAME) ?: "",
                    intent?.getStringExtra(EXTRA_SOUND) ?: "",
                    intent?.getLongArrayExtra(EXTRA_LENGTHS)?.toList() ?: emptyList(),
                    intent?.getBooleanExtra(EXTRA_SCHEDULED, false) ?: false,
                    intent?.getBooleanExtra(EXTRA_FLOATING, false) ?: false,
                    runCatching { VoiceChoice.valueOf(intent?.getStringExtra(EXTRA_VOICE) ?: "OFF") }
                        .getOrDefault(VoiceChoice.OFF)
                ) else check()
            }
            else -> {   // TICK, RESTORE, or a restart by the system (null intent)
                load(this)
                if (!isRunning) return enterStandbyOrStop()
                goForeground()
                check()
                if (!FloatingWindow.appVisible) FloatingWindow.show(this)
            }
        }
        return START_STICKY
    }

    private fun begin(
        presetName: String, soundUri: String, lengths: List<Long>, scheduled: Boolean,
        showFloating: Boolean, voiceChoice: VoiceChoice,
    ) {
        inStandby = false
        handler.removeCallbacks(standbyTick)
        pausedAt = 0L
        name = presetName
        sound = soundUri
        floating = showFloating
        voice = voiceChoice
        plan = RoundPlan(lengths.take(99).map {
            it.coerceIn(Durations.MIN_INTERVAL_MS, Durations.MAX_INTERVAL_MS)
        })
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
        announce(Announcements.start(name, plan), if (scheduled) 600L else 0L)
        check()
        if (!FloatingWindow.appVisible) FloatingWindow.show(this)
    }

    /** Plays any alarm that is due, arms the next one, and refreshes the notification. */
    private fun check() {
        handler.removeCallbacks(tick)
        if (!isRunning) return
        if (isPaused) { updateNotification(0L); return }   // clock frozen: no alarms while paused
        val now = System.currentTimeMillis()
        val e = elapsed()
        val due = plan.done(e)
        if (due > played) {
            val lateBy = now - (startedAt + plan.endOf(due))
            played = due
            save()
            if (lateBy < LATE_LIMIT_MS) {
                val alarmLength = playAlarm()
                announce(Announcements.round(due, plan), alarmLength + 300L)
            }
        }
        if (e >= totalMs) { finish(); return }

        val nextAt = startedAt + plan.endOf(played + 1)
        armBoundary(nextAt)
        updateNotification(nextAt)
        // Backup check while the phone is awake; the exact alarm covers deep sleep.
        handler.postDelayed(tick, (nextAt - now + 20L).coerceIn(50L, 60_000L))
    }

    private fun finish() {
        pausedAt = 0L
        finished = true
        startedAt = 0L
        clearSaved()
        cancelBoundary()
        updateNotification(0L)
        // Let the final alarm finish sounding before shutting down.
        // Let the final alarm (and announcement) finish before shutting down.
        val wait = if (voiceOn()) RINGTONE_MS + 7_000L else RINGTONE_MS + 500L
        handler.postDelayed({ afterRunEnds(keepDone = true) }, wait)
    }

    // ---------- Pause / resume ----------

    /** Freezes the countdown exactly where it is. */
    private fun pause() {
        if (!isRunning || isPaused) return
        pausedAt = System.currentTimeMillis()
        save()
        cancelBoundary()
        handler.removeCallbacks(tick)
        handler.removeCallbacks(stopRingtone)
        ringtone?.stop()
        updateNotification(0L)
        announce(getString(R.string.voice_paused), 0L)
    }

    /** Continues from where it was paused; the paused time is simply skipped. */
    private fun resume() {
        if (!isPaused) return
        startedAt += System.currentTimeMillis() - pausedAt
        pausedAt = 0L
        save()
        announce(getString(R.string.voice_resumed), 0L)
        check()
    }

    private fun stopRun() {
        pausedAt = 0L
        startedAt = 0L
        finished = false
        clearSaved()
        cancelBoundary()
        FloatingWindow.hide(this)
        afterRunEnds(keepDone = false)
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

    private var ringtone: Ringtone? = null
    private var ringtoneUri = ""
    private val stopRingtone = Runnable { ringtone?.stop() }

    /** Plays the round alarm and returns how long it sounds (ms). */
    private fun playAlarm(): Long {
        // Fire-and-forget: the sound plays on its own while the countdown continues.
        val ring = customRingtone()
        val length = (if (ring != null) RINGTONE_MS else BEEP_MS).coerceAtMost(plan.lengths.min() / 3)
        shortWake(length + if (voiceOn()) 7_000L else 0L)
        val played = ring != null && runCatching {
            handler.removeCallbacks(stopRingtone)
            ring.stop()
            ring.play()
            handler.postDelayed(stopRingtone, length)
        }.isSuccess
        if (!played) tone()?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, length.toInt())
        vibrator()?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 400, 200, 400), -1))
        return length
    }

    // ---------- Voice ----------

    private var announcer: Announcer? = null

    private fun voiceOn() = voice != VoiceChoice.OFF

    /** Speaks [text] after [delayMs] (so it follows the alarm sound), if voice is on. */
    private fun announce(text: String, delayMs: Long) {
        val choice = voice
        if (choice == VoiceChoice.OFF) return
        val a = announcer ?: Announcer(this).also { announcer = it }
        a.setChoice(choice)
        handler.postDelayed({ a.speak(text) }, delayMs)
    }

    /** The timer's chosen ringtone, or null for the built-in beep (or if it can't be loaded). */
    private fun customRingtone(): Ringtone? {
        if (sound.isBlank()) return null
        if (ringtone == null || ringtoneUri != sound) {
            ringtone?.stop()
            ringtoneUri = sound
            ringtone = runCatching {
                RingtoneManager.getRingtone(this, Uri.parse(sound))?.apply {
                    audioAttributes = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                    if (Build.VERSION.SDK_INT >= 28) isLooping = true   // fill the few seconds, then stop
                }
            }.getOrNull()
        }
        return ringtone
    }

    /** Keeps the CPU awake just long enough for the alarm to sound. */
    private fun shortWake(length: Long = BEEP_MS) = wakeLock("IntervalTimer::alarm").acquire(length + 2_000L)

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
            .putString("sound", sound)
            .putBoolean("floating", floating)
            .putString("voice", voice.name)
            .putString("lengths", plan.encode())
            .putLong("start", startedAt)
            .putInt("played", played)
            .putLong("pausedAt", pausedAt)
            .commit()
    }

    private fun clearSaved() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().clear().commit()
    }

    // ---------- Lifecycle ----------

    // ---------- Stay ready (standby) ----------

    private var loggedStandbyFor = 0L

    /** Backup check at the scheduled time, in case the alarm itself is late or dropped. */
    private val standbyTick = Runnable {
        Scheduler.scheduleAll(this)
        if (!isRunning) enterStandbyOrStop()
    }

    /** After a run ends: go back to waiting for the next scheduled start, or stop. */
    private fun afterRunEnds(keepDone: Boolean) {
        if (AppSettings.stayReady(this) && Scheduler.nextStart(this) != null) {
            if (keepDone && finished) {
                // Keep the "All rounds complete" message as its own notification.
                getSystemService(NotificationManager::class.java)
                    .notify(DONE_NOTIFICATION_ID, buildNotification(0L))
            }
            releaseRun()
            enterStandbyOrStop()
        } else {
            shutdown(keepNotification = keepDone)
        }
    }

    /**
     * Waits in the foreground with a small silent notification until the next scheduled
     * start, so the phone can't stop the app in the meantime. Stops if nothing is scheduled.
     */
    private fun enterStandbyOrStop(): Int {
        val next = Scheduler.nextStart(this)
        val n = standbyNotification(next)
        runCatching {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, n)
            }
        }
        if (!AppSettings.stayReady(this) || next == null) {
            inStandby = false
            shutdown(keepNotification = false)
            return START_NOT_STICKY
        }
        inStandby = true
        if (next.second != loggedStandbyFor) {
            loggedStandbyFor = next.second
            ScheduleLog.add(this, "Ready – waiting for “${next.first.name}” at ${Scheduler.whenText(next.second)}")
        }
        handler.removeCallbacks(standbyTick)
        val wait = next.second - System.currentTimeMillis() + 2_000L
        handler.postDelayed(standbyTick, wait.coerceIn(1_000L, 15 * 60_000L))
        return START_STICKY
    }

    private fun standbyNotification(next: Pair<Preset, Long>?): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(STANDBY_CHANNEL, getString(R.string.standby_channel),
                NotificationManager.IMPORTANCE_MIN).apply { setShowBadge(false) }
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val text = next?.let { (p, _) ->
            p.name + " · " + (Scheduler.nextText(this, p) ?: "")
        } ?: getString(R.string.app_name)
        return Notification.Builder(this, STANDBY_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(getString(R.string.standby_title))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .build()
    }

    /** Frees everything a run used, without stopping the service. */
    private fun releaseRun() {
        handler.removeCallbacksAndMessages(null)
        ringtone?.stop()
        ringtone = null
        announcer?.shutdown()
        announcer = null
        tone?.release()
        tone = null
        longWakeLock?.takeIf { it.isHeld }?.release()
        longWakeLock = null
    }

    private fun shutdown(keepNotification: Boolean) {
        inStandby = false
        handler.removeCallbacksAndMessages(null)
        ringtone?.stop()
        ringtone = null
        announcer?.shutdown()
        announcer = null
        tone?.release()
        tone = null
        longWakeLock?.takeIf { it.isHeld }?.release()
        longWakeLock = null
        stopForeground(if (keepNotification) STOP_FOREGROUND_DETACH else STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        serviceAlive = false
        inStandby = false
        handler.removeCallbacksAndMessages(null)
        ringtone?.stop()
        announcer?.shutdown()
        tone?.release()
        longWakeLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    // ---------- Notification ----------

    private fun goForeground() {
        createChannel()
        val n = buildNotification(if (isRunning) startedAt + plan.endOf(played + 1) else 0L)
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
            isPaused -> getString(R.string.notification_paused,
                currentRound(elapsed()), rounds, Durations.clock(roundRemaining(elapsed())))
            isRunning -> getString(
                if (plan.uniform) R.string.notification_round else R.string.notification_round_custom,
                (played + 1).coerceAtMost(rounds), rounds, Durations.human(plan.lengthOf(played + 1)))
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
                if (isRunning && !isPaused && nextAt > 0L) {
                    setWhen(nextAt)
                    setShowWhen(true)
                    setUsesChronometer(true)
                    setChronometerCountDown(true)
                }
                if (isRunning) {
                    val toggle = PendingIntent.getService(
                        this@TimerService, 3,
                        Intent(this@TimerService, TimerService::class.java)
                            .setAction(if (isPaused) ACTION_RESUME else ACTION_PAUSE),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                    addAction(Notification.Action.Builder(null,
                        getString(if (isPaused) R.string.resume else R.string.pause), toggle).build())
                }
                if (!finished) addAction(Notification.Action.Builder(null, getString(R.string.stop), stop).build())
            }
            .build()
    }

    private fun updateNotification(nextAt: Long) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(nextAt))
    }
}
