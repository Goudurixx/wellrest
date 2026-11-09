package com.sylvainrousseau.wellrest

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * Countdown engine. Lives in the app process (kept alive by the notification listener).
 * The notification is shown through [RestService] as a foreground service when Android
 * allows it, which lets the card be fully colorized like Whoop's. Otherwise it falls back
 * to a regular notification with its own dark background.
 */
object RestTimer {
    const val NOTIF_ID = 42
    private const val CHANNEL = "rest_countdown_v2"
    private const val CARD_COLOR = 0xFF1B2228.toInt()

    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile var isRunning = false; private set
    private var endElapsed = 0L
    private var totalMs = 0L
    private var nextName: String? = null
    /** true while RestService holds the notification as a foreground service */
    @Volatile var foreground = false

    private val tick = object : Runnable {
        lateinit var ctx: Context
        override fun run() {
            val left = endElapsed - SystemClock.elapsedRealtime()
            if (left <= 0) { finish(ctx, rang = true); return }
            notifyNow(ctx)
            handler.postDelayed(this, (left % 1000).let { if (it < 50) 1000 else it }.coerceAtMost(1000))
        }
    }

    fun start(ctx: Context, seconds: Int, next: String? = null) {
        val app = ctx.applicationContext
        cancelInternal(app)
        ensureChannel(app)

        totalMs = seconds * 1000L
        endElapsed = SystemClock.elapsedRealtime() + totalMs
        nextName = next?.takeIf { it.isNotBlank() }
        isRunning = true

        wakeLock = (app.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WellRest:rest")
            .apply { acquire(totalMs + 10_000) }

        foreground = false
        try {
            app.startForegroundService(Intent(app, RestService::class.java))
        } catch (t: Throwable) {
            LogStore.add("foreground service refused (${t.javaClass.simpleName}), using plain notification")
            notifyNow(app)
        }
        tick.ctx = app
        handler.post(tick)
    }

    fun stop(ctx: Context) = finish(ctx.applicationContext, rang = false)

    private fun finish(ctx: Context, rang: Boolean) {
        val wasRunning = isRunning
        cancelInternal(ctx)
        if (rang && wasRunning) {
            ring(ctx)
            LogStore.add("rest done, rang")
        }
    }

    private fun cancelInternal(ctx: Context) {
        handler.removeCallbacks(tick)
        isRunning = false
        ctx.stopService(Intent(ctx, RestService::class.java))
        nm(ctx).cancel(NOTIF_ID)
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    private fun notifyNow(ctx: Context) {
        if (!isRunning) return
        runCatching { nm(ctx).notify(NOTIF_ID, build(ctx)) }
    }

    fun build(ctx: Context): Notification {
        val now = SystemClock.elapsedRealtime()
        val left = (endElapsed - now).coerceAtLeast(0)
        val progress = if (totalMs > 0) (left * 1000 / totalMs).toInt() else 0
        val label = "Rest"
        val next = nextName?.let { "Up next: $it" } ?: "Next set"
        // Chronometer counts toward its base; +999 so it shows 1:00 → 0:01 rather than 0:59 → 0:00
        val base = now + left + 999

        val skipPi = PendingIntent.getBroadcast(
            ctx, 0, Intent(ctx, StopReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val openPi = PendingIntent.getActivity(
            ctx, 1, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        fun views(layout: Int) = RemoteViews(ctx.packageName, layout).apply {
            setTextViewText(R.id.label, label)
            setTextViewText(R.id.next, next)
            setChronometer(R.id.chrono, base, null, true)
            setChronometerCountDown(R.id.chrono, true)
            setOnClickPendingIntent(R.id.skip, skipPi)
            // When not colorized the shade is light: give the card its own dark background
            if (!foreground) setInt(R.id.root, "setBackgroundResource", R.drawable.bg_notif)
            if (!foreground) setViewPadding(R.id.root, 24, 16, 24, 16)
        }
        val small = views(R.layout.notif_rest_small)
        val big = views(R.layout.notif_rest_big).apply {
            setTextViewText(R.id.total, fmt(totalMs))
            setProgressBar(R.id.progress, 1000, progress, false)
        }

        return NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(label)
            .setContentText(next)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(small)
            .setCustomBigContentView(big)
            .setCustomHeadsUpContentView(small)
            .setColor(CARD_COLOR)
            .setColorized(true) // honoured only for foreground-service notifications
            .setContentIntent(openPi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    fun fmt(ms: Long): String {
        val s = (ms / 1000).toInt()
        return "%d:%02d".format(s / 60, s % 60)
    }

    /** One short sound on the ALARM stream (audible on silent/vibrate) + double buzz. */
    private fun ring(ctx: Context) {
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        runCatching {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(ctx, uri)
                isLooping = false
                setOnCompletionListener { it.release() }
                setOnErrorListener { mp, _, _ -> mp.release(); true }
                prepare()
                start()
            }
        }.onFailure { LogStore.add("sound failed: ${it.message}") }

        @Suppress("DEPRECATION")
        val vib: Vibrator = if (Build.VERSION.SDK_INT >= 31)
            (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        else ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        vib.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 400, 150, 400), -1))
    }

    private fun ensureChannel(ctx: Context) {
        nm(ctx).createNotificationChannel(
            NotificationChannel(CHANNEL, "Rest countdown", NotificationManager.IMPORTANCE_DEFAULT).apply {
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            }
        )
        nm(ctx).deleteNotificationChannel("rest_countdown") // v1 channel
    }

    private fun nm(ctx: Context) = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
}

/** Holds the rest notification as a foreground service so it can be colorized. */
class RestService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        RestTimer.foreground = true
        val n = RestTimer.build(this)
        try {
            ServiceCompat.startForeground(
                this, RestTimer.NOTIF_ID, n,
                if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
            )
        } catch (t: Throwable) {
            RestTimer.foreground = false
            LogStore.add("startForeground failed (${t.javaClass.simpleName}), plain notification")
            stopSelf()
            return START_NOT_STICKY
        }
        if (!RestTimer.isRunning) stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        RestTimer.foreground = false
        super.onDestroy()
    }
}

class StopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        LogStore.add("skipped by user")
        RestTimer.stop(context)
    }
}
