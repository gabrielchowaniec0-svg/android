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
- **Drag it** and it stretches after your finger like gel, then wobbles back.
  **Pull down** on it to expand (same as holding); **push up** to fold it away.
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
