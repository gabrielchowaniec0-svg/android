package com.kinetic.launcher.island

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.MediaStore
import com.kinetic.launcher.FocusActivity
import com.kinetic.launcher.KineticSettings
import com.kinetic.launcher.MainActivity
import kotlin.math.sqrt

/**
 * Shake the phone (two firm shakes) to run your chosen action from anywhere:
 * flashlight, camera, Kinetic search, Focus or an app. Lives in the island's
 * always-running service; the motion sensor only runs while the screen is on and
 * the shake action isn't "off".
 */
class ShakeDetector(private val context: Context) : SensorEventListener {

    private val sensors = context.getSystemService(SensorManager::class.java)
    private val accel = sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val main = Handler(Looper.getMainLooper())
    private var listening = false
    private var firstShakeAt = 0L
    private var lastFiredAt = 0L
    private var torchOn = false

    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            torchOn = enabled
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) = update()
    }

    fun start() {
        context.registerReceiver(
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            },
            Context.RECEIVER_NOT_EXPORTED,
        )
        runCatching { context.getSystemService(CameraManager::class.java).registerTorchCallback(torchCallback, main) }
        // Re-check when the setting changes.
        main.post(object : Runnable {
            override fun run() {
                update()
                main.postDelayed(this, 30_000)
            }
        })
    }

    fun stop() {
        runCatching { context.unregisterReceiver(screenReceiver) }
        runCatching { context.getSystemService(CameraManager::class.java).unregisterTorchCallback(torchCallback) }
        main.removeCallbacksAndMessages(null)
        setListening(false)
    }

    private fun update() {
        val screenOn = context.getSystemService(PowerManager::class.java).isInteractive
        setListening(screenOn && KineticSettings.current.shakeAction != "off")
    }

    private fun setListening(on: Boolean) {
        if (on == listening || accel == null) return
        listening = on
        if (on) sensors.registerListener(this, accel, SensorManager.SENSOR_DELAY_UI) else sensors.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val (x, y, z) = Triple(event.values[0], event.values[1], event.values[2])
        val g = sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH
        if (g < 2.7f) return
        val now = System.currentTimeMillis()
        if (now - lastFiredAt < 1500) return
        // Two strong jolts within ~0.7 s = a deliberate shake (not a bump).
        if (now - firstShakeAt in 120..700) {
            lastFiredAt = now
            firstShakeAt = 0
            main.post { fire() }
        } else {
            firstShakeAt = now
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun fire() {
        val s = KineticSettings.current
        // Only when unlocked (except the flashlight, which is handy in the dark).
        val locked = context.getSystemService(KeyguardManager::class.java).isKeyguardLocked
        if (locked && s.shakeAction != "flashlight") return
        buzz()
        when (s.shakeAction) {
            "flashlight" -> toggleTorch()
            "camera" -> start(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
            "search" -> start(Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_SEARCH, true))
            "focus" -> FocusActivity.open(context)
            "app" -> context.packageManager.getLaunchIntentForPackage(s.shakeApp)?.let(::start)
        }
    }

    private fun toggleTorch() {
        try {
            val cm = context.getSystemService(CameraManager::class.java)
            val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true } ?: return
            cm.setTorchMode(id, !torchOn)
        } catch (_: Exception) {
        }
    }

    private fun buzz() {
        runCatching {
            context.getSystemService(Vibrator::class.java).vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK))
        }
    }

    private fun start(intent: Intent) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
        }
    }
}
