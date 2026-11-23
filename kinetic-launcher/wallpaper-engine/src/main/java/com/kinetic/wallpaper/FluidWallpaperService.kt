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
 * Live wallpaper that renders [fluid_wallpaper.agsl] through a hardware canvas,
 * paced to the panel's refresh by Choreographer (up to 120Hz on the S25 Ultra).
 *
 * AGSL / RuntimeShader are API 33+. Guard the service in the manifest with
 * android:enabled behind a minSdk of 33, or feature-detect before offering it.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class FluidWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = FluidEngine()

    private inner class FluidEngine : Engine(), Choreographer.FrameCallback, SensorEventListener {

        private val shader = RuntimeShader(SHADER_SRC)
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
            registerReceiver(
                powerSaveReceiver,
                IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
                Context.RECEIVER_NOT_EXPORTED,
            )
        }

        override fun onDestroy() {
            runCatching { unregisterReceiver(powerSaveReceiver) }
            WallpaperSignals.removeListener(rippleListener)
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

        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
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
                calmTarget = if (enabled && ((level in 1..20 && !charging) || power?.isPowerSaveMode == true)) 1f else 0f
            }
            calm += (calmTarget - calm) * 0.02f
            val dt = if (lastFrameNanos == 0L) 0f else ((frameTimeNanos - lastFrameNanos) / 1_000_000_000f).coerceIn(0f, 0.1f)
            lastFrameNanos = frameTimeNanos
            time += dt * (1f - 0.65f * calm)
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

            shader.setFloatUniform("iTime", t)
            shader.setFloatUniform("iFocus", focus)
            shader.setFloatUniform("iTilt", tiltX, tiltY)
            shader.setFloatUniform("iTouch", touchX, touchY)
            paint.shader = shader

            var canvas: Canvas? = null
            try {
                canvas = surfaceHolder.lockHardwareCanvas() ?: return
                canvas.drawPaint(paint)
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

        /**
         * Kept in sync with fluid_wallpaper.agsl. If you prefer a single source
         * of truth, drop the .agsl into res/raw and load it with
         * resources.openRawResource(R.raw.fluid_wallpaper).bufferedReader().readText().
         */
        private val SHADER_SRC = """
            uniform float2 iResolution;
            uniform float  iTime;
            uniform float2 iTouch;
            uniform float  iFocus;
            uniform float2 iTilt;
            uniform float4 iR0;
            uniform float4 iR1;
            uniform float4 iR2;
            uniform float3 iRC0;
            uniform float3 iRC1;
            uniform float3 iRC2;
            uniform float4 iGlow;
            uniform float  iCalm;

            const float PI = 3.14159265;

            float hash21(float2 p) {
                p = fract(p * float2(123.34, 345.45));
                p += dot(p, p + 34.345);
                return fract(p.x * p.y);
            }

            float valueNoise(float2 p) {
                float2 i = floor(p);
                float2 f = fract(p);
                float2 u = f * f * (3.0 - 2.0 * f);
                float a = hash21(i + float2(0.0, 0.0));
                float b = hash21(i + float2(1.0, 0.0));
                float c = hash21(i + float2(0.0, 1.0));
                float d = hash21(i + float2(1.0, 1.0));
                return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
            }

            float fbm(float2 p) {
                float sum = 0.0;
                float amp = 0.5;
                for (int i = 0; i < 5; i++) {
                    sum += amp * valueNoise(p);
                    p = p * 2.02 + float2(37.1, 11.7);
                    amp *= 0.5;
                }
                return sum;
            }

            float ribbon(float2 uv, float phase, float freq, float speed, float thick) {
                float y = 0.0;
                y += 0.22 * sin(uv.x * freq + iTime * speed + phase);
                y += 0.12 * cos(uv.x * freq * 1.9 - iTime * speed * 0.7 + phase * 1.3);
                y += 0.06 * sin(uv.x * freq * 3.3 + iTime * speed * 1.3 + phase * 2.1);
                float d = abs(uv.y - y);
                return smoothstep(thick, 0.0, d);
            }

            // A ring spreading out from r.xy since time r.z, fading as it grows.
            float ring(float2 uv, float4 r) {
                float age = iTime - r.z;
                if (r.w <= 0.0 || age < 0.0 || age > 2.4) return 0.0;
                float rad = age * 0.75;
                float d = length(uv - r.xy);
                float band = exp(-((d - rad) * 11.0) * ((d - rad) * 11.0));
                return band * r.w * (1.0 - age / 2.4);
            }

            float2 push(float2 uv, float4 r, float w) {
                float2 dir = uv - r.xy;
                float len = max(length(dir), 0.001);
                return dir / len * w * 0.035;
            }

            half4 main(float2 fragCoord) {
                float2 uv = (fragCoord - 0.5 * iResolution) / iResolution.y;
                float w0 = ring(uv, iR0);
                float w1 = ring(uv, iR1);
                float w2 = ring(uv, iR2);

                float2 warp = float2(
                    fbm(uv * 1.5 + iTime * 0.03),
                    fbm(uv * 1.5 - iTime * 0.025 + 5.2)
                );
                // parallax: each ribbon layer shifts by a different amount with tilt
                float2 par = iTilt * 0.05;
                float2 p = uv + (warp - 0.5) * 0.35 + par;
                // ripples push the ribbons outwards as they pass
                p += push(uv, iR0, w0) + push(uv, iR1, w1) + push(uv, iR2, w2);

                half3 topCol    = half3(0.015, 0.020, 0.035);
                half3 bottomCol = half3(0.040, 0.060, 0.110);
                half3 col = mix(topCol, bottomCol, half(smoothstep(-0.55, 0.55, uv.y)));

                float soft = 1.0 + iFocus * 1.6;              // wider, blurrier ribbons
                float r1 = ribbon(p,        0.0, 2.3, 0.35, 0.16 * soft);
                float r2 = ribbon(p * 1.15 + par * 0.8, 1.7, 3.1, 0.28, 0.11 * soft);
                float r3 = ribbon(p * 0.85 - par * 0.6, 4.0, 1.7, 0.45, 0.20 * soft);

                col += half3(0.12, 0.17, 0.33) * half(r1);
                col += half3(0.18, 0.26, 0.42) * half(r2);
                col += half3(0.08, 0.12, 0.22) * half(r3);

                // music glow: the ribbons pick up the album's colour
                col += half3(iGlow.rgb) * half(iGlow.a * (0.06 + 0.30 * (r1 + r2) + 0.15 * r3));

                // ripple light
                col += half3(iRC0) * half(w0 * 0.42);
                col += half3(iRC1) * half(w1 * 0.42);
                col += half3(iRC2) * half(w2 * 0.42);

                float rim = (r1 * r2 + r2 * r3) * (1.0 - iFocus);
                col += half3(0.35, 0.42, 0.60) * half(pow(rim, 2.0) * 0.9);

                if (iTouch.x > 0.5 || iTouch.y > 0.5) {
                    float2 t = (iTouch - 0.5 * iResolution) / iResolution.y;
                    float d = length(uv - t);
                    col += half3(0.20, 0.30, 0.50) * half(exp(-d * 6.0) * 0.6);
                }

                float g = fbm(fragCoord * 0.9 + iTime * 15.0);
                col += half3(half((g - 0.5) * 0.025 * (1.0 - iFocus)));

                float vig = smoothstep(1.2, 0.2, length(uv));
                col *= half(mix(0.70, 1.0, vig) * (1.0 - 0.35 * iFocus) * (1.0 - 0.18 * iCalm));

                return half4(clamp(col, 0.0, 1.0), 1.0);
            }
        """.trimIndent()
    }
}
