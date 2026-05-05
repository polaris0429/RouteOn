package com.example.routeon

import android.content.Context

object DeveloperModeManager {

    private const val PREFS_NAME = "RouteOnPrefs"
    private const val KEY_DEV_MODE = "developer_mode_enabled"

    fun isEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_DEV_MODE, false)
    }

    fun enable(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_DEV_MODE, true)
            .apply()
    }

    fun disable(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_DEV_MODE, false)
            .apply()
    }

    fun toggle(context: Context): Boolean {
        val next = !isEnabled(context)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_DEV_MODE, next)
            .apply()
        return next
    }
}
