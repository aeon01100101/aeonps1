# AeonPS1 Bench Report — v1.3.1

**Date:** 2026-10-15/16 (UTC)
**Purpose:** Stop shipping paper-verified builds. This rig runs the ACTUAL
`aeonps1_bridge.c` frontend code shipped in the APK (compiled with `-DAEON_BENCH`
plus host JNI/Android stub headers — same translation unit, same callbacks,
same load/run lifecycle the Android app calls) against real libretro cores on
the host, and proves frames/audio flow end-to-end.

**Bench location (outside git):** `~/workspace/aeonps1-firetv/bench/`
- `bench_run` — harness binary (gcc). Usage:
  `bench_run <core.so> <frames> <system_dir> <save_dir> <out_dir> [game|-]`
- `src/bench_main.c` — harness; drives `nativeInit`/`nativeLoadGame`/`nativeRunFrame`/`nativeShutdown`,
  dumps PPM frames at intervals, drains audio ring and measures RMS.
- `src/analyze.py` — frame stats (non-black %, distinct colors, edge density).
- `src/make_exe.py` — hand-assembled minimal PS-X EXE (GPU init + colored rects).
- `src/include/` — host stubs for `jni.h`, `android/log.h`, `android/native_window*.h`.
- `results/` — PPM dumps + run logs per experiment.

## Cores used (Linux x86_64, buildbot nightly)

| Core | URL | Size | SHA-256 |
|------|-----|------|---------|
| pcsx_rearmed_libretro.so | https://buildbot.libretro.com/nightly/linux/x86_64/latest/pcsx_rearmed_libretro.so.zip | 2,176,456 B | `6cc5cea2…` |
| swanstation_libretro.so | https://buildbot.libretro.com/nightly/linux/x86_64/latest/swanstation_libretro.so.zip | 4,902,416 B | `affbd1c…` |

Both HTTP 200, no 404s, filenames as documented.

## OpenBIOS provenance (TEST-ONLY)

