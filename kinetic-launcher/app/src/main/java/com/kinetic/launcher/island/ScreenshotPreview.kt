package com.kinetic.launcher.island

import android.Manifest
import android.app.Activity
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import com.kinetic.launcher.KineticSettings
import com.kinetic.launcher.vault.VaultCrypto
import com.kinetic.launcher.vault.VaultStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * iOS-style screenshot preview: after a normal screenshot, a thumbnail drops out of the
 * island with Edit, Share and Vault. (Turn off Samsung's own screenshot toolbar so only
 * this one shows.)
 */
object ScreenshotPreview {
    data class Shot(val id: Long, val uri: Uri, val thumb: ImageBitmap?)

    private var observer: ContentObserver? = null
    private var app: Context? = null
    private var scope: CoroutineScope? = null
    private var lastId = -1L
    /** The screenshot showing in the island right now. */
    @Volatile
    var current: Shot? = null
        private set

    /** Shows the preview (wired by the island model). */
    var onShot: ((Shot) -> Unit)? = null
    var onDismiss: (() -> Unit)? = null

    fun hasAccess(c: Context) =
        ContextCompat.checkSelfPermission(c, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED

    fun start(context: Context, sc: CoroutineScope) {
        stop()
        val a = context.applicationContext
        app = a
        scope = sc
        lastId = newestScreenshot(a)?.first ?: -1L
        val obs = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                if (!KineticSettings.current.islandScreenshots || !hasAccess(a)) return
                sc.launch(Dispatchers.IO) { check(a) }
            }
        }
        observer = obs
        runCatching { a.contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, obs) }
    }

    fun stop() {
        observer?.let { o -> runCatching { app?.contentResolver?.unregisterContentObserver(o) } }
        observer = null
    }

    /** (id, added-at seconds) of the newest screenshot, if any. */
    private fun newestScreenshot(c: Context): Pair<Long, Long>? = runCatching {
        if (!hasAccess(c)) return null
        c.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_ADDED, MediaStore.Images.Media.RELATIVE_PATH, MediaStore.Images.Media.DISPLAY_NAME),
            null, null, "${MediaStore.Images.Media.DATE_ADDED} DESC",
        )?.use { cur ->
            var found: Pair<Long, Long>? = null
            var looked = 0
            while (cur.moveToNext() && looked < 5 && found == null) {
                looked++
                val path = cur.getString(2).orEmpty()
                val name = cur.getString(3).orEmpty()
                if (path.contains("Screenshot", ignoreCase = true) || name.startsWith("Screenshot", ignoreCase = true)) {
                    found = cur.getLong(0) to cur.getLong(1)
                }
            }
            found
        }
    }.getOrNull()

    private suspend fun check(c: Context) {
        val (id, added) = newestScreenshot(c) ?: return
        if (id == lastId) return
        // Only brand-new ones (not a screenshot restored or copied from years ago).
        if (System.currentTimeMillis() / 1000 - added > 20) return
        lastId = id
        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
        // Samsung writes the file a moment after it appears; try the thumbnail a few times.
        var thumb: ImageBitmap? = null
        repeat(6) {
            if (thumb == null) {
                thumb = runCatching { c.contentResolver.loadThumbnail(uri, Size(360, 780), null).asImageBitmap() }.getOrNull()
                if (thumb == null) kotlinx.coroutines.delay(250)
            }
        }
        val shot = Shot(id, uri, thumb)
        withContext(Dispatchers.Main) {
            current = shot
            onShot?.invoke(shot)
        }
    }

    private fun start(c: Context, i: Intent) = runCatching { c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess

    fun open() {
        val c = app ?: return
        val s = current ?: return
        done()
        start(c, Intent(Intent.ACTION_VIEW).setDataAndType(s.uri, "image/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    fun edit() {
        val c = app ?: return
        val s = current ?: return
        done()
        val i = Intent(Intent.ACTION_EDIT).setDataAndType(s.uri, "image/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        if (!start(c, i)) start(c, Intent(Intent.ACTION_VIEW).setDataAndType(s.uri, "image/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    fun share() {
        val c = app ?: return
        val s = current ?: return
        done()
        val send = Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, s.uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        start(c, Intent.createChooser(send, "Share screenshot"))
    }

    /** Seals the screenshot into the Vault, then asks Android to delete it from the gallery. */
    fun toVault() {
        val c = app ?: return
        val s = current ?: return
        done()
        if (!VaultCrypto.canDrop(c)) {
            IslandBus.say("🔒", "Open the Vault once first to set it up")
            return
        }
        (scope ?: return).launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val name = "Screenshot_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                    val out = File(VaultStore.dropDir(c), "$name.kdb")
                    val tmp = File(out.path + ".part")
                    c.contentResolver.openInputStream(s.uri)?.buffered()?.use { i ->
                        tmp.outputStream().buffered().use { o -> VaultCrypto.dropEncrypt(c, i, o) }
                    } ?: error("can't read")
                    tmp.renameTo(out)
                }.getOrDefault(false)
            }
            if (!ok) {
                IslandBus.say("✕", "Couldn't move it to the Vault")
                return@launch
            }
            IslandBus.say("🔒", "Sealed in the Vault · confirm to remove it from Gallery")
            // Deleting another app's photo needs your OK (Android shows its own dialog).
            runCatching {
                val pi = MediaStore.createDeleteRequest(c.contentResolver, listOf(s.uri))
                c.startActivity(
                    Intent(c, DeleteRequestActivity::class.java).putExtra(DeleteRequestActivity.EXTRA_SENDER, pi.intentSender)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }

    /** The preview is finished with (button pressed or it timed out). */
    fun done() {
        current = null
        onDismiss?.invoke()
    }
}

/** Invisible: shows Android's "Delete this photo?" dialog, then closes. */
class DeleteRequestActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        val sender = intent.getParcelableExtra<android.content.IntentSender>(EXTRA_SENDER)
        if (sender == null) {
            finish()
            return
        }
        runCatching {
            @Suppress("DEPRECATION")
            startIntentSenderForResult(sender, 7, null, 0, 0, 0)
        }.onFailure { finish() }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == RESULT_OK) IslandBus.say("🔒", "Moved to the Vault")
        finish()
    }

    companion object {
        const val EXTRA_SENDER = "sender"
    }
}
