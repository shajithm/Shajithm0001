package com.example.intervaltimer

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var roundText: TextView
    private lateinit var countdownText: TextView
    private lateinit var totalText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var button: Button

    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 200L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        roundText = findViewById(R.id.round)
        countdownText = findViewById(R.id.countdown)
        totalText = findViewById(R.id.total)
        progress = findViewById(R.id.progress)
        button = findViewById(R.id.button)
        progress.max = (TimerService.TOTAL_MS / 1000).toInt()

        button.setOnClickListener {
            if (TimerService.isRunning) {
                startService(Intent(this, TimerService::class.java).setAction(TimerService.ACTION_STOP))
            } else {
                startForegroundService(Intent(this, TimerService::class.java).setAction(TimerService.ACTION_START))
            }
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    private fun render() {
        when {
            TimerService.isRunning -> {
                val e = TimerService.elapsed()
                roundText.text = getString(R.string.round_of, TimerService.currentRound(e), TimerService.ROUNDS)
                countdownText.text = TimerService.format(TimerService.roundRemaining(e))
                totalText.text = getString(R.string.total_left, TimerService.format(TimerService.TOTAL_MS - e))
                progress.progress = (e / 1000).toInt()
                button.text = getString(R.string.stop)
            }
            TimerService.finished -> {
                roundText.text = getString(R.string.done)
                countdownText.text = TimerService.format(0)
                totalText.text = getString(R.string.total_left, TimerService.format(0))
                progress.progress = progress.max
                button.text = getString(R.string.start_again)
            }
            else -> {
                roundText.text = getString(R.string.round_of, 1, TimerService.ROUNDS)
                countdownText.text = TimerService.format(TimerService.ROUND_MS)
                totalText.text = getString(R.string.total_left, TimerService.format(TimerService.TOTAL_MS))
                progress.progress = 0
                button.text = getString(R.string.start)
            }
        }
    }
}
