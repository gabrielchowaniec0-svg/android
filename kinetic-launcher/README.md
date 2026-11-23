# Kinetic Launcher

Custom Android launcher for the Galaxy S25 Ultra in Kotlin, Jetpack Compose and AGSL.

**Open the `kinetic-launcher` folder (the one containing `settings.gradle.kts`) in
Android Studio**, let Gradle sync, then run the **app** configuration on your phone.
Full steps, including USB and Wi‑Fi debugging, are in
[`docs/DEPLOYMENT_GUIDE.md`](docs/DEPLOYMENT_GUIDE.md).

## Modules

| Module | Type | Contents |
|---|---|---|
| `:app` | `com.android.application` | `MainActivity` (MAIN/LAUNCHER + HOME) and the UI: swipeable home pages, app drawer, folders, edit mode, widget picker |
| `:launcher-core` | library | `LauncherGrid` (frozen / editable, half-cell snapping, hold-to-edit), `AppRepository` (real installed apps + icons), `HomeLayoutStore` (pages, folders, widgets — saved), `WidgetHostManager` (real Android widgets), `SystemGestures` (notification shade) |
| `:wallpaper-engine` | library | `FluidWallpaperService` + AGSL shader (`res/raw/fluid_wallpaper.agsl`), wallpaper metadata, manifest entry |
| `:lockscreen-transition` | library | `CinematicUnlock` — icons glide in along flow lines after you unlock with the normal lock screen; `BiometricUnlockBridge` (used to reveal hidden apps) |
| `:security-engine` | library | `StealthCustomizationManager` — app renames and hidden apps, stored in EncryptedSharedPreferences |

## Gestures

| Gesture | Does |
|---|---|
| Swipe left / right | Switch home page |
| Swipe up anywhere | All apps (search, folders, hidden apps) |
| Swipe down anywhere | Android notification shade |
| Hold empty wallpaper ~1 s | Edit mode: move/remove items, folders, widgets, pages, wallpaper |

Dependency graph: `:app` → all four libraries; the libraries don't depend on each other.

## Requirements

- Android Studio with JDK 17+, Android SDK Platform 35
- Device on Android 13+ (`minSdk 33` — AGSL `RuntimeShader` needs API 33)
- Gradle 8.14.3 (via the included wrapper), AGP 8.10.1, Kotlin 2.1.21
