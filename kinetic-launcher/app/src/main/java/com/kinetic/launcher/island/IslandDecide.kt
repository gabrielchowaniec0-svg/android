package com.kinetic.launcher.island

/**
 * The island's choices, kept apart from the drawing so they can be tested on a
 * computer: what's running, which one is in front, and what shape to show.
 */
internal object IslandDecide {
    /** What's going on right now. */
    data class Now(
        val call: Boolean = false,
        val incomingCall: Boolean = false,
        val torch: Boolean = false,
        val nav: Boolean = false,
        /** How many of Kinetic's own live things (stopwatch, Focus…) are running. */
        val lives: Int = 0,
        val trip: Boolean = false,
        /** A timer counting down (not a ringing alarm). */
        val timer: Boolean = false,
        /** An alarm ringing. */
        val alarm: Boolean = false,
        val score: Boolean = false,
        /** Music the island should show (not hidden because you're in that app). */
        val media: Boolean = false,
        val mediaMinimal: Boolean = false,
        val peek: Boolean = false,
        /** The notification is open (or you're replying to it). */
        val peekOpen: Boolean = false,
        val alert: IslandAlert? = null,
        val expanded: Boolean = false,
    )

    /** Everything running, most important first. */
    fun activities(n: Now): List<Activity> = buildList {
        if (n.call) add(Activity.Call)
        if (n.torch) add(Activity.Torch)
        if (n.nav) add(Activity.Nav)
        if (n.lives > 0) add(Activity.Live)
        // A second live thing (e.g. stopwatch + Focus) gets its own place in the stack.
        if (n.lives > 1) add(Activity.Live2)
        if (n.trip) add(Activity.Trip)
        if (n.timer) add(Activity.Timer)
        if (n.score) add(Activity.Score)
        if (n.media) add(Activity.Media)
    }

    /** Sideways swipes rotate the stack; any number of swipes either way stays in range. */
    fun stack(list: List<Activity>, shift: Int): List<Activity> {
        if (list.size < 2) return list
        val k = ((shift % list.size) + list.size) % list.size
        return list.drop(k) + list.take(k)
    }

    /** The shape to show for the thing in front. */
    fun mode(n: Now, primary: Activity?): IslandMode = when {
        n.incomingCall -> IslandMode.CallExpanded
        n.alarm -> IslandMode.AlarmExpanded
        n.peek -> if (n.peekOpen) IslandMode.Peek else IslandMode.PeekCompact
        n.alert != null -> IslandMode.Alert(n.alert)
        primary == null -> IslandMode.Idle
        n.expanded -> when (primary) {
            Activity.Call -> IslandMode.CallExpanded
            Activity.Nav -> IslandMode.NavExpanded
            Activity.Live, Activity.Live2 -> IslandMode.LiveExpanded
            Activity.Torch -> IslandMode.TorchExpanded
            Activity.Timer -> IslandMode.TimerExpanded
            Activity.Trip -> IslandMode.TripExpanded
            Activity.Score -> IslandMode.ScoreExpanded
            Activity.Media -> IslandMode.MediaExpanded
        }
        primary == Activity.Call -> IslandMode.CallCompact
        primary == Activity.Nav -> IslandMode.NavCompact
        primary == Activity.Live || primary == Activity.Live2 -> IslandMode.LiveCompact
        primary == Activity.Torch -> IslandMode.TorchCompact
        primary == Activity.Timer -> IslandMode.TimerCompact
        primary == Activity.Trip -> IslandMode.TripCompact
        primary == Activity.Score -> IslandMode.ScoreCompact
        n.mediaMinimal -> IslandMode.MediaMinimal
        else -> IslandMode.MediaCompact
    }
}
