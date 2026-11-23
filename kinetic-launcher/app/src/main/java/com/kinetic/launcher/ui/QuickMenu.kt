package com.kinetic.launcher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kinetic.core.apps.AppRepository
import com.kinetic.core.apps.SquircleShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private val MenuColor = Color(0xF21B2030)
private val MenuText = Color(0xFFE6EBF6)
private val MenuSubtle = Color(0xFF9AA6C2)

/**
 * One UI–style long-press menu: springs out of the icon, lists the app's own
 * shortcuts (e.g. Chowaniec's Group Chat / Messages / Meet) and a row of actions.
 * Appears above the icon if there's room, otherwise below.
 */
@Composable
fun QuickMenu(
    anchor: Rect?,
    title: String,
    loadShortcuts: () -> List<AppRepository.Shortcut>,
    onShortcut: (AppRepository.Shortcut) -> Unit,
    actions: List<Pair<String, () -> Unit>>,
    onDismiss: () -> Unit,
    /** Rows shown when the app publishes no shortcuts of its own (used for Chowaniec pages). */
    fallbackRows: List<Pair<String, () -> Unit>> = emptyList(),
) {
    val shortcuts by produceState<List<AppRepository.Shortcut>?>(null) {
        value = withContext(Dispatchers.Default) { runCatching { loadShortcuts() }.getOrDefault(emptyList()) }
    }
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, spring(dampingRatio = 0.7f, stiffness = 500f)) }
    BackHandler(onBack = onDismiss)

    val margin = 12.dp
    val gap = 10.dp
    var placedAbove = true

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.25f * appear.value))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
    ) {
        Layout(
            content = {
                Column(
                    Modifier
                        .width(250.dp)
                        .graphicsLayer {
                            val p = appear.value
                            alpha = p.coerceIn(0f, 1f)
                            val s = 0.82f + 0.18f * p
                            scaleX = s
                            scaleY = s
                            transformOrigin = TransformOrigin(0.5f, if (placedAbove) 1f else 0f)
                        }
                        .shadow(16.dp, RoundedCornerShape(22.dp))
                        .clip(RoundedCornerShape(22.dp))
                        .background(MenuColor)
                        // Swallow taps inside the card.
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                        .padding(vertical = 8.dp),
                ) {
                    Text(
                        title,
                        color = MenuSubtle,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                    shortcuts?.forEach { s ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onShortcut(s) }
                                .padding(horizontal = 14.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val icon = s.icon
                            if (icon != null) {
                                Image(icon, contentDescription = null, modifier = Modifier.size(28.dp).clip(SquircleShape))
                            } else {
                                Box(Modifier.size(28.dp).clip(SquircleShape).background(WallpaperPalette.Mid))
                            }
                            Spacer(Modifier.width(12.dp))
                            Text(s.label, color = MenuText, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    val extra = if (shortcuts?.isEmpty() == true) fallbackRows else emptyList()
                    extra.forEach { (label, action) ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { action() }
                                .padding(horizontal = 14.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier.size(28.dp).clip(SquircleShape).background(WallpaperPalette.Mid),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(label.take(1), color = WallpaperPalette.Glint, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            }
                            Spacer(Modifier.width(12.dp))
                            Text(label, color = MenuText, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (!shortcuts.isNullOrEmpty() || extra.isNotEmpty()) {
                        Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp).background(Color.White.copy(alpha = 0.08f)))
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 6.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        actions.forEach { (label, action) ->
                            Text(
                                label,
                                color = MenuText,
                                fontSize = 13.sp,
                                maxLines = 1,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { action() }
                                    .padding(horizontal = 10.dp, vertical = 10.dp),
                            )
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxSize(),
        ) { measurables, constraints ->
            val card = measurables.first().measure(constraints.copy(minWidth = 0, minHeight = 0))
            layout(constraints.maxWidth, constraints.maxHeight) {
                val m = margin.roundToPx()
                val g = gap.roundToPx()
                val a = anchor ?: Rect(
                    constraints.maxWidth / 2f, constraints.maxHeight / 2f,
                    constraints.maxWidth / 2f, constraints.maxHeight / 2f,
                )
                val x = (a.center.x - card.width / 2f).roundToInt()
                    .coerceIn(m, (constraints.maxWidth - card.width - m).coerceAtLeast(m))
                val above = a.top - g - card.height >= m
                placedAbove = above
                val y = if (above) (a.top - g - card.height).roundToInt() else (a.bottom + g).roundToInt()
                card.place(x, y.coerceIn(m, (constraints.maxHeight - card.height - m).coerceAtLeast(m)))
            }
        }
    }
}
