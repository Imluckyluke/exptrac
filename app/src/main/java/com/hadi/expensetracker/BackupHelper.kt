package com.hadi.expensetracker

import org.json.JSONArray
import org.json.JSONObject

/** Serializes/restores expenses and custom banks as JSON, for the backup export/import feature. */
object BackupHelper {

    private const val VERSION = 1

    data class ImportResult(val expensesAdded: Int, val banksAdded: Int)

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

    /** Adds every entry from [json] into the database (this is a merge/append, not a replace). */
    fun importJson(dbHelper: DbHelper, json: String): ImportResult {
        val root = JSONObject(json)

        var expensesAdded = 0
        root.optJSONArray("expenses")?.let { array ->
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val date = obj.optString("date")
                if (date.isBlank()) continue
                val title = obj.optString("title")
                val amount = obj.optDouble("amount", Double.NaN)
                if (amount.isNaN() || title.isBlank()) continue
                val category = obj.optString("category", Category.DEFAULT)
                    .let { if (Category.isValid(it)) it else Category.DEFAULT }
                dbHelper.insertExpense(date, title, amount, category)
                expensesAdded++
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
                val id = dbHelper.addCustomBank(label, sender, sample)
                if (!obj.optBoolean("enabled", true)) {
                    dbHelper.setCustomBankEnabled(id, false)
                }
                banksAdded++
            }
        }

        return ImportResult(expensesAdded, banksAdded)
    }
}
