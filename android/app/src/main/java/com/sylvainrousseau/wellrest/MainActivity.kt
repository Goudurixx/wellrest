package com.sylvainrousseau.wellrest

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : Activity() {

    private val presets = listOf(30, 45, 60, 90, 120)
    private lateinit var prefs: Prefs
    private lateinit var dial: RestDialView
    private lateinit var hint: TextView
    private lateinit var presetRow: LinearLayout
    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var haptics: Haptics

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setContentView(R.layout.activity_main)
        applyInsets(this, findViewById(R.id.root))

        dial = findViewById(R.id.dial)
        hint = findViewById(R.id.hint)
        presetRow = findViewById(R.id.presets)
        statusDot = findViewById(R.id.statusDot)
        statusText = findViewById(R.id.statusText)
        haptics = Haptics(dial)

        findViewById<View>(R.id.settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<View>(R.id.status).setOnClickListener {
            when {
                !hasAccess() -> AppInfo.requestNotificationAccess(this)
                !WhoopListener.connected -> showReconnectHelp()
                else -> startActivity(Intent(this, SettingsActivity::class.java))
            }
        }

        dial.setValue(prefs.restSeconds, animate = false)
        dial.onValueChanged = { s, final ->
            if (final) prefs.restSeconds = s
            renderPresets(s)
        }
        dial.onStartStop = {
            if (RestTimer.isRunning) RestTimer.stop(this)
            else RestTimer.start(this, prefs.restSeconds)
        }

        buildPresets()
        renderPresets(prefs.restSeconds)
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
        // Settings may have changed the rest length meanwhile
        dial.setValue(prefs.restSeconds, animate = false)
        renderPresets(prefs.restSeconds)
        renderRunning()
        renderStatus()
        // Listener unbound while access is granted: ask nicely, then force it
        if (granted && !WhoopListener.connected) {
            reconnecting = true
            renderStatus()
            ListenerHealth.requestRebind(this)
            ui.postDelayed({
                if (!WhoopListener.connected) ListenerHealth.forceRebind(this)
                ui.postDelayed({ reconnecting = false; renderStatus() }, 2500)
            }, 1200)
        }
    }

    override fun onStart() {
        super.onStart()
        WhoopListener.onConnectionChanged = { ui.post { if (WhoopListener.connected) reconnecting = false; renderStatus() } }
        RestTimer.onStateChanged = { renderRunning() }
    }

    override fun onStop() {
        super.onStop()
        WhoopListener.onConnectionChanged = null
        RestTimer.onStateChanged = null
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacksAndMessages(null)
        reconnecting = false
    }

    private val ui = android.os.Handler(android.os.Looper.getMainLooper())
    private var reconnecting = false

    private fun renderRunning() {
        val running = RestTimer.isRunning
        dial.running = running
        hint.text = if (running) "Rest in progress" else "Turn the ring to set your rest"
        presetRow.alpha = if (running) 0.35f else 1f
        for (i in 0 until presetRow.childCount) presetRow.getChildAt(i).isEnabled = !running
    }

    private fun renderStatus() {
        val (color, text) = when {
            !hasAccess() -> R.color.red to "Setup needed — tap to fix"
            WhoopListener.connected -> R.color.green to "Connected to WHOOP"
            reconnecting -> R.color.amber to "Reconnecting to WHOOP…"
            else -> R.color.amber to "Not connected — tap to fix"
        }
        statusDot.backgroundTintList = ColorStateList.valueOf(getColor(color))
        statusText.text = text
    }

    private fun showReconnectHelp() = AppInfo.sheet(
        this,
        icon = R.drawable.ic_bell,
        title = "Reconnect to WHOOP",
        body = "Android has paused WellRest's access to notifications, usually to save battery.",
        points = listOf(
            "Tap Reconnect below.",
            "If it happens again: Settings → Apps → WellRest → Battery → Unrestricted.",
            "Samsung: also remove WellRest from Sleeping apps in Battery settings.",
        ),
        primary = "Reconnect",
        secondary = "Battery settings",
        onSecondary = {
            startActivity(
                Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(android.net.Uri.parse("package:$packageName"))
            )
        },
    ) {
        reconnecting = true
        renderStatus()
        ListenerHealth.forceRebind(this)
        ui.postDelayed({ reconnecting = false; renderStatus() }, 2500)
    }

    private var disclosureShownThisSession = false

    private fun hasAccess() = ListenerHealth.hasAccess(this)

    private fun renderPresets(seconds: Int) {
        for (i in 0 until presetRow.childCount) {
            val chip = presetRow.getChildAt(i)
            chip.isSelected = chip.tag == seconds
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
                setOnClickListener {
                    if (s / 60 != prefs.restSeconds / 60) haptics.minute() else haptics.detent()
                    prefs.restSeconds = s
                    dial.setValue(s)           // knob glides to the preset
                    renderPresets(s)
                }
            }
            presetRow.addView(chip, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                if (i > 0) marginStart = (8 * dp).toInt()
            })
        }
    }

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
