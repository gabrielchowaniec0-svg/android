package com.kinetic.launcher.vault

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Size
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import javax.crypto.SecretKey

/**
 * What's in the Vault. Every photo, video and thumbnail is stored encrypted in
 * Kinetic's private storage (no other app can read it, and it's scrambled anyway);
 * even the list of items (names, dates) is encrypted.
 */
class VaultStore(context: Context, private val key: SecretKey, private val decoy: Boolean = false) {

    data class Item(
        val id: String,
        val name: String,
        val mime: String,
        val addedAt: Long,
        val size: Long,
    ) {
        val isVideo get() = mime.startsWith("video")
    }

    private val app = context.applicationContext
    // The decoy code has its own, separate Vault.
    private val dir = File(app.filesDir, if (decoy) "vault_decoy" else "vault").apply { mkdirs() }
    private val indexFile = File(dir, "index.bin")

    fun items(): List<Item> {
        if (!indexFile.exists()) return emptyList()
        return try {
            val bytes = ByteArrayOutputStream().also { out ->
                indexFile.inputStream().use { VaultCrypto.decryptStream(key, "index", it, out) }
            }.toByteArray()
            val arr = JSONArray(String(bytes))
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Item(o.getString("id"), o.optString("name"), o.optString("mime", "image/jpeg"), o.optLong("added"), o.optLong("size"))
            }.sortedByDescending { it.addedAt }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveIndex(list: List<Item>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("id", it.id).put("name", it.name).put("mime", it.mime).put("added", it.addedAt).put("size", it.size)) }
        val tmp = File(dir, "index.tmp")
        tmp.outputStream().use { VaultCrypto.encryptStream(key, "index", ByteArrayInputStream(arr.toString().toByteArray()), it) }
        tmp.renameTo(indexFile)
    }

    private fun dataFile(id: String) = File(dir, "$id.bin")
    private fun thumbFile(id: String) = File(dir, "$id.thumb")

    /** Copies [uri] into the Vault (encrypted). Returns the new item, or null on failure. */
    fun import(uri: Uri, nameHint: String? = null, mimeHint: String? = null): Item? {
        val resolver = app.contentResolver
        val mime = mimeHint ?: resolver.getType(uri) ?: "image/jpeg"
        val id = UUID.randomUUID().toString()
        return try {
            resolver.openInputStream(uri)?.use { input ->
                dataFile(id).outputStream().use { VaultCrypto.encryptStream(key, id, input, it) }
            } ?: return null
            writeThumb(id, uri, mime)
            val item = Item(id, nameHint ?: uri.lastPathSegment.orEmpty(), mime, System.currentTimeMillis(), dataFile(id).length())
            saveIndex(listOf(item) + items())
            item
        } catch (e: Exception) {
            dataFile(id).delete()
            thumbFile(id).delete()
            null
        }
    }

    /**
     * Moves secure screenshots (sealed while the Vault was locked) in. Returns how many.
     * Also makes sure the drop box exists, for vaults set up before it did.
     */
    fun collectDrops(): Int {
        if (decoy) return 0
        VaultCrypto.ensureDropBox(app, key)
        var n = 0
        dropDir(app).listFiles()?.filter { it.name.endsWith(".kdb") }?.sortedBy { it.name }?.forEach { f ->
            val raw = File(captureDir(app), f.nameWithoutExtension + ".bin")
            var tmp = raw
            try {
                f.inputStream().buffered().use { i -> raw.outputStream().use { o -> VaultCrypto.dropDecrypt(app, key, i, o) } }
                // Screenshots are PNG; intruder photos are JPEG.
                val jpeg = raw.inputStream().use { val b = ByteArray(2); it.read(b) == 2 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() }
                tmp = File(captureDir(app), f.nameWithoutExtension + if (jpeg) ".jpg" else ".png")
                raw.renameTo(tmp)
                if (importFile(tmp, if (jpeg) "image/jpeg" else "image/png") != null) {
                    f.delete()
                    n++
                }
            } catch (_: Exception) {
                wipe(tmp)
                if (raw.exists()) wipe(raw)
            }
        }
        return n
    }

    /** Copies a file Kinetic just made (e.g. a vault-camera photo) in, then wipes the original. */
    fun importFile(file: File, mime: String): Item? {
        val item = import(Uri.fromFile(file), file.name, mime)
        wipe(file)
        return item
    }

    private fun writeThumb(id: String, uri: Uri, mime: String) {
        val bmp: Bitmap? = try {
            if (mime.startsWith("video")) {
                MediaMetadataRetriever().run {
                    setDataSource(app, uri)
                    val f = getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 360, 360)
                    release()
                    f
                }
            } else if (uri.scheme == "file") {
                decodeScaled(uri.path!!, 360)
            } else {
                runCatching { app.contentResolver.loadThumbnail(uri, Size(360, 360), null) }.getOrNull()
                    ?: app.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            }
        } catch (_: Exception) {
            null
        }
        if (bmp == null) return
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 82, out)
        thumbFile(id).outputStream().use { VaultCrypto.encryptStream(key, "$id-thumb", ByteArrayInputStream(out.toByteArray()), it) }
    }

    private fun decodeScaled(path: String, target: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= target && bounds.outHeight / (sample * 2) >= target) sample *= 2
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    fun thumbnail(item: Item): Bitmap? = try {
        val out = ByteArrayOutputStream()
        thumbFile(item.id).inputStream().use { VaultCrypto.decryptStream(key, "${item.id}-thumb", it, out) }
        val b = out.toByteArray()
        BitmapFactory.decodeByteArray(b, 0, b.size)
    } catch (_: Exception) {
        null
    }

    /** The full photo, decrypted in memory only. */
    fun photo(item: Item, maxSide: Int = 4096): Bitmap? = try {
        val out = ByteArrayOutputStream()
        dataFile(item.id).inputStream().use { VaultCrypto.decryptStream(key, item.id, it, out) }
        val b = out.toByteArray()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(b, 0, b.size, bounds)
        var sample = 1
        while (bounds.outWidth / sample > maxSide || bounds.outHeight / sample > maxSide) sample *= 2
        BitmapFactory.decodeByteArray(b, 0, b.size, BitmapFactory.Options().apply { inSampleSize = sample })
    } catch (_: Exception) {
        null
    }

    /** Decrypts a video to a private temporary file for playing (wiped when the Vault locks). */
    fun videoForPlayback(item: Item): File? = try {
        val f = File(playDir(app), "${item.id}.mp4")
        if (!f.exists()) f.outputStream().use { out -> dataFile(item.id).inputStream().use { VaultCrypto.decryptStream(key, item.id, it, out) } }
        f
    } catch (_: Exception) {
        null
    }

    /** Puts the item back into the normal gallery (Pictures/Kinetic or Movies/Kinetic) and removes it from the Vault. */
    fun restoreToGallery(item: Item): Boolean = try {
        val video = item.isVideo
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, item.name.ifBlank { item.id } .let { if ('.' in it) it else it + if (video) ".mp4" else ".jpg" })
            put(MediaStore.MediaColumns.MIME_TYPE, item.mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, (if (video) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES) + "/Kinetic")
        }
        val collection = if (video) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = app.contentResolver.insert(collection, values) ?: throw IllegalStateException()
        app.contentResolver.openOutputStream(uri)?.use { out ->
            dataFile(item.id).inputStream().use { VaultCrypto.decryptStream(key, item.id, it, out) }
        }
        delete(item)
        true
    } catch (_: Exception) {
        false
    }

    fun delete(item: Item) {
        wipe(dataFile(item.id))
        wipe(thumbFile(item.id))
        File(playDir(app), "${item.id}.mp4").let(::wipe)
        saveIndex(items().filter { it.id != item.id })
    }

    companion object {
        fun playDir(context: Context) = File(context.cacheDir, "vault_play").apply { mkdirs() }
        /** Sealed secure screenshots waiting for the Vault to be opened. */
        fun dropDir(context: Context) = File(context.filesDir, "vault_drop").apply { mkdirs() }

        fun captureDir(context: Context) = File(context.cacheDir, "vault_capture").apply { mkdirs() }

        /** Removes decrypted temporary files (called whenever the Vault locks). */
        fun wipeTemp(context: Context) {
            playDir(context).listFiles()?.forEach(::wipe)
            captureDir(context).listFiles()?.forEach(::wipe)
        }

        /** Overwrites then deletes a file. */
        fun wipe(f: File) {
            if (!f.exists()) return
            runCatching {
                val len = f.length()
                if (len in 1..(64L * 1024 * 1024)) {
                    f.outputStream().use { out ->
                        val zeros = ByteArray(64 * 1024)
                        var left = len
                        while (left > 0) {
                            val n = minOf(left, zeros.size.toLong()).toInt()
                            out.write(zeros, 0, n)
                            left -= n
                        }
                    }
                }
            }
            f.delete()
        }
    }
}
