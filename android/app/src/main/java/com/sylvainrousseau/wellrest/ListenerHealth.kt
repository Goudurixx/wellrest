package com.sylvainrousseau.wellrest

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationManagerCompat

/**
 * Keeps WhoopListener bound. Android (Samsung especially) sometimes unbinds a notification
 * listener after the app is put to sleep or updated, while access stays granted, and
 * requestRebind() alone is not always honoured. Disabling and re-enabling the listener
 * component makes the system drop and re-create the binding.
 */
object ListenerHealth {

    fun hasAccess(ctx: Context) =
        NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)

    private fun component(ctx: Context) = ComponentName(ctx, WhoopListener::class.java)

    /** Gentle: ask the system to bind again. */
    fun requestRebind(ctx: Context) {
        runCatching { NotificationListenerService.requestRebind(component(ctx)) }
    }

    /** Forceful: toggle the component so the system rebinds it. */
    fun forceRebind(ctx: Context) {
        val pm = ctx.packageManager
        val c = component(ctx)
        runCatching {
            pm.setComponentEnabledSetting(c, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
            pm.setComponentEnabledSetting(c, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
        }.onFailure { LogStore.add("force rebind failed: ${it.message}") }
        requestRebind(ctx)
        LogStore.add("forced listener rebind")
    }
}
