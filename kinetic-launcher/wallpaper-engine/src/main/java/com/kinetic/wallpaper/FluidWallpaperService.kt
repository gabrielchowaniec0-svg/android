package com.kinetic.wallpaper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.service.wallpaper.WallpaperService
import android.view.Choreographer
import android.view.MotionEvent
import android.view.SurfaceHolder
import androidx.annotation.RequiresApi
import android.os.BatteryManager
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Live wallpaper that renders the chosen [WallpaperStyles] style through a hardware canvas,
 * paced to the panel's refresh by Choreographer (up to 120Hz on the S25 Ultra).
 *
 * AGSL / RuntimeShader are API 33+. Guard the service in the manifest with
 * android:enabled behind a minSdk of 33, or feature-detect before offering it.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class FluidWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = FluidEngine()

    private inner class FluidEngine : Engine(), Choreographer.FrameCallback, SensorEventListener {

        // The look (style, colours, speed…) comes from Settings ▸ Wallpaper and reloads live.
        private val prefs = WallpaperSignals.prefs(this@FluidWallpaperService)
        private var look = WallpaperStyles.load(prefs)
        private var shader: RuntimeShader = WallpaperStyles.shaderFor(look.style)
        private var dim = 0f
        private val lookListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
            if (key == "focus_dim") WallpaperSignals.focusDim = p.getFloat(key, 0f)
            if (!WallpaperStyles.isLookKey(key)) return@OnSharedPreferenceChangeListener
            val newLook = WallpaperStyles.load(p)
            if (newLook.style != look.style) {
                shader = WallpaperStyles.shaderFor(newLook.style)
                if (surfaceWidth > 0) shader.setFloatUniform("iResolution", surfaceWidth.toFloat(), surfaceHeight.toFloat())
            }
            look = newLook
        }
        private val paint = Paint()
        private val choreographer = Choreographer.getInstance()

        private var surfaceWidth = 0
        private var surfaceHeight = 0
        private var running = false
        private val startNanos = System.nanoTime()

        // smoothed touch point handed to the shader as iTouch
        private var touchX = 0f
        private var touchY = 0f
        private var targetX = 0f
        private var targetY = 0f
        private var touchActive = false

        // Softening while the launcher's app drawer / folders are open (eased each frame).
        private var focus = 0f
        private var focusTarget = 0f

        // Tilt parallax from the game rotation vector, self-centring.
        private val sensors = getSystemService(SensorManager::class.java)
        private val rotationSensor = sensors?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        private val rotation = FloatArray(9)
        private val orientation = FloatArray(3)
        private var basePitch = Float.NaN
        private var baseRoll = 0f
        private var tiltTargetX = 0f
        private var tiltTargetY = 0f
        private var tiltX = 0f
        private var tiltY = 0f
        private var sensorOn = false

        // ---- reactions (see WallpaperSignals) ----------------------------------------
        private val pendingRipples = ConcurrentLinkedQueue<WallpaperSignals.Ripple>()
        private val rippleListener = WallpaperSignals.Listener { pendingRipples.add(it) }
        /** Three ripple slots: x, y (shader space), start time, strength; plus colours. */
        private val rippleSlots = Array(3) { FloatArray(4) }
        private val rippleColors = Array(3) { FloatArray(3) }
        private var nextSlot = 0
        private var glow = 0f
        private val glowRgb = FloatArray(3)
        private var calm = 0f
        private var rain = 0f
        private var flies = 0f
        private var snow = 0f
        private var fog = 0f
        private var windU = 0f
        private var warmU = 0f
        private var nightU = 0f
        // Sky details (moon, stars, planes, clouds, weather): lock screen only by default.
        private val keyguard = getSystemService(android.app.KeyguardManager::class.java)
        private var skyVis = 0f
        private var skyTarget = 0f
        private var lastSkyCheck = 0L
        private var cloudsU = 0f
        private var starsOn = true
        private var planesOn = true
        private var linesOn = true
        private val density = resources.displayMetrics.density
        private var tideOn = false
        private var tideLevel = 1f
        private var tideCharging = false
        private var calmTarget = 0f
        private var lastBatteryCheck = 0L
        private var lastFrameNanos = 0L
        /** Wallpaper time; runs slower when it's calming down for a low battery. */
        private var time = 0f
        private val battery = getSystemService(BatteryManager::class.java)

        // Pause the tilt sensor in Battery Saver (it keeps the sensor and CPU awake).
        private val power = getSystemService(PowerManager::class.java)
        private val powerSaveReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = updateSensor()
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            setTouchEventsEnabled(true)
            WallpaperSignals.addListener(rippleListener)
            WallpaperSignals.focusDim = prefs.getFloat("focus_dim", 0f)
            prefs.registerOnSharedPreferenceChangeListener(lookListener)
            registerReceiver(
                powerSaveReceiver,
                IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
                Context.RECEIVER_NOT_EXPORTED,
            )
        }

        override fun onDestroy() {
            runCatching { unregisterReceiver(powerSaveReceiver) }
            WallpaperSignals.removeListener(rippleListener)
            prefs.unregisterOnSharedPreferenceChangeListener(lookListener)
            stop()
            super.onDestroy()
        }

        /** Sensor on only while the wallpaper is visible and Battery Saver is off. */
        private fun updateSensor() {
            val want = running && rotationSensor != null && power?.isPowerSaveMode != true
            if (want && !sensorOn) {
                basePitch = Float.NaN
                sensors?.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_GAME)
                sensorOn = true
            } else if (!want && sensorOn) {
                sensors?.unregisterListener(this)
                sensorOn = false
                // Let the wallpaper ease back to centre.
                tiltTargetX = 0f
                tiltTargetY = 0f
            }
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            surfaceWidth = width
            surfaceHeight = height
            shader.setFloatUniform("iResolution", width.toFloat(), height.toFloat())
        }

        /** The launcher asks the wallpaper to soften behind its app drawer, like One UI's blur. */
        override fun onCommand(
            action: String?,
            x: Int,
            y: Int,
            z: Int,
            extras: Bundle?,
            resultRequested: Boolean,
        ): Bundle? {
            when (action) {
                ACTION_FOCUS_ON -> focusTarget = 1f
                ACTION_FOCUS_OFF -> focusTarget = 0f
            }
            return null
        }

        override fun onVisibilityChanged(visible: Boolean) {
            if (visible) start() else stop()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            stop()
            super.onSurfaceDestroyed(holder)
        }

        override fun onTouchEvent(event: MotionEvent) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    targetX = event.x
                    targetY = event.y
                    touchActive = true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> touchActive = false
            }
            super.onTouchEvent(event)
        }

        private fun start() {
            if (running) return
            running = true
            updateSensor()
            choreographer.postFrameCallback(this)
        }

        private fun stop() {
            running = false
            updateSensor()
            choreographer.removeFrameCallback(this)
        }

        override fun onSensorChanged(event: SensorEvent) {
            SensorManager.getRotationMatrixFromVector(rotation, event.values)
            SensorManager.getOrientation(rotation, orientation)
            val pitch = orientation[1]
            val roll = orientation[2]
            if (basePitch.isNaN()) {
                basePitch = pitch
                baseRoll = roll
            }
            basePitch += (pitch - basePitch) * 0.02f
            baseRoll += (roll - baseRoll) * 0.02f
            tiltTargetX = ((roll - baseRoll) / 0.35f).coerceIn(-1f, 1f)
            tiltTargetY = ((pitch - basePitch) / 0.35f).coerceIn(-1f, 1f)
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

        private var lastDrawNanos = 0L
        private var motionMode = "balanced"

        /**
         * True while nothing on the wallpaper needs full smoothness: no finger on it, no
         * ripple spreading, no tilt or blur easing. Then (in Balanced) it draws at 30 fps.
         */
        private fun stillNow(): Boolean {
            if (touchActive || kotlin.math.abs(touchX) > 0.5f || kotlin.math.abs(touchY) > 0.5f) return false
            if (kotlin.math.abs(focusTarget - focus) > 0.01f) return false
            if (kotlin.math.abs(tiltTargetX - tiltX) > 0.004f || kotlin.math.abs(tiltTargetY - tiltY) > 0.004f) return false
            if (kotlin.math.abs(skyTarget - skyVis) > 0.01f) return false
            for (s in rippleSlots) if (s[3] > 0f && time - s[2] < 3.2f) return false
            return true
        }

        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            // Battery: full smoothness only when something is moving (or "Full" is chosen).
            val saver = power?.isPowerSaveMode == true
            val minGap = when {
                motionMode == "full" && !saver -> 0L
                stillNow() || saver -> 30_000_000L // ~30 fps
                else -> 0L
            }
            if (minGap > 0L && frameTimeNanos - lastDrawNanos < minGap) {
                choreographer.postFrameCallback(this)
                return
            }
            lastDrawNanos = frameTimeNanos
            drawFrame(frameTimeNanos)
            // Re-post immediately; Choreographer paces us to the display refresh.
            choreographer.postFrameCallback(this)
        }

        private fun drawFrame(frameTimeNanos: Long) {
            if (surfaceWidth == 0 || surfaceHeight == 0) return

            // ease the touch point toward its target, then drift back to idle
            if (touchActive) {
                touchX += (targetX - touchX) * 0.18f
                touchY += (targetY - touchY) * 0.18f
            } else {
                touchX += (0f - touchX) * 0.08f
                touchY += (0f - touchY) * 0.08f
            }

            focus += (focusTarget - focus) * 0.16f
            tiltX += (tiltTargetX - tiltX) * 0.12f
            tiltY += (tiltTargetY - tiltY) * 0.12f

            // Calm down (slower, a touch darker) when the battery is low or Battery Saver is on.
            val nowMs = System.currentTimeMillis()
            if (nowMs - lastBatteryCheck > 5_000) {
                lastBatteryCheck = nowMs
                val enabled = WallpaperSignals.enabled(this@FluidWallpaperService, WallpaperSignals.KEY_BATTERY_CALM)
                val level = battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100
                val charging = battery?.isCharging == true
                tideOn = WallpaperSignals.enabled(this@FluidWallpaperService, WallpaperSignals.KEY_BATTERY_TIDE)
                tideLevel = level.coerceIn(0, 100) / 100f
                tideCharging = charging
                calmTarget = if (enabled && ((level in 1..20 && !charging) || power?.isPowerSaveMode == true)) 1f else 0f
            }
            calm += (calmTarget - calm) * 0.02f
            val dt = if (lastFrameNanos == 0L) 0f else ((frameTimeNanos - lastFrameNanos) / 1_000_000_000f).coerceIn(0f, 0.1f)
            lastFrameNanos = frameTimeNanos
            time += dt * look.speed * (1f - 0.65f * calm)
            val t = time

            // New ripples take the oldest slot.
            while (true) {
                val r = pendingRipples.poll() ?: break
                val px = if (r.x < 0f) surfaceWidth / 2f else r.x
                val py = when {
                    r.y >= 0f -> r.y
                    r.y == -1f -> 0f
                    r.y == -2f -> surfaceHeight.toFloat()
                    else -> surfaceHeight / 2f
                }
                val slot = rippleSlots[nextSlot]
                slot[0] = (px - 0.5f * surfaceWidth) / surfaceHeight
                slot[1] = (py - 0.5f * surfaceHeight) / surfaceHeight
                slot[2] = t
                slot[3] = r.strength
                val c = rippleColors[nextSlot]
                c[0] = ((r.color shr 16) and 0xFF) / 255f
                c[1] = ((r.color shr 8) and 0xFF) / 255f
                c[2] = (r.color and 0xFF) / 255f
                nextSlot = (nextSlot + 1) % 3
            }
            for (i in 0 until 3) {
                val s = rippleSlots[i]
                shader.setFloatUniform("iR$i", s[0], s[1], s[2], s[3])
                val c = rippleColors[i]
                shader.setFloatUniform("iRC$i", c[0], c[1], c[2])
            }

            // Music glow, eased in and out.
            glow += (WallpaperSignals.glowAmount - glow) * 0.03f
            val gc = WallpaperSignals.glowColor
            glowRgb[0] += (((gc shr 16) and 0xFF) / 255f - glowRgb[0]) * 0.05f
            glowRgb[1] += (((gc shr 8) and 0xFF) / 255f - glowRgb[1]) * 0.05f
            glowRgb[2] += ((gc and 0xFF) / 255f - glowRgb[2]) * 0.05f
            shader.setFloatUniform("iGlow", glowRgb[0], glowRgb[1], glowRgb[2], glow)
            shader.setFloatUniform("iCalm", calm)
            // Living wallpaper: weather rain, battery tide and notification fireflies.
            rain += (WallpaperSignals.rain - rain) * 0.01f
            shader.setFloatUniform("iRain", rain)
            shader.setFloatUniform("iTide", if (tideOn) tideLevel else -1f, if (tideCharging) 1f else 0f, 0f)
            flies += (WallpaperSignals.fireflies.coerceIn(0, 8) - flies) * 0.03f
            shader.setFloatUniform("iFlies", kotlin.math.round(flies))
            snow += (WallpaperSignals.snow - snow) * 0.01f
            fog += (WallpaperSignals.fog - fog) * 0.01f
            windU += (WallpaperSignals.wind - windU) * 0.01f
            warmU += (WallpaperSignals.warm - warmU) * 0.005f
            nightU += (WallpaperSignals.night - nightU) * 0.005f
            shader.setFloatUniform("iSnow", snow)
            shader.setFloatUniform("iFog", fog)
            shader.setFloatUniform("iWind", windU)
            shader.setFloatUniform("iWarm", warmU)
            shader.setFloatUniform("iNight", nightU)
            shader.setFloatUniform("iMoon", WallpaperSignals.moon)
            // Where the sky shows: checked a few times a second (the lock screen comes and goes).
            if (nowMs - lastSkyCheck > 250) {
                lastSkyCheck = nowMs
                val locked = keyguard?.isKeyguardLocked == true
                skyTarget = when (prefs.getString(WallpaperSignals.KEY_SKY_WHERE, "lock")) {
                    "both" -> 1f
                    "home" -> if (locked) 0f else 1f
                    "off" -> 0f
                    else -> if (locked || isPreview) 1f else 0f
                }
                WallpaperSignals.skyShowing = skyTarget > 0.5f
                starsOn = prefs.getBoolean(WallpaperSignals.KEY_STARS, true)
                planesOn = prefs.getBoolean(WallpaperSignals.KEY_SKY_PLANES, true)
                linesOn = prefs.getBoolean(WallpaperSignals.KEY_CONSTELLATIONS, true)
                motionMode = prefs.getString(WallpaperSignals.KEY_MOTION, "balanced") ?: "balanced"
                if (WallpaperSignals.lat.isNaN()) {
                    val la = prefs.getFloat("wp_lat", Float.NaN)
                    val lo = prefs.getFloat("wp_lon", Float.NaN)
                    if (!la.isNaN() && !lo.isNaN()) {
                        WallpaperSignals.lat = la.toDouble()
                        WallpaperSignals.lon = lo.toDouble()
                    }
                }
            }
            skyVis += (skyTarget - skyVis) * 0.06f
            cloudsU += (WallpaperSignals.clouds - cloudsU) * 0.01f
            shader.setFloatUniform("iSky", skyVis)
            shader.setFloatUniform("iClouds", cloudsU)
            // A Focus can dim the wallpaper.
            dim += (WallpaperSignals.focusDim - dim) * 0.04f
            shader.setFloatUniform("iDim", dim)
            WallpaperStyles.applyLook(shader, look)

            shader.setFloatUniform("iTime", t)
            shader.setFloatUniform("iFocus", focus)
            shader.setFloatUniform("iTilt", tiltX, tiltY)
            shader.setFloatUniform("iTouch", touchX, touchY)
            paint.shader = shader

            var canvas: Canvas? = null
            try {
                canvas = surfaceHolder.lockHardwareCanvas() ?: return
                canvas.drawPaint(paint)
                // The real sky on top: stars and planets, planes and the space station.
                if (skyVis > 0.01f) {
                    SkyOverlay.draw(
                        canvas, surfaceWidth, surfaceHeight, density, skyVis * (1f - 0.45f * dim), nightU, cloudsU,
                        (frameTimeNanos / 1_000_000_000.0 % 10_000.0).toFloat(),
                        stars = starsOn, planes = planesOn, lines = linesOn,
                    )
                }
            } finally {
                if (canvas != null) {
                    try {
                        surfaceHolder.unlockCanvasAndPost(canvas)
                    } catch (_: IllegalStateException) {
                        // surface went away mid-frame; next doFrame is guarded
                    }
                }
            }
        }
    }

    companion object {
        /** Sent by the launcher via WallpaperManager.sendWallpaperCommand. */
        const val ACTION_FOCUS_ON = "com.kinetic.wallpaper.FOCUS_ON"
        const val ACTION_FOCUS_OFF = "com.kinetic.wallpaper.FOCUS_OFF"

        // The shaders live in WallpaperStyles (one per style, all sharing the same reactions).
    }
}
