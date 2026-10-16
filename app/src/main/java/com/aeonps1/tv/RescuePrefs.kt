package com.aeonps1.tv

import java.io.File
import java.util.Locale

/**
 * Pure, host-testable pieces of the startup-rescue flow.
 * (No Android framework calls in here — the host JVM test covers these.)
 */
object RescuePrefs {
    const val KEY_IN_PROGRESS = "launch_in_progress"
    const val KEY_ATTEMPTS = "launch_attempts"

    /** Set when the crash-streak rescue has auto-opened once for the current
     *  bad streak. A fully successful MainActivity session clears it, which
     *  re-arms the gate. While set, the gate never forces RescueActivity a
     *  second time for the same streak — the library opens (in safe mode)
     *  instead, so the gate can never be the reason the app won't open
     *  twice in a row. */
    const val KEY_RESCUE_AUTO_SHOWN = "rescue_auto_shown"

    /** Set to the game path while the emulator is starting/running; cleared
     *  after a few seconds of successful play or on a clean quit/load
     *  failure. If still set at the next EmulatorActivity launch, the last
     *  session died inside emulation (native crash) — show a notice instead
     *  of blindly reloading the same killer file. */
    const val KEY_EMU_IN_PROGRESS = "emu_in_progress"
    const val KEY_SPACE_WARNED = "space_warned"
    const val KEY_LIBRARY_FOLDERS = "library_folders"
    const val KEY_ADDED_FILES = "added_files"

    /** How many died-during-startup launches it takes to open the rescue screen. */
    const val RESCUE_ATTEMPTS = 2

    /** Below this free space the app goes straight to the rescue screen. */
    const val LOW_SPACE_RESCUE_BYTES = 150L * 1024 * 1024

    /** Below this free space the library shows a one-time warning card. */
    const val LOW_SPACE_WARN_BYTES = 300L * 1024 * 1024

    /** Extraction must leave at least this much free afterwards. */
    const val EXTRACT_RESERVE_BYTES = 250L * 1024 * 1024

    /**
     * True when the previous launch died during startup ([inProgress] left set
     * and [attempts] reached the threshold), or when storage is critically
     * low. A critically-low disk is checked directly because on a full disk
     * even the SharedPreferences flag writes may not survive.
     */
    @JvmStatic
    fun shouldEnterRescue(
        inProgress: Boolean,
        attempts: Int,
        freeBytes: Long,
        lowSpaceBytes: Long
    ): Boolean {
        if (freeBytes < lowSpaceBytes) return true
        return inProgress && attempts >= RESCUE_ATTEMPTS
    }

    @JvmStatic
    fun humanSize(bytes: Long): String = when {
        bytes < 0 -> "unknown"
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024L -> "%.1f KB".format(Locale.US, bytes / 1024.0)
        bytes < 1024L * 1024L * 1024L -> "%.1f MB".format(Locale.US, bytes / (1024.0 * 1024))
        else -> "%.2f GB".format(Locale.US, bytes / (1024.0 * 1024 * 1024))
    }

    /** Recursive size of a directory tree; never throws. */
    @JvmStatic
    fun dirSizeBytes(dir: File): Long {
        var total = 0L
        fun walk(f: File) {
            val kids = try {
                f.listFiles()
            } catch (e: Exception) {
                null
            } ?: return
            for (k in kids) {
                try {
                    if (k.isDirectory) walk(k) else total += k.length()
                } catch (e: Exception) {
                    // skip unreadable entries
                }
            }
        }
        return try {
            if (dir.isDirectory) walk(dir)
            total
        } catch (e: Exception) {
            total
        }
    }
}
