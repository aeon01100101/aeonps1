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

    @Volatile private var running = false
    @Volatile private var paused = false
    @Volatile private var menuOpen = false
    @Volatile private var buttonMask = 0

    private lateinit var gamePath: String
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

        startEmulation()
    }

    private fun stateFile(): File {
        val base = File(gamePath).nameWithoutExtension
        return File(savesDir, "$base.state")
    }

    private fun startEmulation() {
        running = true
        emuThread = Thread({
            val corePath = applicationInfo.nativeLibraryDir +
                "/libpcsx_rearmed_libretro_android.so"
            val biosDir = File(filesDir, "bios").absolutePath
            if (!EmulatorBridge.nativeInit(corePath, biosDir, savesDir.absolutePath)) {
                fail("Could not start the emulator core."); return@Thread
            }
            EmulatorBridge.nativeSetScreenMode(prefs().getInt("screen_mode", 0))
            if (!EmulatorBridge.nativeLoadGame(gamePath)) {
                fail(getString(R.string.load_error)); return@Thread
            }
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

    private fun fail(msg: String) {
        running = false
        runOnUiThread {
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            finish()
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
