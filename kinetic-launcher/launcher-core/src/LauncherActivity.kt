package com.kinetic.launcher

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import com.kinetic.core.GridConfig
import com.kinetic.core.GridItem
import com.kinetic.core.LauncherGrid
import com.kinetic.security.StealthCustomizationManager

/**
 * Minimal host that wires the core grid to the customization manager. A real
 * launcher would also load installed apps via PackageManager / LauncherApps and
 * render each DisplayEntry; this shows the data flow end to end.
 *
 * FragmentActivity is used so BiometricPrompt can attach (see the unlock bridge).
 */
class LauncherActivity : FragmentActivity() {

    private lateinit var customization: StealthCustomizationManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        customization = StealthCustomizationManager(this)

        setContent {
            val workspace by customization.activeWorkspace.collectAsState()

            // Example packages; replace with a real app load + DisplayEntry mapping.
            val allPackages = rememberExamplePackages()
            val visible = customization.visiblePackages(allPackages, workspace)

            val items = visible.mapIndexed { i, pkg ->
                val display = customization.resolveDisplay(
                    packageName = pkg,
                    systemLabel = pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() },
                    systemIconKey = null,
                )
                GridItem(
                    id = pkg,
                    cellX = (i % 5).toFloat(),
                    cellY = (i / 5).toFloat(),
                ) {
                    AppIconCell(label = display.label)
                }
            }

            LauncherGrid(
                config = GridConfig(columns = 5, rows = 6, halfSnap = true),
                items = items,
                modifier = Modifier.fillMaxSize(),
                onMove = { _, _, _ -> /* persist layout here */ },
            )
        }
    }

    override fun onStop() {
        super.onStop()
        // Collapse the private space whenever we leave the foreground.
        customization.lockToPrimary()
    }
}
