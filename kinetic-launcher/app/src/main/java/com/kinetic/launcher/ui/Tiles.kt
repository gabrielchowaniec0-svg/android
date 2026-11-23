@file:OptIn(ExperimentalFoundationApi::class)

package com.kinetic.launcher.ui

import androidx.compose.animation.core.Animatable
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
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kinetic.core.apps.SquircleShape
import com.kinetic.launcher.KineticSettings

/** White label with a soft shadow so it reads over any wallpaper. */
val TileLabelStyle = TextStyle(
    color = Color.White,
    fontSize = 12.sp,
    textAlign = TextAlign.Center,
    shadow = Shadow(color = Color.Black.copy(alpha = 0.7f), offset = Offset(0f, 1f), blurRadius = 4f),
)

/** Every icon-sized thing (apps, folders) uses this size so the grid stays even. */
/** Home/drawer icon size (Settings ▸ Home screen ▸ Icon size). */
val IconSize: Dp get() = KineticSettings.current.iconSizeDp.coerceIn(48, 72).dp

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
    /** Spinning notification ring around the icon. */
    ring: Boolean = false,
    /** Plays a small "landing" bounce (e.g. when you come back from this app). */
    pop: Boolean = false,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    val source = remember { MutableInteractionSource() }
    val scale = pressScale(source)
    val landing = remember { Animatable(1f) }
    LaunchedEffect(pop) {
        if (pop) {
            landing.snapTo(0.78f)
            landing.animateTo(1f, spring(dampingRatio = 0.42f, stiffness = 320f))
        }
    }
    var iconBounds by remember { mutableStateOf<Rect?>(null) }
    val clicks = if (onClick != null || onLongClick != null) {
        Modifier.combinedClickable(
            interactionSource = source,
            indication = null,
            onLongClick = onLongClick?.let { handler -> { TapOrigin.rect = iconBounds; handler() } },
            onClick = {
                TapOrigin.rect = iconBounds
                onClick?.invoke()
            },
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
            .onGloballyPositioned { iconBounds = it.boundsInWindow() }
            .iconWave { iconBounds }
            .graphicsLayer {
                val s = scale * landing.value
                scaleX = s
                scaleY = s
            }
            .notificationRing(ring)
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
    /** Ring if any app inside has notifications. */
    ring: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val source = remember { MutableInteractionSource() }
    val scale = pressScale(source)
    var tileBounds by remember { mutableStateOf<Rect?>(null) }
    val pad = tileSize * 0.12f
    val gap = tileSize * 0.05f
    val mini = (tileSize - pad * 2 - gap * 2) / 3
    Column(
        modifier
            .fillMaxSize()
            .then(
                if (onClick != null) {
                    Modifier.clickable(interactionSource = source, indication = null) {
                        TapOrigin.rect = tileBounds
                        onClick()
                    }
                } else {
                    Modifier
                }
            )
            .jiggle(jiggle)
            .padding(2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier
                .size(tileSize)
                .onGloballyPositioned { tileBounds = it.boundsInWindow() }
                .iconWave { tileBounds }
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .notificationRing(ring)
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
    /** Which of [icons] have notifications (same order). */
    rings: List<Boolean> = emptyList(),
    onIconClick: ((index: Int) -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    var tileBounds by remember { mutableStateOf<Rect?>(null) }
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val labelHeight = remember(measurer, density) {
        with(density) { measurer.measure("Ag", TileLabelStyle).size.height.toDp() }
    }
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .jiggle(jiggle),
    ) {
        // Line the panel up with the app icons around it: it runs from the left edge of
        // the first column's icon to the right edge of the second column's icon, and from
        // the top of the first row's icon to the bottom of the second row's icon. The
        // name then sits exactly where the second row's labels are.
        val cellW = maxWidth / 2
        val cellH = maxHeight / 2
        val iconTop = ((cellH - (IconSize + LabelGap + labelHeight)) / 2).coerceAtLeast(0.dp)
        val side = ((cellW - IconSize) / 2).coerceAtLeast(0.dp)
        val panelW = maxWidth - side * 2
        val panelH = (cellH + IconSize).coerceAtMost(maxHeight - iconTop)
        val pad = 10.dp
        val gap = 8.dp
        val mini = minOf((panelW - pad * 2 - gap * 2) / 3, (panelH - pad * 2 - gap * 2) / 3)

        Box(
            Modifier
                .offset(x = side, y = iconTop)
                .size(panelW, panelH)
                .onGloballyPositioned { tileBounds = it.boundsInWindow() }
                .depth3D(RoundedCornerShape(26.dp), elevation = 6.dp, gloss = 0.6f)
                .background(Color.White.copy(alpha = 0.14f))
                .then(
                    if (onClick != null) {
                        Modifier.clickable {
                            TapOrigin.rect = tileBounds
                            onClick()
                        }
                    } else {
                        Modifier
                    }
                )
                .padding(pad),
        ) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceEvenly) {
                icons.take(9).withIndex().chunked(3).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        row.forEach { (index, icon) ->
                            LargeFolderIcon(
                                icon = icon,
                                size = mini,
                                ring = rings.getOrElse(index) { false },
                                onClick = if (onIconClick != null) ({ onIconClick(index) }) else null,
                            )
                        }
                        repeat(3 - row.size) { Spacer(Modifier.size(mini)) }
                    }
                }
            }
        }
        Text(
            name,
            style = TileLabelStyle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .offset(y = iconTop + panelH + LabelGap)
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
        )
    }
}

/** Space between an icon and its label (same as [AppTile]). */
private val LabelGap = 5.dp

@Composable
private fun LargeFolderIcon(icon: ImageBitmap, size: Dp, ring: Boolean, onClick: (() -> Unit)?) {
    val source = remember { MutableInteractionSource() }
    val scale = pressScale(source)
    var bounds by remember { mutableStateOf<Rect?>(null) }
    Image(
        bitmap = icon,
        contentDescription = null,
        modifier = Modifier
            .size(size)
            .onGloballyPositioned { bounds = it.boundsInWindow() }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            // Icons are packed tightly here, so the ring hugs the icon instead of
            // spilling onto its neighbours.
            .notificationRing(ring, grow = 1.5.dp, strokeWidth = 1.75.dp)
            .depth3D(SquircleShape, elevation = 3.dp)
            .then(
                if (onClick != null) {
                    Modifier.clickable(interactionSource = source, indication = null) {
                        TapOrigin.rect = bounds
                        onClick()
                    }
                } else {
                    Modifier
                }
            ),
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
