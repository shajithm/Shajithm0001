package com.example.intervaltimer

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
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Runs one continuous clock of [rounds] x [roundMs].
 *
 * The countdown is derived from a single start timestamp, so playing the alarm
 * never pauses or delays it: at every interval mark a short alarm plays while
 * the clock keeps running straight into the next round.
 */
class TimerService : Service() {

    companion object {
        const val EXTRA_INTERVAL_MS = "interval_ms"
        const val EXTRA_ROUNDS = "rounds"
        const val EXTRA_NAME = "name"
        const val EXTRA_SCHEDULED = "scheduled"
        const val ACTION_START = "com.example.intervaltimer.START"
        const val ACTION_STOP = "com.example.intervaltimer.STOP"

        private const val MAX_ALARM_MS = 1500
        private const val CHANNEL_ID = "interval_timer"
        private const val NOTIFICATION_ID = 1

        /** elapsedRealtime() when the run began; 0 when idle. */
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

        val isRunning get() = startedAt != 0L
        val totalMs get() = roundMs * rounds

        fun clearFinished() { finished = false }

        fun elapsed(): Long =
            if (startedAt == 0L) { if (finished) totalMs else 0L }
            else (SystemClock.elapsedRealtime() - startedAt).coerceAtMost(totalMs)

        /** 1-based round currently counting down. */
        fun currentRound(elapsed: Long): Int =
            (elapsed / roundMs + 1).toInt().coerceAtMost(rounds)

        /** Time left in the current round. */
        fun roundRemaining(elapsed: Long): Long =
            if (elapsed >= totalMs) 0L else roundMs - elapsed % roundMs

        /** mm:ss (or h:mm:ss), rounded up so a fresh 3:00 round reads 03:00. */
        fun format(ms: Long): String {
            val s = (ms + 999) / 1000
            val h = s / 3600
            return if (h > 0) "%d:%02d:%02d".format(h, (s % 3600) / 60, s % 60)
            else "%02d:%02d".format(s / 60, s % 60)
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var tone: ToneGenerator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var alarmsPlayed = 0

    private val tick = object : Runnable {
        override fun run() {
            val elapsed = elapsed()
            val due = (elapsed / roundMs).toInt().coerceAtMost(rounds)
            if (due > alarmsPlayed) {
                alarmsPlayed = due
                playAlarm()
            }
            if (elapsed >= totalMs) {
                finished = true
                startedAt = 0L
                updateNotification(getString(R.string.done_notification, rounds))
                // Let the final alarm finish sounding before shutting down.
                handler.postDelayed({ shutdown(keepNotification = true) }, MAX_ALARM_MS + 500L)
                return
            }
            updateNotification(
                getString(R.string.notification_progress,
                    currentRound(elapsed), rounds, format(roundRemaining(elapsed)))
            )
            // Wake just after the next whole second so round boundaries are hit on time.
            handler.postDelayed(this, 1000L - elapsed % 1000L + 5L)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> shutdown(keepNotification = false)
            else -> if (!isRunning) begin(
                intent?.getStringExtra(EXTRA_NAME) ?: "",
                intent?.getLongExtra(EXTRA_INTERVAL_MS, roundMs) ?: roundMs,
                intent?.getIntExtra(EXTRA_ROUNDS, rounds) ?: rounds,
                intent?.getBooleanExtra(EXTRA_SCHEDULED, false) ?: false
            )
        }
        return START_NOT_STICKY
    }

    private fun begin(presetName: String, intervalMs: Long, count: Int, scheduled: Boolean) {
        name = presetName
        roundMs = intervalMs.coerceAtLeast(5_000L)
        rounds = count.coerceIn(1, 99)

        createChannel()
        val notification = buildNotification(
            getString(R.string.notification_progress, 1, rounds, format(roundMs))
        )
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "IntervalTimer::run")
            .apply { acquire(totalMs + 60_000L) }

        tone = ToneGenerator(AudioManager.STREAM_ALARM, ToneGenerator.MAX_VOLUME)
        alarmsPlayed = 0
        finished = false
        startedAt = SystemClock.elapsedRealtime()
        // A scheduled timer starts by itself, so give a short signal that it has begun.
        if (scheduled) tone?.startTone(ToneGenerator.TONE_PROP_BEEP2, 400)
        handler.post(tick)
    }

    private fun playAlarm() {
        // Fire-and-forget: the tone plays on its own while the countdown continues.
        val length = MAX_ALARM_MS.coerceAtMost((roundMs / 3).toInt())
        tone?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, length)
        vibrator()?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 400, 200, 400), -1))
    }

    private fun vibrator(): Vibrator? =
        if (Build.VERSION.SDK_INT >= 31) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

    private fun shutdown(keepNotification: Boolean) {
        handler.removeCallbacksAndMessages(null)
        if (!finished) startedAt = 0L
        tone?.release()
        tone = null
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        stopForeground(if (keepNotification) STOP_FOREGROUND_DETACH else STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        tone?.release()
        wakeLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW
        ).apply { setShowBadge(false) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, TimerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
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
                if (!finished) addAction(Notification.Action.Builder(null, getString(R.string.stop), stop).build())
            }
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }
}
