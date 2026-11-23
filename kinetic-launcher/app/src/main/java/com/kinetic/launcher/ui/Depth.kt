package com.kinetic.launcher.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Gives a surface (icon, folder, widget) a soft 3D look:
 *  - a drop shadow underneath
 *  - a glossy highlight across the top and a gentle shade at the bottom
 *  - a bevelled rim: light on the top-left edge, dark on the bottom-right
 *
 * Everything is clipped to [shape], so widgets get smooth rounded corners too.
 */
fun Modifier.depth3D(shape: Shape, elevation: Dp = 6.dp, gloss: Float = 1f): Modifier = this
    .shadow(
        elevation = elevation,
        shape = shape,
        clip = false,
        ambientColor = Color.Black.copy(alpha = 0.45f),
        spotColor = Color.Black.copy(alpha = 0.65f),
    )
    .clip(shape)
    .drawWithContent {
        drawContent()
        val outline = shape.createOutline(size, layoutDirection, this)
        // Top gloss
        drawOutline(
            outline,
            Brush.verticalGradient(
                0f to Color.White.copy(alpha = 0.22f * gloss),
                0.45f to Color.Transparent,
                startY = 0f,
                endY = size.height,
            ),
        )
        // Bottom shade
        drawOutline(
            outline,
            Brush.verticalGradient(
                0.6f to Color.Transparent,
                1f to Color.Black.copy(alpha = 0.18f * gloss),
                startY = 0f,
                endY = size.height,
            ),
        )
        // Bevelled rim
        drawOutline(
            outline,
            Brush.linearGradient(
                0f to Color.White.copy(alpha = 0.55f * gloss),
                0.5f to Color.White.copy(alpha = 0.05f),
                1f to Color.Black.copy(alpha = 0.32f * gloss),
                start = Offset.Zero,
                end = Offset(size.width, size.height),
            ),
            style = Stroke(width = 1.5.dp.toPx()),
        )
    }
