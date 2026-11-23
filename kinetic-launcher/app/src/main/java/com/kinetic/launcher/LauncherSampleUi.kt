package com.kinetic.launcher

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Placeholder app list so the sample activity runs before you hook up PackageManager. */
@Composable
fun rememberExamplePackages(): List<String> = remember {
    listOf(
        "com.android.chrome",
        "com.google.android.gm",
        "com.spotify.music",
        "com.android.settings",
        "com.google.android.apps.maps",
        "com.android.camera",
        "com.whatsapp",
        "com.google.android.calendar",
    )
}

/** A single grid cell: a themed tile plus the (possibly masked) label. */
@Composable
fun AppIconCell(label: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(
                    Brush.linearGradient(
                        listOf(Color(0xFF1E2B45), Color(0xFF2E4066)),
                    )
                )
        )
        Text(
            text = label,
            color = Color(0xFFCAD4E8),
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}
