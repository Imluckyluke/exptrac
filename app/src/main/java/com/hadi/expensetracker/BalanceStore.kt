package com.hadi.expensetracker

import android.content.Context

/**
 * Remembers the most recent account balance reported by each bank preset's SMS (e.g. Blu
 * Bank's "موجودی" line, Bank Melli's "مانده" line), so the home screen widget can show it
 * without re-scanning messages. Keyed by the SMS timestamp so that scanning the inbox out of
 * order, or a delayed message arriving late, never overwrites a balance we already know is
 * newer.
 */
object BalanceStore {
    private const val PREFS_NAME = "balance_prefs"
    private const val KEY_VALUE_PREFIX = "value_"
    private const val KEY_TS_PREFIX = "ts_"

    fun recordIfNewer(context: Context, presetId: String, balanceToman: Double, timestampMillis: Long) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastTimestamp = prefs.getLong(KEY_TS_PREFIX + presetId, -1L)
        if (timestampMillis < lastTimestamp) return
        prefs.edit()
            .putLong(KEY_VALUE_PREFIX + presetId, Math.round(balanceToman))
            .putLong(KEY_TS_PREFIX + presetId, timestampMillis)
            .apply()
    }

    /** Returns the latest known balance for [presetId] in Toman, or null if never recorded. */
    fun getBalance(context: Context, presetId: String): Long? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val key = KEY_VALUE_PREFIX + presetId
        if (!prefs.contains(key)) return null
        return prefs.getLong(key, 0L)
    }
}
