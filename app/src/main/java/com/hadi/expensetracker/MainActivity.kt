package com.hadi.expensetracker

import android.Manifest
import android.animation.ValueAnimator
import android.content.Intent
import android.content.res.Configuration
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.text.format.DateFormat
import android.view.View
import android.view.MotionEvent
import android.view.animation.OvershootInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.color.DynamicColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.shape.ShapeAppearanceModel
import com.google.android.material.snackbar.Snackbar
import com.hadi.expensetracker.databinding.ActivityMainBinding
import com.hadi.expensetracker.databinding.DialogAddBankBinding
import com.hadi.expensetracker.databinding.DialogBackupMenuBinding
import com.hadi.expensetracker.databinding.DialogEditExpenseBinding
import com.hadi.expensetracker.databinding.DialogManageCategoriesBinding
import com.hadi.expensetracker.databinding.DialogReviewSmsBinding
import com.hadi.expensetracker.databinding.DialogSmsSettingsBinding
import com.hadi.expensetracker.databinding.ItemCustomBankBinding
import com.hadi.expensetracker.databinding.ItemCustomCategoryBinding
import java.text.DecimalFormat
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var dbHelper: DbHelper
    private lateinit var adapter: ExpenseAdapter
    private val formatter = DecimalFormat("#,###")

    private var jy = 0
    private var jm = 0
    private var jd = 0

    // The Jalali date this device considers "today", captured once at launch so we can
    // show a "Today" badge whenever the user navigates back to it.
    private var todayY = 0
    private var todayM = 0
    private var todayD = 0

    // Guards against animating the total counter up from zero on first screen load.
    private var isFirstLoad = true
    private var lastTotal = 0.0
    private var totalAnimator: ValueAnimator? = null

    // Tracks whether the header card is currently showing its "scrolled" shadow, so we
    // only animate a transition when the state actually changes.
    private var isHeaderElevated = false

    // While the SMS settings dialog is open, points at its permission-status label so the
    // permission launcher's callback can refresh it.
    private var smsPermissionStatusView: TextView? = null

    // Prevents a second review dialog chain from starting while one is already showing.
    private var isReviewFlowActive = false

    private val smsPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
            smsPermissionStatusView?.let { updatePermissionStatusText(it) }
        }

    private val exportBackupLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri == null) return@registerForActivityResult
            try {
                contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(BackupHelper.exportJson(dbHelper).toByteArray(Charsets.UTF_8))
                }
                Snackbar.make(binding.root, R.string.msg_backup_exported, Snackbar.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Snackbar.make(binding.root, R.string.msg_backup_export_failed, Snackbar.LENGTH_SHORT).show()
            }
        }

    private val importBackupLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            try {
                val text = contentResolver.openInputStream(uri)?.bufferedReader()?.readText().orEmpty()
                val result = BackupHelper.importJson(this, dbHelper, text)
                Snackbar.make(
                    binding.root,
                    getString(
                        R.string.msg_backup_imported,
                        result.expensesAdded,
                        result.categoriesAdded,
                        result.banksAdded
                    ),
                    Snackbar.LENGTH_LONG
                ).show()
                populateCategoryChips(binding.chipGroupCategory, selectedCategory(binding.chipGroupCategory))
                refreshList()
                BalanceWidgetProvider.updateAll(this)
            } catch (e: Exception) {
                Snackbar.make(binding.root, R.string.msg_backup_import_failed, Snackbar.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        setupEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets(binding.root)

        dbHelper = DbHelper(this)
        Category.refresh(this, dbHelper)

        if (savedInstanceState != null) {
            // Survive rotation / language switch on the same viewed day.
            jy = savedInstanceState.getInt("jy")
            jm = savedInstanceState.getInt("jm")
            jd = savedInstanceState.getInt("jd")
            todayY = savedInstanceState.getInt("todayY", jy)
            todayM = savedInstanceState.getInt("todayM", jm)
            todayD = savedInstanceState.getInt("todayD", jd)
        } else {
            val cal = GregorianCalendar(Locale.US)
            val (y, m, d) = PersianDate.gregorianToJalali(
                cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH) + 1,
                cal.get(Calendar.DAY_OF_MONTH)
            )
            jy = y; jm = m; jd = d
            todayY = y; todayM = m; todayD = d
        }

        adapter = ExpenseAdapter(
            onEdit = { expense -> showEditExpenseDialog(expense) },
            onDelete = { expense -> deleteExpense(expense, showUndo = true) }
        )
        binding.rvExpenses.layoutManager = LinearLayoutManager(this)
        binding.rvExpenses.adapter = adapter
        (binding.rvExpenses.itemAnimator as? SimpleItemAnimator)?.apply {
            addDuration = 220
            removeDuration = 220
            moveDuration = 220
            changeDuration = 180
        }

        ItemTouchHelper(SwipeToDeleteCallback(binding.rvExpenses) { position ->
            if (position < 0) return@SwipeToDeleteCallback
            val expense = adapter.currentList.getOrNull(position)
            if (expense != null) {
                deleteExpense(expense, showUndo = true)
            } else {
                adapter.notifyItemChanged(position)
            }
        }).attachToRecyclerView(binding.rvExpenses)

        binding.rvExpenses.addOnItemTouchListener(DaySwipeGestureListener())

        setupScrollElevation()

        binding.btnPrev.applyPressAnimation()
        binding.btnNext.applyPressAnimation()
        binding.btnAdd.applyPressAnimation()
        binding.btnSmsSettings.applyPressAnimation()
        binding.btnStats.applyPressAnimation()
        binding.btnBackup.applyPressAnimation()
        binding.btnLanguage.applyPressAnimation()

        binding.btnPrev.setOnClickListener { changeDay(-1) }
        binding.btnNext.setOnClickListener { changeDay(1) }
        binding.btnAdd.setOnClickListener { addExpense() }
        binding.btnSmsSettings.setOnClickListener { showSmsSettingsDialog() }
        binding.btnStats.setOnClickListener {
            startActivity(Intent(this, StatsActivity::class.java))
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.slide_enter_forward, R.anim.slide_exit_forward)
        }
        binding.btnBackup.setOnClickListener { showBackupMenu() }
        binding.btnLanguage.setOnClickListener { toggleAppLanguage() }

        setupAmountFormatting()
        populateCategoryChips(binding.chipGroupCategory, Category.DEFAULT)

        updateDateLabel(animate = false)
        refreshList()
    }

    override fun onResume() {
        super.onResume()
        // Messages may have arrived from the background receiver while the app was closed.
        Thread {
            val hasPending = dbHelper.getAllPendingSms().isNotEmpty()
            if (hasPending) runOnUiThread { startReviewFlow() }
        }.start()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("jy", jy)
        outState.putInt("jm", jm)
        outState.putInt("jd", jd)
        outState.putInt("todayY", todayY)
        outState.putInt("todayM", todayM)
        outState.putInt("todayD", todayD)
    }

    // ---- Category chips ----

    private fun populateCategoryChips(chipGroup: ChipGroup, selectedId: String) {
        chipGroup.removeAllViews()
        for (item in Category.ALL) {
            val chip = Chip(this)
            chip.text = item.label
            chip.isCheckable = true
            chip.tag = item.id
            chip.isChecked = item.id == selectedId
            chip.chipIcon = ContextCompat.getDrawable(this, R.drawable.dot_category)?.mutate()
            chip.chipIcon?.setTint(ContextCompat.getColor(this, item.colorRes))
            chip.chipIconSize = resources.getDimension(R.dimen.category_dot_size)
            chip.isChipIconVisible = true
            chip.shapeAppearanceModel = ShapeAppearanceModel.builder()
                .setAllCornerSizes(20f * resources.displayMetrics.density)
                .build()
            chipGroup.addView(chip)
        }
        val addChip = Chip(this)
        addChip.text = getString(R.string.action_add)
        addChip.isCheckable = false
        addChip.chipIcon = ContextCompat.getDrawable(this, R.drawable.ic_add)
        addChip.chipIconTint = addChip.textColors
        addChip.isChipIconVisible = true
        addChip.contentDescription = getString(R.string.cd_add_category)
        addChip.applyPressAnimation()
        addChip.setOnClickListener {
            showManageCategoriesDialog(chipGroup)
        }
        chipGroup.addView(addChip)
    }

    /** One place to add or remove custom categories, shared by the expense form and the SMS
     * review dialog; [chipGroup] is whichever one triggered it, so this can refresh it in place. */
    private fun showManageCategoriesDialog(chipGroup: ChipGroup) {
        val dialogBinding = DialogManageCategoriesBinding.inflate(layoutInflater)
        var selectedColorIndex = Category.suggestedColorIndex()

        fun renderColorPicker() {
            dialogBinding.llColorPicker.removeAllViews()
            val swatchSize = (28 * resources.displayMetrics.density).toInt()
            val swatchMargin = (8 * resources.displayMetrics.density).toInt()
            Category.COLOR_PALETTE.forEachIndexed { index, colorRes ->
                val swatch = View(this)
                val params = LinearLayout.LayoutParams(swatchSize, swatchSize)
                params.marginEnd = swatchMargin
                swatch.layoutParams = params
                swatch.background = ContextCompat.getDrawable(this, R.drawable.dot_category)?.mutate()
                swatch.background?.setTint(ContextCompat.getColor(this, colorRes))
                val isSelected = index == selectedColorIndex
                swatch.alpha = if (isSelected) 1f else 0.4f
                swatch.scaleX = if (isSelected) 1.15f else 1f
                swatch.scaleY = if (isSelected) 1.15f else 1f
                swatch.setOnClickListener {
                    selectedColorIndex = index
                    renderColorPicker()
                }
                dialogBinding.llColorPicker.addView(swatch)
            }
        }

        fun refreshCategoryList() {
            dialogBinding.llCustomCategories.removeAllViews()
            val categories = Category.ALL
            dialogBinding.tvNoCustomCategories.visibility = if (categories.isEmpty()) View.VISIBLE else View.GONE
            for (item in categories) {
                val rowBinding = ItemCustomCategoryBinding.inflate(layoutInflater, dialogBinding.llCustomCategories, false)
                rowBinding.tvCategoryLabel.text = item.label
                rowBinding.catDot.background.mutate().setTint(ContextCompat.getColor(this, item.colorRes))
                if (item.id == Category.DEFAULT) {
                    // Always kept around as the fallback bucket for uncategorized expenses.
                    rowBinding.btnDeleteCategory.visibility = View.GONE
                } else {
                    rowBinding.btnDeleteCategory.setOnClickListener {
                        val fallback = selectedCategory(chipGroup)
                        if (Category.isBuiltIn(item.id)) {
                            Category.hideBuiltIn(this, dbHelper, item.id)
                        } else {
                            Category.customRowId(item.id)?.let { dbHelper.deleteCustomCategory(it) }
                            Category.refresh(this, dbHelper)
                        }
                        refreshCategoryList()
                        populateCategoryChips(chipGroup, if (fallback == item.id) Category.DEFAULT else fallback)
                        Snackbar.make(binding.root, R.string.msg_category_removed, Snackbar.LENGTH_SHORT).show()
                    }
                }
                dialogBinding.llCustomCategories.addView(rowBinding.root)
            }
            dialogBinding.llCustomCategories.animateChildrenIn()
        }

        renderColorPicker()
        refreshCategoryList()

        dialogBinding.btnAddCategory.setOnClickListener {
            val label = dialogBinding.etCategoryLabel.text.toString().trim()
            if (label.isEmpty()) {
                Snackbar.make(binding.root, R.string.msg_category_required, Snackbar.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val newId = Category.add(this, dbHelper, label, selectedColorIndex)
            dialogBinding.etCategoryLabel.text?.clear()
            selectedColorIndex = Category.suggestedColorIndex()
            renderColorPicker()
            refreshCategoryList()
            populateCategoryChips(chipGroup, newId)
            Snackbar.make(binding.root, R.string.msg_category_added, Snackbar.LENGTH_SHORT).show()
        }

        MaterialAlertDialogBuilder(this)
            .setView(dialogBinding.root)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun selectedCategory(chipGroup: ChipGroup): String {
        val checkedId = chipGroup.checkedChipId
        val chip = chipGroup.findViewById<Chip>(checkedId)
        val id = chip?.tag as? String
        return if (id != null && Category.isValid(id)) id else Category.DEFAULT
    }

    /** Lifts the header card with a subtle shadow once the list underneath is scrolled. */
    private fun setupScrollElevation() {
        val resting = resources.getDimension(R.dimen.elevation_header_resting)
        val scrolled = resources.getDimension(R.dimen.elevation_header_scrolled)
        binding.rvExpenses.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                val shouldElevate = recyclerView.canScrollVertically(-1)
                if (shouldElevate != isHeaderElevated) {
                    isHeaderElevated = shouldElevate
                    ValueAnimator.ofFloat(
                        binding.headerCard.cardElevation,
                        if (shouldElevate) scrolled else resting
                    ).apply {
                        duration = 150
                        addUpdateListener { binding.headerCard.cardElevation = it.animatedValue as Float }
                        start()
                    }
                }
            }
        })
    }

    /** Live-formats the amount field with thousands separators as the user types (e.g. 12,000). */
    private fun setupAmountFormatting() {
        attachAmountFormatter(binding.etAmount)
    }

    /** Normalizes user-typed amounts: Persian/Arabic digits -> Latin, drops all
     * thousands separators (ASCII comma, Persian thousands, spaces) so fa/en input parses. */
    private fun normalizeAmountInput(raw: String): String {
        val sb = StringBuilder(raw.length)
        for (ch in raw) {
            when (ch) {
                in '۰'..'۹' -> sb.append(ch - '۰')
                in '٠'..'٩' -> sb.append(ch - '٠')
                ',', '٬', '،', ' ', ' ', '٫' -> {}
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun attachAmountFormatter(editText: android.widget.EditText) {
        var isFormatting = false
        editText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                if (isFormatting || s == null) return
                isFormatting = true

                val digitsOnly = normalizeAmountInput(s.toString())
                if (digitsOnly.isEmpty()) {
                    isFormatting = false
                    return
                }

                val formatted = try {
                    formatter.format(digitsOnly.toLong())
                } catch (e: NumberFormatException) {
                    digitsOnly
                }

                if (formatted != s.toString()) {
                    editText.setText(formatted)
                    editText.setSelection(formatted.length)
                }
                isFormatting = false
            }
        })
    }

    private fun changeDay(delta: Int) {
        val (gy, gm, gd) = PersianDate.jalaliToGregorian(jy, jm, jd)
        val cal = GregorianCalendar(Locale.US)
        cal.set(gy, gm - 1, gd)
        cal.add(Calendar.DAY_OF_MONTH, delta)
        val (ny, nm, nd) = PersianDate.gregorianToJalali(
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH)
        )
        jy = ny; jm = nm; jd = nd
        updateDateLabel(animate = true)
        animateDayChange()
    }

    /** Swaps the day's list without a visible blink. The RecyclerView's own item
     * animator is held off until the new list has landed (submitList diffs
     * asynchronously), and the content only dips to 0.9 — a full fade-out reads
     * as a flash on every day switch. */
    private fun animateDayChange() {
        binding.dayContent.animate().cancel()
        binding.dayContent.alpha = 0.9f

        val defaultItemAnimator = binding.rvExpenses.itemAnimator
        binding.rvExpenses.itemAnimator = null

        refreshList {
            binding.rvExpenses.itemAnimator = defaultItemAnimator
            binding.dayContent.animate()
                .alpha(1f)
                .setDuration(120)
                .start()
        }
    }

    /** Lets the user swipe to the previous/next day from empty space in the list — either an
     * empty day, or the blank area below the last item — without interfering with the list's
     * own vertical scrolling or the per-row swipe-to-delete gesture. */
    private inner class DaySwipeGestureListener : RecyclerView.SimpleOnItemTouchListener() {
        private var startX = 0f
        private var startY = 0f
        private var isOverBlankSpace = false
        private var handled = false

        override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    isOverBlankSpace = rv.findChildViewUnder(e.x, e.y) == null
                    startX = e.x
                    startY = e.y
                    handled = false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isOverBlankSpace && !handled) {
                        val dx = e.x - startX
                        val dy = e.y - startY
                        val thresholdPx = 60f * rv.resources.displayMetrics.density
                        if (kotlin.math.abs(dx) > thresholdPx && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.5f) {
                            handled = true
                            changeDay(if (dx < 0) 1 else -1)
                            return true
                        }
                    }
                }
            }
            return false
        }
    }

    private fun updateDateLabel(animate: Boolean) {
        val newText = "$jd ${PersianDate.monthName(jm)} $jy"
        val isToday = jy == todayY && jm == todayM && jd == todayD

        if (animate) {
            binding.tvDate.animate().cancel()
            binding.tvDate.animate().alpha(0f).setDuration(100).withEndAction {
                binding.tvDate.text = newText
                binding.tvDate.alpha = 0f
                binding.tvDate.animate().alpha(1f).setDuration(150).start()
            }.start()
        } else {
            binding.tvDate.text = newText
        }

        val badgeCurrentlyVisible = binding.todayBadge.visibility == View.VISIBLE
        if (isToday == badgeCurrentlyVisible) return
        binding.todayBadge.animate().cancel()

        if (isToday) {
            binding.todayBadge.visibility = View.VISIBLE
            binding.todayBadge.alpha = 0f
            binding.todayBadge.scaleX = 0.7f
            binding.todayBadge.scaleY = 0.7f
            binding.todayBadge.animate()
                .alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(200)
                .setInterpolator(OvershootInterpolator(1.8f))
                .start()
        } else {
            binding.todayBadge.animate()
                .alpha(0f).scaleX(0.7f).scaleY(0.7f)
                .setDuration(150)
                .withEndAction { binding.todayBadge.visibility = View.GONE }
                .start()
        }
    }

    private fun addExpense() {
        val title = binding.etTitle.text.toString().trim()
        val amountText = normalizeAmountInput(binding.etAmount.text.toString().trim())

        if (title.isEmpty()) {
            Snackbar.make(binding.root, R.string.msg_enter_description, Snackbar.LENGTH_SHORT).show()
            return
        }
        val amount = amountText.toDoubleOrNull()
        if (amount == null || amount <= 0) {
            Snackbar.make(binding.root, R.string.msg_enter_valid_amount, Snackbar.LENGTH_SHORT).show()
            return
        }

        val category = selectedCategory(binding.chipGroupCategory)
        val key = PersianDate.dateKey(jy, jm, jd)
        dbHelper.insertExpense(key, title, amount, category)

        binding.etTitle.text?.clear()
        binding.etAmount.text?.clear()

        refreshList()
        binding.rvExpenses.post {
            adapter.currentList.lastIndex.takeIf { it >= 0 }?.let { lastIndex ->
                // Instant, not smooth: a long fling from the top reads as a glitch.
                (binding.rvExpenses.layoutManager as? LinearLayoutManager)
                    ?.scrollToPositionWithOffset(lastIndex, 0)
            }
        }
    }

    /** Deletes an expense and, when [showUndo] is true, offers a Snackbar to restore it. */
    private fun deleteExpense(expense: Expense, showUndo: Boolean) {
        dbHelper.deleteExpense(expense.id)
        refreshList()
        if (showUndo) {
            Snackbar.make(binding.root, R.string.msg_expense_deleted, Snackbar.LENGTH_LONG)
                .setAction(R.string.action_undo) {
                    dbHelper.insertExpense(expense.date, expense.title, expense.amount, expense.category)
                    refreshList()
                }
                .show()
        }
    }

    /** Opens an edit dialog pre-filled with [expense]'s current title, amount, and category. */
    private fun showEditExpenseDialog(expense: Expense) {
        val dialogBinding = DialogEditExpenseBinding.inflate(layoutInflater)
        dialogBinding.etEditTitle.setText(expense.title)
        dialogBinding.etEditAmount.setText(formatter.format(expense.amount))
        attachAmountFormatter(dialogBinding.etEditAmount)
        populateCategoryChips(dialogBinding.chipGroupEditCategory, expense.category)

        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.btn_save, null)
            .setNeutralButton(R.string.btn_delete, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val title = dialogBinding.etEditTitle.text.toString().trim()
                val amountText = normalizeAmountInput(dialogBinding.etEditAmount.text.toString().trim())

                if (title.isEmpty()) {
                    Snackbar.make(binding.root, R.string.msg_enter_description, Snackbar.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val amount = amountText.toDoubleOrNull()
                if (amount == null || amount <= 0) {
                    Snackbar.make(binding.root, R.string.msg_enter_valid_amount, Snackbar.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                val category = selectedCategory(dialogBinding.chipGroupEditCategory)
                dbHelper.updateExpense(expense.id, title, amount, category)
                refreshList()
                Snackbar.make(binding.root, R.string.msg_expense_updated, Snackbar.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            dialog.getButton(android.content.DialogInterface.BUTTON_NEUTRAL).setOnClickListener {
                dialog.dismiss()
                deleteExpense(expense, showUndo = true)
            }
        }
        dialog.show()
    }

    private fun refreshList(onCommitted: (() -> Unit)? = null) {
        val key = PersianDate.dateKey(jy, jm, jd)
        val list = dbHelper.getExpensesForDate(key)
        adapter.submitList(list) { onCommitted?.invoke() }
        updateEmptyState(list.isEmpty())

        val total = list.sumOf { it.amount }
        animateTotal(total)
        BalanceWidgetProvider.updateAll(this)
    }

    private fun updateEmptyState(shouldShow: Boolean) {
        val isShown = binding.emptyState.visibility == View.VISIBLE
        if (shouldShow == isShown) return
        binding.emptyState.animate().cancel()

        if (shouldShow) {
            binding.emptyState.visibility = View.VISIBLE
            binding.emptyState.alpha = 0f
            binding.emptyState.animate().alpha(1f).setDuration(200).start()
        } else {
            binding.emptyState.animate().alpha(0f).setDuration(150)
                .withEndAction { binding.emptyState.visibility = View.GONE }
                .start()
        }
    }

    /** Animates the total label counting up/down to its new value instead of jumping. */
    private fun animateTotal(newTotal: Double) {
        totalAnimator?.cancel()

        if (isFirstLoad) {
            isFirstLoad = false
            lastTotal = newTotal
            binding.tvTotal.text = getString(R.string.total_label, formatter.format(newTotal))
            return
        }

        totalAnimator = ValueAnimator.ofFloat(lastTotal.toFloat(), newTotal.toFloat()).apply {
            duration = 350
            addUpdateListener {
                val value = (it.animatedValue as Float).toDouble()
                binding.tvTotal.text = getString(R.string.total_label, formatter.format(value))
            }
            start()
        }
        lastTotal = newTotal
    }

    // ---- Edge-to-edge status/navigation bars ----

    /**
     * Makes the status and navigation bars transparent so the activity's own background shows
     * through them instead of the system drawing a separate (often black, in dark mode) bar
     * color, and sets light/dark bar icons to match the current theme.
     */
    private fun setupEdgeToEdge() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        val isNightMode = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !isNightMode
            isAppearanceLightNavigationBars = !isNightMode
        }
    }

    /** Adds the system bar insets on top of [view]'s existing padding so content isn't hidden behind them. */
    private fun applySystemBarInsets(view: View) {
        val initialTop = view.paddingTop
        val initialBottom = view.paddingBottom
        val initialLeft = view.paddingLeft
        val initialRight = view.paddingRight
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(
                initialLeft + bars.left,
                initialTop + bars.top,
                initialRight + bars.right,
                initialBottom + maxOf(bars.bottom, ime.bottom)
            )
            insets
        }
    }

    // ---- Language ----

    /** Toggles the app's language between Persian and English, independent of the device's
     * system language. AppCompatDelegate persists the choice and recreates the activity (and
     * any others running) to apply it. */
    private fun toggleAppLanguage() {
        val currentTag = AppCompatDelegate.getApplicationLocales()[0]?.language
            ?: resources.configuration.locales[0].language
        val nextTag = if (currentTag == "fa") "en" else "fa"
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(nextTag))
    }

    // ---- Backup ----

    private fun showBackupMenu() {
        val dialogBinding = DialogBackupMenuBinding.inflate(layoutInflater)
        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogBinding.root)
            .create()

        dialogBinding.rowExport.setOnClickListener {
            dialog.dismiss()
            val fileName = "expense_backup_${PersianDate.dateKey(jy, jm, jd)}.json"
            exportBackupLauncher.launch(fileName)
        }
        dialogBinding.rowImport.setOnClickListener {
            dialog.dismiss()
            importBackupLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
        }
        dialog.show()
    }

    // ---- SMS import ----

    private fun hasSmsPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED

    private fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun updatePermissionStatusText(view: TextView) {
        view.text = getString(
            if (hasSmsPermission() && hasNotificationPermission()) {
                R.string.sms_permission_granted
            } else {
                R.string.sms_permission_not_granted
            }
        )
    }

    private fun requestSmsAndNotificationPermissions() {
        val perms = mutableListOf(Manifest.permission.RECEIVE_SMS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        smsPermissionLauncher.launch(perms.toTypedArray())
    }

    private fun renderCustomBanksList(container: LinearLayout) {
        container.removeAllViews()
        for (bank in dbHelper.getCustomBanks()) {
            val rowBinding = ItemCustomBankBinding.inflate(layoutInflater, container, false)
            rowBinding.cbBankEnabled.isChecked = bank.enabled
            rowBinding.tvBankLabel.text = bank.label
            rowBinding.tvBankSender.text = getString(R.string.bank_sender_format, bank.sender)
            rowBinding.cbBankEnabled.setOnCheckedChangeListener { _, checked ->
                dbHelper.setCustomBankEnabled(bank.id, checked)
                BalanceWidgetProvider.updateAll(this)
            }
            rowBinding.btnDeleteBank.setOnClickListener {
                dbHelper.deleteCustomBank(bank.id)
                Snackbar.make(binding.root, R.string.msg_bank_removed, Snackbar.LENGTH_SHORT).show()
                renderCustomBanksList(container)
                BalanceWidgetProvider.updateAll(this)
            }
            container.addView(rowBinding.root)
        }
        container.animateChildrenIn()
    }

    private fun showAddBankDialog(onAdded: () -> Unit) {
        val dialogBinding = DialogAddBankBinding.inflate(layoutInflater)

        fun updatePreview() {
            val sample = dialogBinding.etBankSample.text.toString()
            dialogBinding.tvBankSamplePreview.text = if (sample.isBlank()) {
                getString(R.string.preview_bank_sample_empty)
            } else {
                val parsed = BankSmsParser.parseGeneric(sample)
                val amountText = parsed.amount?.let { formatter.format(it) } ?: "?"
                "${parsed.title} — $amountText Toman"
            }
        }

        dialogBinding.etBankSample.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = updatePreview()
        })

        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.action_add, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val label = dialogBinding.etBankLabel.text.toString().trim()
                val sender = dialogBinding.etBankSender.text.toString().trim()
                val sample = dialogBinding.etBankSample.text.toString().trim()
                if (label.isEmpty() || sender.isEmpty() || sample.isEmpty()) {
                    Snackbar.make(binding.root, R.string.msg_bank_fields_required, Snackbar.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                dbHelper.addCustomBank(label, sender, sample)
                Snackbar.make(binding.root, R.string.msg_bank_added, Snackbar.LENGTH_SHORT).show()
                dialog.dismiss()
                onAdded()
            }
        }
        dialog.show()
    }

    private fun showSmsSettingsDialog() {
        val dialogBinding = DialogSmsSettingsBinding.inflate(layoutInflater)
        dialogBinding.switchPresetBlu.isChecked = SmsPrefs.isPresetEnabled(this, BankPresets.BLU)
        dialogBinding.switchPresetMelli.isChecked = SmsPrefs.isPresetEnabled(this, BankPresets.MELLI)
        updatePermissionStatusText(dialogBinding.tvSmsPermissionStatus)
        smsPermissionStatusView = dialogBinding.tvSmsPermissionStatus

        renderCustomBanksList(dialogBinding.llCustomBanks)
        dialogBinding.btnAddBank.setOnClickListener {
            showAddBankDialog { renderCustomBanksList(dialogBinding.llCustomBanks) }
        }
        dialogBinding.btnGrantSmsPermission.setOnClickListener {
            requestSmsAndNotificationPermissions()
        }

        MaterialAlertDialogBuilder(this)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.btn_save) { _, _ ->
                SmsPrefs.setPresetEnabled(this, BankPresets.BLU, dialogBinding.switchPresetBlu.isChecked)
                SmsPrefs.setPresetEnabled(this, BankPresets.MELLI, dialogBinding.switchPresetMelli.isChecked)
                BalanceWidgetProvider.updateAll(this)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setOnDismissListener { smsPermissionStatusView = null }
            .show()
    }

    private fun startReviewFlow() {
        if (isReviewFlowActive) return
        isReviewFlowActive = true
        showNextPendingSms()
    }

    private fun showNextPendingSms() {
        val next = dbHelper.getAllPendingSms().firstOrNull()
        if (next == null) {
            isReviewFlowActive = false
            return
        }

        val dialogBinding = DialogReviewSmsBinding.inflate(layoutInflater)
        // Shown in the Jalali calendar to match every other date in the app — the SMS
        // timestamp itself is a normal Gregorian epoch value, so this needs the same
        // conversion used everywhere else rather than a raw Gregorian-formatted string.
        val receivedCal = Calendar.getInstance().apply { timeInMillis = next.receivedAt }
        val (ry, rm, rd) = PersianDate.gregorianToJalali(
            receivedCal.get(Calendar.YEAR), receivedCal.get(Calendar.MONTH) + 1, receivedCal.get(Calendar.DAY_OF_MONTH)
        )
        val timeText = DateFormat.format("HH:mm", next.receivedAt)
        dialogBinding.tvReviewMeta.text = "${next.sender} · $rd ${PersianDate.monthName(rm)} · $timeText"
        dialogBinding.tvReviewBody.text = next.body
        dialogBinding.etReviewTitle.setText(next.guessedTitle)
        dialogBinding.etReviewAmount.setText(next.guessedAmount?.let { formatter.format(it) } ?: "")
        populateCategoryChips(dialogBinding.chipGroupReviewCategory, Category.DEFAULT)

        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogBinding.root)
            .setCancelable(false)
            // Positive button listener is overridden below via setOnShowListener so an
            // invalid entry keeps the dialog open instead of silently discarding the item.
            .setPositiveButton(R.string.action_add, null)
            .setNegativeButton(R.string.action_skip) { _, _ ->
                dbHelper.deletePendingSms(next.id)
                NotificationHelper.cancel(this, next.id)
                BalanceWidgetProvider.updateAll(this)
                showNextPendingSms()
            }
            .create()

        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val title = dialogBinding.etReviewTitle.text.toString().trim()
                val amount = dialogBinding.etReviewAmount.text.toString().replace(",", "").toDoubleOrNull()

                if (title.isEmpty()) {
                    dialogBinding.etReviewTitle.error = getString(R.string.msg_enter_description)
                    return@setOnClickListener
                }
                if (amount == null || amount <= 0) {
                    dialogBinding.etReviewAmount.error = getString(R.string.msg_enter_valid_amount)
                    return@setOnClickListener
                }

                val category = selectedCategory(dialogBinding.chipGroupReviewCategory)
                val cal = Calendar.getInstance().apply { timeInMillis = next.receivedAt }
                val (ey, em, ed) = PersianDate.gregorianToJalali(
                    cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH)
                )
                dbHelper.insertExpense(PersianDate.dateKey(ey, em, ed), title, amount, category)
                if (ey == jy && em == jm && ed == jd) refreshList()
                BalanceWidgetProvider.updateAll(this)

                dbHelper.deletePendingSms(next.id)
                NotificationHelper.cancel(this, next.id)
                dialog.dismiss()
                showNextPendingSms()
            }
        }
        dialog.show()
    }
}
