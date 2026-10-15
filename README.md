# AeonPS1 — PlayStation 1 Emulator for Fire TV

**AeonPS1 1.3.0** · package `com.aeonps1.tv` · minSdk 24 · target/compile SDK 35

A PlayStation 1 emulator for Amazon Fire TV, built the same sovereign way as
AeonTV: a TV-first Kotlin frontend written for this project, running real
open-source PS1 emulation cores through a native JNI bridge written in C.

No BIOS and no games are bundled. You supply your own game files (that you own)
and your own BIOS file (required for the DuckStation engine).

## Prototype: DuckStation engine (v1.3.0+)

This build ships **two engines**, switchable in Settings → Emulator engine:

- **DuckStation engine (SwanStation)** — the default in this prototype.
  SwanStation is libretro's GPLv3 fork of the **DuckStation** engine
  (upstream DuckStation relicensed to CC BY-NC-ND in Sept 2024, which forbids
  modified redistribution — so SwanStation, the GPL fork, is the form of the
  engine that can legally ship here). It is the more accurate, better-looking
  engine, but it **requires a real PlayStation BIOS file** (no built-in HLE).
  The bridge runs it in software-rendering mode, answers its core options
  (renderer forced to Software), and implements the extra plumbing it needs
  (core options v2, disk control, logging).
- **PCSX-ReARMed** — the lighter ARM-optimized core from earlier releases,
  still available. Plays most games with no BIOS (built-in HLE replacement).

Since v1.2.1, if a game won't start the app keeps the failure screen up with
the engine's own log, so the next refusal diagnoses itself — quote those lines
back and the plumbing gets fixed.

## What it does (exact)

- **Game library**: big D-pad-friendly grid of your PS1 games. Merges: the app's
  private games folder, any folders you marked in the explorer (scanned in
  place, depth 3), and individually added game files. Recognizes `.cue`
  `.bin` `.iso` `.pbp` `.chd` (a `.bin` is hidden when its `.cue`/`.chd`/
  `.pbp`/`.iso` sibling exists, so multi-track games list once).
- **File Explorer** (the primary way): a built-in TV-first browser — roots for
  Internal storage, every USB/external volume, Downloads, the app folder, and
  the whole file system. Browse with the D-pad; OK on a game file offers
  **Play now** (runs the game right where it sits — nothing is copied) or
  **Add to library**; press **Use this folder** to add a whole folder to the
  library (the button toggles: press again to remove the folder — as many
  folders as you like can be watched at once). On Fire OS 8+ the explorer asks
  once for "All files access" so it can read shared storage and USB sticks.
- **Archive extraction**: tap a `.rar` or `.zip` in the explorer and pick
  **Extract & add games**. It unpacks to the app's `Extracted/<name>/` folder
  (junrar, incl. modern RAR5 and multi-part `.part1.rar` sets) and adds that
  folder to the library automatically. Progress with a real Cancel button,
  free-space check first, password-protected archives are refused plainly.
- **Add Games**: the old system file picker still works and copies your game
  files into the app's private storage (works without any storage permission);
  picking a `.rar`/`.zip` there runs the same extraction flow.
- **Emulation**: PCSX-ReARMed core loaded at runtime; video blitted to a
  SurfaceView (nearest-neighbor scale, 4:3 or Stretch), audio through AudioTrack
  at 44100 Hz stereo.
- **Controls**: any gamepad / Fire TV remote — D-pad, A/B/X/Y mapped to
  Cross/Circle/Square/Triangle (standard RetroArch layout), L1/R1/L2/R2,
  Start/Select, left analog stick (also pushes the D-pad past half-tilt for
  digital-only games). Menu button, Back, or Start+Select opens the game menu.
