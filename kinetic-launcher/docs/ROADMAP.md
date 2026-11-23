# Kinetic — ideas for what's next

This is a wish list of features Kinetic doesn't have yet. It leaves out what we've
already talked about: Control Center, Today view, App Library, backup/restore, lock
screen widgets, the flight tracker, time-of-day wallpaper moods, bigger folder sizes,
and swipe-home animations. Each idea has a rough size: **S** takes an evening,
**M** takes a few sessions, **L** is a bigger project.

The second half of this file lists ways to make Kinetic load and feel faster.

---

## Dynamic Island

| Idea | What it does | Size |
|---|---|---|
| **Earbuds pop-up** | When your Galaxy Buds connect, the island drops down with a picture of the buds and their battery level (left, right and case), like AirPods on an iPhone. | M |
| **Delivery & ride tracking** | Reads the ongoing notifications from apps like Uber and DoorDash and shows a progress bar with "Arriving in 6 min". | M |
| **Live scores** | Shows the score and the time left for your team's game while it's live. | M |
| **Screen-record & mic/camera dots** | Shows a red recording dot, or a green or orange "in use" dot, in the island's small view. | S |
| **Low-battery warning** | Shows "20% — Battery Saver?" with a one-tap button. | S |
| **Quick Share / download progress** | Fills a ring as a file transfers. | S |
| **Unlock summary** | Right after you unlock, briefly shows "3 messages · 1 missed call". | S |
| **Hold-to-peek replies for any app** | The inline reply that Chowaniec messages have, for every messaging app (already partly done; needs polish and a design for each app). | S |

## Home screen & gestures

| Idea | What it does | Size |
|---|---|---|
| **Double-tap to lock** | Double-tap empty wallpaper to turn the screen off. The island's accessibility service can already do this. | S |
| **Gesture shortcuts** | A two-finger swipe down opens Focus, a three-finger tap opens Commands, and drawing a letter on the wallpaper opens an app. | M |
| **Tinted & glass icons** | iOS-style dark, tinted (one colour) and clear "liquid glass" icon modes, made automatically from your current icons. | M |
| **Page transitions** | Choose how pages slide: cube, depth, carousel or the current flat style. | S |
| **App suggestions row** | A row at the top of the drawer that guesses what you'll open next from the time of day, the place and your habits (like Siri Suggestions). | M |
| **Shake to undo** | Undo the last layout change. Kinetic keeps the last 20 layouts. | S |
| **Icon badges with counts** | Small number badges next to the rings, so you can see how many notifications each app has. | S |
| **Edge lighting** | Notifications glow around the screen edge in the wallpaper's colours, through the island overlay. | M |

## Wallpaper

| Idea | What it does | Size |
|---|---|---|
| **Weather wallpaper** | Raindrops run down the glass when it's raining, snow settles when it snows, and a sun flare appears on clear mornings. | M |
| **Music-reactive** | The ribbons, waves or orbs pulse to the beat of what's playing. Needs the microphone or visualizer permission. | M |
| **Photo blend** | Pick a photo and Kinetic turns its colours into a palette, or uses it as a texture under a style. | M |
| **Per-page wallpapers** | Each home page can have its own palette, which cross-fades as you swipe between pages. | S |
| **Wallpaper sharing** | Share a "look" (style, colours and sliders) as a short code or a Chowaniec link. | S |

## Kinetic Commands

| Idea | What it does | Size |
|---|---|---|
| **Voice** | Hold the search pill and speak a command, using Android's speech recognition on the phone. | S |
| **Reminders & events** | "remind me to call Mom at 5" or "lunch with Sam Friday 12" goes straight into your calendar. | M |
| **Chowaniec actions** | "post in group chat …", "note …" and "start a watch party" are sent straight to Chowaniec. Needs a small endpoint on chowaniec.net. | M |
| **Smart typo-proofing** | Fuzzy matching, so "rout to ohare" and "call mon" still work. | S |
| **Command history** | Your recent commands show up as chips when you open search. | S |

## Focus

| Idea | What it does | Size |
|---|---|---|
| **Location triggers** | School Focus turns on when you arrive at school, and turns off when you leave. | M |
| **App-based triggers** | Gaming Focus turns on when you open a game. The accessibility service already knows which app is in front. | S |
| **Focus home pages** | A whole home page that only shows while a Focus is on. | M |
| **Focus filters for Chowaniec** | Only messages from the group chat get through during School. | M |

## System & quality of life

| Idea | What it does | Size |
|---|---|---|
| **Screen-time dashboard** | A daily summary, app limits, and a soft "one more minute" screen, all in Kinetic's style. | M |
| **In-app updater** | Checks GitHub for a new Kinetic build and installs it, so you don't need Android Studio for updates. | M |
| **What's new** sheet | After an update, a short card shows the new features. | S |
| **Reduce motion & bigger text** | Accessibility options that tone down the animations. | S |
| **Crash reporter** | If Kinetic crashes, the next launch offers to copy the error so you can send it to Claude. | S |

---

# Making Kinetic load and feel faster

These are ordered by how much difference I expect each one to make.

### 1. Ship it like a real release
- **Run the `release` variant** (Build ▸ Select Build Variant). Debug builds keep the
  debugger attached and run code half-compiled, which is often 2–5× slower for animations.
- **Turn on R8** (`isMinifyEnabled = true`, `isShrinkResources = true`) with keep rules
  for the reflection we use. The app gets smaller and starts faster.
- **Add a Baseline Profile.** A small benchmark module records Kinetic's start-up, the
  drawer opening and page swipes. Android then pre-compiles exactly those paths at install
  time. This is the biggest single gain for Compose apps, usually 20–40% faster at
  start-up and in the first frames.

### 2. Start faster
- **Cache rendered icons on disk.** Rendering every squircle icon takes time at start-up.
  Kinetic could save the finished PNGs, keyed by package and version, and show those
  instantly while fresh ones load in the background.
- **Load the layout off the main thread.** Parse the home layout in the background,
  and show the cached icons first.
- **Pre-build the app drawer.** Build it once after start-up, hidden, so opening it the
  first time doesn't stutter.
- **Keep work out of `onResume`.** Anything that doesn't have to happen the moment you
  come home (smart-stack checks, permission checks) should wait about 300 ms.

### 3. Cheaper wallpaper
- **Render the wallpaper at a lower resolution.** Draw it at around 60% size and let the
  GPU scale it up. The styles are soft, so it looks the same and uses roughly a third
  of the GPU time. This also helps battery and leaves more headroom for Kinetic's own
  animations.
- **Drop to 60 fps when nothing is moving,** and go back to 120 fps on touch, ripples
  or tilt.
- **Skip frames while an app covers the screen.** Already mostly true; double-check
  this for the lock screen and split screen.

### 4. Smoother Compose
- **Mark layout and app models `@Immutable` or `@Stable`.** Then Compose can skip
  redrawing tiles that haven't changed.
- **Read animated values only in `graphicsLayer` and draw blocks.** Most of the
  code already does this; a quick audit would catch the leftovers.
- **Reduce `onGloballyPositioned` calls.** Only measure an icon's position when it's
  tapped, instead of on every layout pass.
- **Cache the squircle and bevel paths** with `drawWithCache`.
- **Cache widget edge detection.** Remember each widget's card shape per widget type
  and size, instead of re-drawing it off-screen.

### 5. Measure it
- Add **JankStats** with a hidden "show jank" overlay in Settings that highlights slow
  frames as they happen.
- Use **Android Studio ▸ Profiler** or **Perfetto** on a release build to find slow spots.
- Track start-up time with `reportFullyDrawn()` so we can see whether each change helps.
