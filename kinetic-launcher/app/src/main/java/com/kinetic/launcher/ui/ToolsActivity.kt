package com.kinetic.launcher.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Little phone tools: a bubble level, an on-screen ruler and a sound meter. */
class ToolsActivity : ComponentActivity() {
    companion object {
        const val EXTRA_TOOL = "tool"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val first = intent.getStringExtra(EXTRA_TOOL) ?: "level"
        setContent {
            var tool by remember { mutableStateOf(first) }
            Column(
                Modifier.fillMaxSize().background(Color(0xFF07080C)).statusBarsPadding().navigationBarsPadding(),
            ) {
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("level" to "Level", "ruler" to "Ruler", "decibels" to "Sound").forEach { (k, label) ->
                        Text(
                            label, color = if (tool == k) Color.Black else Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clip(RoundedCornerShape(50)).background(if (tool == k) Color.White else Color(0x22FFFFFF))
                                .clickable { tool = k }.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
                Box(Modifier.fillMaxSize()) {
                    when (tool) {
                        "ruler" -> Ruler()
                        "decibels" -> SoundMeter()
                        else -> Level()
                    }
                }
            }
        }
    }
}

@Composable
private fun Level() {
    val context = LocalContext.current
    var x by remember { mutableFloatStateOf(0f) }
    var y by remember { mutableFloatStateOf(0f) }
    var z by remember { mutableFloatStateOf(9.8f) }
    DisposableEffect(Unit) {
        val sm = context.getSystemService(SensorManager::class.java)
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                // Smooth it a little so the bubble doesn't jitter.
                x += (e.values[0] - x) * 0.15f
                y += (e.values[1] - y) * 0.15f
                z += (e.values[2] - z) * 0.15f
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sm.registerListener(l, it, SensorManager.SENSOR_DELAY_GAME) }
        onDispose { sm.unregisterListener(l) }
    }
    // Flat on a table: tilt in both directions. Standing on an edge: the angle of that edge.
    val flat = abs(z) > 7f
    val angle = if (flat) {
        Math.toDegrees(atan2(sqrt((x * x + y * y).toDouble()), abs(z).toDouble())).toFloat()
    } else {
        val a = Math.toDegrees(atan2(x.toDouble(), y.toDouble())).toFloat()
        // Distance from the nearest level edge (0°, 90°, 180°…).
        val m = ((a % 90f) + 90f) % 90f
        if (m > 45f) 90f - m else m
    }
    val level = angle < 0.6f
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Canvas(Modifier.padding(24.dp).fillMaxWidth().weight(1f)) {
            val c = center
            val r = size.minDimension / 2.4f
            drawCircle(Color(0x33FFFFFF), r, c, style = Stroke(3f))
            drawCircle(Color(0x33FFFFFF), r / 6f, c, style = Stroke(3f))
            drawLine(Color(0x22FFFFFF), Offset(c.x - r, c.y), Offset(c.x + r, c.y), 2f)
            drawLine(Color(0x22FFFFFF), Offset(c.x, c.y - r), Offset(c.x, c.y + r), 2f)
            val bx = if (flat) (-x / 9.8f * r * 2f).coerceIn(-r, r) else 0f
            val by = if (flat) (y / 9.8f * r * 2f).coerceIn(-r, r) else 0f
            drawCircle(if (level) Color(0xFF4ADE80) else Color(0xFFFFFFFF), r / 7f, Offset(c.x + bx, c.y + by))
        }
        Text("%.1f°".format(angle), color = if (level) Color(0xFF4ADE80) else Color.White, fontSize = 64.sp, fontWeight = FontWeight.Light)
        Text(if (flat) "Lay the phone on the surface" else "Hold the phone's edge against it", color = Color(0x99FFFFFF), fontSize = 14.sp, modifier = Modifier.padding(bottom = 40.dp))
    }
}

@Composable
private fun Ruler() {
    val dm = LocalContext.current.resources.displayMetrics
    // ydpi = real pixels per inch down the screen.
    val pxPerMm = dm.ydpi / 25.4f
    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            var mm = 0
            while (mm * pxPerMm < size.height) {
                val yy = mm * pxPerMm
                val len = when {
                    mm % 10 == 0 -> 70f
                    mm % 5 == 0 -> 45f
                    else -> 25f
                }
                drawLine(Color.White, Offset(0f, yy), Offset(len, yy), 2f)
                mm++
            }
            var s = 0
            val pxPerSixteenth = dm.ydpi / 16f
            while (s * pxPerSixteenth < size.height) {
                val yy = s * pxPerSixteenth
                val len = when {
                    s % 16 == 0 -> 70f
                    s % 8 == 0 -> 50f
                    s % 4 == 0 -> 35f
                    else -> 20f
                }
                drawLine(Color(0xFF93C5FD), Offset(size.width - len, yy), Offset(size.width, yy), 2f)
                s++
            }
        }
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("cm  ◂   ▸  in", color = Color.White, fontSize = 18.sp)
            Text("Starts at the top edge of the screen", color = Color(0x99FFFFFF), fontSize = 13.sp)
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
private fun SoundMeter() {
    val context = LocalContext.current
    var allowed by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed = it }
    var db by remember { mutableFloatStateOf(0f) }
    var peak by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(allowed) {
        if (!allowed) {
            ask.launch(Manifest.permission.RECORD_AUDIO)
            return@LaunchedEffect
        }
        withContext(Dispatchers.IO) {
            val rate = 44100
            val size = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(4096)
            val rec = runCatching { AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size) }.getOrNull() ?: return@withContext
            val buf = ShortArray(size / 2)
            try {
                rec.startRecording()
                while (isActive) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    var sum = 0.0
                    for (i in 0 until n) sum += buf[i].toDouble() * buf[i]
                    val rms = sqrt(sum / n).coerceAtLeast(1.0)
                    // Rough calibration: phone mics aren't sound meters, so this is an estimate.
                    val v = (20 * log10(rms) + 4).toFloat().coerceIn(0f, 120f)
                    db += (v - db) * 0.3f
                    if (db > peak) peak = db
                }
            } finally {
                runCatching { rec.stop() }
                rec.release()
            }
        }
    }
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        if (!allowed) {
            Text("Needs the microphone", color = Color.White, fontSize = 18.sp)
            Text("Allow", color = Color.Black, modifier = Modifier.padding(16.dp).clip(RoundedCornerShape(50)).background(Color.White).clickable { ask.launch(Manifest.permission.RECORD_AUDIO) }.padding(horizontal = 24.dp, vertical = 10.dp))
            return@Column
        }
        val color = when {
            db < 60 -> Color(0xFF4ADE80)
            db < 85 -> Color(0xFFFACC15)
            else -> Color(0xFFF87171)
        }
        Text("${db.roundToInt()}", color = color, fontSize = 110.sp, fontWeight = FontWeight.Light)
        Text("dB (approximate)", color = Color(0x99FFFFFF), fontSize = 16.sp)
        Text(
            when {
                db < 40 -> "Quiet"
                db < 60 -> "Conversation"
                db < 85 -> "Busy street"
                db < 100 -> "Loud: limit time here"
                else -> "Very loud: protect your ears"
            },
            color = Color.White, fontSize = 20.sp, modifier = Modifier.padding(top = 12.dp),
        )
        Text("Peak ${peak.roundToInt()} dB · tap to reset", color = Color(0x99FFFFFF), fontSize = 14.sp, modifier = Modifier.padding(top = 24.dp).clickable { peak = 0f })
    }
}
