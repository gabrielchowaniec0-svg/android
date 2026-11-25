package com.kinetic.launcher

import android.app.Application
import com.kinetic.wallpaper.DiagLog

/** Runs first in every Kinetic process: sets up the problem log (crashes are recorded on the phone). */
class KineticApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        KineticApp.init(this)
        DiagLog.init(this)
        DiagLog.i("app", "started")
    }
}
