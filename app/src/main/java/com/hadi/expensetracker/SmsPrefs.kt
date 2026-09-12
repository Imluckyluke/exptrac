package com.hadi.expensetracker

import android.content.Context

/** Stores which built-in bank presets (see [BankPresets]) the user has switched on. Custom
 * banks the user adds themselves are stored in [DbHelper] instead, since each needs its own
 * sender/sample text rather than a single on/off flag. */
object SmsPrefs {
    private const val PREFS_NAME = "sms_prefs"
    private const val KEY_PRESET_PREFIX = "preset_enabled_"

    fun isPresetEnabled(context: Context, presetId: String): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_PRESET_PREFIX + presetId, false)

    fun setPresetEnabled(context: Context, presetId: String, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_PRESET_PREFIX + presetId, enabled)
            .apply()
    }
}
