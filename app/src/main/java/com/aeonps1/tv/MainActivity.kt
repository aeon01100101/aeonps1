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
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

/**
 * The library. v1.2.1 hardening:
 *  - every SharedPreferences read goes through SafePrefs (a corrupt key can
 *    never kill launch again);
 *  - the library scan runs on a background thread with per-source try/catch,
 *    so one bad folder or file is skipped, never fatal;
 *  - the app tracks whether the previous launch died during startup and
 *    opens the rescue screen after repeated failures (or when storage is
 *    critically low, checked directly since flag writes may not survive a
 *    full disk).
 */
class MainActivity : Activity() {

    companion object {
        /** Set by RescueActivity: skip the rescue gate AND the folder scan this run. */
        const val EXTRA_FROM_RESCUE = "from_rescue"
        const val EXTRA_SKIP_EXTERNAL_SCAN = "skip_external_scan"
    }

    private lateinit var adapter: GameAdapter
    private lateinit var emptyView: TextView
    private lateinit var grantCard: LinearLayout
    private lateinit var spaceCard: LinearLayout
    private lateinit var spaceText: TextView
    private lateinit var grid: RecyclerView
    private val games = mutableListOf<GameEntry>()

    private val pickGamesCode = 1001
    private val scanExecutor = Executors.newSingleThreadExecutor()
    private var scanToken = 0
    private var firstScanDone = false
    /** Set by the rescue gate when a crash streak continues after rescue was
     *  already auto-shown once: open the library without scanning external
     *  folders rather than forcing rescue again. */
    private var safeMode = false

