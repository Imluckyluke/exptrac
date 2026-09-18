package com.hadi.expensetracker

import android.content.Context

/**
 * Built-in categories are defined in code (so their labels stay localized) but the user can
 * hide any of them (see [CategoryPrefs]); custom categories are stored in the database and can
 * be deleted outright. [refresh] reloads both into an in-memory cache so the rest of the app
 * can keep treating [ALL] as a plain list.
 */
object Category {

    data class Item(val id: String, val label: String, val colorRes: Int)

    private data class BuiltIn(val id: String, val labelRes: Int, val colorRes: Int)

    private val BUILT_IN_DEFS = listOf(
        BuiltIn("food", R.string.cat_food, R.color.chart_1),
        BuiltIn("transport", R.string.cat_transport, R.color.chart_2),
        BuiltIn("bills", R.string.cat_bills, R.color.chart_3),
        BuiltIn("shopping", R.string.cat_shopping, R.color.chart_4),
        BuiltIn("health", R.string.cat_health, R.color.chart_5),
        BuiltIn("entertainment", R.string.cat_entertainment, R.color.chart_6),
        BuiltIn("other", R.string.cat_other, R.color.text_secondary)
    )

    /** The choices offered when adding a category — wide enough that a new category rarely
     * has to repeat a color already in use by something else. */
    val COLOR_PALETTE = intArrayOf(
        R.color.chart_1, R.color.chart_2, R.color.chart_3, R.color.chart_4, R.color.chart_5,
        R.color.chart_6, R.color.chart_7, R.color.chart_8, R.color.chart_9, R.color.chart_10
    )

    private const val CUSTOM_PREFIX = "custom_"

    private var builtIn: List<Item> = emptyList()
    private var custom: List<Item> = emptyList()

    val ALL: List<Item> get() = builtIn + custom

    const val DEFAULT: String = "other"

    /** Reloads built-in labels (in case the locale changed or one was hidden) and the user's
     * custom categories from the database. Call after opening the DB and again after
     * adding/removing/hiding one. */
    fun refresh(context: Context, dbHelper: DbHelper) {
        val hidden = CategoryPrefs.getHiddenBuiltIns(context)
        builtIn = BUILT_IN_DEFS
            .filter { it.id !in hidden }
            .map { Item(it.id, context.getString(it.labelRes), it.colorRes) }
        custom = dbHelper.getCustomCategories().map {
            Item(
                id = "$CUSTOM_PREFIX${it.id}",
                label = it.label,
                colorRes = COLOR_PALETTE[it.colorIndex.coerceIn(0, COLOR_PALETTE.lastIndex)]
            )
        }
    }

    /** The first palette color not already used by a visible category — a sensible default
     * for a new one, without forcing the user to pick. */
    fun suggestedColorIndex(): Int {
        val usedColors = ALL.map { it.colorRes }.toSet()
        val freeIndex = COLOR_PALETTE.indices.firstOrNull { COLOR_PALETTE[it] !in usedColors }
        return freeIndex ?: (custom.size % COLOR_PALETTE.size)
    }

    /** Adds a new custom category with an explicitly chosen palette color ([colorIndex], an
     * index into [COLOR_PALETTE]), refreshes the cache, and returns its category id. */
    fun add(context: Context, dbHelper: DbHelper, label: String, colorIndex: Int): String {
        val id = dbHelper.addCustomCategory(label, colorIndex.coerceIn(0, COLOR_PALETTE.lastIndex))
        refresh(context, dbHelper)
        return "$CUSTOM_PREFIX$id"
    }

    /** Hides a built-in category (it can't be deleted since its label isn't stored text). */
    fun hideBuiltIn(context: Context, dbHelper: DbHelper, id: String) {
        CategoryPrefs.hideBuiltIn(context, id)
        refresh(context, dbHelper)
    }

    fun isBuiltIn(id: String): Boolean = !id.startsWith(CUSTOM_PREFIX)

    /** The raw database row id for a custom category id (e.g. "custom_12" -> 12L), or null if
     * [id] isn't a custom category. */
    fun customRowId(id: String): Long? =
        if (id.startsWith(CUSTOM_PREFIX)) id.removePrefix(CUSTOM_PREFIX).toLongOrNull() else null

    fun labelOf(id: String): String = ALL.firstOrNull { it.id == id }?.label ?: (ALL.lastOrNull()?.label ?: id)

    fun colorResOf(id: String): Int = ALL.firstOrNull { it.id == id }?.colorRes ?: R.color.text_secondary

    fun isValid(id: String): Boolean = ALL.any { it.id == id }
}
