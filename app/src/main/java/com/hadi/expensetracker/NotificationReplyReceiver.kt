package com.hadi.expensetracker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.Locale

class NotificationReplyReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pendingId = intent.getLongExtra(NotificationHelper.EXTRA_PENDING_ID, -1L)
        if (pendingId < 0) return

        val appContext = context.applicationContext
        val pendingResult = goAsync()
        Thread {
            try {
                val dbHelper = DbHelper(appContext)
                when (intent.action) {
                    NotificationHelper.ACTION_SKIP -> {
                        dbHelper.deletePendingSms(pendingId)
                    }
                    NotificationHelper.ACTION_REPLY -> {
                        val results = RemoteInput.getResultsFromIntent(intent)
                        val replyText = results?.getCharSequence(NotificationHelper.KEY_REPLY_TEXT)
                            ?.toString()?.trim().orEmpty()
                        val pending = dbHelper.getPendingSmsById(pendingId)
                        if (pending != null) {
                            val title = replyText.ifBlank { pending.guessedTitle }
                            val amount = pending.guessedAmount
                            if (amount != null && amount > 0 && title.isNotBlank()) {
                                val cal = GregorianCalendar(Locale.US).apply { timeInMillis = pending.receivedAt }
                                val (y, m, d) = PersianDate.gregorianToJalali(
                                    cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH)
                                )
                                dbHelper.insertExpense(PersianDate.dateKey(y, m, d), title, amount, Category.DEFAULT)
                                dbHelper.deletePendingSms(pendingId)
                            } else if (title.isNotBlank()) {
                                // No usable amount yet — keep it pending (with the typed title
                                // saved) so it still shows up when the app is opened.
                                dbHelper.updatePendingSmsTitle(pendingId, title)
                            }
                        }
                    }
                }
                BalanceWidgetProvider.updateAll(appContext)
                NotificationHelper.cancel(appContext, pendingId)
            } finally {
                pendingResult.finish()
            }
        }.start()
    }
}
