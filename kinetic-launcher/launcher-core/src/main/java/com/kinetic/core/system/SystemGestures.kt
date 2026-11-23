package com.kinetic.core.system

import android.annotation.SuppressLint
import android.content.Context

/** Hooks into system UI the way third-party launchers do. */
object SystemGestures {

    /**
     * Pulls down the standard Android notification shade.
     *
     * There's no public API for this, so — like Nova, Niagara and others — it calls
     * StatusBarManager.expandNotificationsPanel() by reflection. It needs the normal
     * (auto-granted) EXPAND_STATUS_BAR permission declared in the app manifest.
     * Returns false if the device blocks it.
     */
    @SuppressLint("WrongConstant", "PrivateApi")
    fun expandNotifications(context: Context): Boolean = try {
        val statusBar = context.getSystemService("statusbar")
        Class.forName("android.app.StatusBarManager")
            .getMethod("expandNotificationsPanel")
            .invoke(statusBar)
        true
    } catch (t: Throwable) {
        false
    }
}
