package com.example.intervaltimer

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.provider.Settings
import android.net.Uri
import android.text.format.DateFormat
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import android.media.RingtoneManager
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

class MainActivity : Activity() {

    companion object {
        private const val MAX_ROUNDS = 99
        private const val REQ_EXPORT = 10
        private const val REQ_IMPORT = 11
        private const val REQ_SOUND = 12
    }

    private enum class Screen { LIST, EDIT, SETTINGS }

    private lateinit var store: PresetStore
    private var screen = Screen.LIST

    // Preset being edited (null id = new preset)
    private var editingId: Long? = null
    private var intervalMs = 0L
    private var rounds = 0
    private var sound = ""
    /** "Each round different" mode, and the length of each round in that mode. */
    private var customMode = false
    private val custom = mutableListOf<Long>()
    private lateinit var modeSame: TextView
    private lateinit var modeCustom: TextView
    private lateinit var uniformSection: View
    private lateinit var customSection: View
    private lateinit var customList: LinearLayout

    // Schedule being edited
    private var schedOn = false
    private var schedHour = 7
    private var schedMinute = 0
    private var repeat = Repeat.DAILY
    private var schedDate: LocalDate = LocalDate.now()
    private var schedDays = Preset.WEEKDAYS

    // List screen
    private lateinit var listView: View
    private lateinit var presetContainer: LinearLayout
    private lateinit var emptyText: View

    // Edit screen
    private lateinit var editView: View
    private lateinit var editTitle: TextView
    private lateinit var nameInput: EditText
    private lateinit var intervalValue: TextView
    private lateinit var roundsValue: TextView
    private lateinit var totalValue: TextView
    private lateinit var deleteButton: View
    private lateinit var scheduleSwitch: Switch
    private lateinit var scheduleOptions: View
    private lateinit var schedTime: TextView
    private lateinit var schedRepeat: TextView
    private lateinit var schedDateRow: View
    private lateinit var schedDateLabel: TextView
    private lateinit var schedDateValue: TextView
    private lateinit var schedDaysRow: LinearLayout
    private lateinit var schedNext: TextView
    /** Weekday buttons as (bit index, view), in display order. */
    private val dayChips = mutableListOf<Pair<Int, TextView>>()

    private lateinit var soundValue: TextView

    // Settings screen
    private lateinit var settingsView: View
    private lateinit var setDefaultInterval: TextView
    private lateinit var setDefaultRounds: TextView
    private lateinit var setWeekStart: TextView
    private lateinit var checklist: LinearLayout
    private lateinit var checklistHint: TextView
    private lateinit var scheduleLog: TextView
    // "While running" options of the timer being edited
    private var floating = false
    private var voice = VoiceChoice.OFF
    private lateinit var editFloatingSwitch: Switch
    private lateinit var editFloatingHint: TextView
    private lateinit var editVoice: TextView
    private var updatingSwitch = false
    private var previewAnnouncer: Announcer? = null

    // Run screen
    private lateinit var runView: View
    private lateinit var runName: TextView
    private lateinit var ring: ProgressRing
    private lateinit var roundsBar: RoundsBar
    private lateinit var roundLabel: TextView
    private lateinit var countdown: TextView
    private lateinit var countdownCaption: TextView
    private lateinit var totalLeft: TextView
    private lateinit var stopButton: TextView
    private lateinit var pauseButton: TextView

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
        store = PresetStore(this)

        listView = findViewById(R.id.list_screen)
        presetContainer = findViewById(R.id.preset_container)
        emptyText = findViewById(R.id.empty_text)
        findViewById<View>(R.id.add_button).setOnClickListener { openEditor(null) }
        findViewById<View>(R.id.menu_button).setOnClickListener { showMenu(it) }

        editView = findViewById(R.id.edit_screen)
        editTitle = findViewById(R.id.edit_title)
        nameInput = findViewById(R.id.name_input)
        // One line to type, but long names wrap instead of scrolling sideways.
        nameInput.setHorizontallyScrolling(false)
        nameInput.maxLines = 3
        intervalValue = findViewById(R.id.interval_value)
        roundsValue = findViewById(R.id.rounds_value)
        totalValue = findViewById(R.id.total_value)
        deleteButton = findViewById(R.id.delete_button)
        findViewById<View>(R.id.back_button).setOnClickListener { closeEditor() }
        findViewById<View>(R.id.interval_minus).repeatOnHold { changeInterval(-1) }
        findViewById<View>(R.id.interval_plus).repeatOnHold { changeInterval(+1) }
        findViewById<View>(R.id.rounds_minus).repeatOnHold { changeRounds(-1) }
        findViewById<View>(R.id.rounds_plus).repeatOnHold { changeRounds(+1) }
        intervalValue.setOnClickListener { pickInterval() }
        roundsValue.setOnClickListener { pickRounds() }
        findViewById<View>(R.id.save_button).setOnClickListener { saveEditor() }
        findViewById<View>(R.id.start_now_button).setOnClickListener { saveEditor()?.let { startTimer(it) } }
        deleteButton.setOnClickListener { confirmDelete() }

