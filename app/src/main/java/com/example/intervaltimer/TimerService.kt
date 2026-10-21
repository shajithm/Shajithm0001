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
 * Runs one continuous 30-minute clock (10 rounds x 3 minutes).
 *
 * The countdown is derived from a single start timestamp, so playing the alarm
 * never pauses or delays it: at every 3-minute mark a short alarm plays while
 * the clock keeps running straight into the next round.
 */
class TimerService : Service() {

    companion object {
        const val ROUNDS = 10
        const val ROUND_MS = 3 * 60 * 1000L
        const val TOTAL_MS = ROUNDS * ROUND_MS
        const val ALARM_MS = 1500           // how long each alarm sounds

        const val ACTION_START = "com.example.intervaltimer.START"
        const val ACTION_STOP = "com.example.intervaltimer.STOP"

        private const val CHANNEL_ID = "interval_timer"
        private const val NOTIFICATION_ID = 1

        /** elapsedRealtime() when the run began; 0 when idle. */
        @Volatile var startedAt = 0L
            private set
        @Volatile var finished = false
            private set

        val isRunning get() = startedAt != 0L

        fun elapsed(): Long =
            if (startedAt == 0L) 0L
            else (SystemClock.elapsedRealtime() - startedAt).coerceAtMost(TOTAL_MS)

        /** 1-based round currently counting down. */
        fun currentRound(elapsed: Long): Int =
            (elapsed / ROUND_MS + 1).toInt().coerceAtMost(ROUNDS)

        /** Time left in the current round. */
        fun roundRemaining(elapsed: Long): Long =
            if (elapsed >= TOTAL_MS) 0L else ROUND_MS - elapsed % ROUND_MS

        fun format(ms: Long): String {
            val totalSec = (ms + 999) / 1000      // round up so it reads 03:00 at start
            return "%02d:%02d".format(totalSec / 60, totalSec % 60)
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var tone: ToneGenerator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var alarmsPlayed = 0

    private val tick = object : Runnable {
        override fun run() {
            val elapsed = elapsed()
            val due = (elapsed / ROUND_MS).toInt().coerceAtMost(ROUNDS)
            if (due > alarmsPlayed) {
                alarmsPlayed = due
                playAlarm()
            }
            if (elapsed >= TOTAL_MS) {
                finished = true
                startedAt = 0L
                updateNotification("All $ROUNDS rounds complete")
                // Let the final alarm finish sounding before shutting down.
                handler.postDelayed({ shutdown(keepNotification = true) }, ALARM_MS + 500L)
                return
            }
            updateNotification(
                "Round ${currentRound(elapsed)} of $ROUNDS · ${format(roundRemaining(elapsed))} left"
            )
            // Wake just after the next whole second so round boundaries are hit on time.
            handler.postDelayed(this, 1000L - elapsed % 1000L + 5L)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> shutdown(keepNotification = false)
            else -> if (!isRunning) begin()
        }
        return START_NOT_STICKY
    }

    private fun begin() {
        createChannel()
        val notification = buildNotification("Round 1 of $ROUNDS · ${format(ROUND_MS)} left")
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "IntervalTimer::run")
            .apply { acquire(TOTAL_MS + 60_000L) }

        tone = ToneGenerator(AudioManager.STREAM_ALARM, ToneGenerator.MAX_VOLUME)
        alarmsPlayed = 0
        finished = false
        startedAt = SystemClock.elapsedRealtime()
        handler.post(tick)
    }

    private fun playAlarm() {
        // Fire-and-forget: the tone plays on its own while the countdown continues.
        tone?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, ALARM_MS)
        vibrator()?.vibrate(
            VibrationEffect.createWaveform(longArrayOf(0, 400, 200, 400), -1)
        )
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
            CHANNEL_ID, "Interval timer", NotificationManager.IMPORTANCE_LOW
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
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(!finished)
            .setCategory(Notification.CATEGORY_ALARM)
            .apply {
                if (!finished) addAction(
                    Notification.Action.Builder(null, "Stop", stop).build()
                )
            }
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }
}
