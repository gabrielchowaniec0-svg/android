package com.kinetic.launcher.vault

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.kinetic.launcher.KineticSettings
import java.io.ByteArrayInputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * After the third wrong Kinetic code in a row, the front camera quietly takes one photo.
 * It's sealed straight into the Vault (like a secure screenshot) and shows up next time
 * the real code opens it, named "Intruder" with the date and time.
 */
object IntruderSnap {
    fun capture(context: Context, owner: LifecycleOwner) {
        if (!KineticSettings.current.intruderPhoto) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        if (!VaultCrypto.canDrop(context)) return
        val app = context.applicationContext
        val future = ProcessCameraProvider.getInstance(app)
        future.addListener({
            runCatching {
                val provider = future.get()
                val capture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
                provider.bindToLifecycle(owner, CameraSelector.DEFAULT_FRONT_CAMERA, capture)
                capture.takePicture(
                    ContextCompat.getMainExecutor(app),
                    object : ImageCapture.OnImageCapturedCallback() {
                        override fun onCaptureSuccess(image: ImageProxy) {
                            val bytes = runCatching {
                                val buf = image.planes[0].buffer
                                ByteArray(buf.remaining()).also { buf.get(it) }
                            }.getOrNull()
                            image.close()
                            runCatching { provider.unbind(capture) }
                            if (bytes != null) Thread { seal(app, bytes) }.start()
                        }

                        override fun onError(exception: ImageCaptureException) {
                            runCatching { provider.unbind(capture) }
                        }
                    },
                )
            }
        }, ContextCompat.getMainExecutor(app))
    }

    private fun seal(context: Context, jpeg: ByteArray) {
        runCatching {
            val name = "Intruder_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val out = File(VaultStore.dropDir(context), "$name.kdb")
            val tmp = File(out.path + ".part")
            tmp.outputStream().buffered().use { VaultCrypto.dropEncrypt(context, ByteArrayInputStream(jpeg), it) }
            jpeg.fill(0)
            tmp.renameTo(out)
        }
    }
}
