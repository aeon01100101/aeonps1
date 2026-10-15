package com.aeonps1.tv

import com.github.junrar.Archive
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.Locale
import java.util.zip.ZipFile

/**
 * Extracts game archives (.rar via junrar, .zip via java.util.zip) on a
 * background thread. RARs and ZIPs are the two ways Isaiah packs his games.
 *
 * Safety rules:
 *  - zip-slip / rar-slip protection: an entry that would escape the
 *    destination folder is skipped, never written.
 *  - free space is checked BEFORE anything is written; if there is not
 *    enough room the whole thing aborts with a plain message.
 *  - a cancelled or failed extraction deletes its own partial output.
 *  - multi-volume RAR sets work: opening the first volume is enough,
 *    junrar's VolumeManager follows the rest when they sit in one folder.
 */
object ArchiveExtractor {

    fun isArchiveExt(ext: String): Boolean =
        ext.lowercase(Locale.US) in setOf("rar", "zip")

    /** Thrown internally to unwind a user-cancelled extraction. */
    private class Cancelled : Exception()

    /** Progress/cancel callbacks. All calls come from the extraction thread. */
    interface Listener {
        fun onProgress(percent: Int, currentFile: String)
        fun isCancelled(): Boolean
        fun checkCancelled() {
            if (isCancelled()) throw Cancelled()
        }
    }

    sealed class Result {
        data class Ok(val destDir: File, val gamesFound: Int) : Result()
        data class Failed(val message: String) : Result()
        data class NoSpace(val needBytes: Long, val haveBytes: Long) : Result()
        object CancelledByUser : Result()
    }

    fun extract(archive: File, destDir: File, listener: Listener?): Result {
        return try {
            when (archive.extension.lowercase(Locale.US)) {
                "zip" -> extractZip(archive, destDir, listener)
                "rar" -> extractRar(archive, destDir, listener)
                else -> Result.Failed("That file is not a .rar or .zip archive.")
            }
        } catch (e: Cancelled) {
            deleteTree(destDir)
            Result.CancelledByUser
        } catch (e: Exception) {
            deleteTree(destDir)
            Result.Failed(friendlyMessage(e))
        }
    }

    // ------------------------------------------------------------------ ZIP

    private fun extractZip(archive: File, destDir: File, listener: Listener?): Result {
        ZipFile(archive).use { zf ->
            val entries = zf.entries().asSequence().toList()
            val files = entries.filter { !it.isDirectory }
            val total = files.sumOf { it.size.coerceAtLeast(0) }
            checkSpace(destDir, total)?.let { return it }

            var done = 0L
            var index = 0
            for (e in files) {
                listener?.checkCancelled()
                index++
                val out = safeOut(destDir, e.name) ?: continue // slip attempt: skip
                out.parentFile?.mkdirs()
                zf.getInputStream(e).use { ins ->
                    BufferedOutputStream(FileOutputStream(out)).use { raw ->
                        val counted = CountingOutputStream(raw) { written ->
                            done += written
                            report(listener, done, total, e.name, index, files.size)
                        }
                        ins.copyTo(counted)
                        counted.flush()
                    }
                }
            }
            return finish(destDir)
        }
    }

    // ------------------------------------------------------------------ RAR

    private fun extractRar(archive: File, destDir: File, listener: Listener?): Result {
        // Opening the first volume is enough: junrar's VolumeManager follows
        // .partN.rar / .rNN volume sets on its own when they sit together.
        Archive(archive).use { arc ->
            if (arc.isPasswordProtected) {
                return Result.Failed(PASSWORD_MESSAGE)
            }
            val headers = arc.fileHeaders
            for (h in headers) {
                if (h.isEncrypted) return Result.Failed(PASSWORD_MESSAGE)
            }
            val fileHeaders = headers.filter { !it.isDirectory }
            val total = fileHeaders.sumOf { it.fullUnpackSize.coerceAtLeast(0) }
            checkSpace(destDir, total)?.let { return it }

            var done = 0L
            var index = 0
            for (h in headers) {
                listener?.checkCancelled()
                val out = safeOut(destDir, h.fileName) ?: continue // slip attempt: skip
                if (h.isDirectory) {
                    out.mkdirs()
                    continue
                }
                index++
                out.parentFile?.mkdirs()
                BufferedOutputStream(FileOutputStream(out)).use { raw ->
                    val counted = CountingOutputStream(raw) { written ->
                        done += written
                        report(listener, done, total, h.fileName, index, fileHeaders.size)
                    }
                    arc.extractFile(h, counted)
                    counted.flush()
                }
            }
            return finish(destDir)
        }
    }

    // ------------------------------------------------------------- helpers

    private const val PASSWORD_MESSAGE =
        "This archive is password-protected — AeonPS1 can't open those yet."

    private fun friendlyMessage(e: Exception): String {
        val msg = (e.message ?: e.javaClass.simpleName).trim()
        if (msg.contains("encrypt", ignoreCase = true) ||
            msg.contains("password", ignoreCase = true)
        ) {
            return PASSWORD_MESSAGE
        }
        return "Couldn't read that archive ($msg)."
    }

    /** Rejects entries that would write outside the destination folder. */
    private fun safeOut(destDir: File, entryName: String): File? {
        return try {
            val base = destDir.canonicalFile
            val out = File(base, entryName.replace('\\', '/'))
            val canonical = out.canonicalPath
            if (canonical == base.canonicalPath ||
                canonical.startsWith(base.canonicalPath + File.separator)
            ) {
                out
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun checkSpace(destDir: File, need: Long): Result.NoSpace? {
        return try {
            destDir.mkdirs()
            val have = destDir.usableSpace
            val required = (need * 1.05).toLong() + 10L * 1024 * 1024
            if (have < required) Result.NoSpace(need, have) else null
        } catch (e: Exception) {
            null
        }
    }

    private fun report(
        listener: Listener?, done: Long, total: Long,
        name: String, index: Int, count: Int
    ) {
        val pct = if (total > 0) ((done * 100) / total).toInt().coerceIn(0, 100)
        else ((index * 100) / count.coerceAtLeast(1)).coerceIn(0, 100)
        listener?.onProgress(pct, File(name.replace('\\', '/')).name)
    }

    private fun finish(destDir: File): Result {
        val games = GameScanner.scanRecursive(destDir, 3)
        return if (games.isNotEmpty()) {
            Result.Ok(destDir, games.size)
        } else {
            deleteTree(destDir)
            Result.Failed(
                "The archive opened fine, but no PlayStation game files " +
                    "(.cue, .bin, .iso, .pbp, .chd) were inside it."
            )
        }
    }

    fun deleteTree(f: File) {
        try {
            if (f.isDirectory) f.listFiles()?.forEach { deleteTree(it) }
            f.delete()
        } catch (e: Exception) {
            // best effort
        }
    }

    /** Counts bytes so progress can be reported while writing. */
    private class CountingOutputStream(
        private val out: OutputStream,
        private val onBytes: (Long) -> Unit
    ) : OutputStream() {
        override fun write(b: Int) {
            out.write(b)
            onBytes(1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            onBytes(len.toLong())
        }

        override fun flush() = out.flush()
        override fun close() = out.close()
    }
}
