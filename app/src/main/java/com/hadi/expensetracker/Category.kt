package com.hadi.expensetracker

/**
 * Fixed set of expense categories. Not user-editable — keeps the monthly breakdown chart
 * meaningful without needing a category-management UI.
 */
object Category {

    data class Item(val id: String, val label: String, val colorRes: Int)

    val ALL: List<Item> = listOf(
        Item("food", "Food", R.color.chart_1),
        Item("transport", "Transport", R.color.chart_2),
        Item("bills", "Bills", R.color.chart_3),
        Item("shopping", "Shopping", R.color.chart_4),
        Item("health", "Health", R.color.chart_5),
        Item("entertainment", "Entertainment", R.color.chart_6),
        Item("other", "Other", R.color.text_secondary)
    )

    const val DEFAULT: String = "other"

    fun labelOf(id: String): String = ALL.firstOrNull { it.id == id }?.label ?: ALL.last().label

    fun colorResOf(id: String): Int = ALL.firstOrNull { it.id == id }?.colorRes ?: ALL.last().colorRes

    fun isValid(id: String): Boolean = ALL.any { it.id == id }
}
