package com.aeonps1.tv

import android.app.Activity
import android.app.ProgressDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.InputStream
import java.util.Locale

class BiosActivity : Activity() {

    private val pickBiosCode = 2001
    private val browseBiosCode = 2002
    private lateinit var statusView: TextView

    private fun biosDir() = File(filesDir, "bios").apply { mkdirs() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bios)

        statusView = findViewById(R.id.bios_status)
        findViewById<TextView>(R.id.bios_path).text = biosDir().absolutePath
        refreshStatus()

        findViewById<View>(R.id.btn_import_bios).setOnClickListener {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            startActivityForResult(i, pickBiosCode)
        }
        findViewById<View>(R.id.btn_browse_bios).setOnClickListener {
            val i = Intent(this, FileExplorerActivity::class.java).apply {
                putExtra(
                    FileExplorerActivity.EXTRA_MODE,
                    FileExplorerActivity.MODE_PICK_FILE
                )
                putExtra(FileExplorerActivity.EXTRA_EXTENSIONS, arrayOf("bin"))
            }
            startActivityForResult(i, browseBiosCode)
        }
        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
    }

    private fun refreshStatus() {
        val found = biosDir().listFiles()
            ?.filter { it.isFile && it.name.lowercase(Locale.US).startsWith("scph") }
            ?.sortedBy { it.name }
            ?: emptyList()
        statusView.text = if (found.isNotEmpty()) {
            getString(R.string.bios_found, found.joinToString(", ") { it.name })
        } else {
            getString(R.string.bios_none)
        }
    }

    /** Copy [ins] into the bios folder under [name]; report through the same path as SAF. */
    private fun importBios(name: String, openStream: () -> InputStream?) {
        val dlg = ProgressDialog(this).apply {
            setMessage(getString(R.string.importing))
            setCancelable(false)
            show()
        }
        Thread {
            var ok = false
            try {
                val safe = File(name).name
                openStream()?.use { ins ->
                    File(biosDir(), safe).outputStream().use { outs -> ins.copyTo(outs) }
                }
                ok = true
            } catch (e: Exception) {
                ok = false
            }
            runOnUiThread {
                dlg.dismiss()
                refreshStatus()
                Toast.makeText(
                    this,
                    if (ok) getString(R.string.bios_imported)
                    else getString(R.string.bios_import_failed),
                    Toast.LENGTH_LONG
                ).show()
            }
        }.start()
    }

    @Deprecated("no androidx activity-result dep needed")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        when (requestCode) {
            pickBiosCode -> {
                val uri: Uri = data.data ?: return
                val name = queryName(uri) ?: "scph1001.bin"
                importBios(name) { contentResolver.openInputStream(uri) }
            }
            browseBiosCode -> {
                val path = data.getStringExtra(FileExplorerActivity.EXTRA_PICKED_PATH)
                    ?: return
                val src = File(path)
                importBios(src.name) { src.inputStream() }
            }
        }
    }

    private fun queryName(uri: Uri): String? {
        return try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        } catch (e: Exception) {
            null
        }
    }
}
