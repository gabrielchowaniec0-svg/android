package com.kinetic.wallpaper

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.service.wallpaper.WallpaperService
import android.view.Choreographer
import android.view.MotionEvent
import android.view.SurfaceHolder
import androidx.annotation.RequiresApi

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

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            setTouchEventsEnabled(true)
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
            basePitch = Float.NaN
            if (rotationSensor != null) sensors?.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_GAME)
            choreographer.postFrameCallback(this)
        }

        private fun stop() {
            running = false
            sensors?.unregisterListener(this)
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

            val t = (frameTimeNanos - startNanos) / 1_000_000_000f
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

            half4 main(float2 fragCoord) {
                float2 uv = (fragCoord - 0.5 * iResolution) / iResolution.y;

                float2 warp = float2(
                    fbm(uv * 1.5 + iTime * 0.03),
                    fbm(uv * 1.5 - iTime * 0.025 + 5.2)
                );
                // parallax: each ribbon layer shifts by a different amount with tilt
                float2 par = iTilt * 0.05;
                float2 p = uv + (warp - 0.5) * 0.35 + par;

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
                col *= half(mix(0.70, 1.0, vig) * (1.0 - 0.35 * iFocus));

                return half4(clamp(col, 0.0, 1.0), 1.0);
            }
        """.trimIndent()
    }
}
