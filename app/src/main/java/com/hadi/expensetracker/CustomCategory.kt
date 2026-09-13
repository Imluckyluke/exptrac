package com.hadi.expensetracker

/** A user-added expense category: a name and an index into a fixed color palette
 * (colors themselves stay in [Category] so both built-in and custom categories share it). */
data class CustomCategory(
    val id: Long,
    val label: String,
    val colorIndex: Int
)
