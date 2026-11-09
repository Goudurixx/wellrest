package com.sylvainrousseau.wellrest

import android.app.Notification
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.RemoteViews
import android.widget.TextView

/**
 * Watches WHOOP's persistent strength-trainer notification.
 *  set state:  exercise name / "Running" / "Warmup", stop or play button
 *  rest state: "Up Next:" + next exercise, grey timer, play button
 * The rest timer starts on a set -> rest transition, judged only from what is actually
 * visible on the expanded card (WHOOP keeps stale or hidden "Up Next" text elsewhere).
 */
class WhoopListener : NotificationListenerService() {

    private val handler = Handler(Looper.getMainLooper())

    /** Last known state: null = unknown, true = resting. Kept across WHOOP re-posting its card. */
    private var resting: Boolean? = null
    /** When the current state began (elapsedRealtime). */
    private var stateSince = 0L
    private var lastSummary: String? = null

    private val resetState = Runnable {
        resting = null
        lastSummary = null
        LogStore.add("workout ended, state reset")
    }

    override fun onListenerConnected() {
        connected = true
        val pkg = Prefs(this).pkg
        val active = runCatching { activeNotifications.toList() }.getOrDefault(emptyList())
        LogStore.add("listener connected — ${active.count { it.packageName == pkg }} WHOOP notification(s) active")
        active.filter { it.packageName == pkg }.forEach { onNotificationPosted(it) }
    }

    override fun onListenerDisconnected() {
        connected = false
        LogStore.add("listener disconnected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val p = Prefs(this)
        if (sbn.packageName != p.pkg) return
        handler.removeCallbacks(resetState) // card is back: not the end of the workout

        val read = readCard(sbn.notification)
        val text = read.visible.joinToString(" | ")

        // Log only structural changes (ignore ticking clock / heart rate)
        val summary = (text + read.extras).replace(Regex("\\d+"), "#")
        if (lastSummary != summary) {
            lastSummary = summary
            LogStore.add("WHOOP [${read.source}]: $text")
            if (read.extras.isNotEmpty()) LogStore.add("   extras (ignored): ${read.extras}")
        }
        if (!p.enabled) return

        val rx = runCatching { Regex(p.restRegex, RegexOption.IGNORE_CASE) }.getOrNull() ?: return
        val nowResting = rx.containsMatchIn(text)
        val was = resting
        val now = SystemClock.elapsedRealtime()
        val prevDuration = now - stateSince
        if (was != nowResting) {
            resting = nowResting
            stateSince = now
            LogStore.add("state: ${if (nowResting) "REST" else "SET"}${if (was == null) " (first seen)" else ""}")
        }

        when {
            nowResting && was == false -> {
                // A real set lasts a few seconds; a blip at workout start does not
                if (prevDuration < MIN_SET_MS) {
                    LogStore.add("   ignored: set lasted only ${prevDuration / 1000.0}s")
                } else {
                    val next = nextExercise(read.visible, rx)
                    LogStore.add("→ REST (${p.restSeconds}s)${next?.let { ", next: $it" } ?: ""}")
                    RestTimer.start(this, p.restSeconds, next)
                }
            }
            !nowResting && was == true && p.cancelOnExercise && RestTimer.isRunning -> {
                LogStore.add("→ SET started, timer cancelled")
                RestTimer.stop(this)
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName != Prefs(this).pkg) return
        LogStore.add("WHOOP notification removed")
        // WHOOP sometimes removes and immediately re-posts its card mid-rest: keep the state
        // through that. If it stays gone, the workout ended: forget the state.
        handler.removeCallbacks(resetState)
        handler.postDelayed(resetState, 5_000)
    }

    /** "Up Next:" | "Bench Press - Dumbbell" → "Bench Press - Dumbbell" */
    private fun nextExercise(parts: List<String>, rx: Regex): String? {
        val i = parts.indexOfFirst { rx.containsMatchIn(it) }
        if (i < 0) return null
        val inline = parts[i].substringAfter(':', "").trim()
        if (inline.isNotEmpty()) return inline
        return parts.getOrNull(i + 1)?.takeIf { it.any(Char::isLetter) }
    }

    private class Card(val visible: List<String>, val source: String, val extras: String)

    /**
     * Reads the text a person actually sees: the expanded custom view if WHOOP provides one,
     * otherwise the collapsed one, otherwise the standard title/text fields.
     */
    private fun readCard(n: Notification): Card {
        val e = n.extras
        val extras = listOf(
            Notification.EXTRA_TITLE, Notification.EXTRA_TEXT, Notification.EXTRA_SUB_TEXT,
        ).mapNotNull { e.getCharSequence(it)?.toString()?.trim()?.takeIf(String::isNotEmpty) }

        @Suppress("DEPRECATION")
        for ((rv, name) in listOf(n.bigContentView to "expanded", n.contentView to "collapsed")) {
            if (rv == null) continue
            val out = LinkedHashSet<String>()
            val ok = runCatching {
                walk(rv.apply(applicationContext, FrameLayout(applicationContext)), out)
            }.isSuccess
            if (ok && out.isNotEmpty()) return Card(out.toList(), name, extras.joinToString(" | "))
        }
        return Card(extras, "fields", "")
    }

    private fun walk(v: View, out: MutableSet<String>) {
        // Skip anything a person can't see: gone/invisible, fully transparent, or transparent text
        if (v.visibility != View.VISIBLE || v.alpha == 0f) return
        if (v is TextView) {
            val t = v.text?.toString()?.trim()
            if (!t.isNullOrEmpty() && Color.alpha(v.currentTextColor) > 0 && v.textSize > 1f) out.add(t)
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i), out)
    }

    companion object {
        @Volatile var connected = false
        private const val MIN_SET_MS = 4_000L
    }
}
