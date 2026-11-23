# Kinetic Launcher — Phase 1

Production-oriented starting code for a custom Android launcher on the Galaxy
S25 Ultra, in Kotlin + Jetpack Compose + AGSL.

## Contents

| Module | File | What it is |
|---|---|---|
| `:wallpaper-engine` | `wallpaper-engine/src/fluid_wallpaper.agsl` | AGSL fragment shader: layered sine/cosine fluid ribbons, fbm grain, specular rim, touch bloom, dark navy/slate palette |
| | `wallpaper-engine/src/FluidWallpaperService.kt` | `WallpaperService.Engine` rendering the shader on a hardware canvas, Choreographer-paced to 120Hz |
| `:lockscreen-transition` | `lockscreen-transition/src/UnlockTransitionController.kt` | Compose unlock: Phase A unblur+scale, Phase B icon glide along cubic-bezier flow lines (`PathMeasure`) with **TOP_BOTTOM / LEFT_RIGHT** options, Phase C widget spring assembly |
| | `lockscreen-transition/src/BiometricUnlockBridge.kt` | Runs the transition on `BiometricPrompt` success; reports the credential tag used |
| `:security-engine` | `security-engine/src/StealthCustomizationManager.kt` | Owner-controlled icon/label masking + a private app workspace gated by a chosen credential; settings stored in EncryptedSharedPreferences |
| `:launcher-core` | `launcher-core/src/LauncherGrid.kt` | Configurable grid (4×5–8×10), half-cell snapping, spring-physics drag |
| | `launcher-core/src/LauncherActivity.kt`, `LauncherSampleUi.kt` | Host wiring grid ↔ customization manager (runnable sample) |
| manifest | `manifest/AndroidManifest.xml`, `manifest/res_xml_fluid_wallpaper.xml` | Launcher `CATEGORY_HOME` + wallpaper service registration |
| docs | `docs/app_build.gradle.kts`, `docs/DEPLOYMENT_GUIDE.md` | Dependencies + step-by-step build/deploy over USB and Wi-Fi |

## Notes

- **minSdk 33** — AGSL (`RuntimeShader`) requires Android 13+.
- The security engine is a standard launcher personalization + hidden-apps
  feature: overrides affect only how *this* launcher draws an entry, the private
  space is unlocked by the device owner's own biometric, and nothing leaves the
  device. Loading installed apps, an icon-pack renderer, and the settings UI are
  marked as the integration work left to you in the deployment guide.
- Start with the deployment guide — it covers project layout, themes, and
  activating the wallpaper + default launcher on the phone.