    private fun prefs() = getSharedPreferences("aeonps1", MODE_PRIVATE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            // The rescue gate runs BEFORE anything else: on a critically full
            // disk even the prefs flag writes below may not survive, so free
            // space is checked directly here.
            // The gate can never be the reason the app won't open twice in a
            // row: the crash-streak rescue auto-opens at most ONCE per bad
            // streak (KEY_RESCUE_AUTO_SHOWN). If the streak continues after
            // the user has already seen rescue, the library opens in safe
            // mode (no external folder scan) instead of forcing rescue
            // again. One fully successful session clears the flag and
            // re-arms the gate. The low-disk trigger always routes to
            // RescueActivity (a working screen with cleanup buttons), never
            // to a dead end.
            val fromRescue = intent.getBooleanExtra(EXTRA_FROM_RESCUE, false)
            if (!fromRescue) {
                val free = try {
                    filesDir.usableSpace
                } catch (e: Exception) {
                    Long.MAX_VALUE
                }
                val inProgress = SafePrefs.getBoolean(
                    prefs(), RescuePrefs.KEY_IN_PROGRESS, false
                )
                val attempts = SafePrefs.getInt(
                    prefs(), RescuePrefs.KEY_ATTEMPTS, 0
                )
                val lowDisk = free < RescuePrefs.LOW_SPACE_RESCUE_BYTES
                val crashStreak =
                    inProgress && attempts >= RescuePrefs.RESCUE_ATTEMPTS
                val autoShown = SafePrefs.getBoolean(
                    prefs(), RescuePrefs.KEY_RESCUE_AUTO_SHOWN, false
                )
                if (lowDisk) {
                    openRescue()
                    return
                }
                if (crashStreak && !autoShown) {
                    try {
                        prefs().edit()
                            .putBoolean(RescuePrefs.KEY_RESCUE_AUTO_SHOWN, true)
                            .apply()
                    } catch (e: Exception) { }
                    openRescue()
                    return
                }
                if (crashStreak && autoShown) {
                    // Rescue already auto-opened for this streak: open the
                    // library in safe mode rather than looping the user back
                    // into rescue. Function, never a lockout.
                    safeMode = true
                }
                markLaunchStarted(attempts)
            }

            setContentView(R.layout.activity_main)

            emptyView = findViewById(R.id.empty_view)
            grantCard = findViewById(R.id.grant_card)
            spaceCard = findViewById(R.id.space_card)
            spaceText = findViewById(R.id.space_text)
            grid = findViewById(R.id.game_grid)
            grid.layoutManager = GridLayoutManager(this, 3)
            adapter = GameAdapter(games) { entry ->
                val i = Intent(this, EmulatorActivity::class.java)
                i.putExtra(EmulatorActivity.EXTRA_PATH, entry.file.absolutePath)
                startActivity(i)
            }
            grid.adapter = adapter

            findViewById<View>(R.id.btn_browse_files).setOnClickListener {
                startActivity(Intent(this, FileExplorerActivity::class.java))
            }
            findViewById<View>(R.id.btn_add_games).setOnClickListener { pickGameFiles() }
            findViewById<View>(R.id.btn_bios).setOnClickListener {
                startActivity(Intent(this, BiosActivity::class.java))
            }
            findViewById<View>(R.id.btn_settings).setOnClickListener {
                startActivity(Intent(this, SettingsActivity::class.java))
            }
            findViewById<Button>(R.id.btn_grant).setOnClickListener {
                StorageAccess.openAllFilesSettings(this)
            }
            findViewById<Button>(R.id.btn_space_rescue).setOnClickListener {
                openRescue()
            }

            maybeRequestStoragePermission()
        } catch (t: Throwable) {
            // Last resort: a broken startup lands on the rescue screen,
            // never on a dead app icon tap.
            Log.e("AeonPS1", "MainActivity init failed", t)
            try {
                openRescue()
            } catch (t2: Throwable) {
                finish()
            }
        }
    }

    private fun openRescue() {
        try {
            startActivity(Intent(this, RescueActivity::class.java))
        } catch (e: Exception) {
            Log.e("AeonPS1", "could not open rescue", e)
        }
        finish()
    }

    private fun markLaunchStarted(attempts: Int) {
        try {
            prefs().edit()
                .putBoolean(RescuePrefs.KEY_IN_PROGRESS, true)
                .putInt(RescuePrefs.KEY_ATTEMPTS, attempts + 1)
                .apply()
        } catch (e: Exception) {
            Log.w("AeonPS1", "could not write launch flags", e)
        }
    }

    /** Called once the first background scan has rendered: startup survived.
     *  Also re-arms the crash-streak rescue gate (clears the "auto-shown"
     *  flag) — one fully successful session ends the bad streak. */
    private fun markLaunchSucceeded() {
        try {
            prefs().edit()
                .putBoolean(RescuePrefs.KEY_IN_PROGRESS, false)
                .putInt(RescuePrefs.KEY_ATTEMPTS, 0)
                .putBoolean(RescuePrefs.KEY_RESCUE_AUTO_SHOWN, false)
                .apply()
        } catch (e: Exception) {
            Log.w("AeonPS1", "could not clear launch flags", e)
        }
    }

    override fun onResume() {
        super.onResume()
        // If the gate above bailed to rescue, there is no UI to scan into.
        if (!::grid.isInitialized) return
        rescanAsync()
    }

    override fun onDestroy() {
        scanToken++ // invalidate any scan still running
        super.onDestroy()
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

    private fun libraryFolders(): Set<String> =
        SafePrefs.getStringSet(prefs(), RescuePrefs.KEY_LIBRARY_FOLDERS)

    private fun addedFiles(): Set<String> =
        SafePrefs.getStringSet(prefs(), RescuePrefs.KEY_ADDED_FILES)

    /** The whole library scan happens off the UI thread, one source at a
     *  time, each inside its own try/catch. One bad folder or file is
     *  skipped — it can never take the app down. */
    private fun rescanAsync() {
        val token = ++scanToken
        val skipExternal =
            intent.getBooleanExtra(EXTRA_SKIP_EXTERNAL_SCAN, false) || safeMode

        runOnUiThread {
            emptyView.text = getString(R.string.loading_games)
            emptyView.visibility = View.VISIBLE
            grid.visibility = View.GONE
            grantCard.visibility = View.GONE
        }

        scanExecutor.execute {
            val found = mutableListOf<GameEntry>()
            val seen = mutableSetOf<String>()
            fun addAll(entries: List<GameEntry>) {
                for (e in entries) {
                    try {
                        if (seen.add(e.file.absolutePath)) found.add(e)
                    } catch (e2: Exception) {
                        // skip one bad entry
                    }
                }
            }

            // (a) imported / private games
            try {
                addAll(GameScanner.scan(gamesDirs()))
            } catch (e: Exception) {
                Log.w("AeonPS1", "private games scan failed", e)
            }

            // (b) folders picked in the explorer, scanned in place
            if (!skipExternal) {
                val saved: Set<String> = try {
                    libraryFolders()
                } catch (e: Exception) {
                    emptySet()
                }
                for (path in saved) {
                    try {
                        val f = File(path)
                        if (f.isDirectory && f.canRead()) {
                            addAll(GameScanner.scanRecursive(f, 3))
                        }
                    } catch (e: Exception) {
                        Log.w("AeonPS1", "folder scan failed: $path", e)
                    }
                }
            }

            // (c) individually added files
            try {
                for (p in addedFiles()) {
                    try {
                        val f = File(p)
                        if (f.isFile && GameScanner.isGameExt(f.extension) &&
                            seen.add(f.absolutePath)
                        ) {
                            found.add(GameEntry(GameScanner.prettyTitle(f), f))
                        }
                    } catch (e: Exception) {
                        Log.w("AeonPS1", "added-file check failed: $p", e)
                    }
                }
            } catch (e: Exception) {
                Log.w("AeonPS1", "added-files scan failed", e)
            }

            try {
                found.sortBy { it.title.lowercase(Locale.US) }
            } catch (e: Exception) {
                // leave unsorted
            }

            val finalList = found
            runOnUiThread {
                if (token != scanToken) return@runOnUiThread // stale scan
                games.clear()
                games.addAll(finalList)
                adapter.notifyDataSetChanged()

                val has = games.isNotEmpty()
                emptyView.visibility = if (has) View.GONE else View.VISIBLE
                if (!has) emptyView.text = getString(R.string.no_games)
                grid.visibility = if (has) View.VISIBLE else View.GONE

                updateCards()
                if (!firstScanDone) {
                    firstScanDone = true
                    markLaunchSucceeded()
                }
            }
        }
    }

    private fun updateCards() {
        // Grant card: saved folders exist but we can't read them without access.
        val hasAccess: Boolean = try {
            StorageAccess.hasBrowseAccess(this)
        } catch (e: Exception) {
            false
        }
        val saved: Set<String> = try {
            libraryFolders()
        } catch (e: Exception) {
            emptySet()
        }
        val someUnreadable = try {
            saved.any { p ->
                val f = File(p)
                !f.isDirectory || !f.canRead()
            }
        } catch (e: Exception) {
            false
        }
        grantCard.visibility =
            if (!hasAccess && saved.isNotEmpty() && someUnreadable) View.VISIBLE
            else View.GONE

        // One-time low-space warning: a nearly-full stick stops apps opening.
        val free: Long = try {
            filesDir.usableSpace
        } catch (e: Exception) {
            Long.MAX_VALUE
        }
        val warned = SafePrefs.getBoolean(prefs(), RescuePrefs.KEY_SPACE_WARNED, false)
        if (free < RescuePrefs.LOW_SPACE_WARN_BYTES && !warned) {
            spaceText.text = getString(
                R.string.space_warn_text, RescuePrefs.humanSize(free)
            )
            spaceCard.visibility = View.VISIBLE
            try {
                prefs().edit().putBoolean(RescuePrefs.KEY_SPACE_WARNED, true).apply()
            } catch (e: Exception) {
                // non-fatal
            }
        } else {
            spaceCard.visibility = View.GONE
        }
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
        val inbox = File(filesDir, "inbox").apply { mkdirs() }
        val dlg = ProgressDialog(this).apply {
            setMessage(getString(R.string.importing))
            setCancelable(false)
            show()
        }
        Thread {
            var ok = 0
            var failed = 0
            val archives = mutableListOf<File>()
            for (uri in uris) {
                try {
                    val name = queryName(uri) ?: "game_${System.currentTimeMillis()}.bin"
                    val safe = File(name).name // strip any path parts
                    val isArchive = ArchiveExtractor.isArchiveExt(
                        safe.substringAfterLast('.', "")
                    )
                    val targetDir = if (isArchive) inbox else destDir
                    contentResolver.openInputStream(uri)?.use { ins ->
                        File(targetDir, safe).outputStream().use { outs -> ins.copyTo(outs) }
                    }
                    if (isArchive) archives.add(File(targetDir, safe))
                    ok++
                } catch (e: Exception) {
                    failed++
                }
            }
            val copyFailed = failed
            runOnUiThread {
                dlg.dismiss()
                if (archives.isNotEmpty()) {
                    // Archives extract through the same flow as the explorer;
                    // the inbox copy is deleted after a successful extraction.
                    extractImportedArchives(archives, 0, copyFailed)
                } else {
                    rescanAsync()
                    Toast.makeText(
                        this,
                        if (copyFailed == 0) getString(R.string.import_done)
                        else getString(R.string.import_failed),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }

    /** Extracts picked archives one at a time, then refreshes the library. */
    private fun extractImportedArchives(
        archives: List<File>, index: Int, copyFailed: Int
    ) {
        if (index >= archives.size) {
            rescanAsync()
            if (copyFailed > 0) {
                Toast.makeText(this, getString(R.string.import_failed), Toast.LENGTH_LONG).show()
            }
            return
        }
        ExtractionUi.extractArchiveToLibrary(
            this, archives[index], deleteSourceAfterSuccess = true
        ) {
            extractImportedArchives(archives, index + 1, copyFailed)
        }
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
