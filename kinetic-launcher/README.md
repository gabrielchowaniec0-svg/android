# Kinetic Launcher

Custom Android launcher for the Galaxy S25 Ultra in Kotlin, Jetpack Compose and AGSL.

**Open the `kinetic-launcher` folder (the one containing `settings.gradle.kts`) in
Android Studio**, let Gradle sync, then run the **app** configuration on your phone.
Full steps, including USB and Wi‑Fi debugging, are in
[`docs/DEPLOYMENT_GUIDE.md`](docs/DEPLOYMENT_GUIDE.md).

## Modules

| Module | Type | Contents |
|---|---|---|
| `:app` | `com.android.application` | `MainActivity` (MAIN/LAUNCHER + HOME), theme, icon — wires all modules together |
| `:launcher-core` | library | `LauncherGrid` (4×5–8×10, half-cell snapping, spring drag), `GridGeometry` |
| `:wallpaper-engine` | library | `FluidWallpaperService` + AGSL shader (`res/raw/fluid_wallpaper.agsl`), wallpaper metadata, manifest entry |
| `:lockscreen-transition` | library | `CinematicUnlock` (unblur → flow-line icon glide, TOP_BOTTOM / LEFT_RIGHT → widget assembly), `BiometricUnlockBridge` |
| `:security-engine` | library | `StealthCustomizationManager` — icon/label overrides and a private workspace opened by a chosen credential, stored in EncryptedSharedPreferences |

Dependency graph: `:app` → all four libraries; the libraries don't depend on each other.

## Requirements

- Android Studio with JDK 17+, Android SDK Platform 35
- Device on Android 13+ (`minSdk 33` — AGSL `RuntimeShader` needs API 33)
- Gradle 8.14.3 (via the included wrapper), AGP 8.10.1, Kotlin 2.1.21
