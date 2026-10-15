package com.aeonps1.tv

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.util.Locale

/**
 * A real, built-in file browser for the TV. D-pad first: OK opens a folder
 * (or a game), Back goes up one level, Back at the roots exits.
 *
 * Two modes:
 *  - browse (default): full explorer — play games in place, add files or
 *    whole folders to the library.
 *  - pick_file (EXTRA_MODE): only lets the user pick one file whose extension
 *    is in EXTRA_EXTENSIONS; the chosen path comes back in EXTRA_PICKED_PATH.
 */
class FileExplorerActivity : Activity() {

    companion object {
        const val EXTRA_MODE = "mode"
        const val EXTRA_EXTENSIONS = "extensions"
        const val EXTRA_PICKED_PATH = "picked_path"
        const val MODE_BROWSE = "browse"
        const val MODE_PICK_FILE = "pick_file"
    }

    private data class Root(val label: String, val path: String)

    private sealed class Row
    private data class RootRow(val label: String, val path: String) : Row()
    private object UpRow : Row()
    private data class EntryRow(
        val file: File,
        val isDir: Boolean,
        val isGame: Boolean,
        val isArchive: Boolean = false
    ) : Row()

    private val rows = mutableListOf<Row>()
    private lateinit var adapter: FileAdapter
    private lateinit var breadcrumb: TextView
    private lateinit var errorView: TextView
    private lateinit var grantCard: LinearLayout
    private lateinit var btnUseFolder: Button
    private lateinit var fileList: RecyclerView

    /** null = roots screen, otherwise the folder being viewed. */
    private var currentPath: File? = null

    private val pickMode get() =
        intent.getStringExtra(EXTRA_MODE) == MODE_PICK_FILE

    private val allowedExts: Set<String>
        get() = intent.getStringArrayExtra(EXTRA_EXTENSIONS)
            ?.map { it.lowercase(Locale.US) }?.toSet() ?: emptySet()

    private fun prefs() = getSharedPreferences("aeonps1", MODE_PRIVATE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_file_explorer)

        breadcrumb = findViewById(R.id.breadcrumb)
        errorView = findViewById(R.id.error_view)
        grantCard = findViewById(R.id.grant_card)
        btnUseFolder = findViewById(R.id.btn_use_folder)
        fileList = findViewById(R.id.file_list)

        adapter = FileAdapter(rows) { onRowClicked(it) }
        fileList.layoutManager = LinearLayoutManager(this)
        fileList.adapter = adapter

