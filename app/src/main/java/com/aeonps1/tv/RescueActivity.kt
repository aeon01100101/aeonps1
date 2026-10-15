package com.aeonps1.tv

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File

/**
 * The rescue screen. It opens when the app keeps dying during startup (or
 * when storage is critically low) and from Settings → "Storage & rescue".
 * Plain, big buttons, no RecyclerView, no native code, everything wrapped —
 * this screen itself must never be the thing that crashes.
 */
class RescueActivity : Activity() {

    private lateinit var infoFree: TextView
    private lateinit var infoExtracted: TextView
    private lateinit var infoFolders: TextView
    private lateinit var deleteList: LinearLayout

    private fun prefs() = getSharedPreferences("aeonps1", MODE_PRIVATE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setContentView(R.layout.activity_rescue)

            infoFree = findViewById(R.id.rescue_info_free)
            infoExtracted = findViewById(R.id.rescue_info_extracted)
            infoFolders = findViewById(R.id.rescue_info_folders)
            deleteList = findViewById(R.id.rescue_delete_list)

            findViewById<Button>(R.id.btn_rescue_try).setOnClickListener { tryNormally() }
            findViewById<Button>(R.id.btn_rescue_forget).setOnClickListener { confirmForget() }
            findViewById<Button>(R.id.btn_rescue_back).setOnClickListener { finish() }
            findViewById<Button>(R.id.btn_rescue_try).requestFocus()

            refresh()
        } catch (t: Throwable) {
            Log.e("AeonPS1", "rescue screen failed", t)
            finish()
        }
    }

    private fun extractedRoot(): File {
        return try {
            File(getExternalFilesDir(null), "Extracted")
        } catch (e: Exception) {
            File(cacheDir, "Extracted")
        }
    }

    private fun refresh() {
        Thread {
            var free = -1L
            var extractedSize = 0L
            var folders = 0
            var files = 0
            var extracted: List<File> = emptyList()
            try {
                free = filesDir.usableSpace
            } catch (e: Exception) {
            }
            try {
                val root = extractedRoot()
                extractedSize = RescuePrefs.dirSizeBytes(root)
                extracted = root.listFiles()
                    ?.filter { it.isDirectory }
                    ?.sortedBy { it.name.lowercase() } ?: emptyList()
            } catch (e: Exception) {
            }
            try {
                folders = SafePrefs.getStringSet(prefs(), RescuePrefs.KEY_LIBRARY_FOLDERS).size
                files = SafePrefs.getStringSet(prefs(), RescuePrefs.KEY_ADDED_FILES).size
            } catch (e: Exception) {
            }
            val sizes: Map<File, Long> = try {
                extracted.associateWith { RescuePrefs.dirSizeBytes(it) }
            } catch (e: Exception) {
                emptyMap()
            }
            runOnUiThread {
                try {
                    infoFree.text = getString(
                        R.string.rescue_free, RescuePrefs.humanSize(free)
                    )
                    infoExtracted.text = getString(
                        R.string.rescue_extracted_use, RescuePrefs.humanSize(extractedSize)
                    )
                    infoFolders.text = getString(
                        R.string.rescue_folders_files, folders, files
                    )
                    rebuildDeleteList(extracted, sizes)
                } catch (t: Throwable) {
                    Log.e("AeonPS1", "rescue refresh failed", t)
                }
            }
        }.start()
    }

    private fun rebuildDeleteList(folders: List<File>, sizes: Map<File, Long>) {
        deleteList.removeAllViews()
        if (folders.isEmpty()) {
            val t = TextView(this).apply {
                text = getString(R.string.rescue_no_extracted)
                setTextColor(getColor(R.color.text_dim))
                textSize = 18f
            }
            deleteList.addView(t)
            return
        }
        for (dir in folders) {
            val size = sizes[dir] ?: 0L
            val b = Button(this, null, 0, R.style.AeonButton).apply {
                text = "${dir.name} — ${RescuePrefs.humanSize(size)}"
                isFocusable = true
                setOnClickListener { confirmDelete(dir, size) }
            }
            deleteList.addView(b)
        }
    }

    /** Clears the rescue counters and opens the library, skipping the
     *  external-folder scan for this session only. */
    private fun tryNormally() {
        try {
            prefs().edit()
                .putBoolean(RescuePrefs.KEY_IN_PROGRESS, false)
                .putInt(RescuePrefs.KEY_ATTEMPTS, 0)
                .apply()
            val i = Intent(this, MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_FROM_RESCUE, true)
                putExtra(MainActivity.EXTRA_SKIP_EXTERNAL_SCAN, true)
            }
            startActivity(i)
        } catch (e: Exception) {
            Log.e("AeonPS1", "try-normally failed", e)
        }
        finish()
    }

    private fun confirmForget() {
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog)
            .setTitle(R.string.rescue_forget)
            .setMessage(R.string.rescue_forget_confirm)
            .setPositiveButton(R.string.rescue_forget) { _, _ -> doForget() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Clears ONLY the library list — deletes no files on disk. */
    private fun doForget() {
        SafePrefs.remove(
            prefs(), RescuePrefs.KEY_LIBRARY_FOLDERS, RescuePrefs.KEY_ADDED_FILES
        )
        try {
            prefs().edit()
                .putBoolean(RescuePrefs.KEY_IN_PROGRESS, false)
                .putInt(RescuePrefs.KEY_ATTEMPTS, 0)
                .apply()
        } catch (e: Exception) {
        }
        try {
            val i = Intent(this, MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_FROM_RESCUE, true)
            }
            startActivity(i)
        } catch (e: Exception) {
            Log.e("AeonPS1", "forget relaunch failed", e)
        }
        finish()
    }

    private fun confirmDelete(dir: File, size: Long) {
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog)
            .setTitle(R.string.rescue_delete_title)
            .setMessage(
                getString(
                    R.string.rescue_delete_confirm, dir.name,
                    RescuePrefs.humanSize(size)
                )
            )
            .setPositiveButton(R.string.rescue_delete_title) { _, _ ->
                ArchiveExtractor.deleteTree(dir)
                refresh()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
