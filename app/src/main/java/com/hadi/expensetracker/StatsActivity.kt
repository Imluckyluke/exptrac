package com.hadi.expensetracker

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.color.DynamicColors
import java.text.DecimalFormat
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.Locale

class StatsActivity : AppCompatActivity() {

    private lateinit var dbHelper: DbHelper
    private val formatter = DecimalFormat("#,###")

    private var jy = 0
    private var jm = 0
    private var halfMode = false
    private var halfSecond = false

    private lateinit var tvMonthLabel: TextView
    private lateinit var tvMonthTotal: TextView
    private lateinit var pieChart: PieChartView
    private lateinit var llCategoryBars: LinearLayout
    private lateinit var llDayTotals: LinearLayout
    private lateinit var tvNoData: View

    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_stats)

        dbHelper = DbHelper(this)
        Category.refresh(this, dbHelper)

        tvMonthLabel = findViewById(R.id.tvMonthLabel)
        tvMonthTotal = findViewById(R.id.tvMonthTotal)
        pieChart = findViewById(R.id.pieChart)
        llCategoryBars = findViewById(R.id.llCategoryBars)
        llDayTotals = findViewById(R.id.llDayTotals)
        tvNoData = findViewById(R.id.tvNoData)

        val cal = GregorianCalendar(Locale.US)
        val (y, m, d) = PersianDate.gregorianToJalali(
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH)
        )
        jy = y
        jm = m
        // Anchor half-month period to today: 5-20 or 20-5th(next).
        if (d >= 20) { halfSecond = true } else if (d >= 5) { halfSecond = false } else {
            if (jm == 1) { jy--; jm = 12 } else { jm-- }
            halfSecond = true
        }

        findViewById<View>(R.id.btnBack).setOnClickListener { finishWithTransition() }
        findViewById<View>(R.id.btnPrevMonth).setOnClickListener { if (halfMode) changeHalf(-1) else changeMonth(-1) }
        findViewById<View>(R.id.btnNextMonth).setOnClickListener { if (halfMode) changeHalf(1) else changeMonth(1) }
        findViewById<View>(R.id.btnMode).setOnClickListener {
            halfMode = !halfMode
            updateModeButton()
            loadCurrent()
        }
        updateModeButton()

        loadCurrent()
    }

    private fun updateModeButton() {
        findViewById<android.widget.Button>(R.id.btnMode)?.let {
            it.text = getString(if (halfMode) R.string.stats_mode_month else R.string.stats_mode_half)
        }
    }

    private fun loadCurrent() {
        if (halfMode) loadHalf() else loadMonth()
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
        loadCurrent()
    }

    private fun nextMonthOf(y: Int, m: Int): Pair<Int, Int> = if (m == 12) Pair(y + 1, 1) else Pair(y, m + 1)
    private fun prevMonthOf(y: Int, m: Int): Pair<Int, Int> = if (m == 1) Pair(y - 1, 12) else Pair(y, m - 1)

    private fun changeHalf(delta: Int) {
        repeat(kotlin.math.abs(delta)) {
            if (delta > 0) {
                if (!halfSecond) { halfSecond = true } else {
                    val (ny, nm) = nextMonthOf(jy, jm); jy = ny; jm = nm; halfSecond = false
                }
            } else {
                if (halfSecond) { halfSecond = false } else {
                    val (py, pm) = prevMonthOf(jy, jm); jy = py; jm = pm; halfSecond = true
                }
            }
        }
        loadCurrent()
    }

    private fun loadHalf() {
        val sKey: String
        val eKey: String
        if (!halfSecond) {
            sKey = PersianDate.dateKey(jy, jm, 5)
            eKey = PersianDate.dateKey(jy, jm, 20)
            tvMonthLabel.text = "5 ${PersianDate.monthName(jm)} - 20 ${PersianDate.monthName(jm)} $jy"
        } else {
            val (ny, nm) = nextMonthOf(jy, jm)
            sKey = PersianDate.dateKey(jy, jm, 20)
            eKey = PersianDate.dateKey(ny, nm, 5)
            tvMonthLabel.text = "20 ${PersianDate.monthName(jm)} - 5 ${PersianDate.monthName(nm)} $ny"
        }
        val expenses = dbHelper.getExpensesForDateRange(sKey, eKey)
        val total = expenses.sumOf { it.amount }
        tvMonthTotal.text = getString(R.string.stats_total_period_label, formatter.format(total))
        tvNoData.visibility = if (expenses.isEmpty()) View.VISIBLE else View.GONE
        renderCategoryBars(expenses)
        renderDayTotals(expenses)
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
        val inflater = LayoutInflater.from(this)

        val orderedCategories = Category.ALL
            .filter { (totals[it.id] ?: 0.0) > 0.0 }
            .sortedByDescending { totals[it.id] }

        pieChart.setData(
            orderedCategories.map { item ->
                PieChartView.Slice(
                    color = ContextCompat.getColor(this, item.colorRes),
                    amount = (totals[item.id] ?: 0.0).toFloat()
                )
            }
        )

        for (item in orderedCategories) {
            val amount = totals[item.id] ?: 0.0
            val row = inflater.inflate(R.layout.item_category_bar, llCategoryBars, false)
            row.findViewById<TextView>(R.id.tvCatLabel).text = item.label
            row.findViewById<TextView>(R.id.tvCatAmount).text = formatter.format(amount)
            row.findViewById<View>(R.id.catDot).background.mutate().setTint(
                ContextCompat.getColor(this, item.colorRes)
            )
            llCategoryBars.addView(row)
        }
        llCategoryBars.animateChildrenIn()
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
        llDayTotals.animateChildrenIn()
    }

    private fun finishWithTransition() {
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.slide_enter_back, R.anim.slide_exit_back)
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        super.onBackPressed()
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.slide_enter_back, R.anim.slide_exit_back)
    }
}
