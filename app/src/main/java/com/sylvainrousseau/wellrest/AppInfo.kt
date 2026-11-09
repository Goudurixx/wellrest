package com.sylvainrousseau.wellrest

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast

/** Store-facing app details. Fill these in before publishing. */
object AppInfo {
    /** Public URL of the privacy policy (same URL as in Play Console). See docs/privacy-policy.html. */
    const val PRIVACY_URL = "https://goudurixx.github.io/wellrest/privacy-policy.html"
    /** Support contact shown in Settings → About (Play also requires one in the Console). */
    const val SUPPORT_EMAIL = "sylvain.rousseau.apps@gmail.com"

    const val DISCLAIMER =
        "WellRest is an independent app. It is not affiliated with, endorsed or sponsored by " +
        "WHOOP, Inc. WHOOP is a trademark of WHOOP, Inc."

    /** Short, plain-language privacy summary — the full policy says the same thing at length. */
    const val PRIVACY_SUMMARY =
        "• WellRest reads the notifications posted by the WHOOP app on this device, only to detect " +
        "when a set ends and to show the next exercise name.\n\n" +
        "• Notifications from every other app are ignored immediately and are never read, stored or shared.\n\n" +
        "• Everything happens on your phone. The app has no internet permission, no account, no ads " +
        "and no analytics. Nothing ever leaves the device.\n\n" +
        "• Your settings (rest length, toggles) are stored on the device only. A short diagnostic log " +
        "of WHOOP card text is kept in memory and cleared when the app closes.\n\n" +
        "• You can revoke notification access at any time in Android Settings, or uninstall the app to " +
        "remove all of its data."

    const val LICENSES =
        "AndroidX Core (androidx.core:core-ktx)\nCopyright The Android Open Source Project\n" +
        "Apache License 2.0\n\n" +
        "Kotlin Standard Library\nCopyright JetBrains s.r.o. and Kotlin contributors\n" +
        "Apache License 2.0\n\n" +
        "Full licence text: https://www.apache.org/licenses/LICENSE-2.0"

    fun version(ctx: Context): String {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
        return "${pi.versionName} ($code)"
    }

    /** True when installed by Google Play: "restricted settings" then doesn't apply. */
    fun installedFromPlay(ctx: Context): Boolean = runCatching {
        val installer = if (Build.VERSION.SDK_INT >= 30)
            ctx.packageManager.getInstallSourceInfo(ctx.packageName).installingPackageName
        else @Suppress("DEPRECATION") ctx.packageManager.getInstallerPackageName(ctx.packageName)
        installer == "com.android.vending"
    }.getOrDefault(false)

    /**
     * Prominent disclosure required by Google Play's User Data policy, shown in-app before
     * sending the user to the system Notification access screen.
     */
    fun requestNotificationAccess(activity: Activity) {
        AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Allow notification access")
            .setMessage(
                "To start your rest timer automatically, WellRest needs Notification access.\n\n" +
                "It uses it only to read the WHOOP app's workout notification, to detect when a set ends " +
                "and show the next exercise.\n\n" +
                "Notifications from other apps are ignored. Nothing is stored or sent anywhere: " +
                "the app has no internet access.\n\n" +
                "On the next screen, turn on WellRest. You can turn it off at any time."
            )
            .setPositiveButton("Continue") { _, _ ->
                Prefs(activity).disclosureAccepted = true
                activity.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
            .setNegativeButton("Not now", null)
            .show()
    }

    fun showText(activity: Activity, title: String, body: String) {
        AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(title).setMessage(body).setPositiveButton("OK", null).show()
    }

    fun open(activity: Activity, uri: String) {
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(activity, "No app can open this link", Toast.LENGTH_SHORT).show()
        }
    }
}
