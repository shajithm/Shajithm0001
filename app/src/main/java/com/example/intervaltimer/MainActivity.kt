package com.example.intervaltimer

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.TextView

class MainActivity : Activity() {

    companion object {
        private const val STEP_MS = 15_000L
        private const val MIN_INTERVAL_MS = 5_000L
        private const val MAX_INTERVAL_MS = 60 * 60_000L
        private const val MAX_ROUNDS = 99
    }

    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private var intervalMs = 3 * 60_000L
    private var rounds = 10

    private lateinit var setupView: View
    private lateinit var runView: View
    private lateinit var intervalValue: TextView
    private lateinit var roundsValue: TextView
    private lateinit var totalValue: TextView

    private lateinit var ring: ProgressRing
    private lateinit var roundsBar: RoundsBar
    private lateinit var roundLabel: TextView
    private lateinit var countdown: TextView
    private lateinit var countdownCaption: TextView
    private lateinit var totalLeft: TextView
    private lateinit var stopButton: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 50L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        intervalMs = prefs.getLong("interval_ms", intervalMs)
        rounds = prefs.getInt("rounds", rounds)

        setupView = findViewById(R.id.setup)
        runView = findViewById(R.id.run)
        intervalValue = findViewById(R.id.interval_value)
        roundsValue = findViewById(R.id.rounds_value)
        totalValue = findViewById(R.id.total_value)
        ring = findViewById(R.id.ring)
        roundsBar = findViewById(R.id.rounds_bar)
        roundLabel = findViewById(R.id.round_label)
        countdown = findViewById(R.id.countdown)
        countdownCaption = findViewById(R.id.countdown_caption)
        totalLeft = findViewById(R.id.total_left)
        stopButton = findViewById(R.id.stop_button)

        findViewById<View>(R.id.interval_minus).repeatOnHold { changeInterval(-1) }
        findViewById<View>(R.id.interval_plus).repeatOnHold { changeInterval(+1) }
        findViewById<View>(R.id.rounds_minus).repeatOnHold { changeRounds(-1) }
        findViewById<View>(R.id.rounds_plus).repeatOnHold { changeRounds(+1) }
        intervalValue.setOnClickListener { pickInterval() }
        roundsValue.setOnClickListener { pickRounds() }

        findViewById<View>(R.id.start_button).setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            startForegroundService(
                Intent(this, TimerService::class.java)
                    .setAction(TimerService.ACTION_START)
                    .putExtra(TimerService.EXTRA_INTERVAL_MS, intervalMs)
                    .putExtra(TimerService.EXTRA_ROUNDS, rounds)
            )
        }
        stopButton.setOnClickListener {
            if (TimerService.isRunning) {
                startService(Intent(this, TimerService::class.java).setAction(TimerService.ACTION_STOP))
            } else {
                TimerService.clearFinished()
            }
        }

        updateSetup()

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

    // ---- Setup screen ----

    private fun changeInterval(direction: Int) {
        val steps = if (direction > 0) intervalMs / STEP_MS + 1
                    else (intervalMs + STEP_MS - 1) / STEP_MS - 1
        intervalMs = (steps * STEP_MS).coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS)
        saveAndUpdate()
    }

    private fun changeRounds(direction: Int) {
        rounds = (rounds + direction).coerceIn(1, MAX_ROUNDS)
        saveAndUpdate()
    }

    private fun saveAndUpdate() {
        prefs.edit().putLong("interval_ms", intervalMs).putInt("rounds", rounds).apply()
        updateSetup()
    }

    private fun updateSetup() {
        intervalValue.text = TimerService.format(intervalMs)
        roundsValue.text = rounds.toString()
        totalValue.text = TimerService.format(intervalMs * rounds)
    }

    private fun pickInterval() {
        val totalSec = (intervalMs / 1000).toInt()
        val minutes = picker(0, 60, totalSec / 60)
        val seconds = picker(0, 59, totalSec % 60).apply {
            setFormatter { "%02d".format(it) }
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, 0)
            addView(column(minutes, getString(R.string.min)))
            addView(TextView(context).apply {
                text = ":"; textSize = 28f; setPadding(dp(12), 0, dp(12), dp(20))
            })
            addView(column(seconds, getString(R.string.sec)))
        }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(R.string.interval)
            .setView(row)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                intervalMs = ((minutes.value * 60L + seconds.value) * 1000L)
                    .coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS)
                saveAndUpdate()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun pickRounds() {
        val count = picker(1, MAX_ROUNDS, rounds)
        val box = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, 0)
            addView(count)
        }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(R.string.rounds)
            .setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ -> rounds = count.value; saveAndUpdate() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun picker(min: Int, max: Int, value: Int) = NumberPicker(this).apply {
        minValue = min; maxValue = max; this.value = value.coerceIn(min, max)
        descendantFocusability = NumberPicker.FOCUS_BLOCK_DESCENDANTS
    }

    private fun column(picker: NumberPicker, label: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        addView(picker)
        addView(TextView(context).apply { text = label; gravity = Gravity.CENTER })
    }

    // ---- Running / finished screen ----

    private fun render() {
        val running = TimerService.isRunning
        val finished = TimerService.finished
        val showRun = running || finished
        setupView.visibility = if (showRun) View.GONE else View.VISIBLE
        runView.visibility = if (showRun) View.VISIBLE else View.GONE
        if (!showRun) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            return
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val total = TimerService.totalMs
        val n = TimerService.rounds
        val e = TimerService.elapsed()
        roundsBar.rounds = n
        roundsBar.progress = e.toFloat() / TimerService.roundMs

        if (finished) {
            ring.fraction = 1f
            ring.setRingColor(getColor(R.color.success))
            roundLabel.text = getString(R.string.complete)
            countdown.text = getString(R.string.done)
            countdownCaption.text = getString(R.string.rounds_done, n)
            totalLeft.text = getString(R.string.total_time, TimerService.format(total))
            stopButton.text = getString(R.string.new_timer)
        } else {
            val remaining = TimerService.roundRemaining(e)
            ring.setRingColor(getColor(R.color.accent))
            ring.fraction = remaining.toFloat() / TimerService.roundMs
            roundLabel.text = getString(R.string.round_of, TimerService.currentRound(e), n)
            countdown.text = TimerService.format(remaining)
            countdownCaption.text = getString(R.string.this_round)
            totalLeft.text = getString(R.string.total_left, TimerService.format(total - e))
            stopButton.text = getString(R.string.stop)
        }
    }

    // ---- Helpers ----

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** Tap to step once; press and hold to keep stepping. */
    private fun View.repeatOnHold(action: () -> Unit) {
        val repeater = object : Runnable {
            override fun run() { action(); handler.postDelayed(this, 80L) }
        }
        setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            action()
        }
        setOnLongClickListener { handler.post(repeater); true }
        setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                handler.removeCallbacks(repeater)
            }
            false
        }
    }
}