        btnUseFolder.setOnClickListener { useThisFolder() }
        findViewById<Button>(R.id.btn_grant).setOnClickListener {
            StorageAccess.openAllFilesSettings(this)
        }

        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh() // re-check the permission grant after the Settings screen
    }

    private fun roots(): List<Root> {
        val out = mutableListOf<Root>()
        out.add(Root(getString(R.string.root_internal), "/storage/emulated/0"))
        // Other volumes under /storage — USB sticks land here as XXXX-XXXX.
        try {
            File("/storage").listFiles()?.forEach { v ->
                if (v.isDirectory && v.name != "emulated" && v.name != "self") {
                    val label =
                        if (v.name.matches(Regex("[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}")))
                            getString(R.string.root_usb, v.name)
                        else getString(R.string.root_storage, v.name)
                    out.add(Root(label, v.absolutePath))
                }
            }
        } catch (e: Exception) {
            // ignore; the roots we know about still show
        }
        out.add(Root(getString(R.string.root_download), "/storage/emulated/0/Download"))
        getExternalFilesDir(null)?.let {
            out.add(Root(getString(R.string.root_app), it.absolutePath))
        }
        out.add(Root(getString(R.string.root_whole), "/"))
        return out.distinctBy { it.path }
    }

    private fun refresh() {
        val hasAccess = StorageAccess.hasBrowseAccess(this)
        breadcrumb.text =
            if (pickMode) getString(R.string.pick_file_title)
            else currentPath?.absolutePath ?: getString(R.string.file_explorer)
        val showFolderBtn = currentPath != null && !pickMode
        btnUseFolder.visibility = if (showFolderBtn) View.VISIBLE else View.GONE
        if (showFolderBtn) {
            btnUseFolder.text =
                if (isLibraryFolder(currentPath!!)) getString(R.string.remove_from_library)
                else getString(R.string.use_this_folder)
        }
        errorView.visibility = View.GONE
        grantCard.visibility = View.GONE

        rows.clear()
        if (currentPath == null) {
            rows.addAll(roots().map { RootRow(it.label, it.path) })
            if (!hasAccess) grantCard.visibility = View.VISIBLE
        } else {
            rows.add(UpRow)
            val kids: Array<File>? = try {
                currentPath!!.listFiles()
            } catch (e: Exception) {
                null
            }
            if (kids == null) {
                errorView.text = if (hasAccess) getString(R.string.cant_open_folder_locked)
                else getString(R.string.cant_open_folder)
                errorView.visibility = View.VISIBLE
                if (!hasAccess) grantCard.visibility = View.VISIBLE
            } else {
                val byName = compareBy<File> { it.name.lowercase(Locale.US) }
                rows.addAll(kids.filter { it.isDirectory }.sortedWith(byName)
                    .map { EntryRow(it, true, false) })
                rows.addAll(kids.filter { it.isFile }.sortedWith(byName)
                    .map {
                        EntryRow(
                            it, false,
                            GameScanner.isGameExt(it.extension),
                            ArchiveExtractor.isArchiveExt(it.extension)
                        )
                    })
                if (kids.isEmpty()) {
                    errorView.text = getString(R.string.folder_empty)
                    errorView.visibility = View.VISIBLE
                }
            }
        }
        adapter.notifyDataSetChanged()
        fileList.scrollToPosition(0)
    }

    private fun goUp() {
        currentPath = currentPath?.parentFile // null at "/" -> back to roots
        refresh()
    }

    override fun onBackPressed() {
        if (currentPath != null) goUp() else super.onBackPressed()
    }

    private fun onRowClicked(row: Row) {
        when (row) {
            is RootRow -> {
                currentPath = File(row.path)
                refresh()
            }
            is UpRow -> goUp()
            is EntryRow -> {
                if (row.isDir) {
                    currentPath = row.file
                    refresh()
                } else {
                    onFileClicked(row.file, row.isGame)
                }
            }
        }
    }

    private fun onFileClicked(file: File, isGame: Boolean) {
        val ext = file.extension.lowercase(Locale.US)
        if (!pickMode && ArchiveExtractor.isArchiveExt(ext)) {
            extractArchiveDialog(file)
            return
        }
        if (pickMode) {
            if (allowedExts.isEmpty() || ext in allowedExts) {
                val result = Intent().putExtra(EXTRA_PICKED_PATH, file.absolutePath)
                setResult(RESULT_OK, result)
                finish()
            } else {
                Toast.makeText(this, getString(R.string.pick_wrong_type), Toast.LENGTH_SHORT).show()
            }
            return
        }
        if (!isGame) {
            Toast.makeText(this, getString(R.string.not_a_game), Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog)
            .setTitle(file.name)
            .setItems(
                arrayOf(
                    getString(R.string.play_now),
                    getString(R.string.add_to_library),
                    getString(R.string.cancel)
                )
            ) { _, which ->
                when (which) {
                    0 -> playInPlace(file)
                    1 -> addFileToLibrary(file)
                }
            }
            .show()
    }

    private fun playInPlace(file: File) {
        // Same launch contract as the library: absolute path via EXTRA_PATH.
        val i = Intent(this, EmulatorActivity::class.java)
        i.putExtra(EmulatorActivity.EXTRA_PATH, file.absolutePath)
        startActivity(i)
    }

    private fun addFileToLibrary(file: File) {
        val set = prefs().getStringSet("added_files", emptySet())!!.toMutableSet()
        set.add(file.absolutePath)
        prefs().edit().putStringSet("added_files", set).apply()
        Toast.makeText(this, getString(R.string.file_added), Toast.LENGTH_SHORT).show()
    }

    /** The folder button is a toggle: add it, or take it back out again. */
    private fun useThisFolder() {
        val dir = currentPath ?: return
        val set = prefs().getStringSet("library_folders", emptySet())!!.toMutableSet()
        if (set.remove(dir.absolutePath)) {
            prefs().edit().putStringSet("library_folders", set).apply()
            Toast.makeText(this, getString(R.string.folder_removed), Toast.LENGTH_SHORT).show()
        } else {
            set.add(dir.absolutePath)
            prefs().edit().putStringSet("library_folders", set).apply()
            Toast.makeText(this, getString(R.string.folder_added), Toast.LENGTH_SHORT).show()
        }
        refresh() // update the button label
    }

    private fun isLibraryFolder(dir: File): Boolean =
        prefs().getStringSet("library_folders", emptySet())!!.contains(dir.absolutePath)

    private fun extractArchiveDialog(file: File) {
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog)
            .setTitle(file.name)
            .setItems(
                arrayOf(
                    getString(R.string.extract_and_add),
                    getString(R.string.cancel)
                )
            ) { _, which ->
                if (which == 0) {
                    ExtractionUi.extractArchiveToLibrary(this, file) { result ->
                        // The folder registered itself with the library;
                        // return so MainActivity rescans and shows the games.
                        if (result is ArchiveExtractor.Result.Ok) finish()
                    }
                }
            }
            .show()
    }

    private class FileAdapter(
        private val items: List<Row>,
        private val onClick: (Row) -> Unit
    ) : RecyclerView.Adapter<FileAdapter.Holder>() {

        class Holder(
            val root: View,
            val icon: TextView,
            val name: TextView,
            val size: TextView
        ) : RecyclerView.ViewHolder(root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val v = android.view.LayoutInflater.from(parent.context)
                .inflate(R.layout.item_file, parent, false)
            return Holder(
                v,
                v.findViewById(R.id.file_icon),
                v.findViewById(R.id.file_name),
                v.findViewById(R.id.file_size)
            )
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val row = items[position]
            val ctx = holder.root.context
            when (row) {
                is RootRow -> {
                    holder.icon.text = "\uD83D\uDCC1" // folder
                    holder.name.text = row.label
                    holder.size.text = ""
                    styleName(holder.name, R.color.text)
                }
                is UpRow -> {
                    holder.icon.text = "\u2B06" // up arrow
                    holder.name.text = ctx.getString(R.string.go_up)
                    holder.size.text = ""
                    styleName(holder.name, R.color.accent)
                }
                is EntryRow -> {
                    if (row.isDir) {
                        holder.icon.text = "\uD83D\uDCC1"
                        holder.name.text = row.file.name
                        holder.size.text = ""
                        styleName(holder.name, R.color.text)
                    } else if (row.isArchive) {
                        holder.icon.text = "\uD83D\uDCE6" // package: archive
                        holder.name.text = row.file.name
                        holder.size.text = humanSize(row.file.length())
                        styleName(holder.name, R.color.accent)
                    } else if (row.isGame) {
                        holder.icon.text = "\u25B6" // play triangle
                        holder.name.text = GameScanner.prettyTitle(row.file)
                        holder.size.text = humanSize(row.file.length())
                        styleName(holder.name, R.color.accent)
                    } else {
                        holder.icon.text = "\u2022" // bullet
                        holder.name.text = row.file.name
                        holder.size.text = humanSize(row.file.length())
                        styleName(holder.name, R.color.text_dim)
                    }
                }
            }
            holder.root.setOnClickListener { onClick(row) }
        }

        private fun styleName(v: TextView, colorRes: Int) {
            v.setTextColor(v.context.getColor(colorRes))
        }

        override fun getItemCount() = items.size

        companion object {
            fun humanSize(bytes: Long): String = com.aeonps1.tv.humanSize(bytes)
        }
    }
}

/** Shared human-readable byte count (used by the explorer and extraction UI). */
internal fun humanSize(bytes: Long): String = when {
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024))
    else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
}
