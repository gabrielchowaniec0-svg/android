package com.kinetic.launcher.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.PowerManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext

/**
 * How the phone is being tilted, as x (left/right) and y (towards/away) in -1..1.
 * It slowly re-centres on however you're holding the phone, so only tilting it
 * moves things. Used for the 3D parallax on the home screen.
 *
 * Only listens to the sensor while [active] (e.g. home screen visible), to save battery.
 */
@Composable
fun rememberDeviceTilt(active: Boolean): State<Offset> {
    val context = LocalContext.current
    val tilt = remember { mutableStateOf(Offset.Zero) }
    DisposableEffect(active) {
        val sensors = context.getSystemService(SensorManager::class.java)
        val sensor = sensors?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        if (!active || sensors == null || sensor == null) {
            tilt.value = Offset.Zero
            return@DisposableEffect onDispose { }
        }
        val listener = TiltListener { x, y -> tilt.value = Offset(x, y) }
        sensors.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        onDispose {
            sensors.unregisterListener(listener)
            tilt.value = Offset.Zero
        }
    }
    return tilt
}

/** Smoothed, self-centring tilt from the game rotation vector (no compass needed). */
class TiltListener(private val onTilt: (x: Float, y: Float) -> Unit) : SensorEventListener {
    private val rotation = FloatArray(9)
    private val orientation = FloatArray(3)
    private var basePitch = Float.NaN
    private var baseRoll = 0f
    private var x = 0f
    private var y = 0f

    override fun onSensorChanged(event: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(rotation, event.values)
        SensorManager.getOrientation(rotation, orientation)
        val pitch = orientation[1]
        val roll = orientation[2]
        if (basePitch.isNaN()) {
            basePitch = pitch
            baseRoll = roll
        }
        // Follow the resting angle slowly so only deliberate tilts show.
        basePitch += (pitch - basePitch) * 0.02f
        baseRoll += (roll - baseRoll) * 0.02f
        val tx = ((roll - baseRoll) / MAX_ANGLE).coerceIn(-1f, 1f)
        val ty = ((pitch - basePitch) / MAX_ANGLE).coerceIn(-1f, 1f)
        x += (tx - x) * 0.25f
        y += (ty - y) * 0.25f
        onTilt(x, y)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private companion object {
        const val MAX_ANGLE = 0.35f // ~20° of tilt = full effect
    }
}

/**
 * True while Battery Saver is on. The tilt effect keeps the motion sensor (and
 * the CPU that reads it) awake, so it's paused in Battery Saver.
 */
@Composable
fun rememberPowerSaveMode(): Boolean {
    val context = LocalContext.current
    val power = remember { context.getSystemService(PowerManager::class.java) }
    var on by remember { mutableStateOf(power?.isPowerSaveMode == true) }
    DisposableEffect(power) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                on = power?.isPowerSaveMode == true
            }
        }
        // Protected system broadcast: still delivered with NOT_EXPORTED.
        context.registerReceiver(
            receiver,
            IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
            Context.RECEIVER_NOT_EXPORTED,
        )
        on = power?.isPowerSaveMode == true
        onDispose { context.unregisterReceiver(receiver) }
    }
    return on
}
