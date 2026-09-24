package com.hadi.expensetracker

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.animation.DecelerateInterpolator
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
    private var hy = 0
    private var hm = 0
    private var halfSecond = false
    private var isTransitioning = false

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
        if (savedInstanceState != null) {
            jy = savedInstanceState.getInt("jy", y)
            jm = savedInstanceState.getInt("jm", m)
            halfMode = savedInstanceState.getBoolean("halfMode", false)
            hy = savedInstanceState.getInt("hy", y)
            hm = savedInstanceState.getInt("hm", m)
            halfSecond = savedInstanceState.getBoolean("halfSecond", d >= 20)
        } else {
            jy = y
            jm = m
            // Anchor half-month period to today WITHOUT touching the monthly anchor.
            if (d >= 20) { hy = y; hm = m; halfSecond = true }
            else if (d >= 5) { hy = y; hm = m; halfSecond = false }
            else {
                val (py, pm) = prevMonthOf(y, m); hy = py; hm = pm; halfSecond = true
            }
        }

        val btnBack = findViewById<View>(R.id.btnBack)
        val btnPrevMonth = findViewById<View>(R.id.btnPrevMonth)
        val btnNextMonth = findViewById<View>(R.id.btnNextMonth)
        val btnMode = findViewById<View>(R.id.btnMode)
        listOf(btnBack, btnPrevMonth, btnNextMonth, btnMode).forEach { it.applyPressAnimation() }
        updateDirectionalIcons()

        btnBack.setOnClickListener { finishWithTransition() }
        btnPrevMonth.setOnClickListener {
            if (isTransitioning) return@setOnClickListener
            if (halfMode) changeHalf(-1) else changeMonth(-1)
        }
        btnNextMonth.setOnClickListener {
            if (isTransitioning) return@setOnClickListener
            if (halfMode) changeHalf(1) else changeMonth(1)
        }
        btnMode.setOnClickListener {
            if (isTransitioning) return@setOnClickListener
            halfMode = !halfMode
            updateModeButton(animate = true)
            loadCurrent(animate = true, direction = if (halfMode) 1 else -1)
        }
        updateModeButton()

        loadCurrent()
    }

    private fun updateModeButton(animate: Boolean = false) {
        findViewById<android.widget.Button>(R.id.btnMode)?.let { button ->
            button.text = getString(if (halfMode) R.string.stats_mode_month else R.string.stats_mode_half)
            if (animate) {
                button.animate().cancel()
                button.rotationY = 12f
                button.scaleX = 0.96f
                button.animate()
                    .rotationY(0f)
                    .scaleX(1f)
                    .setDuration(170)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
        }
    }

    private fun updateDirectionalIcons() {
        val rtl = resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnBack)
            .setIconResource(if (rtl) R.drawable.ic_chevron_right else R.drawable.ic_chevron_left)
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnPrevMonth)
            .setIconResource(if (rtl) R.drawable.ic_chevron_right else R.drawable.ic_chevron_left)
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnNextMonth)
            .setIconResource(if (rtl) R.drawable.ic_chevron_left else R.drawable.ic_chevron_right)
    }

    private fun loadCurrent(animate: Boolean = false, direction: Int = 0) {
        if (isTransitioning) return
        val content = findViewById<View>(R.id.statsScroll)
        if (animate && content != null) {
            isTransitioning = true
            content.animate().cancel()
            content.alpha = 0.68f
            content.scaleY = 0.99f
            content.translationX = 18f * direction.coerceAtLeast(-1).coerceAtMost(1)
        }
        if (halfMode) loadHalf() else loadMonth()
        if (animate) {
            tvMonthLabel.animate().cancel()
            tvMonthLabel.translationY = 5f
            tvMonthLabel.animate().translationY(0f).setDuration(170).start()
            tvMonthTotal.animate().cancel()
            tvMonthTotal.scaleX = 0.98f
            tvMonthTotal.scaleY = 0.98f
            tvMonthTotal.animate()
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(180)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
        if (animate && content != null) {
            content.animate()
                .alpha(1f)
                .scaleY(1f)
                .translationX(0f)
                .setDuration(190)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction { isTransitioning = false }
                .start()
        }
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
        loadCurrent(animate = true, direction = delta)
    }

    private fun nextMonthOf(y: Int, m: Int): Pair<Int, Int> = if (m == 12) Pair(y + 1, 1) else Pair(y, m + 1)
    private fun prevMonthOf(y: Int, m: Int): Pair<Int, Int> = if (m == 1) Pair(y - 1, 12) else Pair(y, m - 1)

    private fun changeHalf(delta: Int) {
        repeat(kotlin.math.abs(delta)) {
            if (delta > 0) {
                if (!halfSecond) { halfSecond = true } else {
                    val (ny, nm) = nextMonthOf(hy, hm); hy = ny; hm = nm; halfSecond = false
                }
            } else {
                if (halfSecond) { halfSecond = false } else {
                    val (py, pm) = prevMonthOf(hy, hm); hy = py; hm = pm; halfSecond = true
                }
            }
        }
        loadCurrent(animate = true, direction = delta)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("jy", jy)
        outState.putInt("jm", jm)
        outState.putBoolean("halfMode", halfMode)
        outState.putInt("hy", hy)
        outState.putInt("hm", hm)
        outState.putBoolean("halfSecond", halfSecond)
    }

    private fun displayNumber(value: Int): String =
        PersianDate.displayNumber(value, resources.configuration.locales[0])

    private fun displayMonth(value: Int): String =
        PersianDate.monthName(value, resources.configuration.locales[0].language == "fa")

    private fun setNoData(shouldShow: Boolean) {
        val isVisible = tvNoData.visibility == View.VISIBLE
        if (shouldShow == isVisible) return
        tvNoData.animate().cancel()
        if (shouldShow) {
            tvNoData.visibility = View.VISIBLE
            tvNoData.alpha = 0f
            tvNoData.translationY = 8f
            tvNoData.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(170)
                .setInterpolator(DecelerateInterpolator())
                .start()
        } else {
            tvNoData.animate()
                .alpha(0f)
                .translationY(-6f)
                .setDuration(120)
                .withEndAction { tvNoData.visibility = View.GONE }
                .start()
        }
    }

    private fun loadHalf() {
        val sKey: String
        val eKey: String
        if (!halfSecond) {
            sKey = PersianDate.dateKey(hy, hm, 5)
            eKey = PersianDate.dateKey(hy, hm, 20)
            tvMonthLabel.text = "${displayNumber(5)} ${displayMonth(hm)} - ${displayNumber(20)} ${displayMonth(hm)} ${displayNumber(hy)}"
        } else {
            val (ny, nm) = nextMonthOf(hy, hm)
            sKey = PersianDate.dateKey(hy, hm, 20)
            eKey = PersianDate.dateKey(ny, nm, 5)
            tvMonthLabel.text = "${displayNumber(20)} ${displayMonth(hm)} - ${displayNumber(5)} ${displayMonth(nm)} ${displayNumber(ny)}"
        }
        val expenses = dbHelper.getExpensesForDateRange(sKey, eKey)
        val total = expenses.sumOf { it.amount }
        tvMonthTotal.text = getString(R.string.stats_total_period_label, formatter.format(total))
        setNoData(expenses.isEmpty())
        renderCategoryBars(expenses)
        renderDayTotals(expenses)
    }

    private fun loadMonth() {
        tvMonthLabel.text = "${displayMonth(jm)} ${displayNumber(jy)}"

        val monthPrefix = PersianDate.monthPrefix(jy, jm)
        val expenses = dbHelper.getExpensesForMonth(monthPrefix)

        val total = expenses.sumOf { it.amount }
        tvMonthTotal.text = getString(R.string.stats_total_label, formatter.format(total))

        setNoData(expenses.isEmpty())

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
