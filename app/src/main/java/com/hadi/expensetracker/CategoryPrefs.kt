package com.hadi.expensetracker

import android.content.Context

/** Stores which built-in categories (see [Category]) the user has removed. Unlike custom
 * categories — which are deleted outright from the database — a built-in is just hidden, since
 * its label is resolved live from string resources (so it stays translated) rather than stored
 * as text. */
object CategoryPrefs {
    private const val PREFS_NAME = "category_prefs"
    private const val KEY_HIDDEN = "hidden_builtin_ids"

    fun getHiddenBuiltIns(context: Context): Set<String> =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getStringSet(KEY_HIDDEN, emptySet())
            ?: emptySet()

    fun hideBuiltIn(context: Context, id: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val updated = HashSet(getHiddenBuiltIns(context))
        updated.add(id)
        prefs.edit().putStringSet(KEY_HIDDEN, updated).apply()
    }
}
