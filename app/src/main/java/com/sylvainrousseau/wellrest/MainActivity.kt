package com.sylvainrousseau.wellrest

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import androidx.core.app.NotificationManagerCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : Activity() {

    private val presets = listOf(30, 45, 60, 90, 120)
    private lateinit var prefs: Prefs
    private lateinit var time: TextView
    private lateinit var presetRow: LinearLayout
    private lateinit var statusDot: View
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setContentView(R.layout.activity_main)
        applyInsets(this, findViewById(R.id.scroll))

        time = findViewById(R.id.time)
        presetRow = findViewById(R.id.presets)
        statusDot = findViewById(R.id.statusDot)
        statusText = findViewById(R.id.statusText)

        findViewById<View>(R.id.settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<View>(R.id.status).setOnClickListener {
            if (hasAccess()) startActivity(Intent(this, SettingsActivity::class.java))
            else AppInfo.requestNotificationAccess(this)
        }
        findViewById<View>(R.id.minus).setOnClickListener { it.tick(); setRest(prefs.restSeconds - 15) }
        findViewById<View>(R.id.plus).setOnClickListener { it.tick(); setRest(prefs.restSeconds + 15) }

        buildPresets()

        bindSwitch(R.id.swAuto, R.id.rowAuto, prefs.enabled) { prefs.enabled = it }
        bindSwitch(R.id.swCancel, R.id.rowCancel, prefs.cancelOnExercise) { prefs.cancelOnExercise = it }

        render()
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    override fun onResume() {
        super.onResume()
        val granted = hasAccess()
        // First launch: explain notification access before anything else (Play prominent disclosure)
        if (!granted && !prefs.disclosureAccepted && !disclosureShownThisSession) {
            disclosureShownThisSession = true
            AppInfo.requestNotificationAccess(this)
        }
        val (color, text) = when {
            !granted -> R.color.red to "Setup needed — tap to fix"
            !WhoopListener.connected -> R.color.amber to "Waiting for WHOOP connection…"
            else -> R.color.green to "Connected to WHOOP"
        }
        statusDot.backgroundTintList = ColorStateList.valueOf(getColor(color))
        statusText.text = text
        if (granted) NotificationListenerService.requestRebind(ComponentName(this, WhoopListener::class.java))
    }

    private var disclosureShownThisSession = false

    private fun hasAccess() = NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)

    private fun setRest(seconds: Int) {
        prefs.restSeconds = seconds
        render()
    }

    private fun render() {
        time.text = RestTimer.fmt(prefs.restSeconds * 1000L)
        for (i in 0 until presetRow.childCount) {
            val chip = presetRow.getChildAt(i)
            chip.isSelected = chip.tag == prefs.restSeconds
        }
    }

    private fun buildPresets() {
        val dp = resources.displayMetrics.density
        presets.forEachIndexed { i, s ->
            val chip = TextView(this).apply {
                tag = s
                text = if (s < 60) "${s}s" else RestTimer.fmt(s * 1000L)
                gravity = Gravity.CENTER
                textSize = 14f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(getColorStateList(R.color.chip_text))
                setBackgroundResource(R.drawable.bg_chip)
                setOnClickListener { it.tick(); setRest(s) }
            }
            presetRow.addView(chip, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                if (i > 0) marginStart = (8 * dp).toInt()
            })
        }
    }

    private fun bindSwitch(switchId: Int, rowId: Int, initial: Boolean, onChange: (Boolean) -> Unit) {
        val sw = findViewById<Switch>(switchId)
        sw.isChecked = initial
        sw.setOnCheckedChangeListener { _, c -> onChange(c) }
        findViewById<View>(rowId).setOnClickListener { sw.toggle() }
    }

    private fun View.tick() = performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)

    companion object {
        /** Edge-to-edge: pad content away from the status / navigation bars. */
        fun applyInsets(activity: Activity, root: View) {
            WindowCompat.setDecorFitsSystemWindows(activity.window, false)
            ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
                val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                WindowInsetsCompat.CONSUMED
            }
        }
    }
}
