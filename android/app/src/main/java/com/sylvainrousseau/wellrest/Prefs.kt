package com.sylvainrousseau.wellrest

import android.content.Context

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("wellrest", Context.MODE_PRIVATE)

    /** Whoop's Android package. Check the log screen if nothing ever shows up. */
    var pkg: String
        get() = sp.getString("pkg", "com.whoop.android")!!
        set(v) = sp.edit().putString("pkg", v.trim()).apply()

    /** Regex that identifies the REST state of the Whoop notification. */
    var restRegex: String
        get() = sp.getString("rest", "Up Next")!!
        set(v) = sp.edit().putString("rest", v).apply()

    var restSeconds: Int
        get() = sp.getInt("secs", 60)
        set(v) = sp.edit().putInt("secs", v.coerceIn(15, 600)).apply()

    /** Cancel the countdown if Whoop switches back to exercise before it ends. */
    var cancelOnExercise: Boolean
        get() = sp.getBoolean("cancel", true)
        set(v) = sp.edit().putBoolean("cancel", v).apply()

    var enabled: Boolean
        get() = sp.getBoolean("enabled", true)
        set(v) = sp.edit().putBoolean("enabled", v).apply()

    /** Play a chime (media stream, headphones-friendly) when the rest ends. */
    var soundEnabled: Boolean
        get() = sp.getBoolean("sound", true)
        set(v) = sp.edit().putBoolean("sound", v).apply()

    /** User has seen and accepted the notification-access disclosure. */
    var disclosureAccepted: Boolean
        get() = sp.getBoolean("disclosure", false)
        set(v) = sp.edit().putBoolean("disclosure", v).apply()
}

/** Small rolling log of what the listener saw, shown in the app for debugging. */
object LogStore {
    private const val MAX = 40
    private val lines = ArrayDeque<String>()

    @Synchronized
    fun add(line: String) {
        val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        lines.addFirst("$ts  $line")
        while (lines.size > MAX) lines.removeLast()
    }

    @Synchronized
    fun dump(): String = if (lines.isEmpty()) "(nothing yet — start a WHOOP workout)" else lines.joinToString("\n")
}
