package com.kinetic.launcher

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import androidx.core.content.ContextCompat

/**
 * Focus triggers beyond the clock:
 *  - **Apps:** a Focus turns on while one of its chosen apps is open (Gaming when a
 *    game opens) and off when you leave them.
 *  - **Places:** a Focus turns on when you arrive somewhere (School at school) and
 *    off when you leave, using Android's built-in proximity alerts (no Google
 *    services needed). Needs location "Allow all the time".
 */
object FocusTriggers {

    private val IGNORED = setOf("com.android.systemui", "android")

    /** Called by the island service when another app comes to the front. */
    fun onForegroundApp(context: Context, pkg: String) {
        FocusModes.init(context)
        if (pkg in IGNORED || pkg.contains("inputmethod") || pkg.contains("honeyboard")) return
        val active = FocusModes.active.value
        val wanted = FocusModes.modes.value.firstOrNull { pkg in it.triggerApps }
        when {
            wanted != null && active == null -> FocusModes.turnOnAuto(context, wanted.id, "app")
            wanted == null && active != null && FocusModes.autoSource == "app" && pkg !in active.triggerApps ->
                FocusModes.turnOff(context)
        }
    }

    // ---- places ---------------------------------------------------------------------

    fun hasLocation(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun hasBackgroundLocation(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun intentFor(context: Context, modeId: String): PendingIntent = PendingIntent.getBroadcast(
        context, modeId.hashCode(),
        Intent(context, FocusPlaceReceiver::class.java).setData(Uri.parse("kinetic://focus/$modeId")),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )

    /** (Re)registers the arrive/leave alerts for every Focus with a place. */
    @SuppressLint("MissingPermission")
    fun registerPlaces(context: Context) {
        FocusModes.init(context)
        if (!hasLocation(context) || !hasBackgroundLocation(context)) return
        val lm = context.getSystemService(LocationManager::class.java) ?: return
        FocusModes.modes.value.forEach { m ->
            val pi = intentFor(context, m.id)
            runCatching { lm.removeProximityAlert(pi) }
            val pl = m.place ?: return@forEach
            runCatching { lm.addProximityAlert(pl.lat, pl.lng, pl.radiusM, -1, pi) }
        }
    }

    /** Gets where you are now (for "Use my current location"). */
    @SuppressLint("MissingPermission")
    fun currentLocation(context: Context, onResult: (Double, Double) -> Unit, onFail: () -> Unit) {
        if (!hasLocation(context)) return onFail()
        val lm = context.getSystemService(LocationManager::class.java) ?: return onFail()
        val provider = when {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> return onFail()
        }
        try {
            lm.getCurrentLocation(provider, null, context.mainExecutor) { loc ->
                if (loc != null) onResult(loc.latitude, loc.longitude) else onFail()
            }
        } catch (_: Exception) {
            onFail()
        }
    }

    internal fun onPlace(context: Context, modeId: String, entering: Boolean) {
        FocusModes.init(context)
        if (entering) {
            if (FocusModes.active.value == null) FocusModes.turnOnAuto(context, modeId, "place")
        } else if (FocusModes.active.value?.id == modeId && FocusModes.autoSource == "place") {
            FocusModes.turnOff(context)
        }
    }
}

/** Arriving at / leaving a Focus place. */
class FocusPlaceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.data?.lastPathSegment ?: return
        FocusTriggers.onPlace(context, id, intent.getBooleanExtra(LocationManager.KEY_PROXIMITY_ENTERING, false))
    }
}
