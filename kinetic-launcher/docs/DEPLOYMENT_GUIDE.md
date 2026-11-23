# Kinetic Launcher — Build & Deploy Guide (Galaxy S25 Ultra)

This guide takes the Phase 1 code (AGSL fluid wallpaper, cinematic unlock
transition, launcher grid, customization/private-space manager) into a running
build on an S25 Ultra, over USB and over Wi‑Fi.

> Requirements: Android Studio Ladybug (or newer), JDK 17, Kotlin 2.0+ with the
> Compose compiler plugin, a device on **Android 13+** (AGSL needs API 33).
> The S25 Ultra ships well above that, so you're covered.

---

## 1. Project layout

Create a new **Empty Activity (Compose)** project, then drop the files in:

```
app/src/main/
├── java/com/kinetic/
│   ├── launcher/   LauncherActivity.kt, LauncherSampleUi.kt
│   ├── core/       LauncherGrid.kt
│   ├── wallpaper/  FluidWallpaperService.kt
│   ├── lockscreen/ UnlockTransitionController.kt, BiometricUnlockBridge.kt
│   └── security/   StealthCustomizationManager.kt
├── res/
│   ├── xml/fluid_wallpaper.xml      (from manifest/res_xml_fluid_wallpaper.xml)
│   ├── drawable/fluid_thumb.png     (any 512×512 preview thumbnail you like)
│   └── values/strings.xml           (add fluid_wallpaper_desc, see below)
└── AndroidManifest.xml              (merge from manifest/AndroidManifest.xml)
```

`fluid_wallpaper.agsl` is kept as a readable reference; the shader string is
also inlined in `FluidWallpaperService.kt`. If you'd rather load it at runtime,
put the `.agsl` in `res/raw/` and read it with `openRawResource`.

Add to `res/values/strings.xml`:

```xml
<string name="fluid_wallpaper_desc">Kinetic dark fluid live wallpaper</string>
```

Also add a `Theme.Kinetic` and `Theme.Kinetic.Home` (a translucent, no‑action‑bar
theme) to `themes.xml` — the Home variant should set a transparent window
background so the wallpaper shows through.

Copy the dependencies from `docs/app_build.gradle.kts` into your module.

---

## 2. Build

1. **File ▸ Sync Project with Gradle Files.**
2. Resolve the `namespace` / `applicationId` to your own if you like.
3. **Build ▸ Make Project** (`Ctrl/Cmd‑F9`). Fix any package‑path mismatches
   (the folders under `java/` must match each file's `package` line).

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
   adb install -r app/build/outputs/apk/debug/app-debug.apk
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
1. Long‑press the home screen ▸ **Wallpapers** (or Settings ▸ Wallpaper).
2. Choose **Live wallpaper ▸ Kinetic Fluid ▸ Set**. Touch the screen to see
   the bloom react.

**Default launcher**
1. **Settings ▸ Apps ▸ Choose default apps ▸ Home app** (wording varies on
   One UI) ▸ select **Kinetic**.
2. Press Home. To go back to One UI Home while developing, repeat and pick
   Samsung's launcher.

**Cinematic unlock + private space**
- The unlock transition runs inside your launcher after a successful
  `BiometricPrompt` (wire `BiometricUnlockBridge` to a button or to your lock
  overlay). It is a launcher‑level animation, not a replacement for the system
  keyguard.
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
- Widget hosting (`AppWidgetHost`) for Phase C — the controller animates
  arbitrary composables, so wrap your hosted widgets as `UnlockWidget` content.
