package com.kinetic.launcher.ui

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import com.kinetic.launcher.island.DynamicIslandService
import kotlin.concurrent.thread

/**
 * "What can I write?": every Kinetic Command, grouped and searchable, each with a Try button.
 * Opened by writing "help", "?" or "commands" (or from Settings).
 */
class HelpActivity : ComponentActivity() {

    data class Example(val write: String, val what: String)
    data class Group(val title: String, val icon: String, val items: List<Example>)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val groups = groups(this)
        setContent {
            var q by remember { mutableStateOf("") }
            val shown = remember(q) {
                val t = q.trim().lowercase()
                if (t.isEmpty()) groups
                else groups.map { g -> g.copy(items = g.items.filter { it.write.lowercase().contains(t) || it.what.lowercase().contains(t) || g.title.lowercase().contains(t) }) }
                    .filter { it.items.isNotEmpty() }
            }
            Column(Modifier.fillMaxSize().background(Color(0xFF07080C)).statusBarsPadding().navigationBarsPadding().imePadding()) {
                Text("What can I write?", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 20.dp, top = 16.dp))
                Text(
                    "Write these with the S Pen on the home screen, or type them in search. Words in examples (names, numbers, places) can be anything.",
                    color = Color(0x99FFFFFF), fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                )
                BasicTextField(
                    q, { q = it }, singleLine = true,
                    textStyle = TextStyle(color = Color.White, fontSize = 17.sp),
                    cursorBrush = SolidColor(Color.White),
                    modifier = Modifier.padding(16.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0x1FFFFFFF)).padding(14.dp),
                    decorationBox = { inner ->
                        if (q.isEmpty()) Text("Search commands (“photo”, “plane”, “school”…)", color = Color(0x66FFFFFF), fontSize = 17.sp)
                        inner()
                    },
                )
                LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 40.dp)) {
                    shown.forEach { g ->
                        item(key = "h_" + g.title) {
                            Text(g.title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 20.dp, top = 18.dp, bottom = 6.dp))
                        }
                        items(g.items, key = { g.title + it.write + it.what }) { ex -> CommandRow(ex) }
                    }
                    if (shown.isEmpty()) item { Text("No command matches “$q”", color = Color(0x99FFFFFF), modifier = Modifier.padding(20.dp)) }
                }
            }
        }
    }

    @Composable
    private fun CommandRow(ex: Example) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 3.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0x14FFFFFF)).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(ex.write, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Text(ex.what, color = Color(0x99FFFFFF), fontSize = 13.sp)
            }
            Spacer(Modifier.width(10.dp))
            Text(
                "Try", color = Color.Black, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(Color.White).clickable { tryIt(ex.write) }.padding(horizontal = 16.dp, vertical = 7.dp),
            )
        }
    }

    /** Runs the example like it was written (texts and calls still ask in the island first). */
    private fun tryIt(text: String) {
        val app = applicationContext
        thread {
            val parsed = KineticCommands.parse(text)
            val ready = parsed?.let { runCatching { KineticCommands.resolve(app, it) }.getOrNull() }
            runOnUiThread {
                val sum = if (parsed == null) SmartSearch.calculate(text) else null
                when {
                    sum != null -> com.kinetic.launcher.island.IslandBus.tell(app, "=", "$text = $sum")
                    ready == null -> Toast.makeText(app, "Try writing “$text” on the home screen", Toast.LENGTH_SHORT).show()
                    DynamicIslandService.isShowing -> {
                        finish()
                        IslandFlow.handle(app, text, ready)
                    }
                    ready.answer -> Toast.makeText(app, ready.done ?: ready.title, Toast.LENGTH_LONG).show()
                    else -> if (!ready.run(this)) Toast.makeText(app, "Couldn't do “$text”", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    companion object {
        private fun ex(write: String, what: String) = Example(write, what)

        fun groups(context: Context): List<Group> {
            val mine = buildList {
                Commands3.shortcuts(context).forEach { (k, v) -> add(ex(k, "Your shortcut for “$v”")) }
                runCatching { com.kinetic.launcher.pen.CommandChains.all(context) }.getOrNull()?.forEach { (k, v) -> add(ex(k, "Your chain: " + v.joinToString(", "))) }
                Commands3.history(context).take(5).forEach { add(ex(it, "Used recently")) }
            }
            return listOfNotNull(if (mine.isNotEmpty()) Group("Yours", "★", mine) else null) + builtIn()
        }

        /** Every built-in example (no phone needed: also used by the automated tests). */
        fun builtIn(): List<Group> {
            return listOf(
                Group(
                    "People", "☏",
                    listOf(
                        ex("call mom", "Calls (asks in the island first)"),
                        ex("text mom on my way", "Sends a text; mention a time (“in 10”) and it offers a reminder"),
                        ex("reply sounds good", "Answers your newest message notification"),
                        ex("call back", "Returns your missed call"),
                        ex("email dad", "Starts an email"),
                        ex("whatsapp mom", "Opens their WhatsApp chat"),
                        ex("send my eta to mom", "Texts your arrival time while Maps directions run"),
                        ex("send last photo to mom", "Your newest photo, ready to send"),
                    ),
                ),
                Group(
                    "Notifications", "🔔",
                    listOf(
                        ex("read it", "Reads your newest notification out loud"),
                        ex("what did i miss", "A summary of your notifications"),
                        ex("snooze notifications 1 hour", "Do Not Disturb that turns itself off"),
                        ex("unsnooze", "Notifications back on early"),
                        ex("clear notifications", "Clears them all (asks first)"),
                        ex("dnd on", "Do Not Disturb on / off"),
                    ),
                ),
                Group(
                    "Remember things", "📌",
                    listOf(
                        ex("remember I parked on level 3", "Saved with where you are"),
                        ex("where did I park", "Shows it, with a Map button"),
                        ex("remember the wifi password is …", "Anything you want to keep"),
                        ex("what's the wifi password", "Finds what you saved"),
                        ex("forget parking", "Clears the parking spot"),
                        ex("forget the wifi password", "Forgets one thing you saved"),
                        ex("what do you remember", "Everything you've saved"),
                        ex("note buy milk", "A sticky note on the home screen"),
                        ex("my notes", "Lists your sticky notes"),
                        ex("remove note milk", "Takes that note off (or tap it, or swipe it sideways)"),
                        ex("clear notes", "Removes every sticky note (asks first)"),
                    ),
                ),
                Group(
                    "Schedule & time", "📅",
                    listOf(
                        ex("my schedule", "Today's events and homework"),
                        ex("my schedule tomorrow", "Or any weekday: “schedule friday”"),
                        ex("timer 10 min", "Starts a timer"),
                        ex("alarm 7am", "Sets an alarm"),
                        ex("stopwatch", "Counts live in the island; then lap and stop"),
                        ex("focus 30 min", "A Focus session with a ring in the island"),
                        ex("end focus", "Turns Focus off now"),
                        ex("time in Tokyo", "The time somewhere else"),
                        ex("time zones: Chicago, London, Tokyo", "Several places at once"),
                        ex("days until christmas", "Days until something"),
                    ),
                ),
                Group(
                    "School", "📚",
                    listOf(
                        ex("homework math page 42 due friday", "Reminds you at 7 pm the night before"),
                        ex("homework", "Lists what's due"),
                        ex("done math", "Ticks it off"),
                        ex("save spanish word gato = cat", "Adds a flashcard"),
                        ex("remove word gato", "Deletes a flashcard"),
                        ex("flashcards spanish", "Quiz in the island"),
                        ex("school days until summer", "Weekdays left"),
                        ex("summer starts june 4", "Sets the last day of school"),
                        ex("define ephemeral", "Looks up a word"),
                    ),
                ),
                Group(
                    "Planes", "✈",
                    listOf(
                        ex("planes", "Planes flying near you"),
                        ex("what's that plane", "Point the camera at it"),
                        ex("plane watch", "Planes flying over you pop up in the island (1 hour)"),
                        ex("track UA123", "Follow a flight live in the island until it lands"),
                        ex("stop tracking", "Stops following the flight"),
                        ex("metar KORD", "Airport weather, in plain English too"),
                        ex("flight UA123", "A flight's status"),
                        ex("phonetic Gabriel", "Golf Alpha Bravo…"),
                        ex("FL350", "Flight level in feet"),
                        ex("250 knots in mph", "Unit conversion"),
                    ),
                ),
                Group(
                    "Photos & camera", "📷",
                    listOf(
                        ex("last photo", "Shows your newest photo"),
                        ex("photos from yesterday", "Or today / last week"),
                        ex("take a screenshot", "Screenshot"),
                        ex("secure screenshot", "Straight into the Vault"),
                        ex("secure video", "Record a video straight into the Vault"),
                        ex("start video recording", "Camera in video mode"),
                    ),
                ),
                Group(
                    "Phone settings", "⚙",
                    listOf(
                        ex("volume 50", "Or “volume up”, “mute”"),
                        ex("brightness 30", "Or “brighter”, “dimmer”"),
                        ex("vibrate", "Ring / vibrate / silent"),
                        ex("flashlight", "Torch on / off"),
                        ex("undo", "Puts back the last volume, brightness, ringer or DND change"),
                        ex("again", "Repeats your last command"),
                        ex("battery", "Battery level"),
                        ex("storage left", "Space left"),
                        ex("screen time", "Today's phone use"),
                    ),
                ),
                Group(
                    "Getting around", "➤",
                    listOf(
                        ex("route to O'Hare", "Directions"),
                        ex("what's my eta", "While directions run"),
                        ex("where am I", "Your address"),
                        ex("share my location with mom", "Sends where you are"),
                        ex("uber to the airport", "Uber / Lyft"),
                        ex("how far is Chicago", "How far"),
                        ex("weather", "Forecast"),
                        ex("sunset", "Sunrise / sunset times"),
                    ),
                ),
                Group(
                    "Music & apps", "♫",
                    listOf(
                        ex("play Imagine Dragons", "Plays music"),
                        ex("next song", "Skip / pause / play"),
                        ex("youtube plane landings", "Searches YouTube"),
                        ex("open camera", "Opens any app"),
                        ex("translate hello to spanish", "Translation"),
                        ex("search best pizza near me", "Web search"),
                    ),
                ),
                Group(
                    "Quick tools", "🧮",
                    listOf(
                        ex("12 x 7", "Any sum (or write an equation)"),
                        ex("15% off 60", "Sale price"),
                        ex("split 84 between 3", "Splitting the bill"),
                        ex("20% tip on 45", "Tip"),
                        ex("100 f in c", "Conversions"),
                        ex("pick for me: pizza, tacos, sushi", "Can't decide?"),
                        ex("flip a coin", "Or “roll a dice”"),
                        ex("level", "Bubble level (also “ruler”, “decibels”)"),
                        ex("tell me a joke", "Or ask the magic 8 ball"),
                    ),
                ),
                Group(
                    "Make your own", "⌘",
                    listOf(
                        ex("shortcut m = call mom", "Then just write “m”"),
                        ex("remove shortcut m", "Deletes that shortcut"),
                        ex("help", "Opens this list"),
                        ex("report problem", "Makes a file with Kinetic's log to send for fixing"),
                    ),
                ),
            )
        }
    }
}
