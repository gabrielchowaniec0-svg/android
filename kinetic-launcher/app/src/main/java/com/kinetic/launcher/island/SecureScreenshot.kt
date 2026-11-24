package com.kinetic.launcher.island

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.Display
import android.view.KeyEvent
import com.kinetic.launcher.KineticSettings
import com.kinetic.launcher.vault.VaultCrypto
import com.kinetic.launcher.vault.VaultStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Secure screenshot: press volume Up, Up, Down, Down quickly. The screen is captured
 * and sealed straight into the Vault. No code is needed to take it, but you need it
 * to look at it. It never touches the gallery or the clipboard.
 *
 * Volume keys still work normally. Kinetic changes the volume itself. While a press
 * could be the start of the gesture, the volume panel waits a moment, so the
 * screenshot doesn't catch it. The volume ends where it started (up 2, down 2).
 */
class SecureScreenshot(
    private val service: AccessibilityService,
    private val scope: CoroutineScope,
    private val onSaved: () -> Unit,
    private val onFailed: () -> Unit,
) {
    private val audio = service.getSystemService(AudioManager::class.java)
    private val power = service.getSystemService(PowerManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val pattern = listOf(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN)
    private val seq = ArrayList<Int>(4)
    private var lastPress = 0L
    private val showPanel = Runnable { adjust(AudioManager.ADJUST_SAME, showUi = true) }

    /** Returns true if Kinetic handled the key (the system should ignore it). */
    fun onKey(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (code != KeyEvent.KEYCODE_VOLUME_UP && code != KeyEvent.KEYCODE_VOLUME_DOWN) return false
        if (!active()) return false
        val island = VolumeHud.enabled()
        if (event.action == KeyEvent.ACTION_UP) {
            VolumeHud.stopRepeat()
            // A long hold is a volume change, not part of the gesture.
            if (event.eventTime - event.downTime > 350) seq.clear()
            return true
        }
        if (event.action != KeyEvent.ACTION_DOWN) return true
        val dir = if (code == KeyEvent.KEYCODE_VOLUME_UP) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER

        // Holding the key: plain volume change (with the island, Kinetic repeats by itself).
        if (event.repeatCount > 0) {
            seq.clear()
            handler.removeCallbacks(showPanel)
            if (!island) adjust(dir, showUi = true)
            return true
        }

        val now = event.eventTime
        if (now - lastPress > GAP_MS) seq.clear()
        lastPress = now
        seq.add(code)
        if (seq != pattern.subList(0, seq.size)) {
            // Not the gesture: maybe this press starts a new one.
            seq.clear()
            if (code == pattern[0]) seq.add(code)
        }

        handler.removeCallbacks(showPanel)
        if (seq.size == pattern.size) {
            seq.clear()
            VolumeHud.stopRepeat()
            // Back to where the volume started.
            if (island) runCatching { audio.adjustStreamVolume(VolumeHud.stream(service), dir, 0) } else adjust(dir, showUi = false)
            if (island) {
                // Slide the volume slider away first so it isn't in the screenshot.
                IslandBus.hideLevel()
                handler.postDelayed({ capture() }, 380)
            } else {
                capture()
            }
        } else if (island) {
            // The island slider shows straight away (it's hidden again before a capture).
            VolumeHud.step(service, dir)
            VolumeHud.startRepeat(service, dir)
        } else if (seq.isNotEmpty()) {
            adjust(dir, showUi = false)
            handler.postDelayed(showPanel, GAP_MS)
        } else {
            adjust(dir, showUi = true)
        }
        return true
    }

    /** Only when switched on, the Vault is ready, the screen is on and there's no call. */
    private fun active(): Boolean =
        KineticSettings.current.secureScreenshot &&
            VaultCrypto.canDrop(service) &&
            power.isInteractive &&
            audio.mode == AudioManager.MODE_NORMAL

    private fun adjust(direction: Int, showUi: Boolean) {
        // With the island's volume slider on, the island shows the level instead of One UI.
        val island = showUi && VolumeHud.enabled()
        runCatching {
            audio.adjustSuggestedStreamVolume(direction, AudioManager.USE_DEFAULT_STREAM_TYPE, if (showUi && !island) AudioManager.FLAG_SHOW_UI else 0)
        }
        if (island) VolumeHud.show(service)
    }

    /** Takes a secure screenshot now (also used by the "secure screenshot" command). */
    fun capture(retry: Boolean = true) {
        // The status bar's colour check uses screenshots too; Android allows about 3 a second.
        if (StatusBarData.sampling && retry) {
            handler.postDelayed({ capture(retry = false) }, 400)
            return
        }
        service.takeScreenshot(
            Display.DEFAULT_DISPLAY,
            service.mainExecutor,
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { seal(result) }
                        if (ok) onSaved() else onFailed()
                    }
                }

                override fun onFailure(errorCode: Int) {
                    if (retry && errorCode == AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) {
                        handler.postDelayed({ capture(retry = false) }, 400)
                    } else {
                        onFailed()
                    }
                }
            },
        )
    }

    private fun seal(result: AccessibilityService.ScreenshotResult): Boolean {
        val buffer = result.hardwareBuffer
        return try {
            val hw = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace) ?: return false
            val bmp = hw.copy(Bitmap.Config.ARGB_8888, false)
            hw.recycle()
            val png = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            bmp.recycle()
            val name = "Screenshot_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val out = File(VaultStore.dropDir(service), "$name.kdb")
            val tmp = File(out.path + ".part")
            tmp.outputStream().buffered().use { VaultCrypto.dropEncrypt(service, ByteArrayInputStream(png), it) }
            png.fill(0)
            tmp.renameTo(out)
        } catch (_: Exception) {
            false
        } finally {
            buffer.close()
        }
    }

    private companion object {
        const val GAP_MS = 450L
    }
}
