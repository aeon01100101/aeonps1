package com.aeonps1.tv

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast

/**
 * What makes the built-in file explorer actually work on Fire OS.
 * On API 30+ (Fire OS 8 and up) the only way to browse shared storage and
 * USB sticks is the "All files access" grant (MANAGE_EXTERNAL_STORAGE);
 * on older versions the normal runtime read permission is enough.
 */
object StorageAccess {
    const val REQUEST_READ = 8

    fun hasBrowseAccess(activity: Activity): Boolean {
        return if (Build.VERSION.SDK_INT >= 30) {
            Environment.isExternalStorageManager()
        } else if (Build.VERSION.SDK_INT >= 23) {
            activity.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    /** Ask for whatever grant is missing; returns true if nothing was missing. */
    fun request(activity: Activity): Boolean {
        if (hasBrowseAccess(activity)) return true
        if (Build.VERSION.SDK_INT >= 30) {
            openAllFilesSettings(activity)
        } else if (Build.VERSION.SDK_INT >= 23) {
            activity.requestPermissions(
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), REQUEST_READ
            )
        }
        return false
    }

    /**
     * Opens the system "Allow all files access" screen for this app.
     * Fire OS sometimes can't resolve the app-specific page, so fall back to
     * the generic list, and never crash: worst case the user gets a hint.
     */
    fun openAllFilesSettings(activity: Activity) {
        try {
            val appSpecific = Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:" + activity.packageName)
            )
            if (appSpecific.resolveActivity(activity.packageManager) != null) {
                activity.startActivity(appSpecific)
                return
            }
            val generic = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
            if (generic.resolveActivity(activity.packageManager) != null) {
                activity.startActivity(generic)
                return
            }
        } catch (e: Exception) {
            // fall through to the hint below
        }
        Toast.makeText(
            activity,
            activity.getString(R.string.grant_open_manually),
            Toast.LENGTH_LONG
        ).show()
    }
}
