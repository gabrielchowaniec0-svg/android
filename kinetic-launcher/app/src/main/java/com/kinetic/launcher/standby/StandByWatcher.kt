package com.kinetic.launcher.standby

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.os.BatteryManager
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.kinetic.launcher.KineticSettings

/**
 * Runs inside the island service: while the phone is charging, watches for it being
 * turned on its side and left still for a couple of seconds, then opens StandBy.
 * The motion sensor is only used while charging.
 */
class StandByWatcher(private val context: Context) : SensorEventListener {
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)
    private val audio = context.getSystemService(AudioManager::class.java)
    private var listening = false
    private var sidewaysSince = 0L
    /** After StandBy closes, wait until the phone has been upright again before reopening. */
    private var armed = true

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_POWER_CONNECTED -> listen(true)
                Intent.ACTION_POWER_DISCONNECTED -> listen(false)
            }
        }
    }

    fun start() {
        ContextCompat.registerReceiver(
            context, receiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        val charging = context.getSystemService(BatteryManager::class.java)?.isCharging == true
        listen(charging)
    }

    fun stop() {
        runCatching { context.unregisterReceiver(receiver) }
        listen(false)
    }

    private fun listen(on: Boolean) {
        if (on == listening) return
        listening = on
        sidewaysSince = 0L
        armed = true
        if (on) {
            sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        } else {
            sensors?.unregisterListener(this)
        }
    }

    override fun onSensorChanged(e: SensorEvent) {
        val x = e.values[0]
        val y = e.values[1]
        val z = e.values[2]
        val sideways = kotlin.math.abs(x) > 7.5f && kotlin.math.abs(z) < 6f && kotlin.math.abs(y) < 4f
        val upright = kotlin.math.abs(y) > 7.5f
        if (upright) armed = true
        val now = System.currentTimeMillis()
        if (!sideways) {
            sidewaysSince = 0L
            return
        }
        if (sidewaysSince == 0L) sidewaysSince = now
        if (now - sidewaysSince < 2500L || !armed || StandByActivity.showing) return
        if (!KineticSettings.current.standBy || !power.isInteractive || audio.mode != AudioManager.MODE_NORMAL) return
        // Only from the lock screen or the home screen (never over a video you're watching).
        val locked = context.getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked == true
        if (!locked && !com.kinetic.launcher.MainActivity.homeVisible) return
        armed = false
        runCatching {
            context.startActivity(Intent(context, StandByActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
