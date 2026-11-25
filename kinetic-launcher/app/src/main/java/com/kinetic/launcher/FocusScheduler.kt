package com.kinetic.launcher

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import java.util.Calendar

/**
 * Focus schedules (like iOS): each Focus can switch itself on at a start time and
 * off at an end time on chosen days. Uses one alarm for the next start/end and
 * re-plans after each one (and after a restart or a clock change).
 */
object FocusScheduler {

    private const val ACTION = "com.kinetic.launcher.FOCUS_SCHEDULE"
    private const val EXTRA_MODE = "mode"
    private const val EXTRA_ON = "on"

    private data class Boundary(val at: Long, val modeId: String, val on: Boolean)

    /** Plans the alarm for the next start or end of any enabled schedule. */
    fun scheduleNext(context: Context) {
        FocusModes.init(context)
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = pending(context, null)
        am.cancel(pi)
        val next = nextBoundary(System.currentTimeMillis()) ?: return
        val intent = Intent(context, FocusScheduleReceiver::class.java).setAction(ACTION)
            .putExtra(EXTRA_MODE, next.modeId).putExtra(EXTRA_ON, next.on)
        // A one-minute window needs no special "exact alarm" permission.
        am.setWindow(AlarmManager.RTC_WAKEUP, next.at, 60_000L, pending(context, intent))
    }

    private fun pending(context: Context, intent: Intent?): PendingIntent = PendingIntent.getBroadcast(
        context, 7,
        intent ?: Intent(context, FocusScheduleReceiver::class.java).setAction(ACTION),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun nextBoundary(now: Long): Boundary? {
        var best: Boundary? = null
        FocusModes.modes.value.filter { it.schedule.enabled && it.schedule.days != 0 }.forEach { m ->
            for (dayOffset in 0..7) {
                val cal = Calendar.getInstance().apply {
                    timeInMillis = now
                    add(Calendar.DAY_OF_YEAR, dayOffset)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                val monday0 = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7
                // Starts happen on the chosen days; an overnight end belongs to the day it started.
                if (m.schedule.onDay(monday0)) {
                    val start = at(cal, m.schedule.startMin)
                    if (start > now && (best == null || start < best!!.at)) best = Boundary(start, m.id, true)
                    val overnight = m.schedule.endMin <= m.schedule.startMin
                    val endCal = (cal.clone() as Calendar).apply { if (overnight) add(Calendar.DAY_OF_YEAR, 1) }
                    val end = at(endCal, m.schedule.endMin)
                    if (end > now && (best == null || end < best!!.at)) best = Boundary(end, m.id, false)
                }
            }
        }
        return best
    }

    private fun at(cal: Calendar, minuteOfDay: Int): Long = (cal.clone() as Calendar).apply {
        set(Calendar.HOUR_OF_DAY, minuteOfDay / 60)
        set(Calendar.MINUTE, minuteOfDay % 60)
    }.timeInMillis

    internal fun fire(context: Context, modeId: String?, on: Boolean) {
        FocusModes.init(context)
        if (modeId != null) {
            if (on) FocusModes.turnOnAuto(context, modeId, "schedule")
            else if (FocusModes.active.value?.id == modeId) FocusModes.turnOff(context)
        }
        scheduleNext(context)
    }

    // ---- car Bluetooth ------------------------------------------------------------------

    internal fun carChanged(context: Context, device: BluetoothDevice?, connected: Boolean) {
        FocusModes.init(context)
        if (device == null) return
        if (Build.VERSION.SDK_INT >= 31 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) return
        val isCar = try {
            val cls = device.bluetoothClass?.deviceClass
            cls == BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO || cls == BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE
        } catch (_: SecurityException) {
            false
        }
        if (!isCar) return
        val mode = FocusModes.modes.value.firstOrNull { it.carTrigger } ?: return
        if (connected) FocusModes.turnOnAuto(context, mode.id, "car")
        else if (FocusModes.active.value?.id == mode.id) FocusModes.turnOff(context)
    }
}

/** Alarm for a schedule boundary, plus re-planning after a restart or clock change. */
class FocusScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            "com.kinetic.launcher.FOCUS_SCHEDULE" ->
                FocusScheduler.fire(context, intent.getStringExtra("mode"), intent.getBooleanExtra("on", false))
            else -> {
                FocusScheduler.scheduleNext(context)
                FocusTriggers.registerPlaces(context)
                Recovery.run(context)
            }
        }
    }
}

/** "Driving" Focus: on when car Bluetooth connects, off when it disconnects. */
class FocusCarReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
        FocusScheduler.carChanged(context, device, intent.action == BluetoothDevice.ACTION_ACL_CONNECTED)
    }
}
