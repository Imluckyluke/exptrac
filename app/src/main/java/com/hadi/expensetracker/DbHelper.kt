package com.hadi.expensetracker

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class DbHelper(context: Context) : SQLiteOpenHelper(context, "expenses.db", null, 5) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE expenses (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                date TEXT NOT NULL,
                title TEXT NOT NULL,
                amount REAL NOT NULL,
                category TEXT NOT NULL DEFAULT '${Category.DEFAULT}'
            )
            """.trimIndent()
        )
        createSmsTables(db)
        createCustomBanksTable(db)
        createCustomCategoriesTable(db)
        createExpenseDateIndex(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Non-destructive: existing expenses are always kept.
        if (oldVersion < 2) {
            createSmsTables(db)
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE expenses ADD COLUMN category TEXT NOT NULL DEFAULT '${Category.DEFAULT}'")
            createCustomBanksTable(db)
        }
        if (oldVersion < 4) {
            createCustomCategoriesTable(db)
        }
        if (oldVersion < 5) {
            normalizeExpenseDates(db)
            createExpenseDateIndex(db)
        }
    }

    private fun createExpenseDateIndex(db: SQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_expenses_date ON expenses(date)")
    }

    private fun normalizeExpenseDates(db: SQLiteDatabase) {
        val updates = mutableListOf<Pair<Long, String>>()
        val cursor = db.query("expenses", arrayOf("id", "date"), null, null, null, null, null)
        cursor.use {
            val idIndex = it.getColumnIndexOrThrow("id")
            val dateIndex = it.getColumnIndexOrThrow("date")
            while (it.moveToNext()) {
                val original = it.getString(dateIndex)
                val normalized = PersianDate.normalizeDateKey(original) ?: continue
                if (normalized != original) updates += it.getLong(idIndex) to normalized
            }
        }
        for ((id, normalized) in updates) {
            db.update(
                "expenses",
                ContentValues().apply { put("date", normalized) },
                "id = ?",
                arrayOf(id.toString())
            )
        }
    }

    private fun createSmsTables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS pending_sms (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                sender TEXT NOT NULL,
                received_at INTEGER NOT NULL,
                body TEXT NOT NULL,
                guessed_title TEXT NOT NULL,
                guessed_amount REAL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS seen_sms (
                unique_key TEXT PRIMARY KEY
            )
            """.trimIndent()
        )
    }

    private fun createCustomBanksTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS custom_banks (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                label TEXT NOT NULL,
                sender TEXT NOT NULL,
                sample TEXT NOT NULL,
                enabled INTEGER NOT NULL DEFAULT 1
            )
            """.trimIndent()
        )
    }

    private fun createCustomCategoriesTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS custom_categories (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                label TEXT NOT NULL,
                color_index INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
    }

    // ---- Expenses ----

    fun insertExpense(date: String, title: String, amount: Double, category: String = Category.DEFAULT): Long {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("date", PersianDate.normalizeDateKey(date) ?: date)
            put("title", title)
            put("amount", amount)
            put("category", category)
        }
        return db.insert("expenses", null, values)
    }

    fun updateExpense(id: Long, title: String, amount: Double, category: String) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("title", title)
            put("amount", amount)
            put("category", category)
        }
        db.update("expenses", values, "id = ?", arrayOf(id.toString()))
    }

    private fun expenseFromCursor(cursor: android.database.Cursor): Expense {
        val categoryIdx = cursor.getColumnIndex("category")
        val category = if (categoryIdx >= 0 && !cursor.isNull(categoryIdx)) {
            cursor.getString(categoryIdx)
        } else {
            Category.DEFAULT
        }
        return Expense(
            id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
            date = PersianDate.normalizeDateKey(cursor.getString(cursor.getColumnIndexOrThrow("date")))
                ?: cursor.getString(cursor.getColumnIndexOrThrow("date")),
            title = cursor.getString(cursor.getColumnIndexOrThrow("title")),
            amount = cursor.getDouble(cursor.getColumnIndexOrThrow("amount")),
            category = if (Category.isValid(category)) category else Category.DEFAULT
        )
    }

    fun getExpensesForDate(date: String): List<Expense> {
        val list = mutableListOf<Expense>()
        val normalizedDate = PersianDate.normalizeDateKey(date) ?: date
        val db = readableDatabase
        val cursor = db.query(
            "expenses", null, "date = ?", arrayOf(normalizedDate),
            null, null, "id ASC"
        )
        cursor.use {
            while (it.moveToNext()) list.add(expenseFromCursor(it))
        }
        return list
    }

    /** All expenses whose date key starts with [monthPrefix] (e.g. "1404-06-"), oldest first. */
    fun getExpensesForMonth(monthPrefix: String): List<Expense> {
        val list = mutableListOf<Expense>()
        val normalizedPrefix = monthPrefix.trimEnd('-').let {
            PersianDate.normalizeDateKey("${it}-01")
                ?.let { key -> key.substring(0, 7) + "-" }
        } ?: monthPrefix
        val db = readableDatabase
        val cursor = db.query(
            "expenses", null, "date LIKE ?", arrayOf("$normalizedPrefix%"),
            null, null, "date ASC, id ASC"
        )
        cursor.use {
            while (it.moveToNext()) list.add(expenseFromCursor(it))
        }
        return list
    }

    /** Every expense in [startKey]..[endKey] inclusive (keys are zero-padded so TEXT compare works). */
    fun getExpensesForDateRange(startKey: String, endKey: String): List<Expense> {
        val list = mutableListOf<Expense>()
        val normalizedStart = PersianDate.normalizeDateKey(startKey) ?: startKey
        val normalizedEnd = PersianDate.normalizeDateKey(endKey) ?: endKey
        val db = readableDatabase
        val cursor = db.query(
            "expenses", null, "date >= ? AND date <= ?", arrayOf(normalizedStart, normalizedEnd),
            null, null, "date ASC, id ASC"
        )
        cursor.use {
            while (it.moveToNext()) list.add(expenseFromCursor(it))
        }
        return list
    }

    fun hasExpense(date: String, title: String, amount: Double): Boolean {
        val normalizedDate = PersianDate.normalizeDateKey(date) ?: date
        val db = readableDatabase
        val cursor = db.query(
            "expenses", arrayOf("amount"), "date = ? AND title = ?",
            arrayOf(normalizedDate, title),
            null, null, null
        )
        cursor.use {
            val idx = it.getColumnIndexOrThrow("amount")
            while (it.moveToNext()) {
                if (it.getDouble(idx) == amount) return true
            }
            return false
        }
    }

    fun hasCustomBank(sender: String): Boolean {
        val db = readableDatabase
        val cursor = db.query(
            "custom_banks", arrayOf("id"), "sender = ?", arrayOf(sender),
            null, null, null, "1"
        )
        cursor.use { return it.moveToFirst() }
    }
    fun getAllExpenses(): List<Expense> {
        val list = mutableListOf<Expense>()
        val db = readableDatabase
        val cursor = db.query("expenses", null, null, null, null, null, "date ASC, id ASC")
        cursor.use {
            while (it.moveToNext()) list.add(expenseFromCursor(it))
        }
        return list
    }

    fun deleteExpense(id: Long) {
        val db = writableDatabase
        db.delete("expenses", "id = ?", arrayOf(id.toString()))
    }

    /** The most recently added expense overall (not limited to a single day), or null if none. */
    fun getLastExpense(): Expense? {
        val db = readableDatabase
        val cursor = db.query("expenses", null, null, null, null, null, "id DESC", "1")
        cursor.use {
            if (!it.moveToFirst()) return null
            return expenseFromCursor(it)
        }
    }

    // ---- SMS import ----

    /**
     * Records [uniqueKey] as seen. Returns true if it was new (not seen before), false if it
     * had already been recorded (in which case the caller should not queue it again).
     */
    fun markSeenIfNew(uniqueKey: String): Boolean {
        val db = writableDatabase
        val values = ContentValues().apply { put("unique_key", uniqueKey) }
        val rowId = db.insertWithOnConflict(
            "seen_sms", null, values, SQLiteDatabase.CONFLICT_IGNORE
        )
        return rowId != -1L
    }

    fun insertPendingSms(sender: String, receivedAt: Long, body: String, guessedTitle: String, guessedAmount: Double?): Long {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("sender", sender)
            put("received_at", receivedAt)
            put("body", body)
            put("guessed_title", guessedTitle)
            if (guessedAmount != null) put("guessed_amount", guessedAmount) else putNull("guessed_amount")
        }
        return db.insert("pending_sms", null, values)
    }

    fun updatePendingSmsTitle(id: Long, title: String) {
        val db = writableDatabase
        val values = ContentValues().apply { put("guessed_title", title) }
        db.update("pending_sms", values, "id = ?", arrayOf(id.toString()))
    }

    fun getAllPendingSms(): List<PendingSms> {
        val list = mutableListOf<PendingSms>()
        val db = readableDatabase
        val cursor = db.query("pending_sms", null, null, null, null, null, "received_at DESC")
        cursor.use {
            val amountIdx = it.getColumnIndexOrThrow("guessed_amount")
            while (it.moveToNext()) {
                list.add(
                    PendingSms(
                        id = it.getLong(it.getColumnIndexOrThrow("id")),
                        sender = it.getString(it.getColumnIndexOrThrow("sender")),
                        receivedAt = it.getLong(it.getColumnIndexOrThrow("received_at")),
                        body = it.getString(it.getColumnIndexOrThrow("body")),
                        guessedTitle = it.getString(it.getColumnIndexOrThrow("guessed_title")),
                        guessedAmount = if (it.isNull(amountIdx)) null else it.getDouble(amountIdx)
                    )
                )
            }
        }
        return list
    }

    fun getPendingSmsById(id: Long): PendingSms? {
        val db = readableDatabase
        val cursor = db.query("pending_sms", null, "id = ?", arrayOf(id.toString()), null, null, null, "1")
        cursor.use {
            if (!it.moveToFirst()) return null
            val amountIdx = it.getColumnIndexOrThrow("guessed_amount")
            return PendingSms(
                id = it.getLong(it.getColumnIndexOrThrow("id")),
                sender = it.getString(it.getColumnIndexOrThrow("sender")),
                receivedAt = it.getLong(it.getColumnIndexOrThrow("received_at")),
                body = it.getString(it.getColumnIndexOrThrow("body")),
                guessedTitle = it.getString(it.getColumnIndexOrThrow("guessed_title")),
                guessedAmount = if (it.isNull(amountIdx)) null else it.getDouble(amountIdx)
            )
        }
    }

    fun deletePendingSms(id: Long) {
        val db = writableDatabase
        db.delete("pending_sms", "id = ?", arrayOf(id.toString()))
    }

    // ---- Custom banks ----

    fun addCustomBank(label: String, sender: String, sample: String): Long {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("label", label)
            put("sender", sender)
            put("sample", sample)
            put("enabled", 1)
        }
        return db.insert("custom_banks", null, values)
    }

    fun getCustomBanks(): List<CustomBank> {
        val list = mutableListOf<CustomBank>()
        val db = readableDatabase
        val cursor = db.query("custom_banks", null, null, null, null, null, "id ASC")
        cursor.use {
            while (it.moveToNext()) {
                list.add(
                    CustomBank(
                        id = it.getLong(it.getColumnIndexOrThrow("id")),
                        label = it.getString(it.getColumnIndexOrThrow("label")),
                        sender = it.getString(it.getColumnIndexOrThrow("sender")),
                        sample = it.getString(it.getColumnIndexOrThrow("sample")),
                        enabled = it.getInt(it.getColumnIndexOrThrow("enabled")) != 0
                    )
                )
            }
        }
        return list
    }

    fun setCustomBankEnabled(id: Long, enabled: Boolean) {
        val db = writableDatabase
        val values = ContentValues().apply { put("enabled", if (enabled) 1 else 0) }
        db.update("custom_banks", values, "id = ?", arrayOf(id.toString()))
    }

    fun deleteCustomBank(id: Long) {
        val db = writableDatabase
        db.delete("custom_banks", "id = ?", arrayOf(id.toString()))
    }

    // ---- Custom categories ----

    fun addCustomCategory(label: String, colorIndex: Int): Long {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("label", label)
            put("color_index", colorIndex)
        }
        return db.insert("custom_categories", null, values)
    }

    fun getCustomCategories(): List<CustomCategory> {
        val list = mutableListOf<CustomCategory>()
        val db = readableDatabase
        val cursor = db.query("custom_categories", null, null, null, null, null, "id ASC")
        cursor.use {
            while (it.moveToNext()) {
                list.add(
                    CustomCategory(
                        id = it.getLong(it.getColumnIndexOrThrow("id")),
                        label = it.getString(it.getColumnIndexOrThrow("label")),
                        colorIndex = it.getInt(it.getColumnIndexOrThrow("color_index"))
                    )
                )
            }
        }
        return list
    }

    fun deleteCustomCategory(id: Long) {
        val db = writableDatabase
        db.delete("custom_categories", "id = ?", arrayOf(id.toString()))
    }
}
