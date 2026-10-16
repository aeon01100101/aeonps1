package com.aeonps1.tv

import android.app.Activity
import android.app.AlertDialog
import android.content.pm.ActivityInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import java.io.File

class EmulatorActivity : Activity() {

    companion object {
        const val EXTRA_PATH = "game_path"
        const val EXTRA_ENGINE = "engine_override"

        // libretro RETRO_DEVICE_ID_JOYPAD_* ids
        private const val ID_CROSS = 0
        private const val ID_CIRCLE = 1
        private const val ID_SELECT = 2
        private const val ID_START = 3
        private const val ID_UP = 4
        private const val ID_DOWN = 5
        private const val ID_LEFT = 6
        private const val ID_RIGHT = 7
        private const val ID_TRIANGLE = 8
        private const val ID_SQUARE = 9
        private const val ID_L1 = 10
        private const val ID_R1 = 11
        private const val ID_L2 = 12
        private const val ID_R2 = 13
        private const val ID_L3 = 14
        private const val ID_R3 = 15
    }

    private var engineOverride: String? = null
    private fun effectiveEngine(): String = engineOverride ?: enginePref(this)
    private fun effectiveCoreFile(): String =
        if (effectiveEngine() == ENGINE_PCSX) CORE_PCSX else CORE_DUCKSTATION

    @Volatile private var running = false
    @Volatile private var paused = false
    @Volatile private var menuOpen = false
    @Volatile private var buttonMask = 0

    private lateinit var gamePath: String
    /** The path actually handed to the core: usually gamePath, but a
     *  generated single-track cue for a bare .bin (v1.3.2). */
    private lateinit var loadPath: String
    private lateinit var savesDir: File
    private lateinit var fpsView: TextView

    private var emuThread: Thread? = null
    private var audioThread: Thread? = null
    private var audioTrack: AudioTrack? = null

