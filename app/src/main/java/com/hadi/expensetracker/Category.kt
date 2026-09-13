package com.hadi.expensetracker

import android.content.Context

/**
 * The built-in categories are fixed (but localized), and the user can add their own on top
 * (see the "+" chip wherever categories are picked). [refresh] loads the built-in labels for
 * the current locale plus the user's custom ones from the database into an in-memory cache, so
 * the rest of the app can keep treating [ALL] as a plain list.
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

    /** Custom categories cycle through the same chart colors as the built-ins. */
    private val CUSTOM_COLOR_PALETTE = intArrayOf(
        R.color.chart_1, R.color.chart_2, R.color.chart_3,
        R.color.chart_4, R.color.chart_5, R.color.chart_6
    )

    private const val CUSTOM_PREFIX = "custom_"

    private var builtIn: List<Item> = emptyList()
    private var custom: List<Item> = emptyList()

    val ALL: List<Item> get() = builtIn + custom

    const val DEFAULT: String = "other"

    /** Reloads built-in labels (in case the locale changed) and the user's custom categories
     * from the database. Call after opening the DB and again after adding/removing one. */
    fun refresh(context: Context, dbHelper: DbHelper) {
        builtIn = BUILT_IN_DEFS.map { Item(it.id, context.getString(it.labelRes), it.colorRes) }
        custom = dbHelper.getCustomCategories().map {
            Item(
                id = "$CUSTOM_PREFIX${it.id}",
                label = it.label,
                colorRes = CUSTOM_COLOR_PALETTE[it.colorIndex.coerceAtLeast(0) % CUSTOM_COLOR_PALETTE.size]
            )
        }
    }

    /** Adds a new custom category, refreshes the cache, and returns its category id. */
    fun add(context: Context, dbHelper: DbHelper, label: String): String {
        val colorIndex = custom.size % CUSTOM_COLOR_PALETTE.size
        val id = dbHelper.addCustomCategory(label, colorIndex)
        refresh(context, dbHelper)
        return "$CUSTOM_PREFIX$id"
    }

    fun labelOf(id: String): String = ALL.firstOrNull { it.id == id }?.label ?: (ALL.lastOrNull()?.label ?: id)

    fun colorResOf(id: String): Int = ALL.firstOrNull { it.id == id }?.colorRes ?: R.color.text_secondary

    fun isValid(id: String): Boolean = ALL.any { it.id == id }
}
