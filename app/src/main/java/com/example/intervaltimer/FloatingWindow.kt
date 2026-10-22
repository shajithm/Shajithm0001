package com.example.intervaltimer

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * Small draggable window shown over other apps while a timer runs and the app is
 * minimized. Shows the round and the countdown. Tap it to open the app; × hides it
 * for the rest of this run.
 */
object FloatingWindow {

    /** True while the app's own screen is visible (then the mini window is hidden). */
    @Volatile var appVisible = false

    private val handler = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    private var root: View? = null
    private var params: WindowManager.LayoutParams? = null
    private lateinit var title: TextView
    private lateinit var roundText: TextView
    private lateinit var countText: TextView
    private lateinit var bar: RoundsBar
    private var dismissedRun = 0L
    private var finishedSince = 0L

    private val updater = object : Runnable {
        override fun run() {
            update()
            if (root != null) handler.postDelayed(this, 500L)
        }
    }

    fun permitted(context: Context) = Settings.canDrawOverlays(context)

    fun show(context: Context) {
        val c = context.applicationContext
        appContext = c
        if (root != null || appVisible || !TimerService.isRunning) return
        if (!TimerService.floating || !permitted(c)) return   // this timer's own setting
        if (dismissedRun == TimerService.startedAt) return
        runCatching { build(c) }
    }

    fun hide(context: Context? = appContext) {
        handler.removeCallbacks(updater)
        val v = root ?: return
        root = null
        runCatching { (context ?: v.context).getSystemService(WindowManager::class.java).removeView(v) }
    }

    private fun build(c: Context) {
        val d = c.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        title = TextView(c).apply {
            setTextColor(c.getColor(R.color.text_secondary))
            textSize = 12f
            maxWidth = dp(170)
            setSingleLine()
            ellipsize = TextUtils.TruncateAt.END
        }
        val close = TextView(c).apply {
            text = "✕"
            textSize = 14f
            setTextColor(c.getColor(R.color.text_secondary))
            setPadding(dp(10), 0, 0, dp(2))
            contentDescription = c.getString(R.string.hide)
            setOnClickListener { dismissedRun = TimerService.startedAt; hide(c) }
        }
        val top = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(close)
        }
        roundText = TextView(c).apply {
            setTextColor(c.getColor(R.color.accent))
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            letterSpacing = 0.08f
        }
        countText = TextView(c).apply {
            setTextColor(c.getColor(R.color.text_primary))
            textSize = 32f
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            fontFeatureSettings = "tnum"
        }
        bar = RoundsBar(c)

        val box = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            minimumWidth = dp(170)
            setPadding(dp(16), dp(10), dp(12), dp(12))
            background = GradientDrawable().apply {
                setColor(0xF0181B22.toInt())
                cornerRadius = 18f * d
                setStroke(dp(1), c.getColor(R.color.track))
            }
            elevation = 8f * d
            addView(top)
            addView(roundText)
            addView(countText)
            addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(4)).apply {
                topMargin = dp(4)
            })
        }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = AppSettings.floatX(c, dp(16))
            y = AppSettings.floatY(c, dp(160))
        }
        dragAndTap(c, box, lp)

        c.getSystemService(WindowManager::class.java).addView(box, lp)
        root = box
        params = lp
        finishedSince = 0L
        update()
        handler.postDelayed(updater, 500L)
    }

    private fun dragAndTap(c: Context, v: View, lp: WindowManager.LayoutParams) {
        val slop = ViewConfiguration.get(c).scaledTouchSlop
        var startX = 0; var startY = 0
        var downX = 0f; var downY = 0f
        var moved = false
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x; startY = lp.y; downX = e.rawX; downY = e.rawY; moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (abs(dx) > slop || abs(dy) > slop) moved = true
                    if (moved) {
                        lp.x = startX + dx.toInt(); lp.y = startY + dy.toInt()
                        runCatching { c.getSystemService(WindowManager::class.java).updateViewLayout(v, lp) }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) AppSettings.setFloatPosition(c, lp.x, lp.y)
                    else c.startActivity(Intent(c, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                }
            }
            true
        }
    }

    private fun update() {
        val c = appContext ?: return
        if (root == null) return
        if (appVisible) { hide(c); return }
        when {
            TimerService.isRunning -> {
                val e = TimerService.elapsed()
                title.text = TimerService.name.ifBlank { c.getString(R.string.app_name) }
                roundText.text = c.getString(R.string.float_round, TimerService.currentRound(e), TimerService.rounds)
                val t = Durations.clock(TimerService.roundRemaining(e))
                countText.text = t
                countText.textSize = if (t.length <= 5) 32f else if (t.length <= 8) 26f else 20f
                bar.rounds = TimerService.rounds
                bar.progress = e.toFloat() / TimerService.roundMs
                finishedSince = 0L
            }
            TimerService.finished -> {
                // Show "Done" for a few seconds, then go away.
                if (finishedSince == 0L) finishedSince = System.currentTimeMillis()
                roundText.text = c.getString(R.string.complete)
                countText.text = c.getString(R.string.done)
                countText.setTextColor(c.getColor(R.color.success))
                bar.progress = bar.rounds.toFloat()
                if (System.currentTimeMillis() - finishedSince > 5_000L) hide(c)
            }
            else -> hide(c)
        }
    }
}
