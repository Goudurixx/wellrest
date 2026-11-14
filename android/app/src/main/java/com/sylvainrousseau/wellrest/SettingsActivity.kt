package com.sylvainrousseau.wellrest

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat

/** Setup, detection tuning and debug tools — kept off the main screen. */
class SettingsActivity : Activity() {

    private val dp by lazy { resources.displayMetrics.density }
    private lateinit var status: TextView
    private lateinit var log: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val p = Prefs(this)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20), px(8), px(20), px(24))
        }

        // Header with back arrow
        col.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageButton(this@SettingsActivity).apply {
                setImageResource(R.drawable.ic_back)
                setBackgroundResource(android.R.color.transparent)
                setOnClickListener { finish() }
            }, LinearLayout.LayoutParams(px(48), px(48)))
            addView(TextView(this@SettingsActivity).apply {
                text = "SETTINGS"; letterSpacing = 0.14f; textSize = 17f
                setTypeface(typeface, Typeface.BOLD); setTextColor(getColor(R.color.text))
            })
        })

        // --- Setup
        // --- Workout behaviour (moved from the main screen)
        val workout = section(col, "WORKOUT")
        workout.addView(toggle("Auto-start after each set", "Starts the rest when WHOOP moves to “Up Next”", p.enabled) { p.enabled = it })
        workout.addView(divider())
        workout.addView(toggle("Sound when rest ends", "Plays in your headphones, even on vibrate", p.soundEnabled) {
            p.soundEnabled = it
            if (it) RestTimer.previewChime(this)
        })
        workout.addView(divider())
        workout.addView(toggle("Stop if I start early", "Cancel the countdown when the next set starts", p.cancelOnExercise) { p.cancelOnExercise = it })

        val setup = section(col, "SETUP")
        status = body("").also { setup.addView(it) }
        // Restricted settings only block sideloaded installs, not Play installs
        if (!AppInfo.installedFromPlay(this)) {
            setup.addView(action("Allow restricted settings (App info → ⋮)") {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.parse("package:$packageName")))
            })
        }
        setup.addView(action("Notification access") { AppInfo.requestNotificationAccess(this) })
        setup.addView(action("Reconnect to WHOOP") {
            ListenerHealth.forceRebind(this)
            status.postDelayed({ onResume() }, 2500)
        })
        setup.addView(action("Battery: set to Unrestricted") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.parse("package:$packageName")))
        })

        // --- Detection
        val det = section(col, "DETECTION")
        det.addView(caption("Rest-state pattern (regex)"))
        val rx = field(p.restRegex).also { det.addView(it) }
        det.addView(caption("WHOOP package"))
        val pkg = field(p.pkg).also { det.addView(it) }
        det.addView(action("Save") {
            p.restRegex = rx.text.toString().ifBlank { "Up Next" }
            p.pkg = pkg.text.toString().ifBlank { "com.whoop.android" }
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
        })

        // --- Tools
        val tools = section(col, "TEST")
        tools.addView(action("Start a rest timer now") {
            RestTimer.start(this, p.restSeconds, "Test exercise")
            Toast.makeText(this, "Pull down the shade to see it", Toast.LENGTH_SHORT).show()
        })
        tools.addView(action("Stop timer") { RestTimer.stop(this) })

        // --- Log
        val logSec = section(col, "LISTENER LOG")
        logSec.addView(LinearLayout(this).apply {
            addView(action("Refresh") { log.text = LogStore.dump() }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(action("Copy") {
                (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("WellRest log", LogStore.dump()))
                Toast.makeText(this@SettingsActivity, "Copied", Toast.LENGTH_SHORT).show()
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = px(8) })
        })
        log = TextView(this).apply {
            typeface = Typeface.MONOSPACE; textSize = 11f; setTextIsSelectable(true)
            setTextColor(getColor(R.color.text_dim)); setPadding(0, px(10), 0, 0)
        }
        logSec.addView(log)

        // --- About (store requirements: version, privacy policy, licences, trademark notice, contact)
        val about = section(col, "ABOUT")
        about.addView(row("Version", AppInfo.version(this)))
        about.addView(action("Privacy") { AppInfo.showPrivacy(this) })
        about.addView(action("Open-source licences") { AppInfo.showLicenses(this) })
        if (AppInfo.SUPPORT_EMAIL.isNotEmpty()) about.addView(action("Contact support") {
            AppInfo.open(this, "mailto:${AppInfo.SUPPORT_EMAIL}?subject=" + Uri.encode("WellRest ${AppInfo.version(this)}"))
        })
        about.addView(TextView(this).apply {
            text = AppInfo.DISCLAIMER; textSize = 12f
            setTextColor(getColor(R.color.text_dim)); setPadding(0, px(14), 0, 0)
        })

        val scroll = ScrollView(this).apply { addView(col, MATCH_PARENT, WRAP_CONTENT) }
        setContentView(scroll)
        MainActivity.applyInsets(this, scroll)
    }

    override fun onResume() {
        super.onResume()
        val granted = NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
        status.text = when {
            !granted -> "⚠️  Notification access not granted. Tap “Notification access” below."
            !WhoopListener.connected -> "⏳  Access granted but not connected. Use Reconnect below, and set battery to Unrestricted."
            else -> "✅  Listening for WHOOP notifications."
        }
        log.text = LogStore.dump()
    }

    // ---- tiny view helpers
    private fun px(v: Int) = (v * dp).toInt()

    private fun section(parent: LinearLayout, title: String): LinearLayout {
        parent.addView(TextView(this).apply {
            text = title; letterSpacing = 0.14f; textSize = 12f
            setTypeface(typeface, Typeface.BOLD); setTextColor(getColor(R.color.green))
            setPadding(px(4), px(22), 0, px(8))
        })
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card)
            setPadding(px(16), px(14), px(16), px(16))
        }
        parent.addView(card, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        return card
    }

    private fun body(t: String) = TextView(this).apply {
        text = t; textSize = 14f; setTextColor(getColor(R.color.text)); setPadding(0, 0, 0, px(6))
    }

    /** Title + subtitle + switch; the whole row toggles. */
    private fun toggle(title: String, subtitle: String, initial: Boolean, onChange: (Boolean) -> Unit): View {
        val sw = Switch(this).apply { isChecked = initial; setOnCheckedChangeListener { _, c -> onChange(c) } }
        return LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = px(64)
            setPadding(0, px(6), 0, px(6))
            addView(LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@SettingsActivity).apply {
                    text = title; textSize = 15f; setTypeface(typeface, Typeface.BOLD); setTextColor(getColor(R.color.text))
                })
                addView(TextView(this@SettingsActivity).apply {
                    text = subtitle; textSize = 13f; setTextColor(getColor(R.color.text_dim)); setPadding(0, px(2), px(8), 0)
                })
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(sw)
            setOnClickListener { sw.toggle() }
        }
    }

    private fun divider() = View(this).apply {
        setBackgroundColor(getColor(R.color.card_stroke))
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, px(1))
    }

    private fun row(label: String, value: String) = LinearLayout(this).apply {
        addView(TextView(this@SettingsActivity).apply {
            text = label; textSize = 15f; setTextColor(getColor(R.color.text))
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        addView(TextView(this@SettingsActivity).apply {
            text = value; textSize = 15f; setTextColor(getColor(R.color.text_dim)); setTextIsSelectable(true)
        })
    }

    private fun caption(t: String) = TextView(this).apply {
        text = t; textSize = 12f; setTextColor(getColor(R.color.text_dim)); setPadding(0, px(8), 0, 0)
    }

    private fun field(v: String) = EditText(this).apply {
        setText(v); textSize = 15f; setSingleLine(); setTextColor(getColor(R.color.text))
    }

    private fun action(t: String, onClick: () -> Unit): View = TextView(this).apply {
        text = t; textSize = 14f; gravity = Gravity.CENTER
        setTypeface(typeface, Typeface.BOLD); setTextColor(getColor(R.color.green))
        setBackgroundResource(R.drawable.bg_outline_btn)
        setPadding(px(12), px(12), px(12), px(12))
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = px(10) }
    }
}
