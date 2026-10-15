package com.aeonps1.tv

import android.Manifest
import android.app.Activity
import android.app.ProgressDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File

class MainActivity : Activity() {

    private lateinit var adapter: GameAdapter
    private lateinit var emptyView: TextView
    private val games = mutableListOf<GameEntry>()

    private val pickGamesCode = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        emptyView = findViewById(R.id.empty_view)
        val grid = findViewById<RecyclerView>(R.id.game_grid)
        grid.layoutManager = GridLayoutManager(this, 3)
        adapter = GameAdapter(games) { entry ->
            val i = Intent(this, EmulatorActivity::class.java)
            i.putExtra(EmulatorActivity.EXTRA_PATH, entry.file.absolutePath)
            startActivity(i)
        }
        grid.adapter = adapter

        findViewById<View>(R.id.btn_add_games).setOnClickListener { pickGameFiles() }
        findViewById<View>(R.id.btn_bios).setOnClickListener {
            startActivity(Intent(this, BiosActivity::class.java))
        }
        findViewById<View>(R.id.btn_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        maybeRequestStoragePermission()
    }

    override fun onResume() {
        super.onResume()
        rescan()
    }

    private fun gamesDirs(): List<File> {
        val dirs = mutableListOf(File(filesDir, "games"))
        // Public folder on external storage, if we can read it.
        if (Build.VERSION.SDK_INT < 33) {
            val pub = File(Environment.getExternalStorageDirectory(), "AeonPS1/games")
            if (pub.isDirectory && pub.canRead()) dirs.add(pub)
        }
        return dirs
    }

    private fun rescan() {
        games.clear()
        games.addAll(GameScanner.scan(gamesDirs()))
        adapter.notifyDataSetChanged()
        val has = games.isNotEmpty()
        emptyView.visibility = if (has) View.GONE else View.VISIBLE
        findViewById<RecyclerView>(R.id.game_grid).visibility =
            if (has) View.VISIBLE else View.GONE
    }

    private fun maybeRequestStoragePermission() {
        if (Build.VERSION.SDK_INT in 23..32) {
            if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 7)
            }
        }
    }

    private fun pickGameFiles() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        startActivityForResult(Intent.createChooser(i, getString(R.string.add_games)), pickGamesCode)
    }

    @Deprecated("startActivityForResult is fine here; no androidx activity-result dep needed")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != pickGamesCode || resultCode != RESULT_OK || data == null) return
        val uris = mutableListOf<Uri>()
        data.clipData?.let { clip ->
            for (k in 0 until clip.itemCount) uris.add(clip.getItemAt(k).uri)
        }
        data.data?.let { uris.add(it) }
        if (uris.isEmpty()) return
        importUris(uris)
    }

    private fun importUris(uris: List<Uri>) {
        val destDir = File(filesDir, "games").apply { mkdirs() }
        val dlg = ProgressDialog(this).apply {
            setMessage(getString(R.string.importing))
            setCancelable(false)
            show()
        }
        Thread {
            var ok = 0
            var failed = 0
            for (uri in uris) {
                try {
                    val name = queryName(uri) ?: "game_${System.currentTimeMillis()}.bin"
                    val safe = File(name).name // strip any path parts
                    contentResolver.openInputStream(uri)?.use { ins ->
                        File(destDir, safe).outputStream().use { outs -> ins.copyTo(outs) }
                    }
                    ok++
                } catch (e: Exception) {
                    failed++
                }
            }
            runOnUiThread {
                dlg.dismiss()
                rescan()
                Toast.makeText(
                    this,
                    if (failed == 0) getString(R.string.import_done)
                    else getString(R.string.import_failed),
                    Toast.LENGTH_LONG
                ).show()
            }
        }.start()
    }

    private fun queryName(uri: Uri): String? {
        return try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            }
        } catch (e: Exception) {
            null
        }
    }

    private class GameAdapter(
        private val items: List<GameEntry>,
        private val onClick: (GameEntry) -> Unit
    ) : RecyclerView.Adapter<GameAdapter.Holder>() {

        class Holder(val title: TextView) : RecyclerView.ViewHolder(title)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val v = android.view.LayoutInflater.from(parent.context)
                .inflate(R.layout.item_game, parent, false)
            val title = v.findViewById<TextView>(R.id.game_title)
            return Holder(title)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val entry = items[position]
            holder.title.text = entry.title
            holder.itemView.setOnClickListener { onClick(entry) }
        }

        override fun getItemCount() = items.size
    }
}
