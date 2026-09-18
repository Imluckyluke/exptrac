package com.hadi.expensetracker

/** A user-added bank SMS source: a name, the sender number/short-code, and a sample message
 * kept only so the settings screen can preview what the generic parser extracts from it. */
data class CustomBank(
    val id: Long,
    val label: String,
    val sender: String,
    val sample: String,
    val enabled: Boolean
)
