package com.kinetic.launcher.island

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Lets the rest of Kinetic show a short message in the Dynamic Island
 * ("Calling Mom", "100 °C = 212 °F", "Opened YouTube"…).
 */
object IslandBus {
    data class Say(val icon: String, val text: String)
    /** A message with a button (e.g. "Allow"); [onTap] runs when it's pressed. */
    data class Ask(
        val icon: String,
        val text: String,
        val button: String,
        val onTap: () -> Unit,
        /** An optional second (grey) button, e.g. "Cancel". */
        val secondary: String? = null,
        val onSecondary: () -> Unit = {},
    )

    private val _asks = MutableSharedFlow<Ask>(extraBufferCapacity = 2)
    val asks: SharedFlow<Ask> = _asks.asSharedFlow()

    /** True if the island will show it (with its button); false means handle it another way. */
    fun ask(
        icon: String,
        text: String,
        button: String,
        secondary: String? = null,
        onSecondary: () -> Unit = {},
        onTap: () -> Unit,
    ): Boolean {
        if (!DynamicIslandService.isRunning) return false
        return _asks.tryEmit(Ask(icon, text, button, onTap, secondary, onSecondary))
    }

    private val _says = MutableSharedFlow<Say>(extraBufferCapacity = 4)
    val says: SharedFlow<Say> = _says.asSharedFlow()

    /** True if the island is on and will show it; false means show it some other way. */
    fun say(icon: String, text: String): Boolean {
        if (!DynamicIslandService.isRunning) return false
        return _says.tryEmit(Say(icon, text))
    }
}
