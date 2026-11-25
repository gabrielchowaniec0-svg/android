package com.kinetic.launcher.ui

import android.view.HapticFeedbackConstants
import android.view.View
import com.kinetic.launcher.KineticSettings

/**
 * Every Kinetic vibration goes through here, so they all feel the same and all follow
 * Settings → Haptics (off means off everywhere, not just in the island).
 */
object Haptics {
    private fun on() = KineticSettings.current.haptics

    /** A tiny tick: a step on a slider, a brightness level, hovering over a folder. */
    fun tick(v: View?) { if (on()) v?.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }

    /** A light tap: pressing something. */
    fun press(v: View?) { if (on()) v?.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK) }

    /** Something worked: saved, dropped into a folder, recording started. */
    fun confirm(v: View?) { if (on()) v?.performHapticFeedback(HapticFeedbackConstants.CONFIRM) }

    /** Picked something up / long-pressed. */
    fun hold(v: View?) { if (on()) v?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) }

    /** Didn't work (wrong code, not allowed). */
    fun reject(v: View?) { if (on()) v?.performHapticFeedback(HapticFeedbackConstants.REJECT) }
}
