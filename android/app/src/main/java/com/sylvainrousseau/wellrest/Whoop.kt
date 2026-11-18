package com.sylvainrousseau.wellrest

import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/** Opening the WHOOP app (package from Settings, default com.whoop.android). */
object Whoop {
    fun launchIntent(ctx: Context): Intent? =
        ctx.packageManager.getLaunchIntentForPackage(Prefs(ctx).pkg)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)

    fun isInstalled(ctx: Context) = launchIntent(ctx) != null

    fun open(ctx: Context): Boolean {
        val i = launchIntent(ctx) ?: return false
        ctx.startActivity(i)
        return true
    }

    fun openPendingIntent(ctx: Context): PendingIntent? {
        val i = launchIntent(ctx) ?: return null
        return PendingIntent.getActivity(ctx, 9, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
}
