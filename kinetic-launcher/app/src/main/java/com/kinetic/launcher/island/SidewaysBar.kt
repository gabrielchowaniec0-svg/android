package com.kinetic.launcher.island

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Sideways (full-screen videos and games) the island is hidden, and so is the
 * status bar drawn beside it. This shows the time and battery across the top for
 * a few seconds when you swipe down from the top edge (when Android brings its
 * bars back), then fades away again.
 */
object SidewaysBar {
    private val _shown = MutableStateFlow(false)
    val shown: StateFlow<Boolean> = _shown.asStateFlow()

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val hide = Runnable { _shown.value = false }

    /** What Android last said about its status bar, and when the phone last turned. */
    private var lastBarShown = true
    private var turnedAt = 0L

    /** The phone just turned: the bar changes that come with turning aren't swipes. */
    fun turned(sideways: Boolean) {
        turnedAt = android.os.SystemClock.uptimeMillis()
        if (!sideways) dismiss()
    }

    /** Android's status bar came or went (from the sideways window's insets). */
    fun barChanged(shownNow: Boolean, sideways: Boolean) {
        val wasShown = lastBarShown
        lastBarShown = shownNow
        if (!sideways) return
        if (!shownNow) {
            dismiss()
            return
        }
        val settling = android.os.SystemClock.uptimeMillis() - turnedAt < 900
        // Hidden → shown while sideways = you swiped down from the top.
        if (!wasShown && !settling) reveal()
    }

    fun reveal() {
        _shown.value = true
        handler.removeCallbacks(hide)
        handler.postDelayed(hide, 3500)
    }

    fun dismiss() {
        handler.removeCallbacks(hide)
        _shown.value = false
    }
}

@Composable
fun SidewaysStatusBar() {
    val shown by SidewaysBar.shown.collectAsState()
    AnimatedVisibility(
        visible = shown,
        enter = fadeIn(tween(180)) + slideInVertically(tween(220)) { -it / 2 },
        exit = fadeOut(tween(260)) + slideOutVertically(tween(260)) { -it / 2 },
    ) {
        Box(
            Modifier.fillMaxSize().background(
                // A soft shade behind so white text reads over bright video.
                Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.45f), Color.Transparent)),
            ),
        ) {
            // Clear of the rounded corners and of the camera, which is at a side now.
            BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 30.dp)) {
                val w = maxWidth
                KineticStatusBar(w, 6.dp, 24.dp, w / 2, w / 2, sideways = true)
            }
        }
    }
}
