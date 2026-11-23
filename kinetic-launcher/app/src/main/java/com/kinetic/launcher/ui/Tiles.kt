@file:OptIn(ExperimentalFoundationApi::class)

package com.kinetic.launcher.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kinetic.core.apps.SquircleShape

/** White label with a soft shadow so it reads over any wallpaper. */
val TileLabelStyle = TextStyle(
    color = Color.White,
    fontSize = 12.sp,
    textAlign = TextAlign.Center,
    shadow = Shadow(color = Color.Black.copy(alpha = 0.7f), offset = Offset(0f, 1f), blurRadius = 4f),
)

/** Every icon-sized thing (apps, folders) uses this size so the grid stays even. */
val IconSize = 60.dp

/** Default folder tile tint (One UI's dark folders). */
val DefaultFolderColor = Color(0xCC1C1B21)

/** Squishes slightly while pressed and springs back, like One UI. */
@Composable
private fun pressScale(source: MutableInteractionSource): Float {
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.88f else 1f,
        animationSpec = spring(dampingRatio = 0.45f, stiffness = 700f),
        label = "press",
    )
    return scale
}

/** An app icon with its label. Taps and long-presses are optional. */
@Composable
fun AppTile(
    icon: ImageBitmap?,
    label: String,
    modifier: Modifier = Modifier,
    iconSize: Dp = IconSize,
    dimmed: Boolean = false,
    jiggle: Boolean = false,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    val source = remember { MutableInteractionSource() }
    val scale = pressScale(source)
    val clicks = if (onClick != null || onLongClick != null) {
        Modifier.combinedClickable(
            interactionSource = source,
            indication = null,
            onLongClick = onLongClick,
            onClick = { onClick?.invoke() },
        )
    } else {
        Modifier
    }
    Column(
        modifier
            .fillMaxSize()
            .then(clicks)
            .jiggle(jiggle)
            .alpha(if (dimmed) 0.45f else 1f)
            .padding(2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        val iconModifier = Modifier
            .size(iconSize)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .depth3D(SquircleShape, elevation = 5.dp)
        if (icon != null) {
            Image(bitmap = icon, contentDescription = label, modifier = iconModifier)
        } else {
            Box(iconModifier.background(Color(0xFF2E4066)))
        }
        Spacer(Modifier.height(5.dp))
        Text(label, style = TileLabelStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A folder: up to nine mini icons (3 × 3, like One UI) in a tinted squircle, with the folder name. */
@Composable
fun FolderTile(
    name: String,
    icons: List<ImageBitmap>,
    modifier: Modifier = Modifier,
    tileSize: Dp = IconSize,
    color: Color = DefaultFolderColor,
    jiggle: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val source = remember { MutableInteractionSource() }
    val scale = pressScale(source)
    val pad = tileSize * 0.12f
    val gap = tileSize * 0.05f
    val mini = (tileSize - pad * 2 - gap * 2) / 3
    Column(
        modifier
            .fillMaxSize()
            .then(if (onClick != null) Modifier.clickable(interactionSource = source, indication = null, onClick = onClick) else Modifier)
            .jiggle(jiggle)
            .padding(2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier
                .size(tileSize)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .depth3D(SquircleShape, elevation = 5.dp, gloss = 0.7f)
                .background(color)
                .padding(pad),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                icons.take(9).chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                        row.forEach { Image(bitmap = it, contentDescription = null, modifier = Modifier.size(mini)) }
                    }
                }
            }
        }
        Spacer(Modifier.height(5.dp))
        Text(name, style = TileLabelStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * One UI–style large (2 × 2) folder: up to nine icons you can tap directly to
 * launch, with the folder name underneath. Tapping the rest of the tile opens it.
 */
@Composable
fun LargeFolderTile(
    name: String,
    icons: List<ImageBitmap>,
    modifier: Modifier = Modifier,
    jiggle: Boolean = false,
    onIconClick: ((index: Int) -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    Column(
        modifier
            .fillMaxSize()
            .jiggle(jiggle)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .depth3D(RoundedCornerShape(28.dp), elevation = 6.dp, gloss = 0.6f)
                .background(Color.White.copy(alpha = 0.14f))
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(8.dp),
        ) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceEvenly) {
                icons.take(9).withIndex().chunked(3).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        row.forEach { (index, icon) -> LargeFolderIcon(icon, if (onIconClick != null) ({ onIconClick(index) }) else null) }
                        repeat(3 - row.size) { Spacer(Modifier.size(44.dp)) }
                    }
                }
            }
        }
        Spacer(Modifier.height(5.dp))
        Text(name, style = TileLabelStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun LargeFolderIcon(icon: ImageBitmap, onClick: (() -> Unit)?) {
    val source = remember { MutableInteractionSource() }
    val scale = pressScale(source)
    Image(
        bitmap = icon,
        contentDescription = null,
        modifier = Modifier
            .size(44.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .depth3D(SquircleShape, elevation = 3.dp)
            .then(if (onClick != null) Modifier.clickable(interactionSource = source, indication = null, onClick = onClick) else Modifier),
    )
}

/** Gentle wobble used for icons while the home screen is in edit mode. */
@Composable
fun Modifier.jiggle(enabled: Boolean): Modifier {
    if (!enabled) return this
    val transition = rememberInfiniteTransition(label = "jiggle")
    val angle by transition.animateFloat(
        initialValue = -1.5f,
        targetValue = 1.5f,
        animationSpec = infiniteRepeatable(tween(130, easing = LinearEasing), RepeatMode.Reverse),
        label = "angle",
    )
    return this.graphicsLayer { rotationZ = angle }
}
