package com.aeonps1.tv

import android.app.Activity
import android.content.Context

/** Emulator engine preference keys. "duckstation" is the prototype default. */
const val ENGINE_DUCKSTATION = "duckstation"
const val ENGINE_PCSX = "pcsx"

/** Core .so file names as bundled in jniLibs. */
const val CORE_DUCKSTATION = "libswanstation_libretro_android.so"
const val CORE_PCSX = "libpcsx_rearmed_libretro_android.so"

/** BIOS file names the DuckStation-family engine accepts (per its core info). */
val DUCKSTATION_BIOS_NAMES = setOf(
    "scph5500.bin", "scph5501.bin", "scph5502.bin",
    "psxonpsp660.bin", "ps1_rom.bin"
)

fun prefsOf(ctx: Context) =
    ctx.getSharedPreferences("aeonps1", Activity.MODE_PRIVATE)

fun enginePref(ctx: Context): String =
    SafePrefs.getString(prefsOf(ctx), "engine", ENGINE_DUCKSTATION)

fun isDuckStation(ctx: Context): Boolean = enginePref(ctx) != ENGINE_PCSX

fun coreFileFor(ctx: Context): String =
    if (isDuckStation(ctx)) CORE_DUCKSTATION else CORE_PCSX

/** True if the bios dir holds any file the DuckStation engine will accept. */
fun hasDuckStationBios(ctx: Context): Boolean {
    val dir = java.io.File(ctx.filesDir, "bios")
    val files = try {
        dir.listFiles()
    } catch (e: Exception) {
        null
    } ?: return false
    return files.any { f ->
        f.isFile && (
            f.name.lowercase(java.util.Locale.US) in DUCKSTATION_BIOS_NAMES ||
                (f.name.lowercase(java.util.Locale.US).startsWith("scph") &&
                    f.name.lowercase(java.util.Locale.US).endsWith(".bin"))
            )
    }
}
