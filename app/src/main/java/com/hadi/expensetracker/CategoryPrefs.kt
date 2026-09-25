package com.hadi.expensetracker

import android.content.Context

/** Stores which built-in categories (see [Category]) the user has removed. Unlike custom
 * categories — which are deleted outright from the database — a built-in is just hidden, since
 * its label is resolved live from string resources (so it stays translated) rather than stored
 * as text. */
object CategoryPrefs {
    private const val PREFS_NAME = "category_prefs"
    private const val KEY_HIDDEN = "hidden_builtin_ids"

    fun getHiddenBuiltIns(context: Context, dbHelper: DbHelper): Set<String> {
        migrateLegacyHiddenBuiltIns(context, dbHelper)
        return dbHelper.getHiddenCategoryIds()
    }

    fun hideBuiltIn(dbHelper: DbHelper, id: String) {
        dbHelper.hideCategory(id)
    }

    private fun migrateLegacyHiddenBuiltIns(context: Context, dbHelper: DbHelper) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val hidden = HashSet(prefs.getStringSet(KEY_HIDDEN, emptySet()).orEmpty())
        if (hidden.isEmpty()) return
        for (id in hidden) dbHelper.hideCategory(id)
        prefs.edit().remove(KEY_HIDDEN).commit()
    }
}
