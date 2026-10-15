package com.aeonps1.tv

import java.io.File
import java.util.Locale

/** A PS1 game file found on storage. */
data class GameEntry(val title: String, val file: File)

private val GAME_EXTS = setOf("cue", "bin", "iso", "pbp", "chd")
/** Extensions that make a same-basename .bin redundant (multi-track images). */
private val PREFERRED_EXTS = setOf("cue", "chd", "pbp", "iso", "m3u")

object GameScanner {
    fun isGameExt(ext: String): Boolean = ext.lowercase(Locale.US) in GAME_EXTS

    fun scan(dirs: List<File>): List<GameEntry> {
        val files = mutableListOf<File>()
        for (dir in dirs) {
            if (!dir.isDirectory) continue
            dir.listFiles()?.forEach { f ->
                if (f.isFile && isGameExt(f.extension)) files.add(f)
            }
        }
        return finish(files)
    }

    /** Scan one directory recursively (bounded depth) — used for explorer library folders. */
    fun scanRecursive(dir: File, maxDepth: Int = 3): List<GameEntry> {
        val files = mutableListOf<File>()
        fun walk(f: File, depth: Int) {
            if (depth > maxDepth || !f.isDirectory) return
            val kids = try {
                f.listFiles()
            } catch (e: Exception) {
                null
            } ?: return
            for (k in kids) {
                if (k.isFile && isGameExt(k.extension)) files.add(k)
                else if (k.isDirectory) walk(k, depth + 1)
            }
        }
        walk(dir, 0)
        return finish(files)
    }

    private fun finish(files: List<File>): List<GameEntry> {
        // Drop a .bin when a preferred sibling (same base name) exists.
        val preferredBases = files
            .filter { it.extension.lowercase(Locale.US) in PREFERRED_EXTS }
            .map { it.nameWithoutExtension.lowercase(Locale.US) }
            .toSet()
        return files
            .filterNot {
                it.extension.lowercase(Locale.US) == "bin" &&
                    it.nameWithoutExtension.lowercase(Locale.US) in preferredBases
            }
            .map { GameEntry(prettyTitle(it), it) }
            .sortedBy { it.title.lowercase(Locale.US) }
    }

    fun prettyTitle(f: File): String {
        var t = f.nameWithoutExtension
            .replace('_', ' ')
            .replace('.', ' ')
            .replace('-', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
        if (t.isEmpty()) t = f.name
        return t
    }
}
