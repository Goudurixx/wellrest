package com.sylvainrousseau.wellrest

import android.app.Activity
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Store-facing app details and the app's dialogs. */
object AppInfo {
    /** Public URL of the privacy policy (same URL as in Play Console). See docs/privacy-policy.html. */
    const val PRIVACY_URL = "https://goudurixx.github.io/wellrest/privacy-policy.html"
    /** Support contact shown in Settings → About (Play also requires one in the Console). */
    const val SUPPORT_EMAIL = "sylvain.rousseau.apps@gmail.com"

    const val DISCLAIMER =
        "WellRest is an independent app. It is not affiliated with, endorsed or sponsored by " +
        "WHOOP, Inc. WHOOP is a trademark of WHOOP, Inc."

    private val PRIVACY_POINTS = listOf(
        "Reads only the WHOOP app's workout notification, to detect when a set ends and show the next exercise.",
        "Notifications from every other app are ignored and never read, stored or shared.",
        "No internet permission, no account, no ads, no analytics. Nothing leaves your phone.",
        "Settings stay on the device. The diagnostic log lives in memory and is cleared when the app closes.",
        "Revoke notification access anytime in Android Settings, or uninstall to remove all data.",
    )

    private const val LICENSES =
        "AndroidX Core (androidx.core:core-ktx)\nCopyright The Android Open Source Project\nApache License 2.0\n\n" +
        "Kotlin Standard Library\nCopyright JetBrains s.r.o. and Kotlin contributors\nApache License 2.0\n\n" +
        "Full licence text: apache.org/licenses/LICENSE-2.0"

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
        sheet(
            activity,
            icon = R.drawable.ic_bell,
            title = "Start rests automatically",
            body = "WellRest needs Notification access to notice when WHOOP switches from a set to “Up Next”.",
            points = listOf(
                "Only WHOOP's workout card is read. Other apps are ignored.",
                "Nothing is stored or sent. WellRest has no internet access.",
                "You can turn it off anytime in Android settings.",
            ),
            hint = "On the next screen, turn on WellRest.",
            primary = "Continue",
            secondary = "Not now",
        ) {
            Prefs(activity).disclosureAccepted = true
            activity.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
    }

    fun showPrivacy(activity: Activity) = sheet(
        activity, icon = R.drawable.ic_shield, title = "Your privacy",
        points = PRIVACY_POINTS,
        primary = if (PRIVACY_URL.isNotEmpty()) "Read full policy" else "Done",
        secondary = if (PRIVACY_URL.isNotEmpty()) "Close" else null,
    ) { if (PRIVACY_URL.isNotEmpty()) open(activity, PRIVACY_URL) }

    fun showLicenses(activity: Activity) = sheet(
        activity, icon = R.drawable.ic_info, title = "Open-source licences",
        body = LICENSES, primary = "Done",
    )

    /** App-styled dialog: dark rounded sheet, icon badge, check-mark points, green button. */
    fun sheet(
        activity: Activity,
        icon: Int,
        title: String,
        body: String? = null,
        points: List<String> = emptyList(),
        hint: String? = null,
        primary: String,
        secondary: String? = null,
        onSecondary: () -> Unit = {},
        onPrimary: () -> Unit = {},
    ) {
        val d = Dialog(activity)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        d.setContentView(R.layout.dialog_sheet)
        val dp = activity.resources.displayMetrics.density

        d.findViewById<ImageView>(R.id.icon).setImageResource(icon)
        d.findViewById<TextView>(R.id.title).text = title
        d.findViewById<TextView>(R.id.body).apply {
            text = body; visibility = if (body.isNullOrEmpty()) View.GONE else View.VISIBLE
        }
        d.findViewById<TextView>(R.id.hint).apply {
            text = hint; visibility = if (hint.isNullOrEmpty()) View.GONE else View.VISIBLE
        }
        val list = d.findViewById<LinearLayout>(R.id.points)
        points.forEach { p ->
            list.addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, (10 * dp).toInt(), 0, 0)
                addView(ImageView(activity).apply { setImageResource(R.drawable.ic_check) },
                    LinearLayout.LayoutParams((20 * dp).toInt(), (20 * dp).toInt()).apply { topMargin = (1 * dp).toInt() })
                addView(TextView(activity).apply {
                    text = p; textSize = 15f; setTextColor(activity.getColor(R.color.text))
                    setLineSpacing(0f, 1.1f)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = (12 * dp).toInt()
                })
            })
        }
        d.findViewById<TextView>(R.id.primary).apply {
            text = primary
            setOnClickListener { d.dismiss(); onPrimary() }
        }
        d.findViewById<TextView>(R.id.secondary).apply {
            if (secondary != null) { text = secondary; visibility = View.VISIBLE; setOnClickListener { d.dismiss(); onSecondary() } }
        }

        // Keep long content scrollable inside a sheet that never exceeds ~70% of the screen
        val maxBody = (activity.resources.displayMetrics.heightPixels * 0.45).toInt()
        d.findViewById<ScrollView>(R.id.scroll).viewTreeObserver.addOnGlobalLayoutListener {
            val sv = d.findViewById<ScrollView>(R.id.scroll)
            if (sv.height > maxBody) sv.layoutParams = sv.layoutParams.apply { height = maxBody }
        }

        d.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val w = minOf((activity.resources.displayMetrics.widthPixels - 32 * dp).toInt(), (420 * dp).toInt())
            setLayout(w, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.CENTER)
            setDimAmount(0.7f)
            setWindowAnimations(android.R.style.Animation_Dialog)
        }
        d.show()
    }

    fun open(activity: Activity, uri: String) {
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(activity, "No app can open this link", Toast.LENGTH_SHORT).show()
        }
    }
}