- **Game menu**: Save State, Load State, Reset Game, Quit to Library (one save
  slot per game, stored in the app's private saves folder).
- **Memory cards**: the core's save RAM is persisted to `<game>.srm` in the
  saves folder — written every ~5 seconds, when the menu opens, on pause, and
  on quit. Survives app restarts.
- **BIOS screen**: explains the situation in plain words, shows the BIOS folder
  path, detects `scph*.bin`, and offers an "Import BIOS file" picker. Without
  a BIOS the core's built-in HLE is used (most games work; some need a real
  BIOS from a PlayStation you own).
- **Settings**: Screen mode (4:3 / Stretch), Show FPS toggle.

## Provenance

- Core: `pcsx_rearmed_libretro_android.so` from the libretro buildbot nightly
  (android/latest), downloaded 2026-10-15:
  - `arm64-v8a`: 1,585,704 bytes,
    sha256 `c9e5ad91e75edf87136b8aea883d184c8d3ef6b6316687eda1ebaea3f7c9aace`
  - `armeabi-v7a`: 1,229,152 bytes,
    sha256 `5a9a5a86d443de02150c87dad215f34d84f51e7ea775c8454dc2933c2c4c2535`
  - Both verified: real ARM ELF shared objects exporting all 20 `retro_*`
    entry points (`retro_init`, `retro_load_game`, `retro_run`, …).
- `libretro.h` from libretro/libretro-common (master, 2026-10-15).
- Core (added v1.3.0): `swanstation_libretro_android.so` from the libretro
  buildbot nightly (android/latest), downloaded 2026-10-15:
  - `arm64-v8a`: 5,698,592 bytes,
    sha256 `3ff5d312b6c9e4e875921560dc04227a31bd8497d0c6e032fa4af463c801352b`
  - `armeabi-v7a`: 5,210,156 bytes,
    sha256 `b4ae25c61b709b83c5d0be4b46243aaca993e8fb69f2241c25bb302fbf74b319`
  - Both verified: real ARM ELF shared objects exporting all 25 `retro_*`
    entry points. Core info (`swanstation_libretro.info`, GPLv3): extensions
    `exe|psexe|cue|bin|img|iso|chd|pbp|ecm|mds|psf|m3u`; BIOS required, accepted
    names `scph5500/5501/5502.bin`, `psxonpsp660.bin`, `ps1_rom.bin`.
  The download keeps the buildbot name `swanstation_libretro_android.so`;
  it is bundled in the APK as `libswanstation_libretro_android.so` (lib-prefix
  required so Android extracts the native library at install time).
  SwanStation is libretro's GPLv3 fork of the DuckStation engine
  (github.com/libretro/swanstation). Upstream DuckStation relicensed to
  CC BY-NC-ND (no derivatives) in Sept 2024, so the GPL fork is the
  redistributable form.
- Both cores are GPLv3. This app's own frontend/bridge code is yours; the
  core `.so` files ship inside the APK as required by the license terms —
  keep them with the app and point users at the cores' sources.
  No BIOS, no games, no keys are bundled.

## Honest limits (v1.1)

- **Not device-tested**: built and verified in the sandbox (see Verification
  below). Isaiah's first on-TV run of v1.0 was what prompted the v1.1 explorer;
  the new screens are likewise not yet verified on real hardware.

- **Not device-tested**: built and verified in the sandbox (see Verification
  below). First run on a real Fire TV is still needed.
- One save-state slot per game; no multiple slots, no rewind, no cheats,
  no netplay, no shaders/filters.
- Only one controller (port 1). No multitap, no light guns, no rumble.
- No core-options UI: cores run at their defaults, except the bridge forces
  the DuckStation engine's renderer to Software (its option key is
  `swanstation_GPU_Renderer`, verified in the core's own options source).
- `.bin` without its `.cue` plays but CD audio tracks may be missing — keep
  the `.cue` next to the `.bin`.
- Performance depends on the Fire TV model. PCSX-ReARMed is the lightest
  accurate-ish PS1 core on ARM, but a weak stick may still drop frames.
- Memory-card flush is periodic (every ~5 s) plus on menu/pause/quit — a hard
  crash could lose the last few seconds of card writes.
