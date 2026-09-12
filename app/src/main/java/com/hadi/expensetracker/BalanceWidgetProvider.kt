package com.hadi.expensetracker

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import java.text.DecimalFormat

/**
 * Home screen widget showing the latest known balance for each enabled bank source (built-in
 * or custom), plus the most recent transaction (a not-yet-reviewed SMS if one is pending,
 * otherwise the last confirmed expense). Tapping it opens the app.
 */
class BalanceWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) updateWidget(context, appWidgetManager, id)
    }

    companion object {
        private val formatter = DecimalFormat("#,###")

        /** Call after anything that could change a balance or the latest transaction. */
        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, BalanceWidgetProvider::class.java))
            for (id in ids) updateWidget(context, manager, id)
        }

        private fun updateWidget(context: Context, manager: AppWidgetManager, appWidgetId: Int) {
            val views = RemoteViews(context.packageName, R.layout.widget_balance)
            val dbHelper = DbHelper(context)

            views.removeAllViews(R.id.balancesContainer)
            val sources = BankRegistry.allEnabledSources(context, dbHelper)
            for (source in sources) {
                val row = RemoteViews(context.packageName, R.layout.widget_bank_row)
                row.setTextViewText(R.id.tvBankLabel, source.label)
                val balance = BalanceStore.getBalance(context, source.key)
                row.setTextViewText(
                    R.id.tvBankBalance,
                    if (balance != null) {
                        context.getString(R.string.widget_amount_format, formatter.format(balance))
                    } else {
                        context.getString(R.string.widget_balance_unknown)
                    }
                )
                views.addView(R.id.balancesContainer, row)
            }

            val pending = dbHelper.getAllPendingSms().firstOrNull()
            if (pending != null) {
                views.setTextViewText(
                    R.id.tvLastTitle,
                    pending.guessedTitle.ifBlank { context.getString(R.string.widget_untitled_prompt) }
                )
                views.setTextViewText(
                    R.id.tvLastAmount,
                    pending.guessedAmount?.let { context.getString(R.string.widget_amount_format, formatter.format(it)) }
                        ?: context.getString(R.string.widget_balance_unknown)
                )
            } else {
                val last = dbHelper.getLastExpense()
                if (last != null) {
                    views.setTextViewText(
                        R.id.tvLastTitle,
                        last.title.ifBlank { context.getString(R.string.widget_untitled_prompt) }
                    )
                    views.setTextViewText(
                        R.id.tvLastAmount,
                        context.getString(R.string.widget_amount_format, formatter.format(last.amount))
                    )
                } else {
                    views.setTextViewText(R.id.tvLastTitle, context.getString(R.string.widget_no_data))
                    views.setTextViewText(R.id.tvLastAmount, "")
                }
            }

            val openIntent = Intent(context, MainActivity::class.java)
            val pendingIntent = PendingIntent.getActivity(
                context, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widgetRoot, pendingIntent)

            manager.updateAppWidget(appWidgetId, views)
        }
    }
}
