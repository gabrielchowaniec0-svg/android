package com.kinetic.launcher.ui

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.kinetic.launcher.island.DynamicIslandService
import com.kinetic.launcher.island.IslandBus

/**
 * Runs a command (from the S Pen) entirely in the Dynamic Island:
 * - answers drop down from the island
 * - things that need checking first (sending a text, calling) ask with Send/Call + Cancel
 * - a missing permission asks with an Allow button, then carries on
 * - everything else just runs, and the island says what happened
 *
 * Returns false if the island is off (the caller shows it another way).
 */
object IslandFlow {
    fun handle(context: Context, text: String, ready: KineticCommands.Ready, confirmed: Boolean = false): Boolean {
        if (!DynamicIslandService.isRunning) return false
        val app = context.applicationContext
        val missing = ready.permission?.takeIf { ContextCompat.checkSelfPermission(app, it) != PackageManager.PERMISSION_GRANTED }

        // 1. Check with you first (texts, calls…), right in the island.
        if (ready.confirm && !confirmed) {
            val line = ready.title + if (ready.subtitle.isNotBlank()) ": ${ready.subtitle}" else ""
            return IslandBus.ask(ready.badge, line, ready.actionLabel, secondary = "Cancel") { handle(app, text, ready, confirmed = true) }
        }
        // 2. A permission is missing: Allow (then it carries on by itself).
        if (missing != null) {
            // After you've already said "Send", the command runs either way (e.g. Messages opens if texting isn't allowed).
            if (confirmed) {
                PermissionActivity.start(app, missing, text, runAnyway = true)
                return true
            }
            return IslandBus.ask(ready.badge, "${ready.title} needs ${PermissionActivity.nameOf(missing)}", "Allow", secondary = "Not now") {
                PermissionActivity.start(app, missing, text)
            }
        }
        // 3. An answer: show it.
        if (ready.answer) {
            return IslandBus.say(ready.badge, ready.done ?: (ready.title + if (ready.subtitle.isNotBlank()) " · ${ready.subtitle}" else ""))
        }
        // 4. Do it.
        if (!ready.quiet) IslandBus.say(ready.badge, ready.done ?: ready.title)
        if (!ready.run(app)) IslandBus.say("✕", "Couldn't do “$text”")
        return true
    }
}
