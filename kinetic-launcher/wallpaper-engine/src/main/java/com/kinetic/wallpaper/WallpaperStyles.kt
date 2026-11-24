package com.kinetic.wallpaper

import android.content.SharedPreferences
import android.graphics.RuntimeShader

/**
 * Kinetic's animated wallpapers. Every style shares the same "reactions" (ripples,
 * music glow, touch, tilt, calm, Focus dimming) and the same look settings
 * (colours, speed, brightness, grain, detail); only the scene in the middle differs.
 */
object WallpaperStyles {

    enum class Style(val id: String, val label: String, val feel: String) {
        Ribbons("ribbons", "Ribbons", "Smooth"),
        Waves("waves", "Waves", "Smooth"),
        Silk("silk", "Silk", "Smooth"),
        Aurora("aurora", "Aurora", "Abstract"),
        Orbs("orbs", "Orbs", "Abstract"),
        Crystal("crystal", "Crystal", "Rough"),
        Dunes("dunes", "Dunes", "Rough"),
        Static("static", "Static", "Rough");

        companion object {
            fun of(id: String?) = entries.firstOrNull { it.id == id } ?: Ribbons
        }
    }

    /** Three colours: deep background, main colour, highlight. */
    data class Palette(val name: String, val deep: Int, val main: Int, val glint: Int)

    val palettes = listOf(
        Palette("Midnight", 0xFF0A0F1C.toInt(), 0xFF2E4266.toInt(), 0xFF5A6B99.toInt()),
        Palette("Ocean", 0xFF03141C.toInt(), 0xFF0E5A73.toInt(), 0xFF4FC3D9.toInt()),
        Palette("Aurora", 0xFF041210.toInt(), 0xFF1E7A5A.toInt(), 0xFF7CF2B0.toInt()),
        Palette("Sunset", 0xFF1A0A12.toInt(), 0xFF8A3A4A.toInt(), 0xFFF2A65A.toInt()),
        Palette("Ember", 0xFF140805.toInt(), 0xFF7A2A10.toInt(), 0xFFFF7A3D.toInt()),
        Palette("Rose", 0xFF160A14.toInt(), 0xFF7A3A6A.toInt(), 0xFFF2A0D0.toInt()),
        Palette("Violet", 0xFF0C0818.toInt(), 0xFF4A2E8A.toInt(), 0xFFA88BFF.toInt()),
        Palette("Graphite", 0xFF0A0A0C.toInt(), 0xFF3A3D45.toInt(), 0xFFB8BEC9.toInt()),
    )

    /** Everything you can customise. */
    data class Look(
        val style: Style = Style.Ribbons,
        val deep: Int = palettes[0].deep,
        val main: Int = palettes[0].main,
        val glint: Int = palettes[0].glint,
        /** 0.2 – 2.0 */
        val speed: Float = 1f,
        /** 0.5 – 1.6 */
        val intensity: Float = 1f,
        /** 0 – 1 */
        val grain: Float = 0.25f,
        /** 0.6 – 1.8 (bigger = more, smaller shapes) */
        val detail: Float = 1f,
    )

    private const val K_STYLE = "wp_style"
    private const val K_DEEP = "wp_col_deep"
    private const val K_MAIN = "wp_col_main"
    private const val K_GLINT = "wp_col_glint"
    private const val K_SPEED = "wp_speed"
    private const val K_INTENSITY = "wp_intensity"
    private const val K_GRAIN = "wp_grain"
    private const val K_DETAIL = "wp_detail"

    /** True if [key] is one of the look's settings (so the wallpaper should reload). */
    fun isLookKey(key: String?) = key != null && key in setOf(K_STYLE, K_DEEP, K_MAIN, K_GLINT, K_SPEED, K_INTENSITY, K_GRAIN, K_DETAIL)