        scheduleSwitch = findViewById(R.id.schedule_switch)
        scheduleOptions = findViewById(R.id.schedule_options)
        schedTime = findViewById(R.id.sched_time)
        schedRepeat = findViewById(R.id.sched_repeat)
        schedDateRow = findViewById(R.id.sched_date_row)
        schedDateLabel = findViewById(R.id.sched_date_label)
        schedDateValue = findViewById(R.id.sched_date)
        schedDaysRow = findViewById(R.id.sched_days)
        schedNext = findViewById(R.id.sched_next)
        findViewById<View>(R.id.schedule_header).setOnClickListener { scheduleSwitch.toggle() }
        scheduleSwitch.setOnCheckedChangeListener { _, on ->
            hideKeyboard()
            schedOn = on
            updateSchedule()
        }
        findViewById<View>(R.id.sched_time_row).setOnClickListener { pickTime() }
        findViewById<View>(R.id.sched_repeat_row).setOnClickListener { pickRepeat() }
        schedDateRow.setOnClickListener { pickDate() }
        buildDayChips()

        modeSame = findViewById(R.id.mode_same)
        modeCustom = findViewById(R.id.mode_custom)
        uniformSection = findViewById(R.id.uniform_section)
        customSection = findViewById(R.id.custom_section)
        customList = findViewById(R.id.custom_list)
        modeSame.setOnClickListener { setCustomMode(false) }
        modeCustom.setOnClickListener { setCustomMode(true) }
        findViewById<View>(R.id.custom_add).setOnClickListener { addRound() }
        findViewById<View>(R.id.custom_duplicate).setOnClickListener { repeatLastRound() }

        soundValue = findViewById(R.id.sound_value)
        findViewById<View>(R.id.sound_row).setOnClickListener { pickSound() }

        settingsView = findViewById(R.id.settings_screen)
        setDefaultInterval = findViewById(R.id.set_default_interval)
        setDefaultRounds = findViewById(R.id.set_default_rounds)
        setWeekStart = findViewById(R.id.set_week_start)
        findViewById<View>(R.id.settings_back).setOnClickListener { screen = Screen.LIST; render() }
        findViewById<View>(R.id.set_default_interval_row).setOnClickListener {
            pickDuration(AppSettings.defaultIntervalMs(this)) {
                AppSettings.setDefaultIntervalMs(this, it); updateSettings()
            }
        }
        findViewById<View>(R.id.set_default_rounds_row).setOnClickListener {
            pickCount(AppSettings.defaultRounds(this)) {
                AppSettings.setDefaultRounds(this, it); updateSettings()
            }
        }
        findViewById<View>(R.id.set_week_start_row).setOnClickListener { pickWeekStart() }
        checklist = findViewById(R.id.checklist)
        checklistHint = findViewById(R.id.checklist_hint)
        scheduleLog = findViewById(R.id.schedule_log)

        editFloatingSwitch = findViewById(R.id.edit_floating_switch)
        editFloatingHint = findViewById(R.id.edit_floating_hint)
        editVoice = findViewById(R.id.edit_voice)
        findViewById<View>(R.id.edit_floating_row).setOnClickListener {
            hideKeyboard()
            if (floating && !FloatingWindow.permitted(this)) askOverlayPermission()
            else editFloatingSwitch.toggle()
        }
        editFloatingSwitch.setOnCheckedChangeListener { _, on ->
            if (updatingSwitch) return@setOnCheckedChangeListener
            hideKeyboard()
            floating = on
            if (on && !FloatingWindow.permitted(this)) askOverlayPermission()
            updateWhileRunning()
        }
        findViewById<View>(R.id.edit_voice_row).setOnClickListener { hideKeyboard(); pickVoice() }

