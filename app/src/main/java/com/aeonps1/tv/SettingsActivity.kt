package com.aeonps1.tv

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.Button

class SettingsActivity : Activity() {

    private fun prefs() = getSharedPreferences("aeonps1", MODE_PRIVATE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val btnScreen = findViewById<Button>(R.id.btn_screen)
        val btnFps = findViewById<Button>(R.id.btn_fps)

        fun refresh() {
            val mode = prefs().getInt("screen_mode", 0)
            btnScreen.text = "${getString(R.string.screen_mode)}: " +
                if (mode == 0) getString(R.string.screen_43) else getString(R.string.screen_stretch)
            val fps = prefs().getBoolean("show_fps", false)
            btnFps.text = "${getString(R.string.show_fps)}: " +
                if (fps) getString(R.string.on) else getString(R.string.off)
        }
        refresh()

        btnScreen.setOnClickListener {
            val cur = prefs().getInt("screen_mode", 0)
            prefs().edit().putInt("screen_mode", if (cur == 0) 1 else 0).apply()
            refresh()
        }
        btnFps.setOnClickListener {
            val cur = prefs().getBoolean("show_fps", false)
            prefs().edit().putBoolean("show_fps", !cur).apply()
            refresh()
        }
        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<View>(R.id.btn_storage).setOnClickListener {
            startActivity(android.content.Intent(this, RescueActivity::class.java))
        }
    }
}