    fun load(p: SharedPreferences): Look {
        val d = Look()
        return Look(
            style = Style.of(p.getString(K_STYLE, null)),
            deep = p.getInt(K_DEEP, d.deep),
            main = p.getInt(K_MAIN, d.main),
            glint = p.getInt(K_GLINT, d.glint),
            speed = p.getFloat(K_SPEED, d.speed),
            intensity = p.getFloat(K_INTENSITY, d.intensity),
            grain = p.getFloat(K_GRAIN, d.grain),
            detail = p.getFloat(K_DETAIL, d.detail),
        )
    }

    fun save(p: SharedPreferences, look: Look) {
        p.edit()
            .putString(K_STYLE, look.style.id)
            .putInt(K_DEEP, look.deep)
            .putInt(K_MAIN, look.main)
            .putInt(K_GLINT, look.glint)
            .putFloat(K_SPEED, look.speed)
            .putFloat(K_INTENSITY, look.intensity)
            .putFloat(K_GRAIN, look.grain)
            .putFloat(K_DETAIL, look.detail)
            .apply()
    }

    fun shaderFor(style: Style): RuntimeShader = RuntimeShader(HEADER + scene(style) + MAIN)

    /** Sets the look uniforms (everything else is set per frame by whoever draws it). */
    fun applyLook(shader: RuntimeShader, look: Look) {
        shader.setFloatUniform("iColA", r(look.deep), g(look.deep), b(look.deep))
        shader.setFloatUniform("iColB", r(look.main), g(look.main), b(look.main))
        shader.setFloatUniform("iColC", r(look.glint), g(look.glint), b(look.glint))
        shader.setFloatUniform("iIntensity", look.intensity)
        shader.setFloatUniform("iGrain", look.grain)
        shader.setFloatUniform("iScale", look.detail)
    }

    /** Neutral values for everything a preview doesn't animate. */
    fun applyDefaults(shader: RuntimeShader) {
        shader.setFloatUniform("iTouch", 0f, 0f)
        shader.setFloatUniform("iFocus", 0f)
        shader.setFloatUniform("iTilt", 0f, 0f)
        for (i in 0 until 3) {
            shader.setFloatUniform("iR$i", 0f, 0f, -10f, 0f)
            shader.setFloatUniform("iRC$i", 0f, 0f, 0f)
        }
        shader.setFloatUniform("iGlow", 0f, 0f, 0f, 0f)
        shader.setFloatUniform("iCalm", 0f)
        shader.setFloatUniform("iDim", 0f)
        shader.setFloatUniform("iRain", 0f)
        shader.setFloatUniform("iTide", -1f, 0f, 0f)
        shader.setFloatUniform("iFlies", 0f)
        shader.setFloatUniform("iSnow", 0f)
        shader.setFloatUniform("iFog", 0f)
        shader.setFloatUniform("iWind", 0f)
        shader.setFloatUniform("iWarm", 0f)
        shader.setFloatUniform("iNight", 0f)
        shader.setFloatUniform("iMoon", -1f)
    }

    private fun r(c: Int) = ((c shr 16) and 0xFF) / 255f
    private fun g(c: Int) = ((c shr 8) and 0xFF) / 255f
    private fun b(c: Int) = (c and 0xFF) / 255f

    // ---- shader source ----------------------------------------------------------------

