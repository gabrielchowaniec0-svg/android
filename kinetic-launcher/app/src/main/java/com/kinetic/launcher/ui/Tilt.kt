package com.kinetic.launcher.ui

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
