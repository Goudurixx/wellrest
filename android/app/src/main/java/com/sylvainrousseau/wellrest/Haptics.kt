package com.sylvainrousseau.wellrest

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * Crown-like haptics. Uses the vibrator's fine-grained primitives (crisp ticks on phones with a
 * good linear motor) and falls back to the system's standard haptic constants elsewhere.
 */
class Haptics(private val view: View) {

    private val vibrator: Vibrator = run {
        val ctx = view.context
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= 31)
            (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        else ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    private val primitives: Boolean = Build.VERSION.SDK_INT >= 31 && vibrator.areAllPrimitivesSupported(
        VibrationEffect.Composition.PRIMITIVE_LOW_TICK,
        VibrationEffect.Composition.PRIMITIVE_TICK,
        VibrationEffect.Composition.PRIMITIVE_CLICK,
        VibrationEffect.Composition.PRIMITIVE_THUD,
    )

    /** One detent of the dial (every 5 s). */
    fun detent() = if (primitives) compose(VibrationEffect.Composition.PRIMITIVE_LOW_TICK to 0.9f)
    else view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)

    /** Crossing a whole minute: a firmer click. */
    fun minute() = if (primitives) compose(VibrationEffect.Composition.PRIMITIVE_CLICK to 0.7f)
    else view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)

    /** Hitting the minimum or maximum: a dull bump. */
    fun limit() = if (primitives) compose(VibrationEffect.Composition.PRIMITIVE_THUD to 0.8f)
    else view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)

    /** Finger lands on the dial. */
    fun grab() = if (primitives) compose(VibrationEffect.Composition.PRIMITIVE_TICK to 0.4f)
    else view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)

    /** Start / stop button. */
    fun confirm() = if (primitives) compose(
        VibrationEffect.Composition.PRIMITIVE_CLICK to 0.9f,
        VibrationEffect.Composition.PRIMITIVE_TICK to 0.5f,
    ) else view.performHapticFeedback(
        if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY
    )

    private fun compose(vararg p: Pair<Int, Float>): Boolean {
        if (Build.VERSION.SDK_INT < 31) return false
        val c = VibrationEffect.startComposition()
        p.forEachIndexed { i, (id, scale) -> c.addPrimitive(id, scale, if (i == 0) 0 else 60) }
        vibrator.vibrate(c.compose())
        return true
    }
}
