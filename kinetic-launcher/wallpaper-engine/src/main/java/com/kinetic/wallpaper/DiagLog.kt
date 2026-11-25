package com.kinetic.wallpaper

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Kinetic's "what went wrong" log. Crashes and errors are written to a small file on the
 * phone (nothing is sent anywhere). "report problem" bundles it, with the phone model,
 * Android version and Kinetic's settings, into one file you can share.
 */
object DiagLog {
    private const val MAX_BYTES = 256 * 1024
    @Volatile
    private var dir: File? = null
    @Volatile
    private var installed = false
    private val time = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    /** Call early in every process (app, island, wallpaper, plane spotter). */
    fun init(context: Context) {
        val app = context.applicationContext
        if (dir == null) dir = File(app.filesDir, "diag").apply { mkdirs() }
        if (installed) return
        installed = true
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching { write("CRASH", "thread ${thread.name}", e) }
            previous?.uncaughtException(thread, e)
        }
    }

    fun i(tag: String, msg: String) = write("info", "$tag: $msg", null)
    fun w(tag: String, msg: String, e: Throwable? = null) = write("warn", "$tag: $msg", e)
    fun e(tag: String, msg: String, e: Throwable? = null) = write("ERROR", "$tag: $msg", e)

    private fun processName(): String = runCatching { Application.getProcessName() }.getOrDefault("?")

    @Synchronized
    private fun write(level: String, msg: String, e: Throwable?) {
        val d = dir ?: return
        runCatching {
            val f = File(d, "log.txt")
            if (f.length() > MAX_BYTES) {
                val old = File(d, "log.old.txt")
                old.delete()
                f.renameTo(old)
            }
            val sb = StringBuilder()
            sb.append(time.format(Date())).append(' ').append(level).append(" [").append(processName()).append("] ").append(msg).append('\n')
            if (e != null) {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                sb.append(sw.toString().lines().take(40).joinToString("\n")).append('\n')
            }
            f.appendText(sb.toString())
        }
    }

    /**
     * Builds the report file: phone details, the settings (no location, no personal notes),
     * extra lines from the caller (permissions, what's switched on), the log, and the
     * system log for this process.
     */
    fun buildReport(context: Context, extra: List<String>): File {
        init(context)
        val app = context.applicationContext
        val out = File(File(app.cacheDir, "diag").apply { mkdirs() }, "kinetic-report-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".txt")
        val sb = StringBuilder()
        sb.append("Kinetic problem report\n")
        sb.append("Made: ").append(Date()).append('\n')
        val pi = runCatching { app.packageManager.getPackageInfo(app.packageName, 0) }.getOrNull()
        sb.append("Kinetic: ").append(pi?.versionName).append(" (").append(pi?.longVersionCode).append(")\n")
        sb.append("Phone: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append(" · Android ").append(Build.VERSION.RELEASE)
            .append(" (SDK ").append(Build.VERSION.SDK_INT).append(") · ").append(Build.DISPLAY).append('\n')
        val dm = app.resources.displayMetrics
        sb.append("Screen: ").append(dm.widthPixels).append('×').append(dm.heightPixels).append(" @ ").append(dm.densityDpi).append(" dpi\n")
        extra.forEach { sb.append(it).append('\n') }
        sb.append("\n---- settings ----\n")
        val hidden = setOf("wp_lat", "wp_lon")
        runCatching {
            WallpaperSignals.prefs(app).all.toSortedMap().forEach { (k, v) ->
                if (k !in hidden) sb.append(k).append(" = ").append(v).append('\n')
            }
        }
        val d = dir
        for (name in listOf("log.old.txt", "log.txt")) {
            val f = d?.let { File(it, name) }
            if (f != null && f.exists()) {
                sb.append("\n---- ").append(name).append(" ----\n")
                sb.append(runCatching { f.readText() }.getOrDefault("(couldn't read)"))
            }
        }
        sb.append("\n---- system log (this process) ----\n")
        runCatching {
            val p = ProcessBuilder("logcat", "-d", "-t", "1500", "--pid=${Process.myPid()}").redirectErrorStream(true).start()
            sb.append(p.inputStream.bufferedReader().readText().takeLast(400_000))
        }.onFailure { sb.append("(not available: ").append(it.message).append(")\n") }
        out.writeText(sb.toString())
        return out
    }

    /** Clears the log (after a report has been sent, if you like). */
    fun clear() {
        dir?.listFiles()?.forEach { it.delete() }
    }
}
