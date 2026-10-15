package com.aeonps1.tv

import android.app.Activity
import android.app.AlertDialog
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.Locale

/**
 * One shared "extract this archive and add its folder to the library" flow,
 * used by the file explorer and by the SAF "Add Games" picker. Destination
 * is always:  <app external files>/Extracted/<archive base name>/
 */
object ExtractionUi {

    fun extractArchiveToLibrary(
        activity: Activity,
        archive: File,
        deleteSourceAfterSuccess: Boolean = false,
        onDone: (ArchiveExtractor.Result) -> Unit
    ) {
        val root = File(activity.getExternalFilesDir(null), "Extracted")
        val baseName = archive.nameWithoutExtension
            .replace(Regex("[^A-Za-z0-9._()\\[\\] -]"), "_")
            .trim()
            .ifEmpty { "archive" }
        val dest = File(root, baseName)

        if (dest.isDirectory && GameScanner.scanRecursive(dest, 3).isNotEmpty()) {
            AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog)
                .setTitle(activity.getString(R.string.already_extracted, archive.name))
                .setItems(
                    arrayOf(
                        activity.getString(R.string.use_existing),
                        activity.getString(R.string.extract_again),
                        activity.getString(R.string.cancel)
                    )
                ) { _, which ->
                    when (which) {
                        0 -> {
                            addLibraryFolder(activity, dest)
                            Toast.makeText(
                                activity,
                                activity.getString(
                                    R.string.extract_done,
                                    archive.name,
                                    GameScanner.scanRecursive(dest, 3).size
                                ),
                                Toast.LENGTH_LONG
                            ).show()
                            onDone(ArchiveExtractor.Result.Ok(dest, 0))
                        }
                        1 -> {
                            ArchiveExtractor.deleteTree(dest)
                            runExtraction(activity, archive, dest, deleteSourceAfterSuccess, onDone)
                        }
                        else -> onDone(ArchiveExtractor.Result.CancelledByUser)
                    }
                }
                .show()
            return
        }
        if (dest.exists()) ArchiveExtractor.deleteTree(dest) // stale/partial leftovers
        runExtraction(activity, archive, dest, deleteSourceAfterSuccess, onDone)
    }

    private fun runExtraction(
        activity: Activity,
        archive: File,
        dest: File,
        deleteSourceAfterSuccess: Boolean,
        onDone: (ArchiveExtractor.Result) -> Unit
    ) {
        var cancelled = false

        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (24 * activity.resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
        }
        val fileView = TextView(activity).apply { text = "" }
        layout.addView(bar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        layout.addView(fileView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        val dlg = AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog)
            .setTitle(activity.getString(R.string.extracting_title, archive.name))
            .setView(layout)
            .setCancelable(false)
            .create()
        val cancelBtn = Button(activity).apply {
            text = activity.getString(R.string.cancel)
            setOnClickListener { cancelled = true }
            isFocusable = true
        }
        layout.addView(cancelBtn)

        val listener = object : ArchiveExtractor.Listener {
            override fun onProgress(percent: Int, currentFile: String) {
                activity.runOnUiThread {
                    bar.progress = percent
                    fileView.text = "$percent% · $currentFile"
                }
            }

            override fun isCancelled(): Boolean = cancelled
        }

        dlg.show()
        // Focus the Cancel button first so the D-pad finds it immediately.
        cancelBtn.requestFocus()

        Thread {
            val result = ArchiveExtractor.extract(archive, dest, listener)
            activity.runOnUiThread {
                dlg.dismiss()
                when (result) {
                    is ArchiveExtractor.Result.Ok -> {
                        addLibraryFolder(activity, dest)
                        if (deleteSourceAfterSuccess) {
                            try {
                                archive.delete()
                            } catch (e: Exception) {
                                // leave the copy; not worth failing over
                            }
                        }
                        Toast.makeText(
                            activity,
                            activity.getString(
                                R.string.extract_done, archive.name, result.gamesFound
                            ),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    is ArchiveExtractor.Result.Failed -> {
                        AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog)
                            .setTitle(archive.name)
                            .setMessage(
                                activity.getString(R.string.extract_failed, result.message)
                            )
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                    }
                    is ArchiveExtractor.Result.NoSpace -> {
                        AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog)
                            .setTitle(archive.name)
                            .setMessage(
                                activity.getString(
                                    R.string.extract_no_space,
                                    human(result.needBytes), human(result.haveBytes)
                                )
                            )
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                    }
                    is ArchiveExtractor.Result.CancelledByUser -> {
                        Toast.makeText(
                            activity,
                            activity.getString(R.string.extract_cancelled),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                onDone(result)
            }
        }.start()
    }

    private fun addLibraryFolder(activity: Activity, dest: File) {
        val prefs = activity.getSharedPreferences("aeonps1", Activity.MODE_PRIVATE)
        val set = prefs.getStringSet("library_folders", emptySet())!!.toMutableSet()
        set.add(dest.absolutePath)
        prefs.edit().putStringSet("library_folders", set).apply()
    }

    private fun human(bytes: Long): String = humanSize(bytes)
}
