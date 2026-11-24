package com.kinetic.launcher.planes

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.Location
import android.location.LocationManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.animation.core.animateFloat
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

/**
 * Plane spotter: point the phone at a plane and see its flight, airline, route,
 * altitude and speed, drawn on top of the live camera.
 *
 * How it knows: your location + live ADS-B positions (adsb.lol / adsb.fi) give each
 * plane's direction and height in the sky; the compass and gyro say where the camera
 * is pointing. Nothing is recorded or saved.
 */
class PlaneSpotterActivity : ComponentActivity(), SensorEventListener {

    private val sensors by lazy { getSystemService(SensorManager::class.java) }
    /** Device → world (east, north, up; magnetic north), smoothed. */
    private val rotation = mutableStateOf(FloatArray(9).also { it[0] = 1f; it[4] = 1f; it[8] = 1f })
    private val raw = FloatArray(9)
    private var haveRotation = false
    private val compassPoor = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val vfov = cameraVerticalFov()
        setContent { Spotter(vfov) }
    }

    override fun onResume() {
        super.onResume()
        sensors?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let { sensors?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    override fun onPause() {
        sensors?.unregisterListener(this)
        super.onPause()
    }

    override fun onSensorChanged(event: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(raw, event.values)
        val prev = rotation.value
        val next = FloatArray(9)
        val k = if (haveRotation) 0.18f else 1f
        for (i in 0 until 9) next[i] = prev[i] + (raw[i] - prev[i]) * k
        haveRotation = true
        rotation.value = next
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        compassPoor.value = accuracy <= SensorManager.SENSOR_STATUS_ACCURACY_LOW
    }

    /** Vertical field of view (degrees) of the main back camera, holding the phone upright. */
    private fun cameraVerticalFov(): Float = runCatching {
        val cm = getSystemService(CameraManager::class.java)
        val id = cm.cameraIdList.first { cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK }
        val ch = cm.getCameraCharacteristics(id)
        val size = ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)!!
        val focal = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)!!.first()
        Math.toDegrees(2.0 * atan((maxOf(size.width, size.height) / 2.0) / focal)).toFloat()
    }.getOrDefault(70f)

    private fun hasPermission(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    @Composable
    private fun Spotter(vfovDeg: Float) {
        var camOk by remember { mutableStateOf(hasPermission(Manifest.permission.CAMERA)) }
        var locOk by remember { mutableStateOf(hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) || hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)) }
        val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            camOk = hasPermission(Manifest.permission.CAMERA)
            locOk = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) || hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        LaunchedEffect(Unit) {
            if (!camOk || !locOk) ask.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }

        var me by remember { mutableStateOf<Location?>(null) }
        LaunchedEffect(locOk) { if (locOk) me = locate(this@PlaneSpotterActivity) { me = it } }
        val declination = remember(me) {
            me?.let { GeomagneticField(it.latitude.toFloat(), it.longitude.toFloat(), it.altitude.toFloat(), System.currentTimeMillis()).declination } ?: 0f
        }

        var planes by remember { mutableStateOf<List<Plane>>(emptyList()) }
        var fetchedAt by remember { mutableLongStateOf(0L) }
        var loading by remember { mutableStateOf(true) }
        LaunchedEffect(me?.latitude?.let { (it * 100).roundToInt() }, me?.longitude?.let { (it * 100).roundToInt() }) {
            val here = me ?: return@LaunchedEffect
            while (true) {
                val list = withContext(Dispatchers.IO) { runCatching { PlaneData.nearby(here.latitude, here.longitude) }.getOrDefault(planes) }
                planes = list
                fetchedAt = System.currentTimeMillis()
                loading = false
                delay(5_000)
            }
        }

        val rot by rotation
        val poor by compassPoor
        // Where everything is in the sky right now (positions moved on since the last fetch).
        val now = System.currentTimeMillis()
        // Modes: radar (no camera needed, great at night) and United-only.
        var radar by remember { mutableStateOf(false) }
        var unitedOnly by remember { mutableStateOf(false) }
        var showLog by remember { mutableStateOf(false) }
        var logCount by remember { mutableStateOf(PlaneLog.all(this@PlaneSpotterActivity).size) }
        val radarView = radar || !camOk
        val shown = if (unitedOnly) planes.filter { isUnited(it) } else planes
        val sky = remember(shown, me, rot, declination) {
            val here = me ?: return@remember emptyList()
            shown.map { skyPos(it, here, declination, (now - fetchedAt) / 1000.0, rot) }
        }
        val cam = remember(rot) { cameraDir(rot) }

        // The plane closest to the middle of the view (sticky so it doesn't flicker).
        var lockedHex by remember { mutableStateOf<String?>(null) }
        // On the radar, "pointing at" means the direction you face (height doesn't matter).
        fun aim(s: SkyPos) = if (radarView) kotlin.math.abs(((s.azimuth - cam[0] + 540f) % 360f) - 180f) else s.angle
        val best = sky.minByOrNull { aim(it) }
        val stillLocked = sky.firstOrNull { it.plane.hex == lockedHex && aim(it) < 14f }
        val locked = stillLocked ?: best?.takeIf { aim(it) < 9f }
        val haptics = LocalHapticFeedback.current
        LaunchedEffect(locked?.plane?.hex) {
            if (locked != null && locked.plane.hex != lockedHex) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            lockedHex = locked?.plane?.hex
        }
        var route by remember { mutableStateOf<Route?>(null) }
        LaunchedEffect(locked?.plane?.callsign) {
            route = null
            val cs = locked?.plane?.callsign ?: return@LaunchedEffect
            route = withContext(Dispatchers.IO) { runCatching { PlaneData.route(cs) }.getOrNull() }
            // Into the logbook once it's been locked on for a moment.
            delay(1200)
            val p = locked?.plane ?: return@LaunchedEffect
            val r = route
            val isNew = withContext(Dispatchers.IO) {
                PlaneLog.add(
                    this@PlaneSpotterActivity,
                    PlaneLog.Entry(p.label, PlaneData.typeName(p.typeCode), r?.airline, r?.fromCode, r?.toCode, p.registration, System.currentTimeMillis()),
                )
            }
            if (isNew) logCount++
        }

        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (radarView) {
                RadarView(sky, cam[0], locked?.plane?.hex)
            } else {
                CameraPreview()
                Canvas(Modifier.fillMaxSize()) {
                    val f = (size.height / 2f) / tan(Math.toRadians(vfovDeg / 2.0)).toFloat()
                    sky.forEach { s -> drawMarker(s, s.plane.hex == locked?.plane?.hex, f) }
                    // Reticle
                    drawCircle(Color.White.copy(alpha = 0.55f), 34.dp.toPx(), center, style = Stroke(1.5.dp.toPx()))
                    drawCircle(Color.White.copy(alpha = 0.8f), 2.dp.toPx(), center)
                }
            }

            // Top bar
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("✈  Plane spotter", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        when {
                            !locOk -> "Needs your location"
                            me == null -> "Finding you…"
                            loading -> "Looking at the sky…"
                            unitedOnly -> "${shown.size} United flights within 35 miles"
                            else -> "${planes.size} planes within 35 miles"
                        },
                        color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip(if (radarView) "Radar" else "Radar", radarView) { if (camOk) radar = !radar }
                        Chip("United", unitedOnly) { unitedOnly = !unitedOnly }
                        Chip("Logbook · $logCount", false) { showLog = true }
                    }
                }
                Text(
                    "Done",
                    color = Color.Black,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .background(Color.White, RoundedCornerShape(50))
                        .clickable { finish() }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            // Bottom card
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(16.dp)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.62f), RoundedCornerShape(28.dp))
                    .padding(horizontal = 22.dp, vertical = 18.dp),
            ) {
                AnimatedContent(targetState = locked?.plane?.hex, label = "card") { hex ->
                    val s = sky.firstOrNull { it.plane.hex == hex }
                    if (s != null) LockedCard(s, route) else HintCard(sky, cam, poor, locOk, me != null)
                }
            }

            if (showLog) Logbook(onClose = { showLog = false })
        }
    }

    @Composable
    private fun LockedCard(s: SkyPos, route: Route?) {
        val p = s.plane
        Column {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(p.label, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(10.dp))
                Text(route?.airline ?: PlaneData.typeName(p.typeCode) ?: "", color = Color.White.copy(alpha = 0.7f), fontSize = 15.sp, modifier = Modifier.padding(bottom = 5.dp))
            }
            if (route != null) {
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Airport(route.fromCode, route.fromCity, Alignment.Start)
                    Text("→", color = Color(0xFF7FB2FF), fontSize = 26.sp)
                    Airport(route.toCode, route.toCity, Alignment.End)
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Stat("Altitude", "%,d ft".format(p.altitudeFt))
                Stat("Speed", p.speedKt?.let { "${(it * 1.15078).roundToInt()} mph" } ?: "–")
                Stat("Away", "%.1f mi".format(s.distanceM / 1609.34))
            }
            val type = PlaneData.typeName(p.typeCode)
            if (type != null && route?.airline != null) {
                Spacer(Modifier.height(8.dp))
                Text(type + (p.registration?.let { " · $it" } ?: ""), color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
            }
        }
    }

    @Composable
    private fun Airport(code: String, city: String, align: Alignment.Horizontal) {
        Column(horizontalAlignment = align) {
            Text(code, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
            Text(city, color = Color.White.copy(alpha = 0.65f), fontSize = 12.sp, maxLines = 1)
        }
    }

    @Composable
    private fun Stat(label: String, value: String) {
        Column {
            Text(label, color = Color.White.copy(alpha = 0.55f), fontSize = 11.sp)
            Text(value, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        }
    }

    @Composable
    private fun HintCard(sky: List<SkyPos>, cam: FloatArray, poorCompass: Boolean, locOk: Boolean, located: Boolean) {
        Column {
            Text(
                when {
                    !locOk -> "Allow location so Kinetic knows which planes are near you"
                    !located -> "Finding where you are…"
                    sky.isEmpty() -> "No planes in the air nearby right now"
                    else -> "Point at a plane"
                },
                color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
            )
            // Guide to the nearest one.
            sky.minByOrNull { it.distanceM }?.let { n ->
                val turn = ((n.azimuth - cam[0] + 540f) % 360f) - 180f
                val tilt = n.elevation - cam[1]
                val lr = if (abs(turn) < 6) "" else if (turn > 0) "turn right ${abs(turn).roundToInt()}°" else "turn left ${abs(turn).roundToInt()}°"
                val ud = if (abs(tilt) < 5) "" else if (tilt > 0) "look up ${abs(tilt).roundToInt()}°" else "look down ${abs(tilt).roundToInt()}°"
                val how = listOf(lr, ud).filter { it.isNotEmpty() }.joinToString(", ").ifEmpty { "it's right there" }
                Spacer(Modifier.height(4.dp))
                Text(
                    "Nearest: ${n.plane.label}, ${"%.1f".format(n.distanceM / 1609.34)} mi away. ${how.replaceFirstChar { it.uppercase() }}",
                    color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp,
                )
            }
            if (poorCompass) {
                Spacer(Modifier.height(4.dp))
                Text("Compass is unsure: wave the phone in a figure 8", color = Color(0xFFFFC56B), fontSize = 13.sp)
            }
        }
    }

    @Composable
    private fun CameraPreview() {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    val future = ProcessCameraProvider.getInstance(ctx)
                    future.addListener({
                        runCatching {
                            val provider = future.get()
                            val preview = Preview.Builder().build()
                            preview.setSurfaceProvider(surfaceProvider)
                            provider.unbindAll()
                            provider.bindToLifecycle(this@PlaneSpotterActivity, CameraSelector.DEFAULT_BACK_CAMERA, preview)
                        }
                    }, ContextCompat.getMainExecutor(ctx))
                }
            },
        )
    }

    // ---- geometry -----------------------------------------------------------------------

    /** A plane's place in the sky and on screen. x/y/z are in the camera's view (z = ahead). */
    class SkyPos(
        val plane: Plane,
        val azimuth: Float,
        val elevation: Float,
        val distanceM: Double,
        val x: Float,
        val y: Float,
        val z: Float,
        /** Degrees away from the middle of the view. */
        val angle: Float,
    )

    /** Camera direction: [azimuth (magnetic), elevation] in degrees. */
    private fun cameraDir(r: FloatArray): FloatArray {
        val vx = -r[2]
        val vy = -r[5]
        val vz = -r[8]
        val az = ((Math.toDegrees(atan2(vx, vy).toDouble()) + 360.0) % 360.0).toFloat()
        val el = Math.toDegrees(asin(vz.coerceIn(-1f, 1f)).toDouble()).toFloat()
        return floatArrayOf(az, el)
    }

    private fun skyPos(p: Plane, me: Location, declination: Float, secondsSince: Double, r: FloatArray): SkyPos {
        // Move the plane along its track since the last update.
        var lat = p.lat
        var lon = p.lon
        if (p.speedKt != null && p.trackDeg != null && secondsSince in 0.0..20.0) {
            val d = p.speedKt * 0.514444 * secondsSince
            val t = Math.toRadians(p.trackDeg)
            lat += (d * cos(t)) / 111_320.0
            lon += (d * sin(t)) / (111_320.0 * cos(Math.toRadians(lat)))
        }
        val ground = PlaneData.distanceM(me.latitude, me.longitude, lat, lon)
        val bearingTrue = PlaneData.bearingDeg(me.latitude, me.longitude, lat, lon)
        val elev = PlaneData.elevationDeg(ground, p.altitudeFt * 0.3048, if (me.hasAltitude()) me.altitude else 200.0)
        // The sensor's north is magnetic north.
        val az = ((bearingTrue - declination + 360.0) % 360.0)
        val b = Math.toRadians(az)
        val e = Math.toRadians(elev)
        val wx = (cos(e) * sin(b)).toFloat()
        val wy = (cos(e) * cos(b)).toFloat()
        val wz = sin(e).toFloat()
        // Screen right = device +X, screen up = device +Y, ahead = device −Z.
        val x = wx * r[0] + wy * r[3] + wz * r[6]
        val y = wx * r[1] + wy * r[4] + wz * r[7]
        val z = -(wx * r[2] + wy * r[5] + wz * r[8])
        val angle = Math.toDegrees(acos(z.coerceIn(-1f, 1f).toDouble())).toFloat()
        return SkyPos(p, az.toFloat(), elev.toFloat(), ground, x, y, z, angle)
    }

    private val labelPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        textSize = 38f
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
        setShadowLayer(6f, 0f, 2f, android.graphics.Color.argb(160, 0, 0, 0))
    }
    private val subPaint = android.graphics.Paint(labelPaint).apply {
        textSize = 30f
        typeface = android.graphics.Typeface.DEFAULT
        color = android.graphics.Color.argb(210, 255, 255, 255)
    }

    private fun DrawScope.drawMarker(s: SkyPos, locked: Boolean, f: Float) {
        if (s.z < 0.15f) return
        val px = center.x + s.x / s.z * f
        val py = center.y - s.y / s.z * f
        if (px < -100 || px > size.width + 100 || py < -100 || py > size.height + 100) return
        val c = Offset(px, py)
        val accent = if (locked) Color(0xFF7FB2FF) else Color.White
        if (locked) {
            // Corner brackets around the locked plane.
            val h = 30.dp.toPx()
            val l = 12.dp.toPx()
            val w = 2.5.dp.toPx()
            for (sx in listOf(-1f, 1f)) for (sy in listOf(-1f, 1f)) {
                val corner = Offset(c.x + sx * h, c.y + sy * h)
                drawLine(accent, corner, Offset(corner.x - sx * l, corner.y), w)
                drawLine(accent, corner, Offset(corner.x, corner.y - sy * l), w)
            }
        }
        drawCircle(accent.copy(alpha = 0.25f), 12.dp.toPx(), c)
        drawCircle(accent, 5.dp.toPx(), c)
        val tx = c.x + 20.dp.toPx()
        drawContext.canvas.nativeCanvas.drawText(s.plane.label, tx, c.y - 2.dp.toPx(), labelPaint)
        drawContext.canvas.nativeCanvas.drawText(
            "%,d ft · %.1f mi".format(s.plane.altitudeFt, s.distanceM / 1609.34), tx, c.y + 14.dp.toPx(), subPaint,
        )
    }

    /** United mainline plus the United Express airlines that only fly for United. */
    private fun isUnited(p: Plane): Boolean {
        val cs = p.callsign.uppercase()
        return listOf("UAL", "GJS", "UCA", "ASH").any { cs.startsWith(it) }
    }

    @Composable
    private fun Chip(label: String, on: Boolean, onClick: () -> Unit) {
        Text(
            label,
            color = if (on) Color.Black else Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .background(if (on) Color.White else Color.White.copy(alpha = 0.18f), RoundedCornerShape(50))
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }

    /** Night radar: you in the middle, the way you're facing at the top, planes as glowing dots. */
    @Composable
    private fun RadarView(sky: List<SkyPos>, heading: Float, lockedHex: String?) {
        val sweep = androidx.compose.animation.core.rememberInfiniteTransition(label = "sweep")
        val angle by sweep.animateFloat(
            0f, 360f,
            androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(4000, easing = androidx.compose.animation.core.LinearEasing)),
            label = "sweepAngle",
        )
        Canvas(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color(0xFF0B2A1E), Color(0xFF02080A))))) {
            val c = Offset(size.width / 2f, size.height * 0.46f)
            val maxR = size.width * 0.46f
            val maxMi = 35f
            val green = Color(0xFF3DDC84)
            // Range rings: 10, 20, 35 miles.
            listOf(10f, 20f, 35f).forEach { mi ->
                val rr = maxR * mi / maxMi
                drawCircle(green.copy(alpha = 0.22f), rr, c, style = Stroke(1.dp.toPx()))
                drawContext.canvas.nativeCanvas.drawText("${mi.toInt()} mi", c.x + 6, c.y - rr + 30, ringPaint)
            }
            drawLine(green.copy(alpha = 0.15f), Offset(c.x, c.y - maxR), Offset(c.x, c.y + maxR), 1.dp.toPx())
            drawLine(green.copy(alpha = 0.15f), Offset(c.x - maxR, c.y), Offset(c.x + maxR, c.y), 1.dp.toPx())
            // Sweep
            val a = Math.toRadians(angle.toDouble() - 90)
            drawLine(green.copy(alpha = 0.5f), c, Offset(c.x + (maxR * kotlin.math.cos(a)).toFloat(), c.y + (maxR * kotlin.math.sin(a)).toFloat()), 2.dp.toPx())
            // "Ahead" marker
            drawCircle(Color.White, 5.dp.toPx(), c)
            drawLine(Color.White.copy(alpha = 0.6f), c, Offset(c.x, c.y - maxR), 1.5.dp.toPx())
            sky.forEach { s ->
                val mi = (s.distanceM / 1609.34).toFloat()
                if (mi > maxMi) return@forEach
                val rel = Math.toRadians((s.azimuth - heading).toDouble() - 90)
                val rr = maxR * mi / maxMi
                val p = Offset(c.x + (rr * kotlin.math.cos(rel)).toFloat(), c.y + (rr * kotlin.math.sin(rel)).toFloat())
                val on = s.plane.hex == lockedHex
                val col = if (on) Color(0xFF7FB2FF) else green
                // Heading tail
                s.plane.trackDeg?.let { t ->
                    val tr = Math.toRadians(t - heading - 90 + 180)
                    drawLine(col.copy(alpha = 0.5f), p, Offset(p.x + (22.dp.toPx() * kotlin.math.cos(tr)).toFloat(), p.y + (22.dp.toPx() * kotlin.math.sin(tr)).toFloat()), 2.dp.toPx())
                }
                drawCircle(col.copy(alpha = 0.25f), (if (on) 14 else 9).dp.toPx(), p)
                drawCircle(col, 4.dp.toPx(), p)
                drawContext.canvas.nativeCanvas.drawText(s.plane.label, p.x + 14.dp.toPx(), p.y - 4.dp.toPx(), labelPaint)
                drawContext.canvas.nativeCanvas.drawText("%,d ft".format(s.plane.altitudeFt), p.x + 14.dp.toPx(), p.y + 12.dp.toPx(), subPaint)
            }
        }
    }

    private val ringPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(120, 61, 220, 132)
        textSize = 28f
    }

    /** Everything you've spotted: totals, your top aircraft types, and the latest sightings. */
    @Composable
    private fun Logbook(onClose: () -> Unit) {
        val log = remember { PlaneLog.all(this@PlaneSpotterActivity) }
        val types = remember(log) { log.mapNotNull { it.type }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value } }
        val airlines = remember(log) { log.mapNotNull { it.airline }.toSet().size }
        val fmt = remember { java.text.SimpleDateFormat("MMM d · h:mm a", java.util.Locale.getDefault()) }
        Column(
            Modifier
                .fillMaxSize()
                .background(Color(0xF2080B12))
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp),
        ) {
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Logbook", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(
                    "Close",
                    color = Color.Black,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.background(Color.White, RoundedCornerShape(50)).clickable(onClick = onClose).padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            Text("${log.size} planes · ${types.size} aircraft types · $airlines airlines", color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
            Spacer(Modifier.height(16.dp))
            androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                if (types.isNotEmpty()) {
                    item { Text("Most spotted", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 8.dp)) }
                    items(types.take(8).size) { i ->
                        val (t, n) = types[i]
                        Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                            Text(t, color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
                            Text("×$n", color = Color(0xFF7FB2FF), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    item { Spacer(Modifier.height(18.dp)) }
                }
                item { Text("Recent", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 8.dp)) }
                if (log.isEmpty()) {
                    item { Text("Point at a plane until it locks on, and it's saved here.", color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp) }
                }
                items(log.size) { i ->
                    val e = log[i]
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Row {
                            Text(e.callsign, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            if (e.from != null && e.to != null) {
                                Spacer(Modifier.width(10.dp))
                                Text("${e.from} → ${e.to}", color = Color(0xFF7FB2FF), fontSize = 15.sp)
                            }
                        }
                        Text(
                            listOfNotNull(e.airline, e.type, fmt.format(java.util.Date(e.at))).joinToString(" · "),
                            color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp,
                        )
                    }
                }
            }
        }
    }

    companion object {
        /** Gets a location fast (last known), then a fresh one when it arrives. */
        fun locate(context: Context, onFresh: (Location) -> Unit): Location? {
            val lm = context.getSystemService(LocationManager::class.java) ?: return null
            val last = try {
                listOf(LocationManager.FUSED_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
                    .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
                    .maxByOrNull { it.time }
            } catch (_: SecurityException) {
                null
            }
            val provider = listOf(LocationManager.FUSED_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                .firstOrNull { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
            if (provider != null) {
                try {
                    lm.getCurrentLocation(provider, null, context.mainExecutor) { it?.let(onFresh) }
                } catch (_: SecurityException) {
                } catch (_: IllegalArgumentException) {
                }
            }
            return last
        }
    }
}