    private val HEADER = """
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
        uniform float  iDim;
        uniform float  iRain;
        uniform float3 iTide;
        uniform float  iFlies;
        uniform float  iSnow;
        uniform float  iFog;
        uniform float  iWind;
        uniform float  iWarm;
        uniform float  iNight;
        uniform float  iMoon;
        uniform float3 iColA;
        uniform float3 iColB;
        uniform float3 iColC;
        uniform float  iIntensity;
        uniform float  iGrain;
        uniform float  iScale;

        float hash21(float2 p) {
            p = fract(p * float2(123.34, 345.45));
            p += dot(p, p + 34.345);
            return fract(p.x * p.y);
        }

        float2 hash22(float2 p) {
            float n = hash21(p);
            return float2(n, hash21(p + n * 17.31));
        }

        float valueNoise(float2 p) {
            float2 i = floor(p);
            float2 f = fract(p);
            float2 u = f * f * (3.0 - 2.0 * f);
            float a = hash21(i);
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

        float fbm3(float2 p) {
            float sum = 0.0;
            float amp = 0.5;
            for (int i = 0; i < 3; i++) {
                sum += amp * valueNoise(p);
                p = p * 2.03 + float2(17.3, 41.9);
                amp *= 0.5;
            }
            return sum;
        }

        // Ripple helpers. A ripple r = (x, y, start time, strength). Each style
        // decides what its ripple looks like (ripplePush / rippleLight below).
        float rippleAge(float4 r) { return iTime - r.z; }

        // Strength left in a ripple (0 once it's finished).
        float alive(float4 r, float life) {
            float a = iTime - r.z;
            if (r.w <= 0.0 || a < 0.0 || a > life) return 0.0;
            return r.w * (1.0 - a / life);
        }

        // A soft band at distance [rad] from the centre.
        float band(float d, float rad, float sharp) {
            float x = (d - rad) * sharp;
            return exp(-x * x);
        }

        float2 dirFrom(float2 uv, float4 r) {
            float2 v = uv - r.xy;
            return v / max(length(v), 0.001);
        }

        // ---- living wallpaper: weather, battery and notifications -------------------

        // Thin, slanted rain streaks drifting down. [scale] = density, [speed] = fall speed.
        float rainLayer(float2 uv, float scale, float speed, float seed) {
            float2 q = float2((uv.x + uv.y * (0.12 + iWind * 0.35)) * scale, uv.y * scale * 0.22 - iTime * speed);
            float2 id = floor(q);
            float2 f = fract(q);
            float h = hash21(id + seed);
            if (h < 0.78) return 0.0;
            float x = abs(f.x - 0.5 - (h - 0.89) * 2.0);
            float streak = smoothstep(0.05, 0.0, x);
            return streak * smoothstep(0.0, 0.35, f.y) * smoothstep(1.0, 0.6, f.y);
        }

        // Soft snowflakes drifting down (and sideways with the wind).
        float snowLayer(float2 uv, float scale, float speed, float seed) {
            float2 q = uv * scale;
            q.y -= iTime * speed;
            q.x += sin(q.y * 0.7 + seed) * 0.3 - iTime * iWind * speed * 0.8;
            float2 id = floor(q);
            float2 f = fract(q) - 0.5;
            float h = hash21(id + seed);
            if (h < 0.72) return 0.0;
            float2 o = (hash22(id + seed * 1.7) - 0.5) * 0.6;
            float d = length(f - o);
            return smoothstep(0.09, 0.0, d) * (0.5 + 0.5 * h);
        }

        float3 sky(float2 uv, float3 col) {
            // Snow: a touch brighter and greyer, with flakes.
            if (iSnow > 0.01) {
                col = mix(col, col * 0.9 + 0.05, 0.3 * iSnow);
                float s = snowLayer(uv, 14.0, 0.35, 0.0) + 0.7 * snowLayer(uv, 24.0, 0.22, 3.1);
                col += float3(0.92, 0.95, 1.0) * s * 0.6 * iSnow;
            }
            // Fog: a slow haze rising from the bottom.
            if (iFog > 0.01) {
                float f = smoothstep(-0.15, 0.5, uv.y) * (0.6 + 0.4 * fbm3(uv * 3.0 + float2(iTime * 0.03, 0.0)));
                col = mix(col, float3(0.55, 0.58, 0.62), f * 0.45 * iFog);
            }
            // Golden hour around sunrise and sunset; a little cooler and dimmer at night.
            col = mix(col, col * float3(1.15, 0.92, 0.76) + float3(0.06, 0.02, 0.0), iWarm * 0.5);
            col *= 1.0 - 0.12 * iNight;
            // The real moon (its phase), top right, at night.
            if (iMoon >= 0.0 && iNight > 0.01) {
                float2 m = uv - float2(0.14, -0.36);
                float r = 0.026;
                float d = length(m);
                if (d < r * 4.0) {
                    float2 l = m / r;
                    float k = cos(6.2831853 * iMoon);
                    float sgn = iMoon < 0.5 ? 1.0 : -1.0;
                    float disc = smoothstep(1.0, 0.9, length(l));
                    float lit = smoothstep(-0.06, 0.06, sgn * l.x - k * sqrt(max(0.0, 1.0 - l.y * l.y)));
                    col = mix(col, float3(0.93, 0.92, 0.85), disc * (0.10 + 0.78 * lit) * iNight);
                    col += float3(0.6, 0.65, 0.8) * exp(-d * 38.0) * 0.07 * iNight;
                }
            }
            return col;
        }

        float3 living(float2 uv, float3 col) {
            col = sky(uv, col);
            // Rain: cooler, a little darker, with falling streaks.
            if (iRain > 0.01) {
                col = mix(col, col * float3(0.80, 0.88, 1.0), 0.45 * iRain);
                float r = rainLayer(uv, 34.0, 2.6, 0.0) + 0.6 * rainLayer(uv, 52.0, 3.4, 7.3);
                col += float3(0.62, 0.74, 0.95) * r * 0.32 * iRain;
            }
            // Battery tide: a softly glowing waterline. Full battery = high tide.
            if (iTide.x >= 0.0) {
                float yl = 0.47 - iTide.x * 0.86 + 0.006 * sin(uv.x * 16.0 + iTime * 1.3) + 0.004 * sin(uv.x * 31.0 - iTime * 2.1);
                float3 tint = iTide.y > 0.5 ? float3(0.35, 0.95, 0.55) : (iTide.x <= 0.2 ? float3(1.0, 0.42, 0.35) : iColC);
                float below = smoothstep(yl - 0.004, yl + 0.012, uv.y);
                col = mix(col, col * 0.92 + tint * 0.05, below * 0.55);
                col += tint * exp(-abs(uv.y - yl) * 140.0) * 0.30;
            }
            // Fireflies: one per app with unread notifications.
            for (int i = 0; i < 8; i++) {
                if (float(i) >= iFlies) break;
                float fi = float(i);
                float t = iTime * (0.18 + 0.03 * fi);
                float2 pos = float2(
                    0.20 * sin(t + fi * 2.39) + 0.04 * sin(t * 2.7 + fi),
                    0.36 * sin(t * 0.71 + fi * 1.71) + 0.05 * cos(t * 1.9 + fi * 3.1));
                float d = length(uv - pos);
                float pulse = 0.55 + 0.45 * sin(iTime * (1.6 + 0.2 * fi) + fi * 4.0);
                col += float3(1.0, 0.86, 0.45) * (exp(-d * d * 9000.0) * 0.9 + exp(-d * 42.0) * 0.10) * pulse;
            }
            return col;
        }

    """.trimIndent() + "\n"

