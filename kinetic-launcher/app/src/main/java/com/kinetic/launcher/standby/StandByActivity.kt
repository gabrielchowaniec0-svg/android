package com.kinetic.launcher.standby

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.Bundle
import android.provider.CalendarContract
import android.provider.MediaStore
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * StandBy (like iPhone): phone charging and turned sideways → a big nightstand clock,
 * your photos, or your calendar (swipe sideways to switch). At night, or in a dark
 * room, everything turns deep red so it doesn't light up the room.
 *
 * Closes when you unplug, stand the phone upright, or swipe up.
 */
class StandByActivity : ComponentActivity(), SensorEventListener {

    private val sensors by lazy { getSystemService(SensorManager::class.java) }
    private val lux = mutableFloatStateOf(100f)
    private var uprightSince = 0L

    private val unplugged = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        ContextCompat.registerReceiver(this, unplugged, IntentFilter(Intent.ACTION_POWER_DISCONNECTED), ContextCompat.RECEIVER_NOT_EXPORTED)
        showing = true
        setContent { StandBy() }
    }

    override fun onResume() {
        super.onResume()
        sensors?.getDefaultSensor(Sensor.TYPE_LIGHT)?.let { sensors?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sensors?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
    }

    override fun onPause() {
        sensors?.unregisterListener(this)
        super.onPause()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(unplugged) }
        showing = false
        super.onDestroy()
    }

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_LIGHT -> lux.floatValue = e.values[0]
            Sensor.TYPE_ACCELEROMETER -> {
                // Stood back upright (portrait) for a second: leave StandBy.
                val upright = kotlin.math.abs(e.values[1]) > 7.5f
                val now = System.currentTimeMillis()
                if (!upright) uprightSince = 0L else if (uprightSince == 0L) uprightSince = now else if (now - uprightSince > 1000) finish()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    @Composable
    private fun StandBy() {
        var now by remember { mutableStateOf(Date()) }
        LaunchedEffect(Unit) {
            while (true) {
                now = Date()
                delay(1000L - System.currentTimeMillis() % 1000L)
            }
        }
        val hour = Calendar.getInstance().apply { time = now }.get(Calendar.HOUR_OF_DAY)
        val light by lux
        // Night red: late at night, or a dark room.
        val night = hour >= 22 || hour < 6 || light < 3f
        val ink = if (night) Color(0xFFB3261E) else Color.White
        val soft = if (night) Color(0xFF7A1A15) else Color.White.copy(alpha = 0.6f)
        val pager = rememberPagerState { 3 }
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(Unit) {
                    var total = 0f
                    detectVerticalDragGestures(onDragStart = { total = 0f }, onDragEnd = { if (total < -120f) finish() }) { c, dy ->
                        total += dy
                        c.consume()
                    }
                },
        ) {
            HorizontalPager(pager, Modifier.fillMaxSize()) { page ->
                when (page) {
                    0 -> ClockPage(now, ink, soft)
                    1 -> PhotoPage(now, ink, night)
                    else -> CalendarPage(now, ink, soft)
                }
            }
            // Page dots
            Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(3) { i ->
                    Box(Modifier.size(6.dp).clip(CircleShape).background(if (i == pager.currentPage) ink else soft.copy(alpha = 0.4f)))
                }
            }
        }
    }

    @Composable
    private fun ClockPage(now: Date, ink: Color, soft: Color) {
        val time = SimpleDateFormat(if (android.text.format.DateFormat.is24HourFormat(this)) "HH:mm" else "h:mm", Locale.getDefault()).format(now)
        val date = SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(now)
        val battery = remember(now.time / 60_000) { getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) }
        val next = remember(now.time / 300_000) { nextEvent() }
        Row(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(time, color = ink, fontSize = 150.sp, fontWeight = FontWeight.Light, maxLines = 1, modifier = Modifier.weight(1.5f))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Text(date, color = ink, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(10.dp))
                if (next != null) {
                    Text("Next", color = soft, fontSize = 14.sp)
                    Text(next, color = ink, fontSize = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(10.dp))
                }
                Text("⚡ $battery%", color = soft, fontSize = 16.sp)
            }
        }
    }

    @Composable
    private fun PhotoPage(now: Date, ink: Color, night: Boolean) {
        var photo by remember { mutableStateOf<ImageBitmap?>(null) }
        // A different photo every 30 seconds.
        LaunchedEffect(now.time / 30_000) { photo = withContext(Dispatchers.IO) { randomPhoto() } ?: photo }
        Box(Modifier.fillMaxSize()) {
            Crossfade(photo, label = "photo") { p ->
                if (p != null) {
                    Image(
                        p, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                        // Red and dim at night.
                        colorFilter = if (night) ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(0.3f, 0.59f, 0.11f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0.6f, 0f))) else null,
                    )
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            if (hasPhotosAccess()) "No photos yet" else "Allow photo access (open the Vault's \"Add from gallery\" once)",
                            color = ink.copy(alpha = 0.6f), fontSize = 16.sp,
                        )
                    }
                }
            }
            Text(
                SimpleDateFormat("h:mm", Locale.getDefault()).format(now),
                color = ink, fontSize = 44.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.TopEnd).padding(24.dp),
            )
        }
    }

    @Composable
    private fun CalendarPage(now: Date, ink: Color, soft: Color) {
        val cal = Calendar.getInstance().apply { time = now }
        val today = cal.get(Calendar.DAY_OF_MONTH)
        val month = SimpleDateFormat("MMMM", Locale.getDefault()).format(now)
        val first = (cal.clone() as Calendar).apply { set(Calendar.DAY_OF_MONTH, 1) }
        val lead = (first.get(Calendar.DAY_OF_WEEK) - first.firstDayOfWeek + 7) % 7
        val days = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
        val events = remember(now.time / 300_000) { upcoming(4) }
        Row(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(month.uppercase(), color = Color(0xFFFF453A).let { if (ink != Color.White) ink else it }, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                val cells = List(lead) { 0 } + (1..days).toList()
                cells.chunked(7).forEach { week ->
                    Row {
                        week.forEach { d ->
                            Box(Modifier.size(34.dp).clip(CircleShape).background(if (d == today) ink else Color.Transparent), contentAlignment = Alignment.Center) {
                                if (d > 0) Text("$d", color = if (d == today) Color.Black else ink, fontSize = 15.sp)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.width(30.dp))
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
                if (events.isEmpty()) {
                    Text(if (hasCalendarAccess()) "Nothing coming up" else "Ask Kinetic \"what's next\" once to allow calendar", color = soft, fontSize = 16.sp)
                }
                events.forEach { (title, whenText) ->
                    Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(4.dp).height(36.dp).clip(RoundedCornerShape(2.dp)).background(ink))
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(title, color = ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(whenText, color = soft, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }

    // ---- data -------------------------------------------------------------------------

    private fun hasCalendarAccess() = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    private fun hasPhotosAccess() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, "android.permission.READ_MEDIA_VISUAL_USER_SELECTED") == PackageManager.PERMISSION_GRANTED

    private fun nextEvent(): String? = upcoming(1).firstOrNull()?.let { "${it.first} · ${it.second}" }

    private fun upcoming(max: Int): List<Pair<String, String>> {
        if (!hasCalendarAccess()) return emptyList()
        val now = System.currentTimeMillis()
        val b = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(b, now)
        ContentUris.appendId(b, now + 7L * 86_400_000L)
        val out = ArrayList<Pair<String, String>>()
        val dayFmt = SimpleDateFormat("EEE h:mm a", Locale.getDefault())
        val timeFmt = SimpleDateFormat("h:mm a", Locale.getDefault())
        runCatching {
            contentResolver.query(
                b.build(),
                arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.ALL_DAY),
                null, null, "${CalendarContract.Instances.BEGIN} ASC",
            )?.use { c ->
                while (c.moveToNext() && out.size < max) {
                    val begin = c.getLong(1)
                    val allDay = c.getInt(2) == 1
                    if (!allDay && begin < now) continue
                    val sameDay = (begin - now) < 86_400_000L && Calendar.getInstance().apply { timeInMillis = begin }.get(Calendar.DAY_OF_YEAR) == Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
                    val w = when {
                        allDay -> "All day"
                        sameDay -> timeFmt.format(Date(begin))
                        else -> dayFmt.format(Date(begin))
                    }
                    out += (c.getString(0) ?: "Event") to w
                }
            }
        }
        return out
    }

    private fun randomPhoto(): ImageBitmap? {
        if (!hasPhotosAccess()) return null
        val ids = ArrayList<Long>()
        runCatching {
            contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media._ID),
                null, null, "${MediaStore.Images.Media.DATE_ADDED} DESC",
            )?.use { c -> while (c.moveToNext() && ids.size < 300) ids += c.getLong(0) }
        }
        if (ids.isEmpty()) return null
        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ids.random())
        return runCatching {
            val size = runCatching { contentResolver.loadThumbnail(uri, android.util.Size(1600, 1600), null) }.getOrNull()
            (size ?: contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) })?.asImageBitmap()
        }.getOrNull()
    }

    companion object {
        /** True while StandBy is on screen. */
        @Volatile
        var showing = false
    }
}
