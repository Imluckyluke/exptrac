package com.hadi.expensetracker

data class PendingSms(
    val id: Long,
    val sender: String,
    val receivedAt: Long,
    val body: String,
    val guessedTitle: String,
    val guessedAmount: Double?
)
