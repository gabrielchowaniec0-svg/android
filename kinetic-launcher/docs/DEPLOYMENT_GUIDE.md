# Kinetic Launcher — Build & Deploy Guide (Galaxy S25 Ultra)

This guide takes the Phase 1 code (AGSL fluid wallpaper, cinematic unlock
transition, launcher grid, customization/private-space manager) into a running
build on an S25 Ultra, over USB and over Wi‑Fi.

> Requirements: Android Studio Ladybug (or newer), JDK 17, Kotlin 2.0+ with the
> Compose compiler plugin, a device on **Android 13+** (AGSL needs API 33).
> The S25 Ultra ships well above that, so you're covered.

---

## 1. Project layout

`kinetic-launcher/` is a complete multi-module Gradle project:

```
kinetic-launcher/
├── settings.gradle.kts         includes all five modules
├── build.gradle.kts            plugin versions (apply false)
├── gradle/libs.versions.toml   version catalog
├── gradlew / gradle/wrapper/   Gradle 8.14.3 wrapper
├── app/                        com.android.application  <- the runnable module
│   └── MainActivity            MAIN/LAUNCHER + HOME; wires the modules below
├── launcher-core/              library: LauncherGrid + GridGeometry
├── wallpaper-engine/           library: FluidWallpaperService (+ its manifest entry)
├── lockscreen-transition/      library: CinematicUnlock + BiometricUnlockBridge
└── security-engine/            library: StealthCustomizationManager
```

Library manifests (e.g. the wallpaper service) merge into `:app` automatically.

---

## 2. Open and build

1. In Android Studio choose **File ▸ Open** and select the **`kinetic-launcher`
   folder itself** (the one containing `settings.gradle.kts`) — not the repo
   root above it, or Studio won't find any modules.
2. Let Gradle sync. Studio may offer to install SDK Platform 35 — accept.
   If it suggests an AGP upgrade, that's optional.
3. The run-configuration dropdown should now show **app**. Pick your phone
   and press **Run**. From a terminal the equivalent is:
   ```
   ./gradlew :app:installDebug
   ```

---

## 3. Put the S25 Ultra into developer mode

1. **Settings ▸ About phone ▸ Software information.**
2. Tap **Build number** seven times; enter your PIN when asked.
3. Back out to **Settings ▸ Developer options** (newly visible).

---

## 4A. Deploy over USB

1. In **Developer options**, enable **USB debugging**.
2. Connect the phone with a data‑capable USB‑C cable.
3. On the phone, approve **Allow USB debugging** for this computer (tick
   "Always allow").
4. Confirm the device is seen:
   ```
   adb devices
   ```
   You should see your serial listed as `device` (not `unauthorized`).
5. In Android Studio, pick the device in the target dropdown and press **Run**
   (`Shift‑F10`), or from a terminal:
   ```
   ./gradlew :app:installDebug
   ```

---

## 4B. Deploy over Wi‑Fi (wireless debugging)

Phone and computer must be on the **same network**. For Android 13+:

1. In **Developer options**, enable **Wireless debugging**.
2. Tap **Wireless debugging ▸ Pair device with pairing code**. Note the
   `IP:PORT` and the 6‑digit code shown.
3. On the computer, pair once:
   ```
   adb pair 192.168.1.42:37115
   ```
   Enter the pairing code when prompted.
4. Then connect to the (different) debug port listed on the main Wireless
   debugging screen:
   ```
   adb connect 192.168.1.42:39021
   ```
5. `adb devices` should now show the `IP:PORT` as `device`. Run from Android
   Studio as usual — the wireless target appears in the dropdown.

> Tip: after the first USB pairing you can switch to wireless without the cable
> using `adb tcpip 5555` then `adb connect <phone-ip>:5555`.

---

## 5. Activate the pieces on the phone

**Default launcher**
1. **Settings ▸ Apps ▸ Choose default apps ▸ Home app** (wording varies on
   One UI) ▸ select **Kinetic**.
2. Press Home. To go back to One UI Home while developing, repeat and pick
   Samsung's launcher.

**Using the home screen**
- Your real installed apps and icons are loaded automatically. On first run,
  your default phone, messages, browser, camera and gallery apps go along the
  bottom row.
- **Swipe left/right** to move between home pages. All swipes respond to a quick
  flick as well as a longer drag (`flingVelocityPx` / `swipeThresholdPx` in `HomeScreen.kt`).
- **Swipe up anywhere** to open all apps, One UI–style: it follows your finger,
  the wallpaper softens behind it, apps are in 4‑column pages you swipe
  sideways, and **Search** is the pill at the bottom. Swipe down to close.
  Long‑press an app for: Add to home screen, folders, Rename, Hide, App info.
- **⋮ menu** (top right of the app list): show hidden apps, **Sort A–Z**, and
  **Import layout from One UI Home**.
