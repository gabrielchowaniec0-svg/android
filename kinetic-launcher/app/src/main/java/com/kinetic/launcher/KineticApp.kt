package com.kinetic.launcher

import android.content.Context

/** Holds the application context for small helpers that need it (set by MainActivity / services). */
object KineticApp {
    @Volatile
    var context: Context? = null
        private set

    fun init(c: Context) {
        if (context == null) context = c.applicationContext
    }

    /** Where commands and live activities keep their small saved things (homework, laps, …). */
    fun pen(c: Context): android.content.SharedPreferences = c.getSharedPreferences("kinetic_pen", Context.MODE_PRIVATE)
}
