package com.hadi.expensetracker

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.text.DecimalFormat
import java.util.Calendar

class StatsActivity : AppCompatActivity() {

    private lateinit var dbHelper: DbHelper
    private val formatter = DecimalFormat("#,###")

    private var jy = 0
    private var jm = 0

    private lateinit var tvMonthLabel: TextView
    private lateinit var tvMonthTotal: TextView
    private lateinit var llCategoryBars: LinearLayout
    private lateinit var llDayTotals: LinearLayout
    private lateinit var tvNoData: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_stats)

        dbHelper = DbHelper(this)

        tvMonthLabel = findViewById(R.id.tvMonthLabel)
        tvMonthTotal = findViewById(R.id.tvMonthTotal)
        llCategoryBars = findViewById(R.id.llCategoryBars)
        llDayTotals = findViewById(R.id.llDayTotals)
        tvNoData = findViewById(R.id.tvNoData)

        val cal = Calendar.getInstance()
        val (y, m, _) = PersianDate.gregorianToJalali(
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH)
        )
        jy = y
        jm = m

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnPrevMonth).setOnClickListener { changeMonth(-1) }
        findViewById<View>(R.id.btnNextMonth).setOnClickListener { changeMonth(1) }

        loadMonth()
    }

    private fun changeMonth(delta: Int) {
        jm += delta
        if (jm > 12) {
            jm = 1
            jy++
        } else if (jm < 1) {
            jm = 12
            jy--
        }
        loadMonth()
    }

    private fun loadMonth() {
        tvMonthLabel.text = "${PersianDate.monthName(jm)} $jy"

        val monthPrefix = String.format("%04d-%02d-", jy, jm)
        val expenses = dbHelper.getExpensesForMonth(monthPrefix)

        val total = expenses.sumOf { it.amount }
        tvMonthTotal.text = getString(R.string.stats_total_label, formatter.format(total))

        tvNoData.visibility = if (expenses.isEmpty()) View.VISIBLE else View.GONE

        renderCategoryBars(expenses)
        renderDayTotals(expenses)
    }

    private fun renderCategoryBars(expenses: List<Expense>) {
        llCategoryBars.removeAllViews()
        val totals = LinkedHashMap<String, Double>()
        for (item in Category.ALL) totals[item.id] = 0.0
        for (expense in expenses) {
            totals[expense.category] = (totals[expense.category] ?: 0.0) + expense.amount
        }
        val maxAmount = totals.values.maxOrNull() ?: 0.0
        val inflater = LayoutInflater.from(this)

        for (item in Category.ALL) {
            val amount = totals[item.id] ?: 0.0
            if (amount <= 0.0) continue

            val row = inflater.inflate(R.layout.item_category_bar, llCategoryBars, false)
            row.findViewById<TextView>(R.id.tvCatLabel).text = item.label
            row.findViewById<TextView>(R.id.tvCatAmount).text = formatter.format(amount)

            val barFill = row.findViewById<View>(R.id.barFill)
            val barEmpty = row.findViewById<View>(R.id.barEmpty)
            val fraction = if (maxAmount > 0) (amount / maxAmount).toFloat().coerceIn(0.03f, 1f) else 0f
            (barFill.layoutParams as LinearLayout.LayoutParams).weight = fraction
            (barEmpty.layoutParams as LinearLayout.LayoutParams).weight = (1f - fraction).coerceAtLeast(0f)
            barFill.setBackgroundColor(ContextCompat.getColor(this, item.colorRes))

            llCategoryBars.addView(row)
        }
    }

    private fun renderDayTotals(expenses: List<Expense>) {
        llDayTotals.removeAllViews()
        val totals = LinkedHashMap<String, Double>()
        for (expense in expenses) {
            totals[expense.date] = (totals[expense.date] ?: 0.0) + expense.amount
        }
        val inflater = LayoutInflater.from(this)

        for ((date, amount) in totals) {
            val day = date.substringAfterLast("-").toIntOrNull() ?: continue
            val row = inflater.inflate(R.layout.item_day_total, llDayTotals, false)
            row.findViewById<TextView>(R.id.tvDayLabel).text = getString(R.string.stats_day_format, day)
            row.findViewById<TextView>(R.id.tvDayAmount).text = formatter.format(amount)
            llDayTotals.addView(row)
        }
    }
}
