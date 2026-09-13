package com.hadi.expensetracker

data class Expense(
    val id: Long,
    val date: String,
    val title: String,
    val amount: Double,
    val category: String = Category.DEFAULT
)