    private val MAIN = """

        half4 main(float2 fragCoord) {
            float2 uv = (fragCoord - 0.5 * iResolution) / iResolution.y;
            float2 par = iTilt * 0.05;
            float2 p = uv + par + ripplePush(uv, iR0) + ripplePush(uv, iR1) + ripplePush(uv, iR2);
            float soft = 1.0 + iFocus * 1.6;

            float3 col = scene(uv, p * iScale, soft);
            float lum = dot(col, float3(0.3, 0.5, 0.2));

            // music glow
            col += iGlow.rgb * iGlow.a * (0.04 + 0.9 * lum);
            // ripple light (each style draws its own kind)
            col += rippleLight(uv, iR0, iRC0) + rippleLight(uv, iR1, iRC1) + rippleLight(uv, iR2, iRC2);
            // touch
            if (iTouch.x > 0.5 || iTouch.y > 0.5) {
                float2 t = (iTouch - 0.5 * iResolution) / iResolution.y;
                col += iColC * 0.45 * exp(-length(uv - t) * 6.0) * 0.6;
            }
            // living wallpaper (rain, battery tide, fireflies)
            col = living(uv, col);
            // film grain
            float g = hash21(fragCoord + fract(iTime * 7.0) * 113.0);
            col += (g - 0.5) * 0.07 * iGrain * (1.0 - iFocus);

            float vig = smoothstep(1.2, 0.2, length(uv));
            col *= mix(0.70, 1.0, vig) * (1.0 - 0.35 * iFocus) * (1.0 - 0.18 * iCalm) * (1.0 - 0.45 * iDim);
            return half4(half3(clamp(col, 0.0, 1.0)), 1.0);
        }
    """.trimIndent()