- **Swipe down anywhere** to pull down the normal Android notification shade.
- Icons are **frozen**. To edit, **press and hold empty wallpaper for about a
  second** (`EDIT_HOLD_MS` in `HomeScreen.kt`). In edit mode:
  - drag icons, folders and widgets to move them (they snap to half‑cells)
  - tap an item for options: remove, make or add to a folder, move to
    another page, resize (widgets)
  - the bottom bar has **Add apps**, **Widgets**, **Wallpaper**, **+ Page**,
    **Delete page** (empty pages only) and **Done**. Back also exits.
- The first time you add a widget, Android asks you to allow Kinetic to create
  widgets. Tap **Allow**, and tick "always" if offered.

**Your phone's layout (built in)**
- One UI Home doesn't let other apps read its layout, so Kinetic has your S25
  Ultra layout written into `app/.../MyPhoneLayout.kt`, copied from your
  screenshots. On first run after updating, Kinetic sets it up automatically:
  - home page: weather & clock widget at the top, Device care widget, the
    large 2 × 2 Google folder (tap its icons directly), Chowaniec, the Google
    search bar, and the Phone / Messages / Gmail / Camera dock
  - app list: your three pages in your order, with your Google, Microsoft,
    Samsung, Xfinity, games, food, streaming, creative, Amazon, smart‑home and
    "business" folders; anything else goes at the end, like new apps in One UI
- Kinetic adds the three widgets one by one. Android may ask you to allow
  Kinetic to add widgets; tap **Allow** (and "always" if offered).
- Your phone's home screen and app list use the same grid: 14 dp side margins,
  4 equal columns and 6 rows per app‑list page, so icons line up between the two.
- To start over: app list ▸ **⋮** ▸ **Reset to my phone's layout**.
- In edit mode, tap a folder ▸ **Make large (2 × 2)** / **Make small**.

**Look & motion**
- Every app icon is drawn into the same One UI–style squircle at the same size,
  including old square or logo‑only icons, so the grid looks even.
- Icons, folders and widgets have a soft 3D look: shadow, top gloss and a
  bevelled rim. Widgets get rounded corners.
- Tilt the phone: the home screen leans slightly and the wallpaper's ribbon
  layers shift at different depths (parallax). The sensor only runs while the
  home screen is visible.
- Icons squish when pressed, pages shrink/fade as you swipe, and app-list rows
  rise into place one after another when it opens.

**Opening apps, rings, menus and search**
- Apps **zoom open out of the icon** you tapped. Android doesn't let a
  third‑party launcher animate an app back *into* its icon when it closes, so
  instead the icon gives a little **landing bounce** when you come home.
- **Notification rings**: a thin ring in the wallpaper's blues spins around any
  app (or folder) with notifications. Turn it on once: Kinetic asks on first
  launch, or use app list ▸ **⋮** ▸ **Notification rings…** ▸ switch **Kinetic** on
  under **Notification access**. Kinetic only checks *which* apps have
  notifications; it doesn't read or store their contents.
- **Long‑press an app** for a One UI‑style pop‑up above the icon. It shows the
  app's own shortcuts (e.g. "New message"), plus **App info**, **Edit home**,
  **Remove** / **Add to home** / **More…**. App shortcuts only appear while
  Kinetic is the default Home app — that's an Android rule.
- **Folders** grow out of the folder you tapped and shrink back into it; the apps
  inside pop in one after another. Back or tapping outside closes it.
- **Smart search** (Search pill in the app list) finds: apps (best match first —
  press Enter on the keyboard to open it), quick maths (`15% of 80`, `12*(3+4)`,
  `2^10`, `sqrt 2` — tap to copy), Chowaniec pages, contacts (Kinetic asks for
  contacts access the first time; you can say no), Settings pages (Wi‑Fi,
  Bluetooth, Battery, Display…), **Search Chowaniec for …** and **Search the web
  for …**.

**Edit‑mode juice**
- Drag an icon onto another icon's spot: that icon **slides out of the way** into
  the spot you came from. Let go and they swap.
- Hold an app over the **middle** of another app for about half a second: the
  target swells and glows. Let go to **make a folder** (or drop it into an
  existing folder the same way).
