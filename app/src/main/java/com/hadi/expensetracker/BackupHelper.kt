package com.hadi.expensetracker

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Serializes/restores expenses, custom categories, and custom banks as JSON, for the backup
 * export/import feature. */
object BackupHelper {

    private const val VERSION = 2

    data class ImportResult(val expensesAdded: Int, val categoriesAdded: Int, val banksAdded: Int)

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
        val root = JSONObject(json)

        var categoriesAdded = 0
        val categoryIdRemap = HashMap<Long, String>()
        root.optJSONArray("customCategories")?.let { array ->
            val existingByLabel = dbHelper.getCustomCategories().associateBy { it.label }
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val label = obj.optString("label")
                if (label.isBlank()) continue
                val oldId = obj.optLong("id", -1L)
                val colorIndex = obj.optInt("colorIndex", 0)

                val existing = existingByLabel[label]
                val newRawId = if (existing != null) {
                    existing.id
                } else {
                    val inserted = dbHelper.addCustomCategory(label, colorIndex)
                    categoriesAdded++
                    inserted
                }
                if (oldId >= 0) categoryIdRemap[oldId] = "custom_$newRawId"
            }
        }
        if (categoriesAdded > 0) {
            Category.refresh(context, dbHelper)
        }

        var expensesAdded = 0
        root.optJSONArray("expenses")?.let { array ->
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val date = obj.optString("date")
                if (date.isBlank()) continue
                val title = obj.optString("title")
                val amount = obj.optDouble("amount", Double.NaN)
                if (amount.isNaN() || title.isBlank()) continue

                val rawCategory = obj.optString("category", Category.DEFAULT)
                val category = when {
                    rawCategory.startsWith("custom_") -> {
                        val oldRawId = rawCategory.removePrefix("custom_").toLongOrNull()
                        oldRawId?.let { categoryIdRemap[it] } ?: Category.DEFAULT
                    }
                    Category.isValid(rawCategory) -> rawCategory
                    else -> Category.DEFAULT
                }
                if (!dbHelper.hasExpense(date, title, amount)) {
                    dbHelper.insertExpense(date, title, amount, category)
                    expensesAdded++
                }
            }
        }

        var banksAdded = 0
        root.optJSONArray("customBanks")?.let { array ->
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val label = obj.optString("label")
                val sender = obj.optString("sender")
                val sample = obj.optString("sample")
                if (label.isBlank() || sender.isBlank()) continue
                if (dbHelper.hasCustomBank(sender)) continue
                val id = dbHelper.addCustomBank(label, sender, sample)
                if (!obj.optBoolean("enabled", true)) {
                    dbHelper.setCustomBankEnabled(id, false)
                }
                banksAdded++
            }
        }

        return ImportResult(expensesAdded, categoriesAdded, banksAdded)
    }
}
