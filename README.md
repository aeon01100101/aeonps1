# AeonPS1 — PlayStation 1 Emulator for Fire TV

**AeonPS1 1.1.0** · package `com.aeonps1.tv` · minSdk 24 · target/compile SDK 35

A PlayStation 1 emulator for Amazon Fire TV, built the same sovereign way as
AeonTV: a TV-first Kotlin frontend written for this project, running the real
open-source **PCSX-ReARMed** libretro core (GPLv3, ARM-optimized — the standard
PS1 core used by RetroArch) through a native JNI bridge written in C.

No BIOS and no games are bundled. You supply your own game files (that you own)
and, optionally, your own BIOS file.

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
- PCSX-ReARMed is GPLv3. This app's own frontend/bridge code is yours; the
  core `.so` files ship inside the APK as required by the license terms —
  keep them with the app and point users at the PCSX-ReARMed sources.
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
- No core-options UI: the core runs at its defaults.
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
