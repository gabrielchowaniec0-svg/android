package com.kinetic.launcher.vault

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.lifecycleScope
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Secure video: records with the camera straight into the Vault. No code is needed to
 * record (it also works from the lock screen), but you need it to watch. The video is
 * written to Kinetic's private storage, sealed with the Vault's drop box key, and the
 * unsealed copy is wiped. It never touches the gallery.
 */
class SecureVideoActivity : ComponentActivity() {

    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var recordingFile: File? = null
    private var isRecording by mutableStateOf(false)
    private var startedAt by mutableLongStateOf(0L)
    private var sealing by mutableStateOf(false)
    private var front by mutableStateOf(false)
    private var previewView: PreviewView? = null

    private val askPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.CAMERA] == true) bindCamera() else {
            Toast.makeText(this, "Secure video needs the camera", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // No screenshots, no preview in Recents; works over the lock screen.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setShowWhenLocked(true)
        setTurnScreenOn(true)

        if (!VaultCrypto.canDrop(this)) {
            Toast.makeText(this, "Open the Vault once first to set it up", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        setContent {
            var elapsed by mutableLongStateOf(0L)
            LaunchedEffect(isRecording) {
                while (isRecording) {
                    elapsed = SystemClock.elapsedRealtime() - startedAt
                    delay(200)
                }
            }
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                AndroidView(
                    factory = { ctx -> PreviewView(ctx).also { previewView = it; it.scaleType = PreviewView.ScaleType.FILL_CENTER } },
                    modifier = Modifier.fillMaxSize(),
                )
                // Top: what this is, and the time while recording.
                Column(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 56.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    val label = when {
                        sealing -> "Sealing into the Vault…"
                        isRecording -> "● " + clock(elapsed)
                        else -> "Secure video · goes straight to the Vault"
                    }
                    Text(
                        label, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clip(RoundedCornerShape(50))
                            .background(if (isRecording) Color(0xFFFF3B30) else Color(0x88000000))
                            .padding(horizontal = 14.dp, vertical = 7.dp),
                    )
                }
                // Bottom: close, record/stop, flip.
                Row(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(bottom = 34.dp, start = 30.dp, end = 30.dp),
                    horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
                ) {
                    RoundText(if (isRecording) "" else "✕") { if (!isRecording && !sealing) finish() }
                    Box(
                        Modifier.size(78.dp).clip(CircleShape).border(4.dp, Color.White, CircleShape)
                            .clickable(enabled = !sealing) { if (isRecording) stopRecording() else startRecording() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier.size(if (isRecording) 30.dp else 62.dp)
                                .clip(if (isRecording) RoundedCornerShape(7.dp) else CircleShape)
                                .background(Color(0xFFFF3B30)),
                        )
                    }
                    RoundText(if (isRecording) "" else "⟲") {
                        if (!isRecording && !sealing) {
                            front = !front
                            bindCamera()
                        }
                    }
                }
            }
        }

        val needed = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isEmpty()) bindCamera() else askPermissions.launch(needed.toTypedArray())
    }

    @androidx.compose.runtime.Composable
    private fun RoundText(text: String, onClick: () -> Unit) {
        Box(
            Modifier.size(52.dp).clip(CircleShape).background(if (text.isEmpty()) Color.Transparent else Color(0x66000000)).clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { Text(text, color = Color.White, fontSize = 22.sp) }
    }

    private fun clock(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }

    private fun bindCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            runCatching {
                val provider = future.get()
                val preview = Preview.Builder().build()
                previewView?.let { preview.setSurfaceProvider(it.surfaceProvider) }
                val recorder = Recorder.Builder()
                    .setQualitySelector(QualitySelector.from(Quality.FHD, androidx.camera.video.FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)))
                    .build()
                val vc = VideoCapture.withOutput(recorder)
                videoCapture = vc
                provider.unbindAll()
                provider.bindToLifecycle(
                    this,
                    if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA,
                    preview, vc,
                )
            }.onFailure {
                Toast.makeText(this, "Couldn't open the camera", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @SuppressLint("MissingPermission")
    private fun startRecording() {
        val vc = videoCapture ?: return
        // Private, never the gallery; wiped as soon as it's sealed.
        val f = File(VaultStore.captureDir(this), "securevideo-${System.currentTimeMillis()}.mp4")
        recordingFile = f
        val pending = vc.output.prepareRecording(this, FileOutputOptions.Builder(f).build())
        val withAudio = if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            pending.withAudioEnabled()
        } else {
            pending
        }
        recording = withAudio.start(ContextCompat.getMainExecutor(this)) { event ->
            when (event) {
                is VideoRecordEvent.Start -> {
                    startedAt = SystemClock.elapsedRealtime()
                    isRecording = true
                    com.kinetic.launcher.ui.Haptics.confirm(window.decorView)
                }
                is VideoRecordEvent.Finalize -> {
                    isRecording = false
                    recording = null
                    val file = recordingFile
                    recordingFile = null
                    if (file != null && file.exists() && file.length() > 0) seal(file)
                    else file?.let(VaultStore::wipe)
                }
            }
        }
    }

    private fun stopRecording() {
        recording?.stop()
    }

    /** Encrypts the finished video into the Vault's drop box and wipes the plain copy. */
    private fun seal(file: File) {
        sealing = true
        val app = applicationContext
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { sealFile(app, file) }
            sealing = false
            val msg = if (ok) "Secure video saved to the Vault" else "Couldn't save the secure video"
            com.kinetic.launcher.island.IslandBus.tell(app, if (ok) "🔒" else "✕", msg)
        }
    }

    override fun onStop() {
        super.onStop()
        // Leaving (or the screen turning off) stops and seals the recording.
        if (isRecording) stopRecording()
    }

    companion object {
        fun open(context: Context) {
            context.startActivity(Intent(context, SecureVideoActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }

        fun sealFile(context: Context, file: File): Boolean = try {
            val name = "Video_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val out = File(VaultStore.dropDir(context), "$name.kdb")
            val tmp = File(out.path + ".part")
            file.inputStream().buffered().use { i -> tmp.outputStream().buffered().use { o -> VaultCrypto.dropEncrypt(context, i, o) } }
            tmp.renameTo(out)
        } catch (_: Exception) {
            false
        } finally {
            VaultStore.wipe(file)
        }
    }
}