    private fun prefs() = getSharedPreferences("aeonps1", MODE_PRIVATE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        setContentView(R.layout.activity_emulator)

        gamePath = intent.getStringExtra(EXTRA_PATH) ?: run { finish(); return }
        savesDir = File(filesDir, "saves").apply { mkdirs() }
        File(filesDir, "bios").mkdirs()
        // Session override (e.g. "Use PCSX this time") beats the stored engine.
        intent.getStringExtra(EXTRA_ENGINE)?.let { engineOverride = it }

        fpsView = findViewById(R.id.fps_view)
        if (prefs().getBoolean("show_fps", false)) fpsView.visibility = android.view.View.VISIBLE

        val surface = findViewById<SurfaceView>(R.id.game_surface)
        surface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) {
                EmulatorBridge.nativeSetSurface(h.surface)
            }
            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, h2: Int) {}
            override fun surfaceDestroyed(h: SurfaceHolder) {
                EmulatorBridge.nativeSetSurface(null)
            }
        })

        // The DuckStation-family engine has no built-in BIOS replacement:
        // v1.3.2: crash sentinel first (never blindly reload a killer file),
        // then pre-load validation (refuse known-bad input with a plain
        // dialog), then the BIOS check / emulation start.
        if (!checkCrashSentinel()) {
            validateAndBegin()
        }
    }

    /**
     * v1.3.2 crash sentinel. A native segfault kills the process instantly,
     * so nothing could clear KEY_EMU_IN_PROGRESS — if it is still set, the
     * last session died inside emulation. Relaunching (e.g. from Recents,
     * which recreates this activity with the same game) must not blindly
     * reload the same killer file. Returns true when a dialog took over
     * and the caller must stop; false to proceed.
     */
    private fun checkCrashSentinel(): Boolean {
        val crashedPath =
            SafePrefs.getString(prefs(), RescuePrefs.KEY_EMU_IN_PROGRESS, "")
        if (crashedPath.isEmpty()) return false
        if (crashedPath == gamePath) {
            showCrashedBeforeDialog()
            return true
        }
        // Died on a different file last time: one-time notice, then clear.
        try {
            Toast.makeText(
                this,
                getString(R.string.emu_crashed_notice, File(crashedPath).name),
                Toast.LENGTH_LONG
            ).show()
        } catch (e: Exception) { }
        SafePrefs.remove(prefs(), RescuePrefs.KEY_EMU_IN_PROGRESS)
        return false
    }

    private fun showCrashedBeforeDialog() {
        runOnUiThread {
            try {
                AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog)
                    .setTitle(getString(R.string.crashed_before_title))
                    .setMessage(
                        getString(R.string.crashed_before_body, File(gamePath).name)
                    )
                    .setPositiveButton(getString(R.string.crashed_before_retry)) { _, _ ->
                        // The sentinel stays set: if it crashes again, this
                        // dialog shows again. Never a lockout — the choice
                        // is always the user's.
                        validateAndBegin()
                    }
                    .setNegativeButton(getString(R.string.crashed_before_back)) { _, _ ->
                        finish()
                    }
                    .setCancelable(false)
                    .show()
            } catch (e: Exception) {
                finish()
            }
        }
    }

    /**
     * Runs pre-load validation; on success begins emulation. Returns false
     * when a dialog took over (validation failure or BIOS notice) and the
     * caller must stop.
     */
    private fun validateAndBegin(): Boolean {
        val path = validatedLoadPath() ?: return false
        loadPath = path
        beginEmulation()
        return true
    }

    private fun beginEmulation() {
        if (effectiveEngine() != ENGINE_PCSX && !hasDuckStationBios(this)) {
            askAboutBios()
        } else {
            startEmulation()
        }
    }

    /**
     * v1.3.2 pre-load validation. Cheap checks on the file before the core
     * ever sees it: bench-proven crash inputs (BIOS-sized .bin, unknown
     * bare-.bin format, cue pointing at missing files) are refused with a
     * plain dialog. Returns the path to hand to the core, or null after
     * showing a dialog. Never crashes, never writes state (a generated cue
     * goes to the cache dir and is not state).
     */
    private fun validatedLoadPath(): String? {
        val file = try {
            File(gamePath)
        } catch (e: Exception) {
            null
        }
        if (file == null || !file.isFile || !file.canRead()) {
            showBadFile(getString(R.string.badfile_missing))
            return null
        }
        val ext = file.extension.lowercase(java.util.Locale.US)
        if (ext == "cue") {
            // The cue sheet itself is tiny; validate what it points to.
            val missing = cueMissingRef(file)
            if (missing != null) {
                showBadFile(getString(R.string.badfile_cue_broken, missing))
                return null
            }
            return gamePath
        }
        val size = try {
            file.length()
        } catch (e: Exception) {
            -1L
        }
        if (size < 32 * 1024L) {
            val sizeText = try {
                RescuePrefs.humanSize(size.coerceAtLeast(0))
            } catch (e: Exception) {
                "0 B"
            }
            showBadFile(getString(R.string.badfile_too_small, sizeText))
            return null
        }
        if (ext == "bin" && size == 524288L) {
            // Exactly the size of a real PS1 BIOS: it is a BIOS, not a game.
            // Handing it to the core as a disc is a bench-proven crash on
            // the PCSX path; refuse with directions instead.
            showBiosNotGame()
            return null
        }
        if (ext == "bin" && !hasSiblingCue(file)) {
            val mode = when {
                size % 2352L == 0L -> "MODE2/2352"
                size % 2048L == 0L -> "MODE1/2048"
                else -> null
            }
            if (mode == null) {
                showBadFile(getString(R.string.badfile_unknown_format))
                return null
            }
            if (effectiveEngine() == ENGINE_PCSX) {
                // Bench-proven: PCSX refuses a bare .bin cleanly, but
                // segfaults on a cue-backed MODE2 image it tries to boot.
                // Never generate a cue for PCSX — hand it the bare file.
                return gamePath
            }
            // What mature frontends do for bare bins: a temporary
            // single-track cue in the cache dir, so the core takes its
            // well-trodden cue path. Falls back to the bare file if the
            // cache write fails.
            return generatedCueFor(file, mode) ?: gamePath
        }
        return gamePath
    }

    private fun hasSiblingCue(bin: File): Boolean {
        return try {
            val base = bin.nameWithoutExtension
            bin.parentFile?.listFiles()?.any { f ->
                f.isFile &&
                    f.nameWithoutExtension.equals(base, ignoreCase = true) &&
                    f.extension.equals("cue", ignoreCase = true)
            } ?: false
        } catch (e: Exception) {
            false
        }
    }

    /** A temporary single-track cue for a bare .bin, in the cache dir. */
    private fun generatedCueFor(bin: File, mode: String): String? {
        return try {
            val dir = File(cacheDir, "gencue").apply { mkdirs() }
            val tag = (bin.absolutePath + "|" + bin.length())
                .hashCode().toUInt().toString(16)
            val cue = File(dir, bin.nameWithoutExtension + "_" + tag + ".cue")
            cue.writeText(
                "FILE \"" + bin.absolutePath + "\" BINARY\n" +
                    "  TRACK 01 " + mode + "\n" +
                    "    INDEX 01 00:00:00\n"
            )
            cue.absolutePath
        } catch (e: Exception) {
            null
        }
    }

    /** Returns the first missing/empty file a .cue references, else null. */
    private fun cueMissingRef(cue: File): String? {
        return try {
            val dir = cue.parentFile ?: return null
            for (raw in cue.readLines()) {
                val line = raw.trim()
                if (!line.startsWith("FILE", ignoreCase = true)) continue
                // FILE "name.bin" BINARY   or   FILE name.bin BINARY
                val rest = line.substring(4).trim()
                val name = if (rest.startsWith("\"")) {
                    rest.substring(1).substringBefore("\"")
                } else {
                    rest.substringBefore(" ").trim()
                }
                if (name.isEmpty()) continue
                val ref = File(name)
                val target = if (ref.isAbsolute) ref else File(dir, name)
                if (!target.isFile || target.length() == 0L) return name
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun showBadFile(message: String) {
        runOnUiThread {
            try {
                AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog)
                    .setTitle(getString(R.string.badfile_title))
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok) { _, _ -> finish() }
                    .setCancelable(false)
                    .show()
            } catch (e: Exception) {
                finish()
            }
        }
    }

    private fun showBiosNotGame() {
        runOnUiThread {
            try {
                AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog)
                    .setTitle(getString(R.string.badfile_bios_title))
                    .setMessage(getString(R.string.badfile_bios_body))
                    .setPositiveButton(getString(R.string.open_bios_screen)) { _, _ ->
                        startActivity(
                            android.content.Intent(this, BiosActivity::class.java)
                        )
                        finish()
                    }
                    .setNeutralButton(getString(R.string.badfile_bios_load_anyway)) { _, _ ->
                        // The user's call: they say it is a game. The crash
                        // sentinel still guards a crash loop.
                        loadPath = gamePath
                        beginEmulation()
                    }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
                    .setCancelable(false)
                    .show()
            } catch (e: Exception) {
                finish()
            }
        }
    }

    private fun askAboutBios() {
        runOnUiThread {
            try {
                AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog)
                    .setTitle(getString(R.string.bios_needed_title))
                    .setMessage(getString(R.string.bios_needed_body))
                    .setPositiveButton(getString(R.string.open_bios_screen)) { _, _ ->
                        startActivity(android.content.Intent(this, BiosActivity::class.java))
                        finish()
                    }
                    .setNeutralButton(getString(R.string.use_pcsx_this_time)) { _, _ ->
                        engineOverride = ENGINE_PCSX
                        startEmulation()
                    }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
                    .setCancelable(false)
                    .show()
            } catch (e: Exception) {
                finish()
            }
        }
    }

    private fun stateFile(): File {
        val base = File(gamePath).nameWithoutExtension
        return File(savesDir, "$base.state")
    }

    private fun startEmulation() {
        running = true
        // v1.3.2 crash sentinel: set BEFORE any native call. A native
        // segfault kills the process instantly, so nothing after this can
        // clear it on a crash — the next launch sees it and warns instead
        // of blindly reloading the same killer file.
        try {
            prefs().edit()
                .putString(RescuePrefs.KEY_EMU_IN_PROGRESS, gamePath)
                .apply()
        } catch (e: Exception) { }
        emuThread = Thread({
            val corePath = applicationInfo.nativeLibraryDir +
                "/" + effectiveCoreFile()
            val biosDir = File(filesDir, "bios").absolutePath
            if (!EmulatorBridge.nativeInit(corePath, biosDir, savesDir.absolutePath)) {
                fail(getString(R.string.load_step_core)); return@Thread
            }
            EmulatorBridge.nativeSetScreenMode(prefs().getInt("screen_mode", 0))
            if (!EmulatorBridge.nativeLoadGame(loadPath)) {
                fail(getString(R.string.load_step_game)); return@Thread
            }
            // The game loaded and the core is alive: once it has run a few
            // seconds, this session is proven — clear the sentinel.
            Thread {
                try {
                    Thread.sleep(8000)
                } catch (e: InterruptedException) { }
                if (running) SafePrefs.remove(prefs(), RescuePrefs.KEY_EMU_IN_PROGRESS)
            }.apply { isDaemon = true; start() }
            val targetFps = EmulatorBridge.nativeGetTargetFps().coerceIn(1f, 120f)
            val frameNs = (1_000_000_000L / targetFps).toLong()
            var uiTick = 0L
            while (running) {
                if (paused || menuOpen) {
                    Thread.sleep(50); continue
                }
                val t0 = System.nanoTime()
                EmulatorBridge.nativeRunFrame()
                val dt = System.nanoTime() - t0
                val sleepMs = (frameNs - dt) / 1_000_000L
                if (sleepMs > 2) Thread.sleep(sleepMs)
                if (prefs().getBoolean("show_fps", false) &&
                    System.currentTimeMillis() - uiTick > 500
                ) {
                    uiTick = System.currentTimeMillis()
                    val fps = EmulatorBridge.nativeGetFps()
                    runOnUiThread { fpsView.text = String.format("%.0f fps", fps) }
                }
            }
            EmulatorBridge.nativeShutdown()
        }, "AeonPS1-Emu").apply { start() }

        audioThread = Thread({
            val rate = 44100
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setBufferSizeInBytes(rate * 4)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            audioTrack = track
            track.play()
            val buf = ShortArray(4096)
            while (running) {
                val n = try {
                    EmulatorBridge.nativeGetAudioSamples(buf, buf.size)
                } catch (e: UnsatisfiedLinkError) { 0 }
                if (n > 0) track.write(buf, 0, n) else Thread.sleep(5)
            }
            try { track.stop() } catch (e: Exception) { }
            track.release()
        }, "AeonPS1-Audio").apply { start() }
    }

    private fun fail(step: String) {
        running = false
        // A clean load failure is not a crash: clear the sentinel so the
        // next launch doesn't warn about a file that merely refused.
        SafePrefs.remove(prefs(), RescuePrefs.KEY_EMU_IN_PROGRESS)
        // Stay on screen with the real details instead of a 2-second toast:
        // the next refusal diagnoses itself.
        val file = try {
            File(gamePath)
        } catch (e: Exception) {
            null
        }
        val size = if (file != null && file.isFile) {
            try {
                RescuePrefs.humanSize(file.length())
            } catch (e: Exception) {
                "unknown"
            }
        } else "unknown"
        val logTail = try {
            EmulatorBridge.nativeGetLogTail().ifBlank { "(no engine log)" }
        } catch (e: Exception) {
            "(engine log unavailable: ${e.message})"
        }
        val coreMsg = try {
            EmulatorBridge.nativeGetCoreMessage().ifBlank { null }
        } catch (e: Exception) {
            null
        }
        val details = buildString {
            if (coreMsg != null) append("Core said: ").append(coreMsg).append("\n\n")
            append(logTail)
        }.take(3000)
        runOnUiThread {
            try {
                AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog)
                    .setTitle(getString(R.string.game_wont_start))
                    .setMessage(
                        getString(
                            R.string.game_wont_start_body,
                            step,
                            file?.name ?: gamePath,
                            gamePath,
                            size,
                            details
                        )
                    )
                    .setPositiveButton(android.R.string.ok) { _, _ -> finish() }
                    .setCancelable(false)
                    .show()
            } catch (e: Exception) {
                finish()
            }
        }
    }

    // ---------------- input ----------------

    private fun keyToId(keyCode: Int): Int = when (keyCode) {
        KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER -> ID_CROSS
        KeyEvent.KEYCODE_BUTTON_B -> ID_CIRCLE
        KeyEvent.KEYCODE_BUTTON_X -> ID_SQUARE
        KeyEvent.KEYCODE_BUTTON_Y -> ID_TRIANGLE
        KeyEvent.KEYCODE_DPAD_UP -> ID_UP
        KeyEvent.KEYCODE_DPAD_DOWN -> ID_DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> ID_LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> ID_RIGHT
        KeyEvent.KEYCODE_BUTTON_START -> ID_START
        KeyEvent.KEYCODE_BUTTON_SELECT -> ID_SELECT
        KeyEvent.KEYCODE_BUTTON_L1 -> ID_L1
        KeyEvent.KEYCODE_BUTTON_R1 -> ID_R1
        KeyEvent.KEYCODE_BUTTON_L2 -> ID_L2
        KeyEvent.KEYCODE_BUTTON_R2 -> ID_R2
        KeyEvent.KEYCODE_BUTTON_THUMBL -> ID_L3
        KeyEvent.KEYCODE_BUTTON_THUMBR -> ID_R3
        else -> -1
    }

    private fun setBit(id: Int, down: Boolean) {
        buttonMask = if (down) buttonMask or (1 shl id) else buttonMask and (1 shl id).inv()
        EmulatorBridge.nativeSetButtons(0, buttonMask)
        // Start + Select together opens the game menu.
        if (down && (buttonMask and ((1 shl ID_START) or (1 shl ID_SELECT))) ==
            ((1 shl ID_START) or (1 shl ID_SELECT))
        ) {
            openMenu()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) { openMenu(); return true }
        val id = keyToId(keyCode)
        if (id >= 0) {
            if (event.repeatCount == 0) setBit(id, true)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val id = keyToId(keyCode)
        if (id >= 0) { setBit(id, false); return true }
        return super.onKeyUp(keyCode, event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_JOYSTICK) &&
            event.action == MotionEvent.ACTION_MOVE
        ) {
            val x = event.getAxisValue(MotionEvent.AXIS_X)
            val y = event.getAxisValue(MotionEvent.AXIS_Y)
            EmulatorBridge.nativeSetAnalog(0, (x * 32767).toInt(), (y * 32767).toInt())
            // Push the d-pad too when the stick is tilted hard: many PS1 games are digital-only.
            setBit(ID_LEFT, x < -0.5f); setBit(ID_RIGHT, x > 0.5f)
            setBit(ID_UP, y < -0.5f); setBit(ID_DOWN, y > 0.5f)
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    override fun onBackPressed() {
        openMenu() // Back opens the game menu; quitting is a choice there.
    }

    // ---------------- game menu ----------------

    private fun openMenu() {
        if (menuOpen || !running) return
        menuOpen = true
        EmulatorBridge.nativeFlushSaveRam()
        val items = arrayOf(
            getString(R.string.menu_save),
            getString(R.string.menu_load),
            getString(R.string.menu_reset),
            getString(R.string.menu_quit)
        )
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog)
            .setTitle(R.string.menu_title)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> doSaveState { menuOpen = false }
                    1 -> doLoadState { menuOpen = false }
                    2 -> doReset { menuOpen = false }
                    3 -> quit() // quit() clears menuOpen itself
                }
            }
            .setOnCancelListener { menuOpen = false }
            .show()
    }

    private fun doSaveState(done: () -> Unit) {
        Thread {
            val ok = try {
                EmulatorBridge.nativeSaveState(stateFile().absolutePath)
            } catch (e: Exception) { false }
            runOnUiThread {
                Toast.makeText(
                    this,
                    if (ok) getString(R.string.saved) else getString(R.string.save_failed),
                    Toast.LENGTH_SHORT
                ).show()
                done()
            }
        }.start()
    }

    private fun doLoadState(done: () -> Unit) {
        val f = stateFile()
        if (!f.exists()) {
            Toast.makeText(this, getString(R.string.load_failed), Toast.LENGTH_SHORT).show()
            done()
            return
        }
        Thread {
            val ok = try {
                EmulatorBridge.nativeLoadState(f.absolutePath)
            } catch (e: Exception) { false }
            runOnUiThread {
                Toast.makeText(
                    this,
                    if (ok) getString(R.string.loaded) else getString(R.string.load_failed),
                    Toast.LENGTH_SHORT
                ).show()
                done()
            }
        }.start()
    }

    private fun doReset(done: () -> Unit) {
        Thread {
            try { EmulatorBridge.nativeReset() } catch (e: Exception) { }
            runOnUiThread { done() }
        }.start()
    }

    private fun quit() {
        running = false
        menuOpen = false
        // Clean quit: this session did not crash.
        SafePrefs.remove(prefs(), RescuePrefs.KEY_EMU_IN_PROGRESS)
        Thread {
            emuThread?.join(5000)
            audioThread?.join(5000)
            runOnUiThread { finish() }
        }.start()
    }

    // ---------------- lifecycle ----------------

    override fun onPause() {
        super.onPause()
        paused = true
        try { EmulatorBridge.nativeFlushSaveRam() } catch (e: Exception) { }
    }

    override fun onResume() {
        super.onResume()
        paused = false
    }

    override fun onDestroy() {
        running = false
        menuOpen = false
        try {
            emuThread?.join(5000)
            audioThread?.join(5000)
        } catch (e: Exception) { }
        super.onDestroy()
    }
}