- Source: `https://psx.arthus.net/roms/bios/openbios.bin`
  (community mirror of PCSX-Redux's MIT-licensed OpenBIOS).
- 524,288 B (512 KiB — correct PS1 BIOS size), SHA-256 `38c1c97a…`.
- **This binary turned out to be BAD for SwanStation**: it lacks the `OpenBIOS`
  magic at offset `0x78` that SwanStation's BIOS detector requires, and when
  placed as `scph5501.bin` it made the core boot into a zombie state
  (system "running" but zero video frames, silent audio — see below).
  With the file removed, SwanStation falls back to its **built-in OpenBIOS**
  and runs correctly.
- **NEVER in the app/repo/APK.** The app requires the user to supply their own
  real BIOS (scph5500/5501/5502.bin, psxonpsp660.bin, or ps1_rom.bin).

## Per-core results

### SwanStation (DuckStation engine) — PROVEN WORKING

- Init: OK. 113 core options registered, renderer forced to Software,
  pixel format negotiated **RGB565**.
- Load: `retro_load_game(NULL)` → OK (BIOS boot).
- Run: **900 frames** → `video_refresh=900`, **dup=3** (only the first 3),
  `audio_batches=940`, `audio_frames=663,497`, **audio RMS 1710.7 (REAL, not silence)**.
- Geometry: single stable `640x478` (OpenBIOS boot animation).
- Frames: 6/6 PPMs dumped, 100% non-black, 3–4 distinct colors, animated
  (colors shift between dumps — the OpenBIOS logo animation renders).
- 3600-frame soak: identical behavior, no degradation.
- **Verdict: the DuckStation engine runs end-to-end through our bridge —
  real video frames and real audio.**

### PCSX-ReARMed — PROVEN WORKING (with core-side color quirk)

- Init: OK. 54 core options registered, pixel format negotiated **RGB565**.
- `retro_load_game(NULL)` correctly **refused** by the core (`info->path required`);
  our bridge now guards NULL paths. Invalid disc images correctly rejected
  (`unsupported/invalid CD image`).
- Load: hand-built minimal PS-X EXE (`content/hello.exe`, 114 MIPS instructions:
  GPU init 320x240, blue fill, 8 white bars) → **load OK**.
- Run: **900 frames** → `video_refresh=900`, **dup=0** — every frame real.
- Geometry **switches mid-run handled**: `640x480 → 316x240`, ring-logged,
  frames kept flowing (this is the #1 suspect path for "game works, text doesn't").
- Frames: 6/6 PPMs, 12.66% non-black, stable across the run.
- **Quirk (core-side, NOT a bridge bug): R/B channels are swapped** in the
  x86_64 buildbot core's output (filled pure red → frame shows blue; green is
  unaffected). The bridge's RGB565→ARGB8888 conversion is textbook-correct
  per the libretro spec; the core's `bgr555_to_rgb565` blit on x86_64 appears
  to emit BGR. The Android ARM build uses the NEON blit path — may not apply
  there. Noted, not "fixed" (not our code).

## Bugs found by the rig + fixes (all in `app/src/main/jni/aeonps1_bridge.c`)

1. **`RETRO_ENVIRONMENT_GET_AUDIO_VIDEO_ENABLE` refused every frame.**
   SwanStation asks this once per `retro_run`; our refusal spammed the ring log
   900×/run. Now answered: `*(int*)data = VIDEO|AUDIO; return true;`
2. **`RETRO_ENVIRONMENT_SET_CORE_OPTIONS_DISPLAY` refused.** Now accepted (no-op).
3. **`RETRO_ENVIRONMENT_SET_SUPPORT_NO_GAME` refused.** Now returns true
   (we support BIOS-shell boot with no content).
4. **`RETRO_ENVIRONMENT_GET_DISK_CONTROL_INTERFACE_VERSION` refused.**
   Now returns 1 (we provide the v1 ext disk interface).
5. **Missing diagnostics:** the ring log now records the negotiated pixel format
   (`core pixel format: RGB565`) and every geometry change
   (`core set geometry: …` / `video geometry now …`) so the TV error dialog can
   show them later.
6. Bench-only instrumentation (`#ifdef AEON_BENCH`): geometry-attempt history
   (incl. dropped oversize frames), video/dup/audio counters, `aeon_bench_*`
   accessors, NULL-game-path guard in `nativeLoadGame`. **Zero effect on the
   Android build** (all guarded out; Android behavior surface unchanged).

The Android release build compiles warning-clean with these changes
(`gradle-build.sh assembleRelease` — BUILD SUCCESSFUL).

## What remains unproven (honest gaps)

- **A real game disc image on real hardware** — only Isaiah's Fire TV settles
  this. The rig proves the frontend drives both cores correctly; it cannot prove
  a retail game's compatibility, controller feel, or performance on the stick.
- **SwanStation + real user BIOS** — the rig used the core's built-in OpenBIOS
  (the downloaded OpenBIOS binary was bad). A real scph5501.bin on the TV is
  the true test.
- **PCSX color quirk on ARM** — unknown whether the R/B swap exists in the
  Android ARM build; PCSX is the fallback engine, SwanStation is the default.
- **Performance** — software rendering on a Fire TV stick CPU is the known
  tradeoff; frame pacing on device is unmeasured.

## v1.3.1 release

- versionCode 6, versionName "1.3.1".
- APK: `output/aeonps1-v1.3.1.apk` (15,282,688 B,
  SHA-256 `e74e25665b757cb565c54238a160c4b8941752725b8ae1a47a8739cc4c987b94`),
  cert `21030059…` (identical — installs over v1.3.0).
- `aapt`: package `com.aeonps1.tv` vc6/1.3.1, sdkVersion 24, targetSdk 35.
- `unzip -l`: both cores × both ABIs, lib-prefixed names.
- No BIOS, no games, no OpenBIOS in the APK (secrets audit: signing stays outside the repo).

---

# AeonPS1 Bench Report — v1.3.2 (.bin crash + lockout fix)

## Test disc images (all content homebrew/own-authored, legal)

Authored in Python (`bench/src/make_exe.py`, `bench/src/make_disc.py`):
hand-built PS-X EXE (hello.exe, 2504 B) inside a proper Mode 2 Form 1
(2352-byte sector) image: 12 B sync, 3 B MSF-BCD, mode 0x02, XA subheaders,
2048 B data, 4 B EDC (ECMA-130 CRC), 276 B ECC, ISO9660 PVD@16 (vol id
`AEONPS1`), SYSTEM.CNF (`BOOT = cdrom:\HELLO.EXE;1`), padded to 128 sectors
(301,056 B). Test set in `bench/content/disc_test/`:

- `game.bin` + `game.cue` — valid bootable MODE2/2352 image
- `bare.bin` — same bytes, no cue
- `bare_gen.cue` — generated single-track cue (same-dir, relative path)
- `gencue/bare_abspath.cue` — generated cue in a *separate* dir with an
  *absolute* FILE path (exactly what the app's v1.3.2 code writes)
- `random.bin` — 10 MB random bytes
- `trunc.bin` — valid image cut at 37% (111,390 B)
- `bioslike.bin` — 524,288 random bytes (real-BIOS size)
- `tiny.bin` — 4,096 random bytes
- `game_m1.bin` + `.cue` — MODE1/2048 variant; `trunc_m1.bin`
- `nocnf.bin` + `.cue` — valid MODE2 image, no SYSTEM.CNF
- `missingexe.bin` + `.cue` — SYSTEM.CNF points to MISSING.EXE;1

## .bin crash matrix (actual bridge, both cores, separate process per case)

| case | PCSX-ReARMed | SwanStation |
|---|---|---|
| valid cue+bin (MODE2/2352) | **SEGFAULT in loader** (exit 139) | OK, no crash (shell) |
| bare .bin (no cue) | LOAD-FAILED clean (exit 1; core guessed 2048 sectors, refused) | OK, no crash (shell) |
| bare .bin via generated cue | (not run — app never generates for PCSX) | OK, no crash (shell) |
| abs-path cue in separate dir | — | OK, no crash (shell) |
| random 10 MB | LOAD-FAILED clean | OK, no crash (shell) |
| trunc 37% (bare) | **SEGFAULT** (exit 139; core guessed 2352, parsed SYSTEM.CNF, crashed) | OK, no crash (shell) |
| bioslike 512 KiB | LOAD-FAILED clean | OK, no crash (shell) |
| tiny 4 KiB | LOAD-FAILED clean | OK, no crash (shell) |
| MODE1/2048 valid/trunc | LOAD-FAILED clean | OK, no crash (shell) |
| no SYSTEM.CNF | LOAD-FAILED clean | OK, no crash (shell) |
| SYSTEM.CNF → missing EXE | LOAD-FAILED clean | OK, no crash (shell) |

**Crash condition (refined):** PCSX-ReARMed segfaults when its HLE CD-boot
chain successfully parses a MODE2/2352 image's ISO9660 + SYSTEM.CNF and
proceeds to load/execute the disc program — with a cue OR a bare .bin (the
core's sector-size autodetect guesses right on trunc.bin). The crash is
inside the core binary; the frontend cannot fix it, only avoid feeding it
known-bad input. SwanStation never crashed on any input (worst case: its
built-in OpenBIOS shell).

**Re-run after fix:** matrix re-run against the fixed bridge (atomic .srm,
v1.3.2) — identical results, no regressions. .srm verified written via
.tmp + rename (128 KiB card present, zero .tmp leftovers). The app-side
validation (BIOS-sized/tiny/unknown-format refusal) lives in Kotlin and was
not bench-run; the bench covers the core-side cases.

## "Stays crashed" persistence audit (code-traced)

- **CONFIRMED (mechanism):** native segfault kills the process instantly; on
  relaunch from Recents, Android recreates the task's top activity —
  EmulatorActivity — with the same crashing .bin intent → instant re-crash.
  EmulatorActivity had no crash guard → crash loop → "stays crashed until
  reinstall" (reinstall clears the task stack). Fix: `emu_in_progress`
  sentinel (EmulatorActivity sets before the emu thread starts; clears on
  clean quit / clean load failure / ~8 s of successful play; next launch
  with it still set shows "AeonPS1 closed on this file before" + [Try again]
  / [Back to library] — information, never a lockout).
- **CONFIRMED (not the cause):** the rescue gate cannot be the lockout — its
  flags clear after the first scan render, long before any game tap; the gate
  only fires during MainActivity startup. Hardened anyway: crash-streak
  rescue auto-opens at most once per streak (`rescue_auto_shown`); after the
  user has seen it, the library opens in safe mode instead. Invariant: the
  rescue gate can never be the reason the app won't open twice in a row.
- **CONFIRMED (not a lockout, fixed anyway):** `.srm` writes were non-atomic
  (`fopen "wb"` direct) — a SIGSEGV mid-write left a truncated card (the
  load path already size-guards, so it was ignored, not fatal). Now
  `.tmp` + `rename()`.
- **PLAUSIBLE (self-healing):** prefs XML truncated by a mid-write SIGSEGV —
  the platform starts empty on parse failure and SafePrefs guards every
  read; already mitigated.

## What remains unproven (honest gaps, v1.3.2)

- The PCSX segfault was reproduced on Linux x86_64 only; whether it fires on
  ARM is unknown. The fixes (validation + sentinel) don't depend on it.
- SwanStation's built-in OpenBIOS degraded every test image to its shell —
  it never booted the homebrew disc (shell cube, not the hello.exe screen);
  real game + real BIOS on the TV is the true test.
- Retail-game boot, real Sony BIOS, TV performance: still only settleable on
  Isaiah's Fire TV.
