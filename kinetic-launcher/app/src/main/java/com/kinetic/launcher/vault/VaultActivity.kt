package com.kinetic.launcher.vault

import android.Manifest
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Size
import android.view.WindowManager
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.crypto.SecretKey

private val Bg = Color(0xFF05070C)
private val Card = Color(0xFF151B2B)
private val Subtle = Color(0xFF8C93A6)
private val Accent = Color(0xFF8FA6D8)
private val Danger = Color(0xFFFF453A)

/**
 * The encrypted Vault: photos and videos only you can open, with Kinetic's own
 * PIN or pattern. Screenshots and the recent-apps preview are blocked, and it
 * locks the moment you leave it.
 */
class VaultActivity : ComponentActivity() {

    /** The unlocked key (memory only). Cleared whenever the Vault locks. */
    private var key by mutableStateOf<SecretKey?>(null)
    /** Set while we've sent you to the gallery picker / camera / delete prompt, so leaving doesn't lock. */
    private var awaitingResult = false
    private var captureFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // No screenshots, no screen recording, blank preview in recent apps.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        setRecentsScreenshotEnabled(false)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Accent)) {
                Box(Modifier.fillMaxSize().background(Bg).systemBarsPadding()) {
                    var setUp by remember { mutableStateOf(VaultCrypto.isSetUp(this@VaultActivity)) }
                    // Opened from Settings ▸ "Change Kinetic code": unlock, then choose a new one.
                    var changing by remember { mutableStateOf(intent.getBooleanExtra(EXTRA_CHANGE_CODE, false)) }
                    // Opened from Settings ▸ "Decoy code": unlock with the real code, then choose the decoy.
                    var decoySetup by remember { mutableStateOf(intent.getBooleanExtra(EXTRA_DECOY, false)) }
                    val k = key
                    AnimatedContent(
                        targetState = when {
                            !setUp -> 0
                            k == null -> 1
                            changing -> 3
                            decoySetup -> 4
                            else -> 2
                        },
                        transitionSpec = { fadeIn() togetherWith fadeOut() },
                        label = "vault",
                    ) { screen ->
                        when (screen) {
                            0 -> Centered {
                                ChooseCode { code, kind ->
                                    VaultCrypto.setUp(this@VaultActivity, code, kind)
                                    setUp = true
                                }
                            }
                            1 -> Centered {
                                UnlockWithCode(
                                    if (changing || decoySetup) "Your real code" else "Vault",
                                    "Enter your Kinetic code",
                                    // Changing codes needs the real one (never the decoy).
                                    allowDecoy = !(changing || decoySetup),
                                ) { key = it }
                            }
                            3 -> Centered {
                                ChooseCode { code, kind ->
                                    key?.let { VaultCrypto.rewrap(this@VaultActivity, it, code, kind) }
                                    changing = false
                                    android.widget.Toast.makeText(this@VaultActivity, "Kinetic code changed", android.widget.Toast.LENGTH_SHORT).show()
                                    finish()
                                }
                            }
                            4 -> Centered {
                                ChooseCode { code, kind ->
                                    val ctx = this@VaultActivity
                                    val msg = when {
                                        kind != VaultCrypto.kind(ctx) -> "Use a " + (if (VaultCrypto.kind(ctx) == VaultCrypto.Kind.Pin) "6-digit PIN" else "pattern") + ", like your real code"
                                        !VaultCrypto.setUpDecoy(ctx, code) -> "That's your real code. Pick a different one"
                                        else -> "Decoy code set"
                                    }
                                    android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_LONG).show()
                                    decoySetup = false
                                    finish()
                                }
                            }
                            else -> key?.let { Unlocked(it) }
                        }
                    }
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Leaving the Vault locks it (unless we just opened the picker or camera for it).
        if (!awaitingResult) lock()
    }

    override fun onDestroy() {
        lock()
        super.onDestroy()
    }

    private fun lock() {
        key = null
        VaultStore.wipeTemp(this)
    }

    @Composable
    private fun Centered(content: @Composable () -> Unit) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
    }

    // ---- the unlocked vault ------------------------------------------------------------

    @Composable
    private fun Unlocked(key: SecretKey) {
        val store = remember(key) { VaultStore(this, key, decoy = VaultCrypto.lastWasDecoy) }
        var refresh by remember { mutableIntStateOf(0) }
        val items by produceState(initialValue = emptyList<VaultStore.Item>(), refresh) {
            value = withContext(Dispatchers.IO) {
                // Secure screenshots taken while the Vault was locked come in first.
                store.collectDrops()
                store.items()
            }
        }
        var viewing by remember { mutableStateOf<VaultStore.Item?>(null) }
        var picking by remember { mutableStateOf(false) }
        var busy by remember { mutableStateOf<String?>(null) }
        val scope = rememberCoroutineScope()

        // Vault camera: the photo goes straight into the Vault, never the gallery.
        val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
            awaitingResult = false
            val f = captureFile
            captureFile = null
            if (ok && f != null) {
                busy = "Encrypting…"
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { store.importFile(f, "image/jpeg") } != null
                    busy = null
                    refresh++
                    if (!ok) vaultToast("Couldn't save the photo. " + VaultStore.failReason(this@VaultActivity))
                }
            } else {
                f?.let(VaultStore::wipe)
            }
        }

        // Vault video: recorded by the camera app straight into the Vault, never the gallery.
        val takeVideo = rememberLauncherForActivityResult(ActivityResultContracts.CaptureVideo()) { ok ->
            awaitingResult = false
            val f = captureFile
            captureFile = null
            if (ok && f != null && f.length() > 0) {
                busy = "Encrypting video…"
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { store.importFile(f, "video/mp4") } != null
                    busy = null
                    refresh++
                    if (!ok) vaultToast("Couldn't save the video. " + VaultStore.failReason(this@VaultActivity))
                }
            } else {
                f?.let(VaultStore::wipe)
            }
        }

        BackHandler(viewing != null || picking) {
            viewing = null
            picking = false
        }

        when {
            picking -> GalleryPicker(
                onCancel = { picking = false },
                onImported = {
                    picking = false
                    refresh++
                },
                store = store,
            )
            viewing != null -> Viewer(
                store,
                viewing!!,
                onClose = { viewing = null },
                onChanged = {
                    viewing = null
                    refresh++
                },
            )
            else -> Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Vault", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
                        Text("${items.size} item${if (items.size == 1) "" else "s"} · encrypted", color = Subtle, fontSize = 13.sp)
                    }
                    Pill("Lock") { lock() }
                }
                Row(Modifier.padding(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Pill("＋ Add from gallery", filled = true) { picking = true }
                    Pill("Camera") {
                        val f = File(VaultStore.captureDir(this@VaultActivity), "capture-${System.currentTimeMillis()}.jpg")
                        captureFile = f
                        awaitingResult = true
                        takePhoto.launch(FileProvider.getUriForFile(this@VaultActivity, "$packageName.vaultfiles", f))
                    }
                    Pill("Video") {
                        val f = File(VaultStore.captureDir(this@VaultActivity), "capture-${System.currentTimeMillis()}.mp4")
                        captureFile = f
                        awaitingResult = true
                        takeVideo.launch(FileProvider.getUriForFile(this@VaultActivity, "$packageName.vaultfiles", f))
                    }
                }
                Spacer(Modifier.height(12.dp))
                busy?.let { Text(it, color = Accent, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp)) }
                if (items.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            "Nothing here yet.\nAdd photos or videos from your gallery,\nor take one with the Vault camera or video.",
                            color = Subtle,
                            fontSize = 15.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        modifier = Modifier.fillMaxSize().padding(horizontal = 2.dp),
                    ) {
                        items(items, key = { it.id }) { item ->
                            VaultThumb(store, item) { viewing = item }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun VaultThumb(store: VaultStore, item: VaultStore.Item, onClick: () -> Unit) {
        val thumb by produceState<ImageBitmap?>(initialValue = null, item.id) {
            value = withContext(Dispatchers.IO) { store.thumbnail(item)?.asImageBitmap() }
        }
        Box(
            Modifier
                .padding(2.dp)
                .aspectRatio(1f)
                .clip(RoundedCornerShape(6.dp))
                .background(Card)
                .clickable(onClick = onClick),
        ) {
            thumb?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            if (item.isVideo) {
                Text("▶", color = Color.White, fontSize = 14.sp, modifier = Modifier.align(Alignment.BottomStart).padding(6.dp))
            }
        }
    }

    // ---- viewing one item ------------------------------------------------------------------

    @Composable
    private fun Viewer(store: VaultStore, item: VaultStore.Item, onClose: () -> Unit, onChanged: () -> Unit) {
        val scope = rememberCoroutineScope()
        var confirmDelete by remember { mutableStateOf(false) }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (item.isVideo) {
                val file by produceState<File?>(initialValue = null, item.id) {
                    value = withContext(Dispatchers.IO) { store.videoForPlayback(item) }
                }
                file?.let { f ->
                    AndroidView(
                        factory = { ctx ->
                            VideoView(ctx).apply {
                                setVideoPath(f.absolutePath)
                                setMediaController(MediaController(ctx).also { it.setAnchorView(this) })
                                setOnPreparedListener { start() }
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            } else {
                val photo by produceState<ImageBitmap?>(initialValue = null, item.id) {
                    value = withContext(Dispatchers.IO) { store.photo(item)?.asImageBitmap() }
                }
                var scale by remember { mutableFloatStateOf(1f) }
                var offset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
                photo?.let {
                    Image(
                        it,
                        null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectTransformGestures { _, pan, zoom, _ ->
                                    scale = (scale * zoom).coerceIn(1f, 6f)
                                    offset = if (scale == 1f) androidx.compose.ui.geometry.Offset.Zero else offset + pan
                                }
                            }
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                translationX = offset.x
                                translationY = offset.y
                            },
                    )
                }
            }
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f))))
                    .padding(18.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Pill("Close") { onClose() }
                Spacer(Modifier.weight(1f))
                Pill("Move to gallery") {
                    scope.launch {
                        withContext(Dispatchers.IO) { store.restoreToGallery(item) }
                        onChanged()
                    }
                }
                Pill(if (confirmDelete) "Tap to confirm" else "Delete", danger = true) {
                    if (!confirmDelete) {
                        confirmDelete = true
                    } else {
                        scope.launch {
                            withContext(Dispatchers.IO) { store.delete(item) }
                            onChanged()
                        }
                    }
                }
            }
        }
    }

    // ---- picking from the gallery -----------------------------------------------------------

    private data class GalleryItem(val uri: Uri, val name: String, val mime: String)

    /**
     * Kinetic's own gallery grid (so the originals can be removed from your gallery
     * after they're safely in the Vault).
     */
    @Composable
    private fun GalleryPicker(store: VaultStore, onCancel: () -> Unit, onImported: () -> Unit) {
        val scope = rememberCoroutineScope()
        var allowed by remember { mutableStateOf(hasMediaAccess()) }
        val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            awaitingResult = false
            allowed = hasMediaAccess()
        }
        LaunchedEffect(Unit) {
            if (!allowed) {
                awaitingResult = true
                val perms = mutableListOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
                if (Build.VERSION.SDK_INT >= 34) perms += Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
                ask.launch(perms.toTypedArray())
            }
        }
        val media by produceState(initialValue = emptyList<GalleryItem>(), allowed) {
            value = if (allowed) withContext(Dispatchers.IO) { queryGallery() } else emptyList()
        }
        val selected = remember { mutableStateListOf<GalleryItem>() }
        var progress by remember { mutableStateOf<String?>(null) }
        val deleteOriginals = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
            awaitingResult = false
            onImported()
        }

        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Pill("Cancel") { onCancel() }
                Spacer(Modifier.weight(1f))
                Text(progress ?: "${selected.size} selected", color = Color.White, fontSize = 15.sp)
                Spacer(Modifier.weight(1f))
                Pill("Move to Vault", filled = true) {
                    if (selected.isEmpty() || progress != null) return@Pill
                    val chosen = selected.toList()
                    scope.launch {
                        val moved = ArrayList<Uri>()
                        chosen.forEachIndexed { i, g ->
                            progress = "Encrypting ${i + 1} of ${chosen.size}…"
                            val ok = withContext(Dispatchers.IO) { store.import(g.uri, g.name, g.mime) != null }
                            if (ok) moved += g.uri
                        }
                        progress = null
                        val missed = chosen.size - moved.size
                        if (missed > 0) {
                            // Those stay in the gallery, untouched.
                            vaultToast(
                                (if (missed == 1) "1 item couldn't be moved" else "$missed items couldn't be moved") +
                                    " and was left in your gallery. " + VaultStore.failReason(this@VaultActivity),
                            )
                        }
                        // Remove the originals from the gallery (Android asks you to confirm once).
                        if (moved.isNotEmpty()) {
                            runCatching {
                                val pi = MediaStore.createDeleteRequest(contentResolver, moved)
                                awaitingResult = true
                                deleteOriginals.launch(IntentSenderRequest.Builder(pi.intentSender).build())
                            }.onFailure { onImported() }
                        } else {
                            onImported()
                        }
                    }
                }
            }
            if (!allowed) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Kinetic needs access to your photos to move them into the Vault.", color = Subtle, fontSize = 15.sp, modifier = Modifier.padding(32.dp))
                }
            } else {
                LazyVerticalGrid(columns = GridCells.Fixed(4), modifier = Modifier.fillMaxSize().padding(horizontal = 2.dp)) {
                    items(media, key = { it.uri.toString() }) { g ->
                        val on = g in selected
                        val thumb by produceState<ImageBitmap?>(initialValue = null, g.uri) {
                            value = withContext(Dispatchers.IO) {
                                runCatching { contentResolver.loadThumbnail(g.uri, Size(240, 240), null).asImageBitmap() }.getOrNull()
                            }
                        }
                        Box(
                            Modifier
                                .padding(1.5.dp)
                                .aspectRatio(1f)
                                .background(Card)
                                .clickable { if (on) selected.remove(g) else selected.add(g) },
                        ) {
                            thumb?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                            Box(
                                Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(6.dp)
                                    .size(22.dp)
                                    .clip(CircleShape)
                                    .background(if (on) Accent else Color.Black.copy(alpha = 0.3f))
                                    .border(1.5.dp, Color.White, CircleShape),
                                contentAlignment = Alignment.Center,
                            ) { if (on) Text("${selected.indexOf(g) + 1}", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                            if (g.mime.startsWith("video")) {
                                Text("▶", color = Color.White, fontSize = 12.sp, modifier = Modifier.align(Alignment.BottomStart).padding(5.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    private fun hasMediaAccess(): Boolean {
        fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
        return granted(Manifest.permission.READ_MEDIA_IMAGES) || granted(Manifest.permission.READ_MEDIA_VIDEO) ||
            (Build.VERSION.SDK_INT >= 34 && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED))
    }

    private fun queryGallery(): List<GalleryItem> {
        val out = ArrayList<GalleryItem>()
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
        )
        val selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE}, ${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO})"
        runCatching {
            contentResolver.query(collection, projection, selection, null, "${MediaStore.Files.FileColumns.DATE_ADDED} DESC")?.use { c ->
                while (c.moveToNext() && out.size < 3000) {
                    val id = c.getLong(0)
                    val video = c.getInt(3) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                    val base = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    out += GalleryItem(ContentUris.withAppendedId(base, id), c.getString(1).orEmpty(), c.getString(2) ?: if (video) "video/mp4" else "image/jpeg")
                }
            }
        }
        return out
    }

    @Composable
    private fun Pill(label: String, filled: Boolean = false, danger: Boolean = false, onClick: () -> Unit) {
        Text(
            label,
            color = if (filled) Color.Black else if (danger) Danger else Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .clip(RoundedCornerShape(18.dp))
                .background(if (filled) Accent else Card)
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 9.dp),
        )
    }

    companion object {
        const val EXTRA_CHANGE_CODE = "change_code"
        const val EXTRA_DECOY = "decoy_setup"

        fun decoySetup(context: android.content.Context) {
            context.startActivity(Intent(context, VaultActivity::class.java).putExtra(EXTRA_DECOY, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }

        fun changeCode(context: android.content.Context) {
            context.startActivity(Intent(context, VaultActivity::class.java).putExtra(EXTRA_CHANGE_CODE, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }

        fun open(context: android.content.Context) {
            context.startActivity(Intent(context, VaultActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

private fun android.content.Context.vaultToast(text: String) =
    android.widget.Toast.makeText(applicationContext, text, android.widget.Toast.LENGTH_LONG).show()
