package com.hadi.expensetracker

import android.content.Context

object SmsImportHelper {

    /**
     * Processes one SMS message from an already-matched [source]: skips it if it has been seen
     * before (by sender+timestamp+body), otherwise parses it, queues it in [DbHelper]'s
     * pending_sms table for review, and records any account balance it reported. Returns the
     * newly queued row, or null if it was a duplicate.
     */
    fun ingest(
        context: Context,
        dbHelper: DbHelper,
        source: BankRegistry.Source,
        sender: String,
        timestampMillis: Long,
        body: String
    ): PendingSms? {
        val uniqueKey = "$sender|$timestampMillis|${body.hashCode()}"
        if (!dbHelper.markSeenIfNew(uniqueKey)) return null

        val parsed = BankSmsParser.parse(sender, body)
        val pendingId = dbHelper.insertPendingSms(sender, timestampMillis, body, parsed.title, parsed.amount)

        if (parsed.balance != null) {
            BalanceStore.recordIfNewer(context, source.key, parsed.balance, timestampMillis)
        }

        return PendingSms(pendingId, sender, timestampMillis, body, parsed.title, parsed.amount)
    }
}
