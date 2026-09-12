package com.hadi.expensetracker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony

class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return

        val sender = messages[0].displayOriginatingAddress ?: messages[0].originatingAddress ?: return
        val timestamp = messages[0].timestampMillis
        val fullBody = messages.joinToString(separator = "") { it.messageBody ?: "" }

        // All matching/parsing/DB work is deferred to the background thread below so this
        // (main-thread) callback never blocks on SQLite.
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        Thread {
            try {
                val dbHelper = DbHelper(appContext)
                val source = BankRegistry.findMatchingSource(appContext, dbHelper, sender)
                if (source != null) {
                    val pending = SmsImportHelper.ingest(appContext, dbHelper, source, sender, timestamp, fullBody)
                    if (pending != null) {
                        BalanceWidgetProvider.updateAll(appContext)
                        NotificationHelper.showPendingTransactionNotification(appContext, pending, source.label)
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }.start()
    }
}