- Widgets get a round **stretch handle** at the bottom‑right. Drag it and the
  widget stretches live; let go and it snaps to whole cells with a springy
  settle ("Not enough room" if something's in the way).

**Chowaniec integration**
- Kinetic recognises your **Chowaniec** app (the CH icon Chrome installed from
  chowaniec.net) by its name.
- **Long‑press Chowaniec**: shows Group Chat, Messages, Chowaniec AI, Notes and
  Settings. These come from the site's own app shortcuts when Chrome published
  them, otherwise Kinetic adds them itself.
- **Notification rings** work for Chowaniec's push notifications too.
- **Search**: type a page name (`notes`, `calendar`, `watch`, `games`…) or just
  `chow` to jump straight into that page inside the Chowaniec app, or use
  **Search Chowaniec for …** (opens chowaniec.net/search).

**Dynamic Island**
- A pure‑black, iPhone‑style island sits around the front camera, above every
  app, the status bar and the notification shade.
- Turn it on: app list ▸ **⋮** ▸ **Dynamic Island…** ▸ **Turn on in Settings**,
  then switch on **Kinetic Dynamic Island** and allow it. (Android only lets
  accessibility services draw above the status bar; it doesn't read the screen.)
  If the switch is greyed out, go to Settings ▸ Apps ▸ Kinetic ▸ ⋮ ▸ **Allow
  restricted settings** first.
- Music and calls need **Notification access** too (the same switch as the
  notification rings).
- What it shows, like iOS:
  - **Music/video:** album art on the left, a waveform tinted from the art on
    the right. Tap to open the app; hold to expand with title, progress (tap the
    bar to seek), and previous / play‑pause / next.
  - **Calls:** green phone and timer; incoming calls expand with the caller and
    the call's own Answer / Decline buttons.
  - **Charging** (with %), and **Silent / Vibrate / Ring** when you change the
    ringer.
- **Pull down** on it and it morphs open, following your finger the whole way;
  let go past about a third (or flick) and it finishes opening, otherwise it
  settles back. **Push up** on a big one to fold it the same way.
- Nothing is ever drawn over the camera: compact layouts keep the middle empty,
  and big shapes start their content below the camera.
- Like iOS, a song doesn't show while you're inside the app playing it. If it's
  paused and you've been out of that app for 10 seconds, the island shrinks to
  just the app's little logo (`MINIMAL_AFTER_MS` in `IslandModel.kt`); tap the
  logo to go back, or pull it down for controls.
- The gap above the island is set by `TOP_NUDGE_DP` in `DynamicIslandService.kt`.
- Tap outside to fold it back; it also folds itself after a few seconds. It
  hides in landscape. While resting it lets touches through, so pulling down the
  shade from the middle still works.

**More in the Dynamic Island**
- The pill is now slimmer: its top stays where it was, and its height is set so
  it's centred exactly on the camera. Change the gap in Settings ▸ Dynamic
  Island ▸ *Gap above the island* (the pill gets slimmer as the gap grows).
- **Timers / stopwatch** (Clock app): an orange timer icon and the countdown.
  Hold or pull down for Pause/Resume and Cancel. **Alarms** ring in the island
  with an orange *Snooze* and a grey *Stop*.
- **Directions** (Google Maps / Waze): the next turn arrow and distance; expand
  for the street and arrival time.
- **Chowaniec messages** drop down from the island with the sender and message.
  Tap to open, flick up to dismiss, or tap **Reply** to type an answer right there
  (when the notification offers a reply). Other messaging apps can do the same
  (Settings ▸ *Messages from all apps*).
- **Two things at once**: the second activity (e.g. a timer while music plays)
  pops out of the pill as a little bubble on the right; tap it to open that app.
- **Lock animation**: a padlock on the lock screen that swings open when you
  unlock.

**Smart stacks**
- In edit mode, tap a widget ▸ **Add widget to stack…** and pick another widget:
  both now share that spot. Swipe up/down on it to flip (it loops; dots show
  where you are).
- **Smart rotate** (on by default, per stack in the same menu) flips to what fits:
  the playing app's widget while music plays, calendar/weather in the morning,
  notes/reminders in the day, weather/battery in the evening. It leaves it alone
  for 3 minutes after you flip it yourself.
- The same menu can remove one widget from the stack or the whole stack.

**Wallpaper reactions** (each can be turned off in Settings)
- Unlock ripple from the middle, a ripple from any app icon you open, a soft
  pulse from the top for notifications, a green wave from the bottom when you
  plug in, and ribbons tinted with the album colours while music plays.
- Below 20% battery (or in Battery Saver) the wallpaper slows down and dims.
- **Apps react too:** home icons hop as each ripple passes them.

**Settings**
- App list ▸ **⋮** ▸ **Kinetic settings**, or **Settings** on the edit-mode bar.
- Setup status (default Home, Notification access, Dynamic Island) with shortcuts,
  every island feature, notification rings, haptics, icon size, and every
  wallpaper reaction.

**Lock screen + live wallpaper**
- Kinetic uses the phone's normal lock screen. When you unlock straight to the
  home screen, the icons glide in along the wallpaper's flow lines.
- To set the wallpaper, enter edit mode and tap **Wallpaper**, then tap **Set
  wallpaper**. If One UI asks where to apply it, choose **Home and lock
  screens** so it animates on the lock screen too.
- If One UI only offers the home screen, Samsung is keeping the lock screen for
  its own wallpapers on that software version. A third‑party app can't override
  that setting.

---

## 6. Performance checks on the S25 Ultra

- Confirm the wallpaper actually runs at the panel rate: **Developer options ▸
  Show refresh rate** overlay, or profile with:
  ```
  adb shell dumpsys gfxinfo com.kinetic.launcher framestats
  ```
- The render loop is Choreographer‑driven, so it already matches the display's
  current refresh (60/120Hz depending on One UI's motion‑smoothness setting).
  If you see thermal throttling, drop the fbm octaves in the shader from 5 to 4
  and halve the grain term.
- Watch for jank during the unlock: the `Modifier.blur` in Phase A is the most
  expensive effect; lower `maxBlur` if needed.

---

## 7. What's deliberately left to you

- An icon‑pack renderer that turns `iconKey` into a themed drawable (icons
  currently come straight from the system, including One UI theming).
- A settings screen (grid size, edit hold time, unlock flow direction).
- Dragging items between pages (use "Move to next/previous page" for now) and
  drag‑onto‑icon to create folders (use the edit menu for now).


## Wallpaper studio

Edit mode ▸ **Wallpaper**, or Settings ▸ Personalise ▸ **Wallpaper**.
- **Styles:** smooth (*Ribbons, Waves, Silk*), abstract (*Aurora, Orbs*) and rough
  (*Crystal, Dunes, Static*). Each style has its own ripple. Waves send a swell
  rolling up and down. Silk gets fine folds. Aurora shimmers. Orbs push out a gooey
  bubble. Crystal shards flash in turn. Dunes get a gritty shock wave. Static tears
  into glitchy rows. Every style still reacts to unlocks, app launches,
  notifications, charging, music and tilt.
- **Colours:** 8 palettes, or the *Custom colour* slider to build your own.
- **Feel:** speed, brightness, grain and detail sliders.
- The big preview is the real shader. Tap it to see a ripple. Changes apply live.
  If Kinetic isn't your wallpaper yet, use **Set as wallpaper**.

## Focus

Open it from the app list ▸ ⋮ ▸ **Focus…**, Settings ▸ Personalise ▸ **Focus**, or
the **Focus** quick-settings tile. To add the tile, pull down the quick panel,
tap ✎ and drag *Focus* in.
- The tile turns your last Focus on or off. Long-pressing it opens the picker.
- Built in: Do Not Disturb, Sleep, School, Gaming, Personal and Driving.
- Hold a Focus to customise it:
  - Silence (Do Not Disturb)
  - Let every app through
  - Wallpaper dimming
  - Which home page to jump to
  - Which apps may still show rings and island pop-ups
- The island shows "*Sleep* On" when a Focus switches, and keeps its little icon
  next to the camera while it's on.
- For silencing, Kinetic needs **Do Not Disturb access** once. The picker
  has a link to it.

## Kinetic Commands

Type a command into the app-list search. A card shows what will happen; tap the
button or press Enter.
- **Call:** `call Mom`, `call 555 1234`. The first time, Kinetic asks to place
  calls directly. If you say no, the dialer opens ready to go.
- **Text:** `text Dad on my way`, `text Mom Smith: running late`.
- **Email:** `email Sam`.
- **Directions:** `route to O'Hare` (also `directions to`, `navigate to`, `take me to`).
- **Timers and alarms:** `timer 10 min`, `25 min timer`, `alarm 6:30am`, `wake me up at 7`.
- **Music and video:** `play lofi beats`, `youtube landing tutorials`.
- **Phone controls:** `flashlight on`, `wifi`, `volume`.

Kinetic sends each command to the app you use most for that job (Maps or Waze,
Spotify or YouTube Music…). This needs **Usage access**, which you can turn on
in Settings ▸ Setup. Without it, Android's default app is used.

## Focus schedules

Hold a Focus ▸ **Schedule** ▸ *Turn on automatically*. Pick the start and end
times and the days; overnight schedules like 10:30 PM–7:00 AM work.
**Driving** can also turn on when your car's Bluetooth connects. Kinetic asks for
Bluetooth permission the first time.

## More island activities

- **Deliveries and rides** (Uber, Uber Eats, DoorDash, Lyft, Grubhub, Instacart and
  others): the app's icon plus the ETA in green. Expanded, it shows the status and a
  progress track.
- **Live scores** from ESPN, theScore, Yahoo Sports, the league apps, FotMob or Google:
  for example `CHI 21 · 17 GRE`. Expanded, it shows full team names, a big score and the
  game clock.
- **Privacy dots** next to the island, like iOS: orange when an app uses the mic,
  green when one uses the camera, red while the screen is being recorded.
- **Low battery:** at 15% (the same moment One UI warns), "Low Battery 15%" appears
  in red. Tap it to open Battery Saver.

## Gestures

- **Double-tap empty wallpaper** to lock the phone. This needs the Dynamic Island
  switch on.
- **Two fingers down** opens Focus. **A three-finger tap** opens search and Commands.
- **Shake to…** (Settings ▸ Gestures): flashlight, camera, search, Focus or any app,
  from anywhere. Two firm shakes trigger it. Only the flashlight works while the phone
  is locked.

## Icons & pages

Settings ▸ Icons & pages:
- **Icon style:** Normal, *Tinted* (one colour from your wallpaper), *Glass*
  (pale, see-through, with a frosted rim) or *Mono*.
- **Page transition:** Depth, Flat, Cube, Carousel or Fade.

## Smarter search

Typos are forgiven: "spotfy" finds Spotify, "ytm" finds YouTube Music, "rout to
ohare" still routes, and "call mon" finds Mom.

## Focus triggers

When you hold a Focus to edit it, you can also set:
- **Place:** *Use my current location*, then choose 100, 200 or 500 m. The Focus
  turns on when you arrive and off when you leave. It needs location
  "Allow all the time".
- **Turn on when I open…:** pick apps (for example your games for Gaming). The
  Focus turns off again when you leave them.

## Updates

See **UPDATES.md** for the one-time setup. After that, every merge becomes an update
you can install from Kinetic.

## Unlock animation

When the screen wakes on the lock screen, the island becomes a small Face ID–style
square just under the camera, with a face that turns side to side. It stays above
the clock, and it disappears the moment you unlock. Android doesn't
tell apps when the scan starts or which method worked, so the animation follows
"screen on while locked" and "unlocked".

## Speed tip: run the "release" build

Debug builds (Android Studio's default) are noticeably slower, especially for
animations: the app runs with debugging hooks and isn't fully compiled. For
day-to-day use pick **Build ▸ Select Build Variant… ▸ app: release**, then Run.
It's signed with the same debug key, so it installs over the debug version.

## Vault & the Kinetic code

The Vault (drawer ⋮ ▸ Vault, or Settings ▸ Privacy ▸ Vault) keeps photos and videos encrypted inside Kinetic.

- **Kinetic code.** The first time, you choose a 6-digit PIN or a pattern. It protects the Vault *and* "Show hidden apps". It's separate from your phone's lock screen. Change it under Settings ▸ Privacy ▸ Change Kinetic code.
- **Wrong tries.** After 5 wrong tries Kinetic makes you wait 30 seconds, doubling each time (up to 15 minutes).
- **Adding.** "Add from gallery" encrypts the photos/videos you pick, then Android asks you to confirm deleting the originals. "Camera" takes a photo that goes straight into the Vault and never appears in your gallery.
- **Taking things out.** Open an item ▸ "Move to gallery" puts it back in Pictures/Kinetic or Movies/Kinetic.
- **Privacy.** Screenshots and the recent-apps preview are blocked, and the Vault locks the moment you leave it.
- **How it's protected.** Each file is encrypted with AES-256. The key is locked twice: once with your code and once with a key stored in the phone's secure hardware. A copied file is useless on another phone or without your code.
- **⚠️ No recovery.** If you forget the code, or uninstall Kinetic / clear its data, the Vault's contents are gone for good. Keep a backup of anything important elsewhere.

### Why not "just one fingerprint"?
Android only tells an app "a fingerprint on this phone matched". It never says *which* finger, and apps can't register their own fingerprints. So Kinetic can't limit secure things to one finger. That's why the Vault and hidden apps use the Kinetic code instead of fingerprints.

### Secure screenshot
Press **Volume Up, Up, Down, Down** quickly (within about half a second between presses). The island's padlock closes and the phone gives a short buzz. The screenshot is sealed straight into the Vault without asking for your code. It never appears in the gallery, recent files or the clipboard. Next time you open the Vault, it's there.

- It needs the Dynamic Island switch on (Accessibility), and the Vault set up. If you set up the Vault before this update, open it once so secure screenshots can start working.
- Turn it off in Settings ▸ Privacy ▸ Secure screenshot.
- Volume keys still work normally. Kinetic changes the volume itself, and the volume panel shows up a split second later after Volume Up. That pause is so it doesn't end up in your screenshot. The volume ends where it started.
- It doesn't run during calls or with the screen off. Apps that block screenshots (banking apps, for example) still come out black.
- How it's safe without a code: the screenshot is locked with a "drop box" key that can only lock, not unlock. The key that opens it is itself locked inside the Vault.
- If the gesture does nothing after updating, switch the Dynamic Island off and on again in Accessibility. Android asks once more because it now also handles the volume keys.

## S Pen writing, living wallpaper & plane spotter

### Write on the home screen (S Pen)
Pull out the S Pen and write anywhere on the home screen. Pause for a moment and Kinetic reads it:
- A **command** runs straight away: "mom" (calls if it matches a contact command like "call mom"), "timer 10 min", "flashlight", "planes", "route to O'Hare".
- An **app's name** opens that app ("spotify", "maps").
- **Anything else** opens search with what you wrote.

Fingers work exactly as before, and tapping an icon with the pen still opens it. Only pen *strokes* become ink. Handwriting is read on the phone itself. The first time, Kinetic downloads Google's English handwriting model (about 20 MB). Switch it off under Settings ▸ S Pen.

### Living wallpaper
Settings ▸ Living wallpaper:
- **Rain when it's raining:** streaks fall and the colours cool when it's raining where you are. This uses your rough location and the free Open-Meteo weather service, checked every 30 minutes.
- **Battery tide:** a glowing waterline. Full battery means high tide. It turns green while charging and red when low.
- **Fireflies:** one firefly drifts around for each app with unread notifications.

### Plane spotter
Open it from the app list ⋮ ▸ Plane spotter, or write/type "planes". Point the camera at a plane. When it lines up with the circle, it locks on and shows the flight number, airline, where it's flying from and to, altitude, speed, distance and aircraft type. If nothing is in view, it tells you which way to turn to find the nearest plane.

It uses your location, the compass and live flight data from the community networks adsb.lol / adsb.fi, plus routes from adsbdb.com. If labels drift sideways, the compass needs calibrating: wave the phone in a figure 8. Some small private planes don't share a route.

## Mini assistant (write with the S Pen or type in search)

**Do things**
- "open spotify" (typos are fine; hidden apps stay hidden)
- "screenshot" · "secure screenshot" (straight into the Vault)
- "record video" (camera opens in video mode; tap record) · "take a photo" · "selfie"
- "text mom running late". Typed in search, **Send** sends it right away. Written with the pen, it opens in search first so you can check it before sending. If Android won't allow Kinetic to send texts, Messages opens with the text typed in.
- "remind me to call grandma at 6" (labelled alarm) · "remind me to flip the laundry in 40 min" (timer)
- "lock" · "notifications" · "quick settings" · "recent apps" · "bluetooth" · "settings" · "kinetic settings" · "wallpaper" · "focus" · "vault" · "search for …"

**Ask things** (the answer appears where you wrote, or as a card in search)
- "what's the weather" · "will it rain" · "do I need an umbrella"
- "when's my dentist appointment" · "what's next" (asks once for calendar access)
- "battery" · "what's the date" · "time in Tokyo"
- "15% of 84" · "12 x 7" · "5 miles in km" · "350 F to C" · "2 cups in ml"
- "where is UA1234": live altitude, speed, route and distance from you
- "flip a coin" · "roll a dice" · "roll d20"

## Face unlock animation
When the screen wakes, Kinetic watches the phone's real lock state. The tick appears the moment face (or fingerprint) unlock succeeds. If you stay on the lock screen, the padlock opens there. If it hasn't worked after about 3 seconds, the face icon shakes and fades.

The scan only shows when the phone is held up. It doesn't show when the phone is flat on a table or in a pocket (it uses the motion and proximity sensors). If Smart Lock already unlocked the phone, the padlock simply opens.

## Island upgrades (Batch 2)
- **Island stacks:** when two or more things are live (music, timer, directions, a delivery, a score), swipe sideways on the island to bring the next one to the front. Little dots show which one you're on.
- **Progress bars:** any app's download, upload or install progress shows in the island with a percentage. Pull it down for the full bar. You can switch this off in Settings ▸ Dynamic Island ▸ Progress bars.
- **My teams:** in Settings ▸ Dynamic Island ▸ My teams, type your teams (e.g. "Bears, Cubs"). Only their games show, and the island bounces with a buzz when the score changes.
- **Island look:** Black (default), Glass (a dark see-through finish with a thin edge) or Outline (an edge in the colour of whatever is playing).
- **Music glow:** a soft, breathing glow in the album's colours around the island while music plays.

## Quick wins (Batch 1)
- **Number badges:** a red unread count on app icons that have a notification ring. Switch it off in Settings ▸ Home ▸ Number badges.
- **"New" folder:** apps you install wait in a New folder in the app list for a week, then join the normal list.
- **Charging:** the island shows "Full at 11:40 PM" once Android has worked out the estimate.
- **Earbuds & headphones:** when they connect, the island shows their name and battery (if they report it).
- **Voice search:** hold the Search pill in the app list and say a command, an app or a question.

## Privacy (Batch 3)
- **Decoy code:** Settings ▸ Privacy ▸ Decoy code. It's a second code (the same type as your real one) that opens a separate, fake Vault. If someone makes you unlock, they see only what you put in the fake one. The decoy never shows hidden apps or opens locked apps.
- **Intruder photo:** after 3 wrong codes in a row, the front camera quietly takes one photo. It goes straight into the real Vault, named "Intruder" with the date and time (needs camera permission, e.g. from the plane spotter). It's on by default; switch it off in Settings ▸ Privacy.
- **Lock apps:** long-press any app ▸ Require Kinetic code. Opening it from Kinetic (home, the app list, search or the S Pen) then asks for your code first. Opening it from recent apps or another app's link doesn't, because Android doesn't let launchers block those.

## Plane spotter extras (Batch 7)
- **Logbook:** every plane you lock onto is saved (the same flight counts once a day), with its airline, route, aircraft type and time. Tap **Logbook** for your totals, most-spotted aircraft types and recent sightings.
- **United:** shows only United flights (United mainline plus United Express from GoJet, CommuteAir and Mesa).
- **Radar:** a night view that doesn't need the camera. You're in the middle and the way you're facing is at the top. Planes show as glowing dots with their heading, and rings mark 10, 20 and 35 miles. Turn until a plane is straight ahead to lock onto it. It also turns on automatically if the camera isn't allowed.

## Wallpaper & Focus (Batch 6)
- **Snow, fog & wind:** snowflakes drift down when it snows, a slow haze rises when it's foggy, and rain slants (and snow drifts) with the real wind. Settings ▸ Living wallpaper.
- **Sunset & moon:** colours warm up around your real sunrise and sunset. At night the wallpaper dims slightly and the real moon, in today's phase, glows in the top-right corner.
- **Focus timer:** write or type "focus 25 min" (or "study 45 min", "pomodoro 25 min"). Your Study/School/Work Focus turns on, a 25-minute timer starts, and the Focus turns itself off when the time's up.

## iOS-style screens (Batch 5)
- **StandBy:** put the phone on the charger and turn it on its side (from the home or lock screen), and leave it still for a couple of seconds. You get a big clock with the date, your next event and the battery level. Swipe sideways for your photos or your calendar. Late at night or in a dark room it all turns deep red. To leave, unplug, stand the phone upright or swipe up. Settings ▸ Home ▸ StandBy.
- **Control Center:** on the home screen, swipe down from the top-right corner. It has Wi-Fi, internet, Bluetooth and airplane mode, music controls, flashlight, Focus, timer, camera, calculator, screenshot, Vault and settings, plus brightness and volume sliders. The brightness slider asks once for "Modify system settings". Swiping down anywhere else still opens the normal notification shade.
- **App Library:** in the app list, tap ⋮ ▸ App Library view. Every app is sorted into groups (Social, Games, Productivity…). Tap a big icon to open it, or the little grid for the whole group.
- **Search:** "define serendipity" gives a definition, and "Mom's birthday" shows the date, how many days away it is and how old they'll turn.
- **Smart Rotate** was already built in: widget stacks flip to music while it plays and to calendar or weather in the morning.

## S Pen extras (Batch 4)
- **Shapes:** Settings ▸ S Pen ▸ Shapes. Give a heart, circle, check, star or triangle a command (e.g. heart → "call mom"). Drawing that shape in one stroke on the home screen runs the command.
- **Sticky notes:** write "note buy milk" (or type it in search). A yellow sticky with your handwriting appears on the first home page. Swipe it sideways to throw it away.
- **Maths with steps:** write "2x + 5 = 17". The island shows "Subtract 5 → Divide by 2 → x = 6".
- **Command chains:** Settings ▸ S Pen ▸ Command chains. Make one name run several commands, one per line (e.g. "gaming mode" = "focus 60 min" then "open roblox"). Write or type the name to run them all in order.

## More commands (S Pen or search)
**Music:** "next", "previous", "pause", "resume", "what's playing" · "spotify lofi beats" / "play Taylor Swift on spotify"
**Sound & screen:** "volume 40", "louder", "quieter", "mute", "unmute" · "silent", "vibrate", "ring" · "dnd on/off" · "brightness 70", "brighter", "dimmer"
**Inside apps:**
- "coffee near me" / "maps target" (Maps)
- "uber to the airport" / "lyft home"
- "whatsapp mom running late"
- "translate where is the bathroom to spanish" / "how do you say thank you in finnish" (Google Translate)
- "add event dentist tomorrow at 3pm" / "schedule practice friday at 6" (calendar)
- "keep buy milk" (Google Keep) / "samsung notes ideas for video" (Samsung Notes)
- "youtube.com" / "go to wikipedia.org" (any website)
- "voice memo" (Voice Recorder)
**Settings:** "hotspot", "battery saver", "airplane mode", "display", "storage", "gps"
**Answers:** "where am I" · "tip 20% on 45" / "tip 18% on 120 split 4" · "days until christmas" / "countdown to july 4" · "random number 1 to 50"

**Permissions:** when a command needs something you haven't allowed yet (contacts, location, silent mode, brightness, music controls…), the Dynamic Island drops down with an **Allow** button. Tap it: Android asks, and the command runs as soon as you say yes. For Do Not Disturb, brightness and music controls, it opens the right settings page; switch Kinetic on there and try the command again.

## Commands in the Dynamic Island
With the island on, S Pen commands now happen in the island instead of opening search:
- **Texts and calls** drop down with **Send/Call** and **Cancel** ("text mom hello" → "Text Mom: "hello"" [Cancel] [Send]).
- **Missing permissions** drop down with **Allow** / **Not now**, then carry on by themselves (e.g. Allow contacts → then the Send prompt).
- **Answers and results** drop down for a few seconds.
- **Anything it doesn't understand** drops down with **Search** (Kinetic search) and **Web**.

New commands:
- "screen time" (today's total and top apps; asks once for usage access)
- "storage left" · "what did I miss" (unread count per app) · "clear notifications" (asks first)
- "share my location with mom" (a Maps link, confirmed in the island)
- "copy 555-0123" (to the clipboard)
- "how far is Chicago" · "sunset" / "sunrise" · "moon phase" · "planes nearby" (count plus the closest flight)
- "tell me a joke" · "magic 8 ball will I pass my test" / "should I get pizza"

## Commands, part 3 (write these with the S Pen or type them in search)

**Doing things in one line**
- After you text someone "be there in 10", the island offers a reminder in 10 minutes.
- `reply on my way` answers your newest message notification. `call back` returns your missed call (or opens call history). `read it` reads your newest notification out loud.
- `snooze notifications 1 hour` turns on Do Not Disturb and turns it off again by itself.
- `what's my ETA` / `send my ETA to mom` (while Google Maps directions are running).

**Your stuff**
- `remember I parked on level 3` saves it with where you are; `where did I park` shows it with a Map button. Works for anything: `remember the wifi password is …` then `what's the wifi password`. `forget parking` clears it.
- `last photo`, `send last photo to mom`, `photos from yesterday`.
- `my schedule`, `my schedule tomorrow`, `schedule friday` (includes homework due that day).

**School**
- `homework math page 42 due friday` saves it and reminds you at 7 pm the evening before. `homework` lists it; `done math` ticks it off.
- `save spanish word gato = cat`, then `flashcards spanish` quizzes you in the island (Show → Next).
- `stopwatch`, `lap`, `stop`. The island shows the time when you write lap/stop (it is not a live ticking counter).
- `school days until summer` (weekdays only; holidays aren't known). Set the date with `summer starts june 4`.

**Planes**
- `metar KORD` (or `metar ORD`): the raw report plus plain English.
- `what's that plane` opens the plane spotter. `phonetic Gabriel` spells it Golf Alpha Bravo… `FL350` → 35,000 ft.

**Quick tools**
- `split 84 between 3`, `15% off 60`, `time zones: Chicago, London, Tokyo`, `pick for me: pizza, tacos, sushi`.
- `level`, `ruler`, `decibels` open a tools screen (the sound reading is approximate: phone mics aren't real sound meters).

**Smarter commands**
- `again` repeats your last command. `undo` puts back the last volume, brightness, ringer or Do Not Disturb change.
- Writing shortcuts: `shortcut m = call mom`, or Settings → Writing shortcuts.
- If you write the start of a command you've used before ("ca"), the island offers to finish it ("call mom?" → Do it).

## "What can I write?" (all commands)
- Write `help`, `?` or `commands` (or open **Commands** in the app list, or Settings → All commands).
- Every command is grouped (People, Notifications, Remember things, School, Planes…), searchable, and has a **Try** button. Your own shortcuts, chains and recent commands appear at the top under "Yours".

## Island fix
- The island's contents could fade out and stay invisible after pulling it open or closed (leaving an empty black pill or box). The fade-back no longer gets cut short, and a safety check restores the contents whenever nothing is being pulled.

## Island: live activities, sliders and celebrations
- **Live stopwatch**: `stopwatch` now counts live in the island. Pull it down for **Lap** and **Stop** (or write `lap` / `stop`).
- **Focus ring**: `focus 30 min` shows a ring that empties in the island (pull down for **End now**). When it finishes: a chime and confetti.
- **Up next**: 15 minutes before a calendar event, the island counts down to it (pull down for **Open** / **Hide**). Settings → Island → Up next.
- **Volume slider**: the volume buttons show Kinetic's slider in the island instead of One UI's panel (calls still use One UI's). Settings → Island → Volume slider.
- **Brightness slider**: shows when brightness changes while auto-brightness is off (with auto-brightness on it would pop up all day).
- **Rain alerts**: "Rain starting in about 15 min" (checked every 20 minutes while the screen is on, at most once every 3 hours). Settings → Island → Rain alerts.
- **Plane watch**: `plane watch` — for the next hour, planes passing within about 4 miles pop up with their route and altitude (tap to open the plane spotter). `plane watch off` stops it.
- **Track a flight**: `track UA123` — follows it live (climbing / cruising / landing soon, altitude, speed) and celebrates when it lands. `stop tracking` ends it.
- **Celebrations**: confetti when you finish homework (`done math`), a Focus session, a flashcard deck, or a tracked flight lands.
- **Progress bars** now have a ring around the app icon, and a "✓ done" when they finish.
- Already there from earlier: charging, earbuds, silent/vibrate and low-battery pop-ups.