- Screen mode and FPS toggle apply at game start.

## Project layout

```
aeonps1-firetv/
  src/            <- git repo (this folder)
    app/          <- Android app module (Kotlin + JNI bridge + cores in jniLibs)
    build.gradle, settings.gradle, gradle.properties
    README.md, SETUP.md
  signing/        <- OUTSIDE the repo: aeonps1.keystore + keystore.properties
  output/         <- release APK lands here
```

## Building

Build only via the sandbox wrapper (sets JDK 17, proxy, CA):

```
~/workspace/aeonradio/tools/gradle-build.sh -p ~/workspace/aeonps1-firetv/src assembleRelease
```

Copy the APK to `output/` afterwards. Requires the Android NDK r26d
(`~/workspace/aeonphoto/android-sdk/ndk/26.3.11579264`).

## Verification (this release)

- APK: `output/aeonps1-v1.1.0.apk` — size/sha256 filled in at build time below.
  (v1.0.0 archive: `output/aeonps1-v1.0.0.apk` — 4,142,810 bytes,
  sha256 `a2d90290abc22ba8d085acf70ea77bb06d035c0a6daa17b0d13abe377b729570`.)
- `aapt dump badging`: package `com.aeonps1.tv`, versionCode 2, versionName
  1.1.0, sdkVersion 24 / targetSdkVersion 35, LEANBACK_LAUNCHER, label AeonPS1.
- `aapt dump permissions`: includes `MANAGE_EXTERNAL_STORAGE` +
  `READ_EXTERNAL_STORAGE`.
- `apksigner verify`: passes. Signer cert SHA-256 MUST equal v1.0.0's
  `21:03:00:59:67:FA:01:48:C8:2D:FF:35:A3:9C:62:A8:6C:A9:C9:19:8B:03:22:D9:0D:FD:43:C0:25:47:1E:8F`
  (same permanent AeonPS1 key, so v1.1.0 installs over v1.0.0).
- `apksigner verify`: passes. Signer cert SHA-256
  `21:03:00:59:67:FA:01:48:C8:2D:FF:35:A3:9C:62:A8:6C:A9:C9:19:8B:03:22:D9:0D:FD:43:C0:25:47:1E:8F`
  (permanent AeonPS1 key, RSA-4096, valid to 2056).
- APK contains `lib/arm64-v8a/libpcsx_rearmed_libretro_android.so` (1,585,704 B),
  `lib/armeabi-v7a/libpcsx_rearmed_libretro_android.so` (1,229,152 B), and both
  `libaeonps1_bridge.so` builds (18,256 / 14,676 B); the bridge exports all 15
  `Java_com_aeonps1_tv_EmulatorBridge_*` JNI symbols; the core exports all
  `retro_*` entry points.
- NOT device-tested (no Fire TV / ARM device in this sandbox).

## If the app ever won't open (v1.2.1+)

A full Fire TV can refuse to open apps at all — and extracted games live on
the TV's internal storage, so that is the usual suspect. v1.2.1:

- **Rescue mode**: if the app dies twice during startup (or storage is
  critically low) it opens a "Storage & rescue" screen instead: free space,
  what the extracted games use, how many library folders are watched, plus
  big buttons to *try opening normally* (skips the folder scan for one run),
  *forget the library list* (clears the list only — deletes no files), and
  *delete an extracted game* (frees space; the original archive is untouched).
  It's also reachable any time from Settings → "Storage & rescue".
- **Startup is crash-proof**: every preferences read is guarded (a corrupt
  key resets itself instead of killing launch), and the library scan runs on
  a background thread with per-folder error isolation and a 20,000-file
  visit cap.
- **Extraction keeps a reserve**: unpacking only starts if at least
  max(250 MB, 5% of the disk) would still be free afterwards; below 300 MB
  free the library shows a one-time warning card.
- A game that won't start now leaves a dialog on screen with the file name,
  full path and size instead of a 2-second toast.
