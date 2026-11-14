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
import android.media.AudioFocusRequest
import android.media.AudioManager
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
    /** Increments with every rest; a Skip tap only stops the rest it was shown on. */
    @Volatile var generation = 0; private set

    /**
     * RestService lifecycle. Android kills the app if a service started with
     * startForegroundService() is stopped before it has called startForeground(), so the
     * service is never stopped from outside while PENDING: it checks [isRunning] itself.
     */
    enum class Svc { NONE, PENDING, FOREGROUND }
    @Volatile var svc = Svc.NONE
    /** true while RestService holds the notification (card can be colorized) */
    val foreground get() = svc == Svc.FOREGROUND

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
        ensureChannel(app)
        handler.removeCallbacks(tick)

        generation++
        totalMs = seconds * 1000L
        endElapsed = SystemClock.elapsedRealtime() + totalMs
        nextName = next?.takeIf { it.isNotBlank() }
        isRunning = true

        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = (app.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WellRest:rest")
            .apply { acquire(totalMs + 10_000) }

        when (svc) {
            // Service already holds the card: just refresh it with the new countdown
            Svc.FOREGROUND, Svc.PENDING -> notifyNow(app)
            Svc.NONE -> try {
                svc = Svc.PENDING
                app.startForegroundService(Intent(app, RestService::class.java))
            } catch (t: Throwable) {
                svc = Svc.NONE
                LogStore.add("foreground service refused (${t.javaClass.simpleName}), using plain notification")
                notifyNow(app)
            }
        }
        tick.ctx = app
        handler.post(tick)
    }

    fun stop(ctx: Context) = finish(ctx.applicationContext, rang = false)

    /** Skip button: ignored if it belongs to an earlier rest. */
    fun skip(ctx: Context, gen: Int) {
        if (gen != generation) { LogStore.add("stale skip ignored"); return }
        LogStore.add("skipped by user")
        stop(ctx)
    }

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
        // Only stop the service once it is in the foreground; a PENDING one stops itself
        if (svc == Svc.FOREGROUND) {
            ctx.stopService(Intent(ctx, RestService::class.java))
            svc = Svc.NONE
        }
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
            ctx, generation, Intent(ctx, StopReceiver::class.java).putExtra(StopReceiver.EXTRA_GEN, generation),
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
    /**
     * End of rest: WellRest's own chime + double buzz.
     * The chime uses the MEDIA stream: it plays even in vibrate/silent mode, goes to headphones
     * when they're connected (phone speaker otherwise, at media volume), and briefly ducks the
     * user's music instead of pausing it.
     */
    private fun ring(ctx: Context) {
        if (Prefs(ctx).soundEnabled) playChime(ctx)

        @Suppress("DEPRECATION")
        val vib: Vibrator = if (Build.VERSION.SDK_INT >= 31)
            (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        else ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        vib.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 400, 150, 400), -1))
    }

    private fun playChime(ctx: Context) {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attrs)
            .build()
        runCatching {
            am.requestAudioFocus(focus)
            val afd = ctx.resources.openRawResourceFd(R.raw.rest_done)
            MediaPlayer().apply {
                setAudioAttributes(attrs)
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                afd.close()
                setOnCompletionListener { it.release(); am.abandonAudioFocusRequest(focus) }
                setOnErrorListener { mp, _, _ -> mp.release(); am.abandonAudioFocusRequest(focus); true }
                prepare()
                start()
            }
            if (am.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) LogStore.add("chime played but media volume is 0")
        }.onFailure {
            am.abandonAudioFocusRequest(focus)
            LogStore.add("sound failed: ${it.message}")
        }
    }

    /** Preview from the main screen toggle. */
    fun previewChime(ctx: Context) = playChime(ctx.applicationContext)

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
        // Always honour the startForeground() contract first, even if the rest was already
        // skipped or cancelled meanwhile: skipping it is what got the app killed before.
        RestTimer.svc = RestTimer.Svc.FOREGROUND
        try {
            ServiceCompat.startForeground(
                this, RestTimer.NOTIF_ID, RestTimer.build(this),
                if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
            )
        } catch (t: Throwable) {
            RestTimer.svc = RestTimer.Svc.NONE
            LogStore.add("startForeground failed (${t.javaClass.simpleName}), plain notification")
            stopSelf()
            return START_NOT_STICKY
        }
        if (!RestTimer.isRunning) {
            // Rest ended while the service was starting: leave cleanly
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            RestTimer.svc = RestTimer.Svc.NONE
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // A new rest may already have restarted the service; only reset if nothing is running
        if (!RestTimer.isRunning) RestTimer.svc = RestTimer.Svc.NONE
        super.onDestroy()
    }
}

class StopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        RestTimer.skip(context, intent?.getIntExtra(EXTRA_GEN, -1) ?: -1)
    }

    companion object {
        const val EXTRA_GEN = "gen"
    }
}
