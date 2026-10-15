# AeonPS1 — PlayStation 1 Emulator for Fire TV

**AeonPS1 1.0.0** · package `com.aeonps1.tv` · minSdk 24 · target/compile SDK 35

A PlayStation 1 emulator for Amazon Fire TV, built the same sovereign way as
AeonTV: a TV-first Kotlin frontend written for this project, running the real
open-source **PCSX-ReARMed** libretro core (GPLv3, ARM-optimized — the standard
PS1 core used by RetroArch) through a native JNI bridge written in C.

No BIOS and no games are bundled. You supply your own game files (that you own)
and, optionally, your own BIOS file.

## What it does (exact)

- **Game library**: big D-pad-friendly grid of your PS1 games. Scans the app's
  private games folder plus `/storage/emulated/0/AeonPS1/games` when readable.
  Recognizes `.cue` `.bin` `.iso` `.pbp` `.chd` (a `.bin` is hidden when its
  `.cue`/`.chd`/`.pbp`/`.iso` sibling exists, so multi-track games list once).
- **Add Games**: system file picker copies your game files into the app's
  private storage (works from USB drives, no permissions needed).
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

## Honest limits (v1.0)

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

- APK: `output/aeonps1-release.apk` — 4,142,810 bytes,
  sha256 `a2d90290abc22ba8d085acf70ea77bb06d035c0a6daa17b0d13abe377b729570`.
- `aapt dump badging`: package `com.aeonps1.tv`, versionCode 1, versionName
  1.0.0, sdkVersion 24 / targetSdkVersion 35, LEANBACK_LAUNCHER, label AeonPS1.
- `apksigner verify`: passes. Signer cert SHA-256
  `21:03:00:59:67:FA:01:48:C8:2D:FF:35:A3:9C:62:A8:6C:A9:C9:19:8B:03:22:D9:0D:FD:43:C0:25:47:1E:8F`
  (permanent AeonPS1 key, RSA-4096, valid to 2056).
- APK contains `lib/arm64-v8a/libpcsx_rearmed_libretro_android.so` (1,585,704 B),
  `lib/armeabi-v7a/libpcsx_rearmed_libretro_android.so` (1,229,152 B), and both
  `libaeonps1_bridge.so` builds (18,256 / 14,676 B); the bridge exports all 15
  `Java_com_aeonps1_tv_EmulatorBridge_*` JNI symbols; the core exports all
  `retro_*` entry points.
- NOT device-tested (no Fire TV / ARM device in this sandbox).
