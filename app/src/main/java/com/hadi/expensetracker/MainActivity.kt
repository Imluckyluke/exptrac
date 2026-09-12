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
import android.view.animation.OvershootInterpolator
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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
import com.google.android.material.snackbar.Snackbar
import com.hadi.expensetracker.databinding.ActivityMainBinding
import com.hadi.expensetracker.databinding.DialogAddBankBinding
import com.hadi.expensetracker.databinding.DialogReviewSmsBinding
import com.hadi.expensetracker.databinding.DialogSmsSettingsBinding
import com.hadi.expensetracker.databinding.ItemCustomBankBinding
import java.text.DecimalFormat
import java.util.Calendar

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

    // Guards against re-entrant text changes while we reformat the amount field live.
    private var isFormattingAmount = false

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
                val result = BackupHelper.importJson(dbHelper, text)
                Snackbar.make(
                    binding.root,
                    getString(R.string.msg_backup_imported, result.expensesAdded, result.banksAdded),
                    Snackbar.LENGTH_LONG
                ).show()
                refreshList()
                BalanceWidgetProvider.updateAll(this)
            } catch (e: Exception) {
                Snackbar.make(binding.root, R.string.msg_backup_import_failed, Snackbar.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        setupEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets(binding.root)

        dbHelper = DbHelper(this)

        val cal = Calendar.getInstance()
        val (y, m, d) = PersianDate.gregorianToJalali(
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH)
        )
        jy = y; jm = m; jd = d
        todayY = y; todayM = m; todayD = d

        adapter = ExpenseAdapter { expense -> deleteExpense(expense, showUndo = true) }
        binding.rvExpenses.layoutManager = LinearLayoutManager(this)
        binding.rvExpenses.adapter = adapter
        (binding.rvExpenses.itemAnimator as? SimpleItemAnimator)?.apply {
            addDuration = 220
            removeDuration = 220
            moveDuration = 220
            changeDuration = 180
        }

        ItemTouchHelper(SwipeToDeleteCallback(binding.rvExpenses) { position ->
            val expense = adapter.currentList.getOrNull(position)
            if (expense != null) {
                deleteExpense(expense, showUndo = true)
            } else {
                adapter.notifyItemChanged(position)
            }
        }).attachToRecyclerView(binding.rvExpenses)

        setupScrollElevation()

        binding.btnPrev.applyPressAnimation()
        binding.btnNext.applyPressAnimation()
        binding.btnAdd.applyPressAnimation()
        binding.btnSmsSettings.applyPressAnimation()
        binding.btnStats.applyPressAnimation()
        binding.btnBackup.applyPressAnimation()

        binding.btnPrev.setOnClickListener { changeDay(-1) }
        binding.btnNext.setOnClickListener { changeDay(1) }
        binding.btnAdd.setOnClickListener { addExpense() }
        binding.btnSmsSettings.setOnClickListener { showSmsSettingsDialog() }
        binding.btnStats.setOnClickListener { startActivity(Intent(this, StatsActivity::class.java)) }
        binding.btnBackup.setOnClickListener { showBackupMenu() }

        setupAmountFormatting()
        populateCategoryChips(binding.chipGroupCategory, Category.DEFAULT)

        updateDateLabel(animate = false)
        refreshList()
    }

    override fun onResume() {
        super.onResume()
        // Messages may have arrived from the background receiver while the app was closed.
        Thread {
            val hasPending = DbHelper(this).getAllPendingSms().isNotEmpty()
            if (hasPending) runOnUiThread { startReviewFlow() }
        }.start()
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
            chipGroup.addView(chip)
        }
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
        binding.etAmount.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                if (isFormattingAmount || s == null) return
                isFormattingAmount = true

                val digitsOnly = s.toString().replace(",", "")
                if (digitsOnly.isEmpty()) {
                    isFormattingAmount = false
                    return
                }

                val formatted = try {
                    formatter.format(digitsOnly.toLong())
                } catch (e: NumberFormatException) {
                    digitsOnly
                }

                if (formatted != s.toString()) {
                    binding.etAmount.setText(formatted)
                    binding.etAmount.setSelection(formatted.length)
                }
                isFormattingAmount = false
            }
        })
    }

    private fun changeDay(delta: Int) {
        val (gy, gm, gd) = PersianDate.jalaliToGregorian(jy, jm, jd)
        val cal = Calendar.getInstance()
        cal.set(gy, gm - 1, gd)
        cal.add(Calendar.DAY_OF_MONTH, delta)
        val (ny, nm, nd) = PersianDate.gregorianToJalali(
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH)
        )
        jy = ny; jm = nm; jd = nd
        updateDateLabel(animate = true)
        binding.rvExpenses.scheduleLayoutAnimation()
        refreshList()
    }

    private fun updateDateLabel(animate: Boolean) {
        val newText = "$jd ${PersianDate.monthName(jm)} $jy"
        val isToday = jy == todayY && jm == todayM && jd == todayD

        if (animate) {
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
        val amountText = binding.etAmount.text.toString().trim().replace(",", "")

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

    private fun refreshList() {
        val key = PersianDate.dateKey(jy, jm, jd)
        val list = dbHelper.getExpensesForDate(key)
        adapter.submitList(list)
        updateEmptyState(list.isEmpty())

        val total = list.sumOf { it.amount }
        animateTotal(total)
        BalanceWidgetProvider.updateAll(this)
    }

    private fun updateEmptyState(shouldShow: Boolean) {
        val isShown = binding.emptyState.visibility == View.VISIBLE
        if (shouldShow == isShown) return

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

    // ---- Backup ----

    private fun showBackupMenu() {
        val popup = PopupMenu(this, binding.btnBackup)
        popup.menu.add(0, 1, 0, R.string.backup_export)
        popup.menu.add(0, 2, 1, R.string.backup_import)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    val fileName = "expense_backup_${PersianDate.dateKey(jy, jm, jd)}.json"
                    exportBackupLauncher.launch(fileName)
                    true
                }
                2 -> {
                    importBackupLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                    true
                }
                else -> false
            }
        }
        popup.show()
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
        dialogBinding.cbPresetBlu.isChecked = SmsPrefs.isPresetEnabled(this, BankPresets.BLU)
        dialogBinding.cbPresetMelli.isChecked = SmsPrefs.isPresetEnabled(this, BankPresets.MELLI)
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
                SmsPrefs.setPresetEnabled(this, BankPresets.BLU, dialogBinding.cbPresetBlu.isChecked)
                SmsPrefs.setPresetEnabled(this, BankPresets.MELLI, dialogBinding.cbPresetMelli.isChecked)
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
        val pendingDbHelper = DbHelper(this)
        val next = pendingDbHelper.getAllPendingSms().firstOrNull()
        if (next == null) {
            isReviewFlowActive = false
            return
        }

        val dialogBinding = DialogReviewSmsBinding.inflate(layoutInflater)
        val dateText = DateFormat.format("MMM d, HH:mm", next.receivedAt)
        dialogBinding.tvReviewMeta.text = "${next.sender} · $dateText"
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
                pendingDbHelper.deletePendingSms(next.id)
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
                pendingDbHelper.insertExpense(PersianDate.dateKey(ey, em, ed), title, amount, category)
                if (ey == jy && em == jm && ed == jd) refreshList()
                BalanceWidgetProvider.updateAll(this)

                pendingDbHelper.deletePendingSms(next.id)
                NotificationHelper.cancel(this, next.id)
                dialog.dismiss()
                showNextPendingSms()
            }
        }
        dialog.show()
    }
}
