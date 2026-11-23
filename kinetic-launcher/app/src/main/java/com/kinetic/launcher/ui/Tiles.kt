@file:OptIn(ExperimentalFoundationApi::class)

package com.kinetic.launcher.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
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

/** White label with a soft shadow so it reads over any wallpaper. */
val TileLabelStyle = TextStyle(
    color = Color.White,
    fontSize = 12.sp,
    textAlign = TextAlign.Center,
    shadow = Shadow(color = Color.Black.copy(alpha = 0.7f), offset = Offset(0f, 1f), blurRadius = 4f),
)

/** An app icon with its label. Taps and long-presses are optional. */
@Composable
fun AppTile(
    icon: ImageBitmap?,
    label: String,
    modifier: Modifier = Modifier,
    iconSize: Dp = 54.dp,
    dimmed: Boolean = false,
    jiggle: Boolean = false,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    val clicks = if (onClick != null || onLongClick != null) {
        Modifier.combinedClickable(onClick = { onClick?.invoke() }, onLongClick = onLongClick)
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
        if (icon != null) {
            Image(bitmap = icon, contentDescription = label, modifier = Modifier.size(iconSize))
        } else {
            Box(Modifier.size(iconSize).clip(CircleShape).background(Color(0xFF2E4066)))
        }
        Spacer(Modifier.height(4.dp))
        Text(label, style = TileLabelStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A folder: up to four mini icons in a frosted tile, with the folder name. */
@Composable
fun FolderTile(
    name: String,
    icons: List<ImageBitmap>,
    modifier: Modifier = Modifier,
    jiggle: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    Column(
        modifier
            .fillMaxSize()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .jiggle(jiggle)
            .padding(2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier
                .size(54.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color.White.copy(alpha = 0.18f))
                .padding(7.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                icons.take(4).chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        row.forEach { Image(bitmap = it, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(name, style = TileLabelStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
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
