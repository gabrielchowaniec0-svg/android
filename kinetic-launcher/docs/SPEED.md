# Making Kinetic fast — step by step

Part 1 is about how Kinetic is **built**. Part 2 is about what it **does at start-up**.
Part 2 is already in the code; Part 1 needs a few clicks from you, once.

## Part 1 — ship it like a real release

### Step 1 · Switch to the release build
1. Open the project in Android Studio and let it sync (bottom status bar idle).
2. Menu **Build ▸ Select Build Variant…**. A *Build Variants* panel opens on the left.
3. In the row **:app**, click *debug* and choose **release**. Leave the other rows as
   they are; Android Studio sets them to match.
4. Press **Run ▶**. The first release build takes longer, because R8 is shrinking the app.
5. It installs over your current Kinetic. Your layout and settings stay.

> What changed: release builds run without debugger hooks and are fully compiled.
> **R8** is now switched on: it removes unused code and optimises the rest, so the
> app is smaller and starts faster. If anything ever breaks only in release, tell
> me what happened; it's usually one missing "keep" rule in `app/proguard-rules.pro`.

### Step 2 · Record a Baseline Profile (the big one)
A Baseline Profile is a list of the code Kinetic runs most: start-up, page
swipes and the app list. Android pre-compiles that list at install time, so those
moments are fast from the very first run. Compose apps usually get 20–40% faster
start-up and smoother first frames.

1. **Plug your S25 Ultra into the PC** with USB debugging on, the same way you install
   Kinetic now. Unlock it and leave the screen on.
2. In Android Studio, open the **Gradle** panel (the elephant icon on the right edge).
3. Go to **KineticLauncher ▸ app ▸ Tasks ▸ other**. *(If you don't see "Tasks",
   click the 🔧 icon in the Gradle panel and turn off "Only include test tasks…",
   or use the search box in the panel.)*
4. Double-click **generateReleaseBaselineProfile**. *(If it isn't listed, use
   **generateBaselineProfile**.)*
5. Watch the phone. Kinetic opens by itself, swipes pages and opens the app list a
   few times. **Don't touch the phone** until it stops. This takes 3–8 minutes.
6. When it finishes, a new file appears at
   `app/src/release/generated/baselineProfiles/baseline-prof.txt`.
7. Commit it with **Git ▸ Commit…** (tick the new file, message "Baseline profile")
   ▸ **Commit and Push**. You can also tell me and I'll add it in a pull request.
8. Run the **release** build again (Step 1). The profile is now inside the app.

Redo Step 2 after big updates, roughly once a month. The old profile still helps
until then.

### Step 3 · Check it worked (optional)
- In the app list, time how long it takes to open after a fresh restart of the
  phone. Before and after the profile should feel noticeably different.
- For exact numbers: **Run ▸ Profile 'app'** ▸ *CPU / Frames*, on the release build.

## Part 2 — faster start-up (done in this update)
- **Icons are saved to disk.** Kinetic now keeps each finished squircle icon as a
  small image. The next time Kinetic starts, it loads those images instead of
  re-drawing every icon. When an app updates, its saved icon is made again
  automatically.
- **Widgets stay up to date in the background** (from the previous update), so
  coming home doesn't make them all redraw at once.
- **Checks wait until the screen has settled.** Update checks and smart-stack
  decisions start about a second after you come home, never in the way of the
  first frames.

Still on the list (see ROADMAP.md): drawing the wallpaper at a lower resolution,
pre-building the app list, and an in-app "show slow frames" overlay.