    private fun scene(style: Style): String = when (style) {
        Style.Ribbons -> """
            float ribbon(float2 uv, float phase, float freq, float speed, float thick) {
                float y = 0.22 * sin(uv.x * freq + iTime * speed + phase);
                y += 0.12 * cos(uv.x * freq * 1.9 - iTime * speed * 0.7 + phase * 1.3);
                y += 0.06 * sin(uv.x * freq * 3.3 + iTime * speed * 1.3 + phase * 2.1);
                return smoothstep(thick, 0.0, abs(uv.y - y));
            }
            float3 scene(float2 uv, float2 p, float soft) {
                float2 warp = float2(fbm(p * 1.5 + iTime * 0.03), fbm(p * 1.5 - iTime * 0.025 + 5.2));
                float2 q = p + (warp - 0.5) * 0.35;
                float3 col = mix(iColA * 0.35, iColA, smoothstep(-0.55, 0.55, uv.y));
                float r1 = ribbon(q, 0.0, 2.3, 0.35, 0.16 * soft);
                float r2 = ribbon(q * 1.15, 1.7, 3.1, 0.28, 0.11 * soft);
                float r3 = ribbon(q * 0.85, 4.0, 1.7, 0.45, 0.20 * soft);
                col += iColB * 0.75 * r1 * iIntensity;
                col += mix(iColB, iColC, 0.3) * r2 * iIntensity;
                col += iColB * 0.45 * r3 * iIntensity;
                float rim = (r1 * r2 + r2 * r3) * (1.0 - iFocus);
                col += iColC * pow(rim, 2.0) * 0.9 * iIntensity;
                return col;
            }
                    // Ribbons: a clean ring of light that pushes the ribbons outwards.
            float2 ripplePush(float2 uv, float4 r) {
                float k = alive(r, 2.4);
                if (k <= 0.0) return float2(0.0);
                return dirFrom(uv, r) * band(length(uv - r.xy), rippleAge(r) * 0.75, 11.0) * k * 0.035;
            }
            float3 rippleLight(float2 uv, float4 r, float3 rc) {
                float k = alive(r, 2.4);
                if (k <= 0.0) return float3(0.0);
                return rc * band(length(uv - r.xy), rippleAge(r) * 0.75, 11.0) * k * 0.42;
            }
        """.trimIndent()

        Style.Waves -> """
            float3 scene(float2 uv, float2 p, float soft) {
                float3 col = mix(iColA * 0.5, iColA, smoothstep(0.6, -0.6, uv.y));
                // Rolling bands, one behind the other, each a little brighter.
                for (int i = 0; i < 6; i++) {
                    float fi = float(i);
                    float base = 0.45 - fi * 0.17;
                    float y = base
                        + 0.07 * sin(p.x * (2.0 + fi * 0.35) + iTime * (0.35 + fi * 0.05) + fi * 1.7)
                        + 0.035 * sin(p.x * (5.1 + fi) - iTime * 0.6 + fi);
                    float edge = 0.012 * soft;
                    float body = smoothstep(y + edge, y - edge, p.y);
                    float3 layer = mix(iColA, iColB, (fi + 1.0) / 6.0) * (0.55 + 0.1 * fi) * iIntensity;
                    col = mix(col, layer, body);
                    float crest = exp(-abs(p.y - y) * 90.0 / soft);
                    col += iColC * crest * 0.35 * iIntensity;
                }
                return col;
            }
                    // Waves: a swell that rolls up and down the screen as a wavy line.
            float swell(float2 uv, float4 r) {
                float a = rippleAge(r);
                float y = abs(uv.y - r.y + 0.04 * sin(uv.x * 9.0 + a * 5.0));
                return band(y, a * 0.6, 9.0);
            }
            float2 ripplePush(float2 uv, float4 r) {
                float k = alive(r, 2.6);
                if (k <= 0.0) return float2(0.0);
                return float2(0.0, sign(uv.y - r.y)) * swell(uv, r) * k * 0.06;
            }
            float3 rippleLight(float2 uv, float4 r, float3 rc) {
                float k = alive(r, 2.6);
                if (k <= 0.0) return float3(0.0);
                return mix(rc, iColC, 0.5) * swell(uv, r) * k * 0.35;
            }
        """.trimIndent()

        Style.Silk -> """
            float3 scene(float2 uv, float2 p, float soft) {
                // Folded silk: smooth warped stripes with soft light.
                float2 q = p * 1.4;
                float n = fbm(q * 0.7 + float2(iTime * 0.04, -iTime * 0.03));
                float s = sin((q.x + q.y * 0.6) * 3.0 + n * 6.0 + iTime * 0.25);
                float shade = 0.5 + 0.5 * s;
                float sheen = pow(shade, 6.0 / soft);
                float3 col = mix(iColA, iColB * 0.9, shade * 0.8 * iIntensity);
                col += iColC * sheen * 0.5 * iIntensity;
                return col;
            }
                    // Silk: fine concentric folds run through the fabric, catching the light.
            float folds(float2 uv, float4 r) {
                float d = length(uv - r.xy);
                float front = rippleAge(r) * 0.7;
                return sin((d - front) * 42.0) * exp(-abs(d - front) * 9.0);
            }
            float2 ripplePush(float2 uv, float4 r) {
                float k = alive(r, 2.4);
                if (k <= 0.0) return float2(0.0);
                return dirFrom(uv, r) * folds(uv, r) * k * 0.012;
            }
            float3 rippleLight(float2 uv, float4 r, float3 rc) {
                float k = alive(r, 2.4);
                if (k <= 0.0) return float3(0.0);
                return mix(iColC, rc, 0.3) * max(folds(uv, r), 0.0) * k * 0.3;
            }
        """.trimIndent()

        Style.Aurora -> """
            float3 scene(float2 uv, float2 p, float soft) {
                float3 col = iColA * (0.6 + 0.4 * smoothstep(0.6, -0.6, uv.y));
                // Curtains of light hanging from the top, drifting sideways.
                for (int i = 0; i < 3; i++) {
                    float fi = float(i);
                    float x = p.x * (1.2 + fi * 0.4) + iTime * (0.05 + fi * 0.02) + fi * 3.1;
                    float wave = fbm(float2(x, iTime * 0.05 + fi)) - 0.5;
                    float y = 0.15 - fi * 0.12 + wave * 0.5;
                    float band = exp(-abs(p.y - y) * (7.0 / soft));
                    float streaks = 0.6 + 0.4 * valueNoise(float2(x * 14.0, fi * 4.0));
                    float fade = smoothstep(-0.7, 0.4, p.y);
                    col += mix(iColB, iColC, fi * 0.4) * band * streaks * fade * 0.55 * iIntensity;
                }
                // a few stars
                float2 cell = floor(uv * 90.0);
                float star = step(0.995, hash21(cell)) * (0.5 + 0.5 * sin(iTime * 2.0 + hash21(cell + 3.0) * 6.28));
                col += iColC * star * 0.6 * smoothstep(-0.1, 0.5, uv.y);
                return col;
            }
                    // Aurora: a shimmering burst that lifts the curtains as it passes.
            float shimmer(float2 uv, float4 r) {
                float2 v = uv - r.xy;
                float a = rippleAge(r);
                float rays = 0.45 + 0.55 * valueNoise(float2(atan(v.y, v.x) * 5.0, a * 3.0));
                return band(length(v), a * 0.9, 7.0) * rays;
            }
            float2 ripplePush(float2 uv, float4 r) {
                float k = alive(r, 2.6);
                if (k <= 0.0) return float2(0.0);
                return float2(0.0, -shimmer(uv, r) * k * 0.05);
            }
            float3 rippleLight(float2 uv, float4 r, float3 rc) {
                float k = alive(r, 2.6);
                if (k <= 0.0) return float3(0.0);
                return mix(rc, iColC, 0.6) * shimmer(uv, r) * k * 0.6;
            }
        """.trimIndent()

        Style.Orbs -> """
            float3 scene(float2 uv, float2 p, float soft) {
                float3 col = iColA * 0.8;
                float field = 0.0;
                float hi = 0.0;
                // Glowing blobs that drift and melt into each other.
                for (int i = 0; i < 6; i++) {
                    float fi = float(i);
                    float2 c = float2(
                        0.45 * sin(iTime * (0.13 + fi * 0.03) + fi * 2.1),
                        0.7 * cos(iTime * (0.11 + fi * 0.025) + fi * 1.3)
                    );
                    float r = 0.16 + 0.05 * sin(iTime * 0.3 + fi);
                    float d = length(p - c);
                    field += r * r / (d * d + 0.0004);
                    hi += exp(-d * 9.0 / soft) * (0.5 + 0.5 * sin(fi));
                }
                float body = smoothstep(0.8, 1.2 + 0.4 * soft, field);
                col = mix(col, iColB * iIntensity, body);
                col += iColC * hi * 0.35 * iIntensity;
                col += iColB * 0.15 * smoothstep(0.2, 1.0, field) * iIntensity;
                return col;
            }
                    // Orbs: a soft, gooey bubble that shoves the blobs aside.
            float bubble(float2 uv, float4 r) {
                return band(length(uv - r.xy), rippleAge(r) * 0.65, 5.5);
            }
            float2 ripplePush(float2 uv, float4 r) {
                float k = alive(r, 2.6);
                if (k <= 0.0) return float2(0.0);
                return dirFrom(uv, r) * bubble(uv, r) * k * 0.07;
            }
            float3 rippleLight(float2 uv, float4 r, float3 rc) {
                float k = alive(r, 2.6);
                if (k <= 0.0) return float3(0.0);
                float b = bubble(uv, r);
                return (rc * b * 0.22 + iColC * b * b * b * 0.35) * k;
            }
        """.trimIndent()

        Style.Crystal -> """
            float3 scene(float2 uv, float2 p, float soft) {
                // Broken glass: slowly shifting cells with bright, sharp edges.
                float2 q = p * 4.0;
                float2 i = floor(q);
                float2 f = fract(q);
                float d1 = 8.0;
                float d2 = 8.0;
                float id = 0.0;
                for (int y = -1; y <= 1; y++) {
                    for (int x = -1; x <= 1; x++) {
                        float2 o = float2(float(x), float(y));
                        float2 h = hash22(i + o);
                        float2 pt = o + 0.5 + 0.42 * sin(iTime * 0.25 + 6.2831 * h);
                        float d = length(pt - f);
                        if (d < d1) { d2 = d1; d1 = d; id = hash21(i + o); }
                        else if (d < d2) { d2 = d; }
                    }
                }
                float edge = 1.0 - smoothstep(0.0, 0.06 * soft, d2 - d1);
                float facet = 0.35 + 0.65 * id;
                float3 col = mix(iColA, iColB, facet * 0.8 * iIntensity);
                col *= 0.75 + 0.35 * (1.0 - d1);
                col += iColC * edge * 0.7 * iIntensity;
                return col;
            }
                    // Crystal: shards light up one by one as the shock wave reaches them.
            float2 ripplePush(float2 uv, float4 r) { return float2(0.0); }
            float3 rippleLight(float2 uv, float4 r, float3 rc) {
                float k = alive(r, 2.2);
                if (k <= 0.0) return float3(0.0);
                float2 cell = floor(uv * 22.0);
                float2 c = (cell + 0.5) / 22.0;
                float b = band(length(c - r.xy), rippleAge(r) * 0.9, 12.0);
                float flash = b * (0.3 + 0.7 * step(0.45, hash21(cell)));
                return (iColC * flash * 0.9 + rc * b * 0.15) * k;
            }
        """.trimIndent()

        Style.Dunes -> """
            float height(float2 q) {
                float h = fbm3(q + float2(iTime * 0.03, 0.0));
                return abs(h - 0.5) * 2.0;
            }
            float3 scene(float2 uv, float2 p, float soft) {
                // Rough, grainy terrain lit from the side.
                float2 q = p * 2.4;
                float e = 0.01;
                float h = height(q);
                float hx = height(q + float2(e, 0.0)) - h;
                float hy = height(q + float2(0.0, e)) - h;
                float3 n = normalize(float3(-hx / e * 0.15, -hy / e * 0.15, 1.0));
                float light = clamp(dot(n, normalize(float3(-0.6, 0.5, 0.6))), 0.0, 1.0);
                float lines = smoothstep(0.08 * soft, 0.0, abs(fract(h * 9.0) - 0.5) - 0.42);
                float3 col = mix(iColA, iColB, h * 0.9 * iIntensity) * (0.35 + 0.9 * light);
                col += iColC * lines * 0.18 * iIntensity;
                return col;
            }
                    // Dunes: a sandy shock wave — bright crest, dark trough, gritty texture.
            float2 ripplePush(float2 uv, float4 r) {
                float k = alive(r, 2.4);
                if (k <= 0.0) return float2(0.0);
                float d = length(uv - r.xy);
                float rad = rippleAge(r) * 0.6;
                return dirFrom(uv, r) * (band(d, rad, 14.0) - band(d, rad - 0.06, 14.0)) * k * 0.03;
            }
            float3 rippleLight(float2 uv, float4 r, float3 rc) {
                float k = alive(r, 2.4);
                if (k <= 0.0) return float3(0.0);
                float d = length(uv - r.xy);
                float rad = rippleAge(r) * 0.6;
                float grit = 0.55 + 0.45 * hash21(floor(uv * 240.0));
                return (rc * band(d, rad, 14.0) * 0.5 - iColB * band(d, rad - 0.06, 14.0) * 0.35) * grit * k;
            }
        """.trimIndent()

        Style.Static -> """
            float3 scene(float2 uv, float2 p, float soft) {
                // Rough, glitchy scanlines over a slow gradient.
                float t = floor(iTime * 12.0);
                float row = floor(uv.y * 160.0);
                float jitter = (hash21(float2(row, t)) - 0.5) * 0.02 * step(0.92, hash21(float2(row * 0.1, t)));
                float2 q = p + float2(jitter, 0.0);
                float base = fbm3(q * 1.5 + iTime * 0.05);
                float3 col = mix(iColA, iColB, base * iIntensity);
                float scan = 0.85 + 0.15 * sin(uv.y * 900.0 + iTime * 3.0);
                col *= scan;
                float speck = step(0.985 - 0.01 * iIntensity, hash21(floor(uv * 300.0) + t));
                col += iColC * speck * 0.5;
                float bar = exp(-abs(fract(uv.y * 0.5 - iTime * 0.05) - 0.5) * 40.0);
                col += iColC * bar * 0.08 * iIntensity;
                return col;
            }
                    // Static: a glitch burst — rows tear sideways and colours split.
            float2 ripplePush(float2 uv, float4 r) {
                float k = alive(r, 1.8);
                if (k <= 0.0) return float2(0.0);
                float b = band(length(uv - r.xy), rippleAge(r) * 1.1, 8.0);
                float tear = hash21(float2(floor(uv.y * 60.0), floor(iTime * 30.0))) - 0.5;
                return float2(tear * 0.12 * b * k, 0.0);
            }
            float3 rippleLight(float2 uv, float4 r, float3 rc) {
                float k = alive(r, 1.8);
                if (k <= 0.0) return float3(0.0);
                float b = band(length(uv - r.xy), rippleAge(r) * 1.1, 8.0);
                float flick = 0.5 + step(0.6, hash21(float2(floor(uv.y * 80.0), floor(iTime * 20.0))));
                return float3(rc.r * 1.3, rc.g * 0.4, rc.b * 1.4) * b * k * 0.5 * flick;
            }
        """.trimIndent()
    } + "\n"
}
