package com.aeonps1.tv

import android.content.SharedPreferences
import android.util.Log

/**
 * Defensive SharedPreferences access. A single corrupt key (wrong type,
 * garbage value) must never kill app startup again: every read catches
 * ClassCastException and friends, resets the offending key, and falls back
 * to the default.
 */
object SafePrefs {
    private const val TAG = "AeonPS1"

    fun getStringSet(prefs: SharedPreferences, key: String): Set<String> {
        return try {
            val raw = prefs.getStringSet(key, emptySet())
            if (raw == null) {
                remove(prefs, key)
                emptySet()
            } else {
                // Copy + filter: never hand out the live set, never trust its contents.
                raw.filterIsInstance<String>().toSet()
            }
        } catch (e: Exception) {
            Log.w(TAG, "bad pref $key, resetting", e)
            remove(prefs, key)
            emptySet()
        }
    }

    fun getString(prefs: SharedPreferences, key: String, def: String): String {
        return try {
            prefs.getString(key, def) ?: def
        } catch (e: Exception) {
            Log.w(TAG, "bad pref $key, resetting", e)
            remove(prefs, key)
            def
        }
    }

    fun getInt(prefs: SharedPreferences, key: String, def: Int): Int {
        return try {
            prefs.getInt(key, def)
        } catch (e: Exception) {
            Log.w(TAG, "bad pref $key, resetting", e)
            remove(prefs, key)
            def
        }
    }

    fun getBoolean(prefs: SharedPreferences, key: String, def: Boolean): Boolean {
        return try {
            prefs.getBoolean(key, def)
        } catch (e: Exception) {
            Log.w(TAG, "bad pref $key, resetting", e)
            remove(prefs, key)
            def
        }
    }

    fun remove(prefs: SharedPreferences, vararg keys: String) {
        try {
            prefs.edit().apply { keys.forEach { remove(it) } }.apply()
        } catch (e: Exception) {
            Log.w(TAG, "could not reset prefs", e)
        }
    }
}
