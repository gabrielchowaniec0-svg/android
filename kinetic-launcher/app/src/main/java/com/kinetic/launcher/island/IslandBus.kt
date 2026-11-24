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

    private val _says = MutableSharedFlow<Say>(extraBufferCapacity = 4)
    val says: SharedFlow<Say> = _says.asSharedFlow()

    /** True if the island is on and will show it; false means show it some other way. */
    fun say(icon: String, text: String): Boolean {
        if (!DynamicIslandService.isRunning) return false
        return _says.tryEmit(Say(icon, text))
    }
}
