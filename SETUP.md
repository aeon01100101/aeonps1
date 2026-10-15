# AeonPS1 Setup — sideload on Fire TV

## 1. Install the APK

1. On your Fire TV, install a file manager that can sideload APKs (e.g. **Downloader**
   by AFTVnews) and allow "Install unknown apps" for it when asked.
2. Copy `aeonps1-release.apk` to a USB stick (or send it to the TV with
   "Send Files to TV" / adb).
3. Open the APK on the TV and install. It appears as **AeonPS1** in your apps.

> First install only: if you ever had a differently-signed AeonPS1 build, uninstall
> it first. This release is signed with the permanent AeonPS1 key, so future
> updates will install right over it.

## 2. Add your games

You must own the games you play. AeonPS1 ships with no games and no BIOS.

1. Open AeonPS1 → press **Add Games**.
2. Pick your PS1 game files from USB storage or anywhere the picker can see:
   `.cue` `.bin` `.iso` `.pbp` `.chd`.
   - Tip: keep the `.cue` next to its `.bin` so CD audio works.
3. Wait for the copy to finish. Your games appear in the library.

(Advanced: you can also drop files into `/storage/emulated/0/AeonPS1/games`
with adb or a file manager; the library scans that folder on Fire OS 7.)

## 3. Optional: add a real BIOS

Most games run without one (built-in HLE). If a game glitches, a real BIOS
from a PlayStation you own can help:

1. Open AeonPS1 → **BIOS**. The screen shows the folder and what's detected.
2. Press **Import BIOS file** and pick your `scph1001.bin` (or similar).

## 4. Play

- Pick a game with the remote or a gamepad. D-pad/left stick move, A = Cross,
  B = Circle, X = Square, Y = Triangle, Start/Select as labeled.
- **Menu button** (or Back, or Start+Select) opens the game menu:
  Save State / Load State / Reset / Quit.
- Memory cards save automatically — no extra step.

## Controls table

| Your button        | PlayStation button |
|--------------------|--------------------|
| A / OK             | Cross              |
| B                  | Circle             |
| X                  | Square             |
| Y                  | Triangle           |
| D-pad / left stick | D-pad              |
| Start              | Start              |
| Select             | Select             |
| L1 / R1            | L1 / R1            |
| L2 / R2            | L2 / R2            |
| Menu / Back / Start+Select | Game menu   |

## If something goes wrong

- **Black screen on game start**: the file may be a bad dump; try the `.cue`
  version or re-copy the files.
- **No sound / stutter**: weaker Fire TV sticks can struggle; close other apps.
- **Game not listed**: make sure the extension is one of the supported ones and
  the copy finished.
