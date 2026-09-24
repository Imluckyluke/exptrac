package com.hadi.expensetracker

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Serializes/restores expenses, custom categories, and custom banks as JSON, for the backup
 * export/import feature. */
object BackupHelper {

    private const val VERSION = 2

    data class ImportResult(
        val expensesAdded: Int,
        val categoriesAdded: Int,
        val banksAdded: Int,
        val expensesAlreadyPresent: Int = 0,
        val skipped: Int = 0,
        val latestDate: String? = null
    )

    private data class ExpenseKey(
        val date: String,
        val title: String,
        val amount: Double,
        val category: String
    )

    fun exportJson(dbHelper: DbHelper): String {
        val root = JSONObject()
        root.put("version", VERSION)
        root.put("exportedAt", System.currentTimeMillis())

        val expensesArray = JSONArray()
        for (expense in dbHelper.getAllExpenses()) {
            val obj = JSONObject()
            obj.put("date", expense.date)
            obj.put("title", expense.title)
            obj.put("amount", expense.amount)
            obj.put("category", expense.category)
            expensesArray.put(obj)
        }
        root.put("expenses", expensesArray)

        val categoriesArray = JSONArray()
        for (category in dbHelper.getCustomCategories()) {
            val obj = JSONObject()
            obj.put("id", category.id)
            obj.put("label", category.label)
            obj.put("colorIndex", category.colorIndex)
            categoriesArray.put(obj)
        }
        root.put("customCategories", categoriesArray)

        val banksArray = JSONArray()
        for (bank in dbHelper.getCustomBanks()) {
            val obj = JSONObject()
            obj.put("label", bank.label)
            obj.put("sender", bank.sender)
            obj.put("sample", bank.sample)
            obj.put("enabled", bank.enabled)
            banksArray.put(obj)
        }
        root.put("customBanks", banksArray)

        return root.toString(2)
    }

    /** Adds every entry from [json] into the database (this is a merge/append, not a replace).
     * Custom categories are restored before expenses, and expenses that reference one are
     * remapped to whatever row id it ends up with here (which won't necessarily match the id
     * it had on the device the backup came from) — matched by label if it already exists,
     * so re-importing the same backup twice doesn't create duplicate categories. */
    fun importJson(context: Context, dbHelper: DbHelper, json: String): ImportResult {
        val root = JSONObject(json.removePrefix("\uFEFF").trim())
        val version = root.optInt("version", 0)
        require(version == 1 || version == 2)
        val expensesArray = requireNotNull(root.optJSONArray("expenses"))

        val database = dbHelper.writableDatabase
        var categoriesAdded = 0
        var expensesAdded = 0
        var expensesAlreadyPresent = 0
        var banksAdded = 0
        var skipped = 0
        var latestDateValue: String? = null

        database.beginTransaction()
        try {
            val categoryIdRemap = HashMap<Long, String>()
            val existingCategories = dbHelper.getCustomCategories()
            val existingIdsByLabel = existingCategories
                .associate { it.label.trim() to it.id }
                .toMutableMap()
            val existingCustomIds = existingCategories.associateBy { it.id }

            root.optJSONArray("customCategories")?.let { array ->
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val label = obj.optString("label").trim()
                    if (label.isBlank()) continue
                    val oldId = obj.optLong("id", -1L)
                    val colorIndex = obj.optInt("colorIndex", 0)
                        .coerceIn(0, Category.COLOR_PALETTE.lastIndex)

                    val newRawId = existingIdsByLabel[label] ?: run {
                        val inserted = dbHelper.addCustomCategory(label, colorIndex)
                        check(inserted >= 0)
                        categoriesAdded++
                        existingIdsByLabel[label] = inserted
                        inserted
                    }
                    if (oldId >= 0) categoryIdRemap[oldId] = "custom_$newRawId"
                }
            }

            val existingExpenseCounts = HashMap<ExpenseKey, Int>()
            for (expense in dbHelper.getAllExpenses()) {
                val key = ExpenseKey(expense.date, expense.title, expense.amount, expense.category)
                existingExpenseCounts[key] = (existingExpenseCounts[key] ?: 0) + 1
            }

            for (i in 0 until expensesArray.length()) {
                val obj = expensesArray.optJSONObject(i)
                if (obj == null) {
                    skipped++
                    continue
                }

                val date = PersianDate.normalizeDateKey(obj.optString("date"))
                val title = obj.optString("title").trim()
                val amount = obj.optDouble("amount", Double.NaN)
                if (date == null || title.isBlank() || amount.isNaN() || amount.isInfinite() || amount <= 0.0) {
                    skipped++
                    continue
                }

                val rawCategory = obj.optString("category", Category.DEFAULT).trim()
                val category = when {
                    rawCategory.startsWith("custom_") -> {
                        val oldRawId = rawCategory.removePrefix("custom_").toLongOrNull()
                        oldRawId?.let { categoryIdRemap[it] ?: existingCustomIds[it]?.let { id -> "custom_$id" } }
                    }
                    Category.isBuiltInId(rawCategory) -> rawCategory
                    else -> Category.DEFAULT
                }
                if (category == null) {
                    skipped++
                    continue
                }

                val currentLatest = latestDateValue
                if (currentLatest == null || date > currentLatest) latestDateValue = date
                val key = ExpenseKey(date, title, amount, category)
                val existingCount = existingExpenseCounts[key] ?: 0
                if (existingCount > 0) {
                    existingExpenseCounts[key] = existingCount - 1
                    expensesAlreadyPresent++
                } else {
                    val inserted = dbHelper.insertExpense(date, title, amount, category)
                    check(inserted >= 0)
                    expensesAdded++
                }
            }

            root.optJSONArray("customBanks")?.let { array ->
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val label = obj.optString("label").trim()
                    val sender = obj.optString("sender").trim()
                    val sample = obj.optString("sample")
                    if (label.isBlank() || sender.isBlank()) continue
                    if (dbHelper.hasCustomBank(sender)) continue
                    val id = dbHelper.addCustomBank(label, sender, sample)
                    check(id >= 0)
                    if (!obj.optBoolean("enabled", true)) {
                        dbHelper.setCustomBankEnabled(id, false)
                    }
                    banksAdded++
                }
            }

            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }

        Category.refresh(context, dbHelper)
        return ImportResult(
            expensesAdded = expensesAdded,
            categoriesAdded = categoriesAdded,
            banksAdded = banksAdded,
            expensesAlreadyPresent = expensesAlreadyPresent,
            skipped = skipped,
            latestDate = latestDateValue
        )
    }
}