        runView = findViewById(R.id.run_screen)
        runName = findViewById(R.id.run_name)
        ring = findViewById(R.id.ring)
        roundsBar = findViewById(R.id.rounds_bar)
        roundLabel = findViewById(R.id.round_label)
        countdown = findViewById(R.id.countdown)
        countdownCaption = findViewById(R.id.countdown_caption)
        totalLeft = findViewById(R.id.total_left)
        stopButton = findViewById(R.id.stop_button)
        pauseButton = findViewById(R.id.pause_button)
        pauseButton.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            TimerService.togglePause(this)
        }
        stopButton.setOnClickListener {
            if (TimerService.isRunning) {
                startService(Intent(this, TimerService::class.java).setAction(TimerService.ACTION_STOP))
            } else {
                TimerService.clearFinished()
            }
        }

        buildList()
        handleStartIntent(intent)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        if (screen == Screen.SETTINGS) updateSettings()
        if (screen == Screen.EDIT) updateWhileRunning()   // e.g. back from the permission screen
        // Pick up a run that continued while the app was closed.
        TimerService.load(this)
        if (TimerService.isRunning && !TimerService.serviceAlive) TimerService.resume(this)
        // Re-arm schedules, and run one that was missed in the last few minutes.
        Scheduler.scheduleAll(this)
        TimerService.refreshStandby(this)
        if (screen == Screen.LIST) buildList()
        handler.post(refresh)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleStartIntent(intent)
    }

    /** Opened from a "scheduled timer" alert: start that timer right away. */
    private fun handleStartIntent(intent: Intent?) {
        val id = intent?.getLongExtra(Scheduler.EXTRA_START_ID, -1L) ?: -1L
        if (id < 0) return
        intent?.removeExtra(Scheduler.EXTRA_START_ID)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        getSystemService(android.app.NotificationManager::class.java).cancel(Scheduler.startNotificationId(id))
        TimerService.load(this)
        if (!TimerService.isRunning) store.get(id)?.let {
            ScheduleLog.add(this, "Started “${it.name}” from the alert")
            startTimer(it, scheduled = true)
        }
    }

    override fun onStart() {
        super.onStart()
        FloatingWindow.appVisible = true
        FloatingWindow.hide(this)
    }

    override fun onStop() {
        FloatingWindow.appVisible = false
        FloatingWindow.show(this)   // only if the timer is running and the option is on
        super.onStop()
    }

    override fun onDestroy() {
        previewAnnouncer?.shutdown()
        super.onDestroy()
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (screen == Screen.EDIT && !TimerService.isRunning && !TimerService.finished) {
            closeEditor()
        } else if (screen == Screen.SETTINGS && !TimerService.isRunning && !TimerService.finished) {
            screen = Screen.LIST
            render()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    // ---- List screen ----

    private fun buildList() {
        presetContainer.removeAllViews()
        val presets = store.all()
        emptyText.visibility = if (presets.isEmpty()) View.VISIBLE else View.GONE
        val inflater = LayoutInflater.from(this)
        presets.forEach { p ->
            val item = inflater.inflate(R.layout.item_preset, presetContainer, false)
            item.findViewById<TextView>(R.id.preset_name).text = p.name
            item.findViewById<TextView>(R.id.preset_detail).text = (
                if (p.custom.isNotEmpty())
                    getString(R.string.custom_detail, p.roundCount, Durations.human(p.totalMs))
                else getString(R.string.preset_detail,
                    Durations.human(p.intervalMs), p.rounds, Durations.human(p.totalMs))
            ) + (if (p.voice != VoiceChoice.OFF) "  ·  🔊" else "") + (if (p.floating) "  ·  ⧉" else "")
            val sched = item.findViewById<TextView>(R.id.preset_schedule)
            if (p.scheduleOn) {
                val next = Scheduler.nextText(this, p)
                sched.text = "⏰  " + Scheduler.describe(this, p) +
                    (if (next != null && p.repeat != Repeat.ONCE) "\n" + next else "")
                sched.visibility = View.VISIBLE
            } else {
                sched.visibility = View.GONE
            }
            item.setOnClickListener { openEditor(p) }
            item.findViewById<View>(R.id.preset_play).setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                startTimer(p)
            }
            presetContainer.addView(item)
        }
    }

    // ---- Edit screen ----

    private fun openEditor(preset: Preset?) {
        editingId = preset?.id
        intervalMs = preset?.intervalMs ?: AppSettings.defaultIntervalMs(this)
        custom.clear()
        preset?.custom?.let { custom.addAll(it) }
        customMode = custom.isNotEmpty()
        rounds = preset?.rounds ?: AppSettings.defaultRounds(this)
        sound = preset?.sound ?: ""
        floating = preset?.floating ?: false
        voice = preset?.voice ?: VoiceChoice.OFF
        updateWhileRunning()
        updateSound()
        schedOn = preset?.scheduleOn ?: false
        schedHour = preset?.hour ?: 7
        schedMinute = preset?.minute ?: 0
        repeat = preset?.repeat ?: Repeat.DAILY
        schedDate = preset?.date?.takeIf { it >= LocalDate.now() } ?: LocalDate.now()
        schedDays = preset?.days ?: Preset.WEEKDAYS
        scheduleSwitch.isChecked = schedOn
        updateSchedule()
        nameInput.setText(preset?.name ?: "")
        nameInput.setSelection(nameInput.text.length)
        editTitle.text = getString(if (preset == null) R.string.new_timer else R.string.edit_timer)
        deleteButton.visibility = if (preset == null) View.GONE else View.VISIBLE
        updateEditor()
        screen = Screen.EDIT
        render()
        if (preset == null) {
            nameInput.requestFocus()
            nameInput.post {
                (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .showSoftInput(nameInput, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    private fun closeEditor() {
        hideKeyboard()
        screen = Screen.LIST
        buildList()
        render()
    }

    private fun editorPreset(id: Long) = Preset(
        id = id,
        name = nameInput.text.toString().trim().ifEmpty { defaultName() },
        intervalMs = if (customMode) custom.firstOrNull() ?: intervalMs else intervalMs,
        rounds = if (customMode) custom.size else rounds,
        custom = if (customMode) custom.toList() else emptyList(),
        scheduleOn = schedOn,
        hour = schedHour,
        minute = schedMinute,
        repeat = repeat,
        date = schedDate,
        days = schedDays,
        sound = sound,
        floating = floating,
        voice = voice,
    )

    /** Saves the editor's values and returns the saved preset, or null if the schedule is invalid. */
    private fun saveEditor(): Preset? {
        val preset = editorPreset(editingId ?: store.newId())
        val missing = when {
            customMode && custom.isEmpty() -> R.string.need_rounds
            customMode && custom.any { it < Durations.MIN_INTERVAL_MS } -> R.string.need_round_lengths
            customMode -> 0
            preset.intervalMs < Durations.MIN_INTERVAL_MS && preset.rounds < 1 -> R.string.need_both
            preset.intervalMs < Durations.MIN_INTERVAL_MS -> R.string.need_interval
            preset.rounds < 1 -> R.string.need_rounds
            else -> 0
        }
        if (missing != 0) {
            AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(R.string.need_title)
                .setMessage(missing)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return null
        }
        if (preset.scheduleOn && Scheduler.nextMillis(preset) == null) {
            val msg = if (preset.repeat == Repeat.DAYS && preset.days == 0) R.string.pick_a_day
                      else R.string.time_passed
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            return null
        }
        store.save(preset)
        Scheduler.schedule(this, preset)
        TimerService.refreshStandby(this)
        editingId = preset.id
        closeEditor()
        if (preset.scheduleOn) {
            Scheduler.nextText(this, preset)?.let {
                Toast.makeText(this, getString(R.string.scheduled_toast, preset.name, it), Toast.LENGTH_LONG).show()
            }
            if (!scheduleReady() || vendor != Vendor.OTHER) offerScheduleSetup()
        }
        return preset
    }

    private fun offerScheduleSetup() {
        if (vendor != Vendor.OTHER && scheduleReady() && getSharedPreferences("settings", MODE_PRIVATE)
                .getBoolean("vendor_setup_shown", false)) return
        getSharedPreferences("settings", MODE_PRIVATE).edit().putBoolean("vendor_setup_shown", true).apply()
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(R.string.schedule_setup_title)
            .setMessage(R.string.schedule_setup_message)
            .setPositiveButton(R.string.review) { _, _ -> openSettings() }
            .setNegativeButton(R.string.later, null)
            .show()
    }

    private fun askExactAlarmPermission() {
        if (Build.VERSION.SDK_INT < 31) return
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(R.string.exact_title)
            .setMessage(R.string.exact_message)
            .setPositiveButton(R.string.open_settings) { _, _ ->
                startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:$packageName")))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun defaultName(): String {
        val names = store.all().map { it.name }.toSet()
        var n = store.all().size + 1
        while ("Timer $n" in names) n++
        return "Timer $n"
    }

    private fun confirmDelete() {
        val id = editingId ?: return
        val name = nameInput.text.toString().trim().ifEmpty { getString(R.string.this_timer) }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(getString(R.string.delete_title, name))
            .setMessage(R.string.delete_message)
            .setPositiveButton(R.string.delete) { _, _ ->
                Scheduler.cancel(this, id); store.delete(id); closeEditor()
                TimerService.refreshStandby(this)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun changeInterval(direction: Int) {
        intervalMs = Durations.step(intervalMs, direction)
        updateEditor()
    }

    private fun changeRounds(direction: Int) {
        rounds = (rounds + direction).coerceIn(0, MAX_ROUNDS)
        updateEditor()
    }

    private fun updateEditor() {
        val text = TimerService.format(intervalMs)
        intervalValue.text = text
        intervalValue.textSize = when { text.length <= 5 -> 44f; text.length <= 8 -> 36f; else -> 26f }
        roundsValue.text = rounds.toString()

        modeSame.isSelected = !customMode
        modeCustom.isSelected = customMode
        uniformSection.visibility = if (customMode) View.GONE else View.VISIBLE
        customSection.visibility = if (customMode) View.VISIBLE else View.GONE
        if (customMode) buildRoundList()
        totalValue.text = Durations.human(if (customMode) custom.sum() else intervalMs * rounds)
    }

    // ---- Each round its own length ----

    private fun setCustomMode(on: Boolean) {
        hideKeyboard()
        if (on == customMode) return
        if (on && custom.isEmpty() && rounds > 0 && intervalMs >= Durations.MIN_INTERVAL_MS) {
            // Start from the current "same length" setup, so it only needs adjusting.
            repeat(rounds) { custom.add(intervalMs) }
        }
        if (!on && custom.isNotEmpty()) {
            // Keep the round count and use the first round's length.
            rounds = custom.size
            if (custom[0] >= Durations.MIN_INTERVAL_MS) intervalMs = custom[0]
        }
        customMode = on
        updateEditor()
    }

    private fun buildRoundList() {
        customList.removeAllViews()
        if (custom.isEmpty()) {
            customList.addView(TextView(this).apply {
                text = getString(R.string.no_rounds_yet)
                setTextColor(getColor(R.color.text_secondary))
                textSize = 14f
                setPadding(0, dp(12), 0, dp(12))
            })
            return
        }
        custom.forEachIndexed { i, ms ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(52)
            }
            row.addView(TextView(this).apply {
                text = getString(R.string.round_n, i + 1)
                setTextColor(getColor(R.color.text_secondary))
                textSize = 15f
            }, LinearLayout.LayoutParams(dp(84), LinearLayout.LayoutParams.WRAP_CONTENT))
            row.addView(TextView(this).apply {
                text = if (ms > 0) Durations.clock(ms) else "--:--"
                setTextColor(getColor(if (ms >= Durations.MIN_INTERVAL_MS) R.color.text_primary else R.color.accent))
                textSize = 22f
                typeface = android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL)
                fontFeatureSettings = "tnum"
                setBackgroundResource(android.R.drawable.list_selector_background)
                setPadding(dp(8), dp(8), dp(8), dp(8))
                setOnClickListener {
                    hideKeyboard()
                    pickDuration(custom[i]) { custom[i] = it; updateEditor() }
                }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(TextView(this).apply {
                text = "✕"
                textSize = 16f
                gravity = Gravity.CENTER
                setTextColor(getColor(R.color.text_secondary))
                contentDescription = getString(R.string.remove_round)
                setBackgroundResource(android.R.drawable.list_selector_background)
                setOnClickListener { custom.removeAt(i); updateEditor() }
            }, LinearLayout.LayoutParams(dp(44), dp(44)))
            if (i > 0) customList.addView(View(this).apply {
                setBackgroundColor(getColor(R.color.track))
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1))
            customList.addView(row)
        }
    }

    private fun addRound() {
        hideKeyboard()
        if (custom.size >= MAX_ROUNDS) return
        val suggested = custom.lastOrNull() ?: intervalMs.takeIf { it >= Durations.MIN_INTERVAL_MS } ?: 0L
        pickDuration(suggested) { if (it > 0) { custom.add(it); updateEditor() } }
    }

    private fun repeatLastRound() {
        hideKeyboard()
        val last = custom.lastOrNull() ?: return addRound()
        if (custom.size < MAX_ROUNDS) { custom.add(last); updateEditor() }
    }

    private fun pickInterval() {
        hideKeyboard()
        pickDuration(intervalMs) { intervalMs = it; updateEditor() }
    }

    /** Days / hours / minutes / seconds picker. 0 is allowed (means "not set"). */
    private fun pickDuration(current: Long, onPicked: (Long) -> Unit) {
        val totalSec = current / 1000
        val two = NumberPicker.Formatter { "%02d".format(it) }
        val days = picker(0, Durations.MAX_DAYS, (totalSec / 86_400).toInt())
        val hours = picker(0, 23, ((totalSec % 86_400) / 3600).toInt()).apply { setFormatter(two) }
        val minutes = picker(0, 59, ((totalSec % 3600) / 60).toInt()).apply { setFormatter(two) }
        val seconds = picker(0, 59, (totalSec % 60).toInt()).apply { setFormatter(two) }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(16), dp(8), 0)
            addView(column(days, getString(R.string.days_unit)))
            addView(column(hours, getString(R.string.hours_unit)))
            addView(column(minutes, getString(R.string.min)))
            addView(column(seconds, getString(R.string.sec)))
        }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(R.string.interval)
            .setView(row)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val picked = days.value * Durations.DAY + hours.value * Durations.HOUR +
                    minutes.value * Durations.MINUTE + seconds.value * Durations.SECOND
                onPicked(if (picked == 0L) 0L
                         else picked.coerceIn(Durations.MIN_INTERVAL_MS, Durations.MAX_INTERVAL_MS))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun pickRounds() {
        hideKeyboard()
        pickCount(rounds) { rounds = it; updateEditor() }
    }

    private fun pickCount(current: Int, onPicked: (Int) -> Unit) {
        val count = picker(0, MAX_ROUNDS, current)
        val box = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, 0)
            addView(count)
        }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(R.string.rounds)
            .setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ -> onPicked(count.value) }
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
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        addView(picker)
        addView(TextView(context).apply { text = label; gravity = Gravity.CENTER })
    }

    // ---- Schedule controls ----

    private fun buildDayChips() {
        schedDaysRow.removeAllViews()
        dayChips.clear()
        val size = dp(40)
        AppSettings.orderedDays(AppSettings.weekStart(this)).forEach { day ->
            val i = day.value - 1   // bit index: Monday = 0 … Sunday = 6
            val chip = TextView(this).apply {
                text = day.getDisplayName(TextStyle.NARROW, Locale.getDefault())
                gravity = Gravity.CENTER
                textSize = 15f
                setBackgroundResource(R.drawable.bg_day)
                contentDescription = day.getDisplayName(TextStyle.FULL, Locale.getDefault())
                setOnClickListener {
                    it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    schedDays = schedDays xor (1 shl i)
                    updateSchedule()
                }
            }
            val cell = android.widget.FrameLayout(this).apply {
                addView(chip, android.widget.FrameLayout.LayoutParams(size, size, Gravity.CENTER))
            }
            schedDaysRow.addView(cell, LinearLayout.LayoutParams(0, size, 1f))
            dayChips.add(i to chip)
        }
    }

    private fun updateSchedule() {
        scheduleOptions.visibility = if (schedOn) View.VISIBLE else View.GONE
        if (!schedOn) return

        schedTime.text = Scheduler.timeText(this, schedHour, schedMinute)
        schedRepeat.text = Scheduler.repeatText(this, repeat) + "  ›"

        schedDateRow.visibility =
            if (repeat == Repeat.ONCE || repeat == Repeat.WEEKLY || repeat == Repeat.MONTHLY) View.VISIBLE
            else View.GONE
        schedDateLabel.text = getString(if (repeat == Repeat.ONCE) R.string.date else R.string.starting)
        schedDateValue.text = Scheduler.dateText(schedDate) + "  ›"

        schedDaysRow.visibility = if (repeat == Repeat.DAYS) View.VISIBLE else View.GONE
        dayChips.forEach { (i, chip) ->
            val on = schedDays and (1 shl i) != 0
            chip.isSelected = on
            chip.setTextColor(getColor(if (on) android.R.color.white else R.color.text_secondary))
        }

        val preview = editorPreset(editingId ?: 0L).copy(scheduleOn = true)
        val next = Scheduler.nextText(this, preview)
        schedNext.text = when {
            next == null && repeat == Repeat.DAYS && schedDays == 0 -> getString(R.string.pick_a_day)
            next == null -> getString(R.string.time_passed)
            repeat == Repeat.ONCE -> getString(R.string.starts_once, next)
            else -> Scheduler.describe(this, preview) + "\n" + next
        }
    }

    private fun pickTime() {
        hideKeyboard()
        TimePickerDialog(this, { _, h, m ->
            schedHour = h; schedMinute = m; updateSchedule()
        }, schedHour, schedMinute, DateFormat.is24HourFormat(this)).show()
    }

    private fun pickDate() {
        hideKeyboard()
        DatePickerDialog(this, { _, y, m, d ->
            schedDate = LocalDate.of(y, m + 1, d); updateSchedule()
        }, schedDate.year, schedDate.monthValue - 1, schedDate.dayOfMonth).apply {
            datePicker.minDate = System.currentTimeMillis() - 1000
            datePicker.firstDayOfWeek = AppSettings.calendarDay(AppSettings.weekStart(this@MainActivity))
        }.show()
    }

    private fun pickRepeat() {
        hideKeyboard()
        val options = Repeat.values()
        val labels = options.map { r ->
            when (r) {
                Repeat.WEEKLY -> getString(R.string.repeat_weekly_on,
                    schedDate.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault()))
                Repeat.MONTHLY -> getString(R.string.repeat_monthly_on, Scheduler.ordinal(schedDate.dayOfMonth))
                else -> Scheduler.repeatText(this, r)
            }
        }.toTypedArray()
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(R.string.repeat)
            .setSingleChoiceItems(labels, options.indexOf(repeat)) { d, which ->
                repeat = options[which]
                updateSchedule()
                d.dismiss()
            }
            .show()
    }

    // ---- Starting and the run screen ----

    private fun startTimer(p: Preset, scheduled: Boolean = false) {
        hideKeyboard()
        startForegroundService(TimerService.startIntent(this, p, scheduled))
    }

    private fun render() {
        val running = TimerService.isRunning
        val finished = TimerService.finished
        val showRun = running || finished
        runView.visibility = if (showRun) View.VISIBLE else View.GONE
        listView.visibility = if (!showRun && screen == Screen.LIST) View.VISIBLE else View.GONE
        settingsView.visibility = if (!showRun && screen == Screen.SETTINGS) View.VISIBLE else View.GONE
        editView.visibility = if (!showRun && screen == Screen.EDIT) View.VISIBLE else View.GONE

        if (!showRun) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            return
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val total = TimerService.totalMs
        val n = TimerService.rounds
        val e = TimerService.elapsed()
        runName.text = TimerService.name
        roundsBar.rounds = n
        roundsBar.progress = TimerService.plan.progress(e)

        if (finished) {
            ring.fraction = 1f
            ring.setRingColor(getColor(R.color.success))
            roundLabel.text = getString(R.string.complete)
            countdown.text = getString(R.string.done)
            countdown.textSize = 68f
            countdownCaption.text = getString(R.string.rounds_done, n)
            totalLeft.text = getString(R.string.total_time, Durations.human(total))
            stopButton.text = getString(R.string.back_to_timers)
            pauseButton.visibility = View.GONE
            countdown.alpha = 1f
        } else {
            val remaining = TimerService.roundRemaining(e)
            ring.setRingColor(getColor(R.color.accent))
            ring.fraction = remaining.toFloat() / TimerService.currentRoundLength(e)
            roundLabel.text = getString(R.string.round_of, TimerService.currentRound(e), n)
            val text = TimerService.format(remaining)
            countdown.text = text
            countdown.textSize = when { text.length <= 5 -> 68f; text.length <= 8 -> 52f; else -> 36f }
            val paused = TimerService.isPaused
            countdownCaption.text = getString(if (paused) R.string.paused_caption else R.string.this_round)
            countdownCaption.setTextColor(getColor(if (paused) R.color.accent else R.color.text_secondary))
            // Paused: the countdown gently blinks so it's obvious the clock is stopped.
            countdown.alpha = if (paused && (System.currentTimeMillis() / 600) % 2 == 0L) 0.35f else 1f
            if (paused) ring.setRingColor(getColor(R.color.text_secondary))
            pauseButton.visibility = View.VISIBLE
            pauseButton.text = getString(if (paused) R.string.resume else R.string.pause)
            totalLeft.text = getString(R.string.total_left, TimerService.format(total - e))
            stopButton.text = getString(R.string.stop)
        }
    }

    // ---- Alarm sound ----

    private fun soundName(uri: String): String =
        if (uri.isBlank()) getString(R.string.builtin_beep)
        else runCatching { RingtoneManager.getRingtone(this, Uri.parse(uri))?.getTitle(this) }
            .getOrNull() ?: getString(R.string.custom_sound)

    private fun updateSound() {
        soundValue.text = soundName(sound) + "  ›"
    }

    private fun pickSound() {
        hideKeyboard()
        val options = arrayOf(getString(R.string.builtin_beep), getString(R.string.choose_ringtone))
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(R.string.alarm_sound)
            .setSingleChoiceItems(options, if (sound.isBlank()) 0 else 1) { d, which ->
                d.dismiss()
                if (which == 0) { sound = ""; updateSound() } else openRingtonePicker()
            }
            .show()
    }

    private fun openRingtonePicker() {
        val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE,
                RingtoneManager.TYPE_ALARM or RingtoneManager.TYPE_RINGTONE or RingtoneManager.TYPE_NOTIFICATION)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, getString(R.string.alarm_sound))
            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, false)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                if (sound.isBlank()) null else Uri.parse(sound))
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQ_SOUND)
        } catch (e: Exception) {
            Toast.makeText(this, R.string.no_ringtone_picker, Toast.LENGTH_LONG).show()
        }
    }

    // ---- Settings screen ----

    private fun openSettings() {
        updateSettings()
        screen = Screen.SETTINGS
        render()
    }

    private fun updateSettings() {
        val ms = AppSettings.defaultIntervalMs(this)
        setDefaultInterval.text = (if (ms == 0L) getString(R.string.not_set) else Durations.clock(ms)) + "  ›"
        val n = AppSettings.defaultRounds(this)
        setDefaultRounds.text = (if (n == 0) getString(R.string.not_set) else n.toString()) + "  ›"
        setWeekStart.text = AppSettings.weekStart(this)
            .getDisplayName(TextStyle.FULL, Locale.getDefault()) + "  ›"
        buildChecklist()
    }

    // ---- Scheduled-start checklist ----

    /** Phone makers with their own background-app killers that need extra settings. */
    private enum class Vendor { HONOR, HUAWEI, XIAOMI, OTHER }

    private val vendor: Vendor = (Build.MANUFACTURER + " " + Build.BRAND).lowercase(Locale.ROOT).let {
        when {
            "honor" in it -> Vendor.HONOR
            "huawei" in it -> Vendor.HUAWEI
            "xiaomi" in it || "redmi" in it || "poco" in it -> Vendor.XIAOMI
            else -> Vendor.OTHER
        }
    }

    /** Tries each screen in turn; falls back to the app's own settings page. */
    private fun openFirst(vararg components: Pair<String, String>) {
        for ((pkg, cls) in components) {
            val ok = runCatching {
                startActivity(Intent().setComponent(android.content.ComponentName(pkg, cls))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.isSuccess
            if (ok) return
        }
        runCatching {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }

    private fun notificationsOk() =
        getSystemService(android.app.NotificationManager::class.java).areNotificationsEnabled()

    private fun batteryOk() =
        getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    private fun fullScreenOk() = Build.VERSION.SDK_INT < 34 ||
        getSystemService(android.app.NotificationManager::class.java).canUseFullScreenIntent()

    /** True when everything Android itself reports is allowed. */
    private fun scheduleReady() =
        Scheduler.canScheduleExact(this) && notificationsOk() && batteryOk() && fullScreenOk()

    private fun buildChecklist() {
        checklist.removeAllViews()
        val pkg = Uri.parse("package:$packageName")
        val ready = AppSettings.stayReady(this)
        valueRow(getString(R.string.check_stay_ready), getString(if (ready) R.string.on_ok else R.string.off_tap),
            if (ready) R.color.success else R.color.accent) {
            AppSettings.setStayReady(this, !ready)
            TimerService.refreshStandby(this)
            updateSettings()
        }
        fun open(action: String, data: Uri? = pkg) = runCatching {
            startActivity(Intent(action).apply { if (data != null) setData(data) })
        }.isSuccess

        if (Build.VERSION.SDK_INT >= 31) {
            checkRow(R.string.check_exact, Scheduler.canScheduleExact(this)) {
                open(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
            }
        }
        checkRow(R.string.check_notifications, notificationsOk()) {
            runCatching {
                startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
            }
        }
        checkRow(R.string.check_battery, batteryOk()) {
            if (!open(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)) {
                open(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS, null)
            }
        }
        if (Build.VERSION.SDK_INT >= 34) {
            checkRow(R.string.check_fullscreen, fullScreenOk()) {
                open(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
            }
        }
        if (vendor == Vendor.HONOR || vendor == Vendor.HUAWEI) {
            // MagicOS / EMUI: Settings → Apps → App launch
            checkRow(R.string.check_app_launch, null) {
                openFirst(
                    "com.hihonor.systemmanager" to "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
                    "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
                    "com.huawei.systemmanager" to "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity",
                )
            }
            checkRow(R.string.check_app_info, null) { open(Settings.ACTION_APPLICATION_DETAILS_SETTINGS) }
        }
        if (vendor == Vendor.XIAOMI) {
            checkRow(R.string.check_autostart, null) {
                val ok = runCatching {
                    startActivity(Intent().setComponent(android.content.ComponentName(
                        "com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")))
                }.isSuccess
                if (!ok) open(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            }
            checkRow(R.string.check_app_settings, null) { open(Settings.ACTION_APPLICATION_DETAILS_SETTINGS) }
        }
        valueRow(getString(R.string.check_test), getString(R.string.test_go), R.color.text_primary) {
            Scheduler.scheduleTest(this)
            Toast.makeText(this, R.string.test_scheduled, Toast.LENGTH_LONG).show()
            updateSettings()
        }
        checklistHint.text = getString(R.string.stay_ready_hint) +
            getString(when (vendor) {
                Vendor.HONOR, Vendor.HUAWEI -> R.string.checklist_hint_honor
                Vendor.XIAOMI -> R.string.checklist_hint_xiaomi
                Vendor.OTHER -> R.string.checklist_hint
            })
        scheduleLog.text = ScheduleLog.read(this).ifBlank { getString(R.string.log_empty) }
    }

    /** One checklist row. [ok]: true = allowed, false = needs fixing, null = can't be checked (just open). */
    private fun checkRow(label: Int, ok: Boolean?, action: () -> Unit) = valueRow(
        getString(label),
        getString(when (ok) { true -> R.string.check_ok; false -> R.string.check_fix; null -> R.string.check_open }),
        when (ok) { true -> R.color.success; false -> R.color.accent; null -> R.color.text_primary },
        action,
    )

    private fun valueRow(label: String, value: String, valueColor: Int, action: () -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(52)
            setBackgroundResource(android.R.drawable.list_selector_background)
            isClickable = true
            setOnClickListener { action() }
        }
        row.addView(TextView(this).apply {
            text = label
            textSize = 16f
            setTextColor(getColor(R.color.text_secondary))
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(TextView(this).apply {
            text = value
            textSize = 15f
            setTextColor(getColor(valueColor))
        })
        checklist.addView(row)
    }

    // ---- "While running" options (per timer) ----

    private fun updateWhileRunning() {
        updatingSwitch = true
        editFloatingSwitch.isChecked = floating
        updatingSwitch = false
        if (floating && !FloatingWindow.permitted(this)) {
            editFloatingHint.text = getString(R.string.floating_needs_permission)
            editFloatingHint.setTextColor(getColor(R.color.accent))
        } else {
            editFloatingHint.text = getString(R.string.floating_hint)
            editFloatingHint.setTextColor(getColor(R.color.text_secondary))
        }
        editVoice.text = getString(voice.label) + "  ›"
    }

    private fun askOverlayPermission() {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(R.string.overlay_title)
            .setMessage(R.string.overlay_message)
            .setPositiveButton(R.string.open_settings) { _, _ ->
                runCatching {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")))
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun pickVoice() {
        val options = VoiceChoice.values()
        val labels = options.map { getString(it.label) }.toTypedArray()
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(R.string.voice_title)
            .setSingleChoiceItems(labels, options.indexOf(voice)) { d, which ->
                val choice = options[which]
                voice = choice
                updateWhileRunning()
                d.dismiss()
                if (choice != VoiceChoice.OFF) previewVoice(choice)
            }
            .show()
    }

    /** Plays a sample so the user can hear the chosen voice. */
    private fun previewVoice(choice: VoiceChoice) {
        val a = previewAnnouncer ?: Announcer(this).also { previewAnnouncer = it }
        a.setChoice(choice)
        a.speak(Announcements.round(3, RoundPlan(List(10) { 3 * Durations.MINUTE })))
        handler.postDelayed({
            if (a.isReady && !a.realVoice) Toast.makeText(this, R.string.voice_fallback, Toast.LENGTH_LONG).show()
        }, 2500L)
    }

    // ---- Export / import ----

    private fun showMenu(anchor: View) {
        android.widget.PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, R.string.export_timers)
            menu.add(0, 2, 1, R.string.import_timers)
            menu.add(0, 3, 2, R.string.settings)
            setOnMenuItemClickListener {
                when (it.itemId) { 1 -> exportTimers(); 2 -> importTimers(); 3 -> openSettings() }
                true
            }
        }.show()
    }

    private fun pickWeekStart() {
        val days = AppSettings.orderedDays(DayOfWeek.SUNDAY)
        val labels = days.map { it.getDisplayName(TextStyle.FULL, Locale.getDefault()) }.toTypedArray()
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(R.string.week_start_title)
            .setSingleChoiceItems(labels, days.indexOf(AppSettings.weekStart(this))) { d, which ->
                AppSettings.setWeekStart(this, days[which])
                buildDayChips()
                buildList()
                updateSettings()
                d.dismiss()
            }
            .show()
    }

    private fun exportTimers() {
        if (store.all().isEmpty()) {
            Toast.makeText(this, R.string.nothing_to_export, Toast.LENGTH_SHORT).show()
            return
        }
        @Suppress("DEPRECATION")
        startActivityForResult(
            Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/json")
                .putExtra(Intent.EXTRA_TITLE, "interval-timers-${LocalDate.now()}.json"),
            REQ_EXPORT
        )
    }

    private fun importTimers() {
        @Suppress("DEPRECATION")
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/plain", "application/octet-stream")),
            REQ_IMPORT
        )
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_SOUND) {
            if (resultCode == RESULT_OK) {
                @Suppress("DEPRECATION")
                val picked: Uri? = data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
                sound = picked?.toString() ?: ""
                updateSound()
            }
            return
        }
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        when (requestCode) {
            REQ_EXPORT -> try {
                val presets = store.all()
                contentResolver.openOutputStream(uri, "wt")!!.use {
                    it.write(PresetStore.exportJson(presets).toByteArray())
                }
                Toast.makeText(this, resources.getQuantityString(
                    R.plurals.exported, presets.size, presets.size), Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this, R.string.export_failed, Toast.LENGTH_LONG).show()
            }

            REQ_IMPORT -> try {
                val text = contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
                val incoming = PresetStore.parseImport(text)
                if (incoming.isEmpty()) {
                    Toast.makeText(this, R.string.import_empty, Toast.LENGTH_LONG).show()
                } else {
                    confirmImport(incoming)
                }
            } catch (e: Exception) {
                Toast.makeText(this, R.string.import_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun confirmImport(incoming: List<Preset>) {
        val n = incoming.size
        val builder = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(resources.getQuantityString(R.plurals.import_title, n, n))
            .setMessage(R.string.import_message)
            .setPositiveButton(R.string.import_add) { _, _ -> applyImport(incoming, replace = false) }
            .setNegativeButton(android.R.string.cancel, null)
        if (store.all().isNotEmpty()) {
            builder.setNeutralButton(R.string.import_replace) { _, _ -> applyImport(incoming, replace = true) }
        }
        builder.show()
    }

    private fun applyImport(incoming: List<Preset>, replace: Boolean) {
        val existing = if (replace) emptyList() else store.all()
        if (replace) store.all().forEach { Scheduler.cancel(this, it.id) }
        val (merged, added) = PresetStore.merge(existing, incoming, store.newId())
        store.replaceAll(merged)
        Scheduler.scheduleAll(this)
        TimerService.refreshStandby(this)
        buildList()
        val skipped = incoming.size - added
        val msg = resources.getQuantityString(R.plurals.imported, added, added) +
            (if (skipped > 0) " " + resources.getQuantityString(R.plurals.skipped, skipped, skipped) else "")
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    // ---- Helpers ----

    private fun hideKeyboard() {
        nameInput.clearFocus()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(nameInput.windowToken, 0)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** Tap to step once; press and hold to keep stepping. */
    private fun View.repeatOnHold(action: () -> Unit) {
        val repeater = object : Runnable {
            override fun run() { action(); handler.postDelayed(this, 80L) }
        }
        setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            hideKeyboard()
            action()
        }
        setOnLongClickListener { hideKeyboard(); handler.post(repeater); true }
        setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                handler.removeCallbacks(repeater)
            }
            false
        }
    }
}
