package com.kinetic.launcher.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.kinetic.core.apps.SquircleShape
import kotlin.math.roundToInt

/**
 * Hold an app and drag it: drop it on a folder to put it inside (home screen and app
 * list). Folders register where they are; the dragged icon floats under your finger.
 */
object AppDrag {
    /** [key] identifies the dragged app ("home:<page>:<itemId>" or "drawer:<appKey>"). */
    data class Drag(val key: String, val icon: ImageBitmap?, val pos: Offset)

    var drag by mutableStateOf<Drag?>(null)
        private set

    /** Folder drop targets in window coordinates ("home:<page>:<folderId>" / "drawer:<folderId>"). */
    val targets = mutableStateMapOf<String, Rect>()

    /** The folder under the finger right now. */
    var hover by mutableStateOf<String?>(null)
        private set

    /** Called when an app is dropped on a folder: (dragKey, folderKey). */
    var onDrop: ((String, String) -> Unit)? = null
    var onHoverChange: (() -> Unit)? = null
    /** Called as a drag begins (closes the long-press menu). */
    var onStart: (() -> Unit)? = null

    fun start(key: String, icon: ImageBitmap?, pos: Offset) {
        drag = Drag(key, icon, pos)
        hover = null
        onStart?.invoke()
    }

    fun move(pos: Offset) {
        val d = drag ?: return
        drag = d.copy(pos = pos)
        // Home apps only drop on home folders, app-list apps on app-list folders.
        val prefix = d.key.substringBefore(':')
        val h = targets.entries.firstOrNull { (k, r) -> k.startsWith("$prefix:") && r.inflate(6f).contains(pos) }?.key
        if (h != hover) {
            hover = h
            if (h != null) onHoverChange?.invoke()
        }
    }

    fun end() {
        val d = drag
        val h = hover
        drag = null
        hover = null
        if (d != null && h != null) onDrop?.invoke(d.key, h)
    }

    fun cancel() {
        drag = null
        hover = null
    }
}

/** Marks a folder tile as a drop target, and makes it swell while an app hovers over it. */
@Composable
fun Modifier.folderDropTarget(key: String): Modifier {
    DisposableEffect(key) { onDispose { AppDrag.targets.remove(key) } }
    val over = AppDrag.hover == key
    val s by androidx.compose.animation.core.animateFloatAsState(if (over) 1.14f else 1f, label = "drop")
    return this
        .onGloballyPositioned { AppDrag.targets[key] = it.boundsInWindow() }
        .graphicsLayer {
            scaleX = s
            scaleY = s
        }
}

/** The icon that follows your finger while you drag (put at the very top of the screen's layers). */
@Composable
fun AppDragLayer() {
    val d = AppDrag.drag ?: return
    var origin by remember { mutableStateOf(Offset.Zero) }
    val size = IconSize * 1.1f
    val half = with(LocalDensity.current) { size.toPx() / 2 }
    Box(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInWindow() }) {
        val icon = d.icon
        if (icon != null) {
            Image(
                icon, null,
                modifier = Modifier
                    .offset { IntOffset((d.pos.x - origin.x - half).roundToInt(), (d.pos.y - origin.y - half * 1.3f).roundToInt()) }
                    .size(size)
                    .graphicsLayer {
                        alpha = 0.92f
                        shadowElevation = 18.dp.toPx()
                        shape = SquircleShape
                        clip = true
                    },
            )
        }
    }
}
