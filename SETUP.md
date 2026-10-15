# AeonPS1 Setup — sideload on Fire TV

## 1. Install the APK

1. On your Fire TV, install a file manager that can sideload APKs (e.g. **Downloader**
   by AFTVnews) and allow "Install unknown apps" for it when asked.
2. Copy `aeonps1-v1.2.0.apk` to a USB stick (or send it to the TV with
   "Send Files to TV" / adb).
3. Open the APK on the TV and install. It appears as **AeonPS1** in your apps.

> First install only: if you ever had a differently-signed AeonPS1 build, uninstall
> it first. This release is signed with the permanent AeonPS1 key, so future
> updates will install right over it.

## 2. Add your games

You must own the games you play. AeonPS1 ships with no games and no BIOS.
Games stay on your USB stick or internal storage — nothing is copied.

1. Open AeonPS1 → press **Browse Files**.
2. First time: press **Turn on storage access** when asked, so the explorer
   can read your USB stick and shared storage.
3. Pick a place: Internal storage, your USB / external storage, Downloads.
4. Find a folder with your PS1 games (`.cue` `.bin` `.iso` `.pbp` `.chd` —
   keep the `.cue` next to its `.bin` so CD audio works) and press
   **Use this folder** — its games appear in the library every time you open
   the app. Or open one game file and pick **Play now** to play it directly.
   The folder button is a toggle: press it again to take the folder out of
   the library. You can watch as many folders as you like at once.

### Games inside a RAR?

Tap the `.rar` (or `.zip`) in Browse Files and pick **Extract &amp; add games**:
it unpacks to the app's `Extracted/<name>/` folder and adds that folder to
your library by itself — the games show up right away.

- Multi-part `.rar` sets (`.part1.rar`, `.part2.rar`, …) work when all the
  parts sit in the same folder — just tap the first part.
- Modern RAR5 archives are supported; **password-protected archives are not**
  (the app will tell you plainly).
- Extraction goes to internal storage, so you need that much free space.
  The app checks first and warns you if there isn't enough.
- Picking a `.rar`/`.zip` through the old **Add Games** picker works too —
  it extracts the same way (the temporary copy is deleted afterwards).

(The old **Add Games** picker still works too: it copies game files into the
app's private storage, no permissions needed.)

## 3. Optional: add a real BIOS

Most games run without one (built-in HLE). If a game glitches, a real BIOS
from a PlayStation you own can help:

1. Open AeonPS1 → **BIOS**. The screen shows the folder and what's detected.
2. Press **Import BIOS file** and pick your `scph1001.bin` (or similar) —
   or **Browse for BIOS file** to find it with the built-in explorer.

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
