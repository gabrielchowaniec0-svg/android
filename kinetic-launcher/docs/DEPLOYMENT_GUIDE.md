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

**Live wallpaper**
1. Tap the **Wallpaper** button at the bottom of Kinetic's home screen — it
   opens the system preview for **Kinetic Fluid** directly.
2. Tap **Set wallpaper**. Touch the screen to see the bloom react.

**Default launcher**
1. **Settings ▸ Apps ▸ Choose default apps ▸ Home app** (wording varies on
   One UI) ▸ select **Kinetic**.
2. Press Home. To go back to One UI Home while developing, repeat and pick
   Samsung's launcher.

**Cinematic unlock + private space**
- `MainActivity` starts on a "Tap to unlock" overlay. Tapping runs
  `BiometricPrompt` (fingerprint, or your PIN as a fallback); on success the
  icons glide into their grid spots and the draggable grid takes over. It
  re-arms when the screen turns off. If no biometric or screen lock is set up,
  the tap just plays the animation. This is a launcher‑level animation, not a
  replacement for the system keyguard.
- In your settings UI, call `bindPrivateCredential(tag)` to let one of the
  owner's credentials open the private workspace; `workspaceForCredential(tag)`
  then returns `PRIMARY` or `PRIVATE` after each unlock.

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

- Loading real installed apps (`LauncherApps` / `PackageManager`) and mapping
  each to a `DisplayEntry` — the sample uses a fixed package list.
- An icon‑pack renderer that turns `iconKey` into a themed drawable.
- A settings surface to edit masks, assign a private‑space credential, and pick
  the unlock flow direction.
- Persisting the grid layout (drag positions currently last for the session).
- Widget hosting (`AppWidgetHost`) for Phase C — the controller animates
  arbitrary composables, so wrap your hosted widgets as `UnlockWidget` content.
