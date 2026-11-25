package com.kinetic.launcher

import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** Is there working internet right now? Used to say "You're offline" instead of a confusing error. */
object Net {
    fun online(): Boolean {
        val ctx = KineticApp.context ?: return true
        return runCatching {
            val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return true
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }.getOrDefault(true)
    }

    /** The line to show under a lookup that failed. */
    fun why(otherwise: String) = if (online()) otherwise else "You're offline. Try again when you have signal"
}
