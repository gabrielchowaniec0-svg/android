package com.kinetic.launcher

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * In-app updater. Every merge into main builds a signed APK on GitHub (see
 * .github/workflows/release.yml) and publishes it as a Release. Kinetic checks
 * the latest release, downloads the APK and asks Android to install it — no
 * Android Studio needed.
 */
object Updater {

    private const val LATEST = "https://api.github.com/repos/gabrielchowaniec0-svg/android/releases/latest"
    private const val PREFS = "kinetic_updater"

    data class Release(val code: Long, val title: String, val notes: String, val apkUrl: String, val sizeBytes: Long)

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data class Available(val release: Release) : State
        data class Downloading(val release: Release, val progress: Float) : State
        data class Installing(val release: Release) : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    fun installedCode(context: Context): Long =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode }.getOrDefault(0L)

    /** Checks GitHub at most every [minIntervalMs] unless forced. */
    suspend fun check(context: Context, force: Boolean = false, minIntervalMs: Long = 6 * 3600_000L): State {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val last = prefs.getLong("last_check", 0L)
        if (!force && System.currentTimeMillis() - last < minIntervalMs) return _state.value
        _state.value = State.Checking
        val result = withContext(Dispatchers.IO) {
            try {
                val conn = (URL(LATEST).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10_000
                    readTimeout = 15_000
                    setRequestProperty("Accept", "application/vnd.github+json")
                }
                if (conn.responseCode == 404) {
                    // Nothing published yet: GitHub skips releases until the signing key secret is added.
                    return@withContext State.Failed("No builds on GitHub yet — finish the one-time setup in docs/UPDATES.md")
                }
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(body)
                val code = json.optString("tag_name").substringAfterLast('-').toLongOrNull() ?: return@withContext State.UpToDate
                val assets = json.optJSONArray("assets")
                var url: String? = null
                var size = 0L
                if (assets != null) for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name").endsWith(".apk")) {
                        url = a.optString("browser_download_url")
                        size = a.optLong("size")
                        break
                    }
                }
                val release = Release(code, json.optString("name", "Kinetic"), json.optString("body"), url ?: return@withContext State.UpToDate, size)
                if (code > installedCode(context)) State.Available(release) else State.UpToDate
            } catch (e: Exception) {
                State.Failed(if (Net.online()) "Couldn't reach GitHub" else "You're offline. Try again when you have signal")
            }
        }
        prefs.edit().putLong("last_check", System.currentTimeMillis()).apply()
        _state.value = result
        return result
    }

    /** True once Kinetic may install updates (Android's "Install unknown apps" switch). */
    fun canInstall(context: Context) = context.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.packageName))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** Downloads the APK and hands it to Android's installer (it asks you to confirm). */
    suspend fun downloadAndInstall(context: Context, release: Release) {
        if (!canInstall(context)) {
            openInstallPermission(context)
            return
        }
        // The download and Android's copy of it both need room.
        val free = runCatching { android.os.StatFs(context.cacheDir.path).availableBytes }.getOrDefault(Long.MAX_VALUE)
        if (release.sizeBytes > 0 && free < release.sizeBytes * 2 + 50L * 1024 * 1024) {
            _state.value = State.Failed("Not enough storage for the update. It needs about ${(release.sizeBytes * 2 / 1_048_576) + 50} MB free")
            return
        }
        _state.value = State.Downloading(release, 0f)
        val file = withContext(Dispatchers.IO) {
            try {
                val dir = File(context.cacheDir, "updates").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val out = File(dir, "Kinetic-${release.code}.apk")
                var url = URL(release.apkUrl)
                // Timeouts so a dropped connection fails instead of hanging forever.
                fun open(u: URL) = (u.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 30_000
                }
                var conn = open(url)
                // GitHub redirects downloads to its storage host.
                var hops = 0
                while (conn.responseCode in 300..399 && hops < 5) {
                    url = URL(conn.getHeaderField("Location"))
                    conn = open(url)
                    hops++
                }
                val total = conn.contentLengthLong.takeIf { it > 0 } ?: release.sizeBytes
                conn.inputStream.use { input ->
                    out.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var lastEmit = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            done += n
                            if (total > 0 && done - lastEmit > 256 * 1024) {
                                lastEmit = done
                                _state.value = State.Downloading(release, (done.toFloat() / total).coerceIn(0f, 1f))
                            }
                        }
                    }
                }
                out
            } catch (e: Exception) {
                // Don't leave half a file behind.
                runCatching { File(context.cacheDir, "updates").listFiles()?.forEach { it.delete() } }
                null
            }
        }
        if (file == null) {
            _state.value = State.Failed(if (Net.online()) "Download failed. Try again" else "Download stopped: no internet")
            return
        }
        _state.value = State.Installing(release)
        withContext(Dispatchers.IO) {
            try {
                val installer = context.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                    setAppPackageName(context.packageName)
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
                val id = installer.createSession(params)
                installer.openSession(id).use { session ->
                    session.openWrite("kinetic.apk", 0, file.length()).use { out ->
                        file.inputStream().use { it.copyTo(out) }
                        session.fsync(out)
                    }
                    val intent = Intent(context, UpdateResultReceiver::class.java)
                    val pi = PendingIntent.getBroadcast(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                    session.commit(pi.intentSender)
                }
            } catch (e: Exception) {
                _state.value = State.Failed("Install failed: ${e.message ?: "unknown error"}")
            }
        }
    }

    internal fun reportFailure(message: String) {
        _state.value = State.Failed(message)
    }
}

/** Android's installer reports back here; it may first need you to tap "Update". */
class UpdateResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let(context::startActivity)
            }
            PackageInstaller.STATUS_SUCCESS -> Unit // Kinetic restarts on the new version.
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
                Updater.reportFailure(
                    if ("signature" in msg.lowercase() || "conflict" in msg.lowercase()) {
                        "This build is signed with a different key. See docs/UPDATES.md (one-time fix)."
                    } else {
                        "Install failed: $msg"
                    }
                )
            }
        }
    }
}
