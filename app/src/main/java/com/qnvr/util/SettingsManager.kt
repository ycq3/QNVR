package com.qnvr.util

import android.content.Context
import android.content.SharedPreferences

object SettingsManager {
    private const val PREFS_NAME = "qnvr_settings"
    private const val KEY_AUTO_START = "auto_start"
    private const val KEY_BOOT_START = "boot_start"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun isAutoStartEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_AUTO_START, true)
    }

    fun setAutoStartEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_AUTO_START, enabled).apply()
    }

    fun isBootStartEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_BOOT_START, true)
    }

    fun setBootStartEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_BOOT_START, enabled).apply()
    }
}
