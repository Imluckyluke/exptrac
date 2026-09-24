package com.hadi.expensetracker

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import java.text.DecimalFormat

/**
 * Home screen widget showing the latest known balance for each enabled bank source (built-in
 * or custom), plus the most recent transaction (a not-yet-reviewed SMS if one is pending,
 * otherwise the last confirmed expense). Tapping it opens the app.
 */
class BalanceWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) {
            updateWidget(context, appWidgetManager, id, appWidgetManager.getAppWidgetOptions(id))
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        updateWidget(context, appWidgetManager, appWidgetId, newOptions)
    }

    companion object {
        private const val COMPACT_MAX_AREA_DP2 = 9_000L
        private const val DETAILED_MIN_AREA_DP2 = 20_000L
        private const val SHORT_HEIGHT_DP = 80
        private const val SHORT_WIDGET_MAX_WIDTH_DP = 200
        private const val DEFAULT_SIZE_DP = 40

        private val formatter = DecimalFormat("#,###")

        private enum class LayoutMode(val resourceId: Int) {
            COMPACT(R.layout.widget_balance_compact),
            NARROW(R.layout.widget_balance_narrow),
            WIDE(R.layout.widget_balance_wide),
            DETAILED(R.layout.widget_balance)
        }

        private data class BalanceRow(val label: String, val amount: String)

        private data class WidgetContent(
            val balanceRows: List<BalanceRow>,
            val totalLabel: String,
            val totalAmount: String?,
            val sourceCount: Int,
            val lastTitle: String,
            val lastAmount: String
        )

        /** Call after anything that could change a balance or the latest transaction. */
        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, BalanceWidgetProvider::class.java))
            for (id in ids) {
                updateWidget(context, manager, id, manager.getAppWidgetOptions(id))
            }
        }

        private fun updateWidget(
            context: Context,
            manager: AppWidgetManager,
            appWidgetId: Int,
            options: Bundle
        ) {
            val content = readContent(context)
            val openIntent = Intent(context, MainActivity::class.java)
            val pendingIntent = PendingIntent.getActivity(
                context, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val views = createRemoteViews(context, options, content, pendingIntent)
            manager.updateAppWidget(appWidgetId, views)
        }

        private fun createRemoteViews(
            context: Context,
            options: Bundle,
            content: WidgetContent,
            pendingIntent: PendingIntent
        ): RemoteViews {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                @Suppress("DEPRECATION")
                val sizes = options.getParcelableArrayList<SizeF>(
                    AppWidgetManager.OPTION_APPWIDGET_SIZES
                ).orEmpty().distinct().take(16)
                if (sizes.isNotEmpty()) {
                    return RemoteViews(
                        sizes.associateWith { size ->
                            createViews(
                                context,
                                layoutMode(size.width.toInt(), size.height.toInt()),
                                size.height.toInt(),
                                content,
                                pendingIntent
                            )
                        }
                    )
                }
            }
            val height = widgetHeight(options)
            return createViews(
                context,
                layoutMode(options),
                height,
                content,
                pendingIntent
            )
        }

        private fun createViews(
            context: Context,
            mode: LayoutMode,
            heightDp: Int,
            content: WidgetContent,
            pendingIntent: PendingIntent
        ): RemoteViews {
            val views = RemoteViews(context.packageName, mode.resourceId)
            when (mode) {
                LayoutMode.COMPACT -> bindCompact(context, views, content)
                LayoutMode.NARROW -> {
                    bindBalanceList(context, views, content, true, narrowRowLimit(heightDp))
                    bindNoBalances(views, content)
                }
                LayoutMode.WIDE -> bindWide(context, views, content)
                LayoutMode.DETAILED -> {
                    bindBalanceList(context, views, content, false, detailedRowLimit(heightDp))
                    bindNoBalances(views, content)
                    bindLastTransaction(views, content)
                }
            }
            views.setOnClickPendingIntent(R.id.widgetRoot, pendingIntent)
            return views
        }

        private fun readContent(context: Context): WidgetContent {
            val dbHelper = DbHelper(context)
            val sources = BankRegistry.allEnabledSources(context, dbHelper)
            val balanceRows = sources.map { source ->
                val balance = BalanceStore.getBalance(context, source.key)
                BalanceRow(
                    label = source.label,
                    amount = balance?.let { formatAmount(context, it) }
                        ?: context.getString(R.string.widget_balance_unknown)
                )
            }
            val knownBalances = sources.mapNotNull { BalanceStore.getBalance(context, it.key) }
            val totalBalance = if (knownBalances.size == sources.size && knownBalances.isNotEmpty()) {
                formatAmount(context, knownBalances.sum())
            } else {
                null
            }
            val totalLabel = when {
                sources.isEmpty() -> context.getString(R.string.app_name)
                sources.size == 1 -> sources.first().label
                else -> context.getString(R.string.widget_total_balance)
            }

            val pending = dbHelper.getAllPendingSms().firstOrNull()
            val lastTitle: String
            val lastAmount: String
            if (pending != null) {
                lastTitle = pending.guessedTitle.ifBlank {
                    context.getString(R.string.widget_untitled_prompt)
                }
                lastAmount = pending.guessedAmount?.let { formatAmount(context, it) }
                    ?: context.getString(R.string.widget_balance_unknown)
            } else {
                val last = dbHelper.getLastExpense()
                if (last != null) {
                    lastTitle = last.title.ifBlank {
                        context.getString(R.string.widget_untitled_prompt)
                    }
                    lastAmount = formatAmount(context, last.amount)
                } else {
                    lastTitle = context.getString(R.string.widget_no_data)
                    lastAmount = ""
                }
            }

            return WidgetContent(
                balanceRows = balanceRows,
                totalLabel = totalLabel,
                totalAmount = totalBalance,
                sourceCount = sources.size,
                lastTitle = lastTitle,
                lastAmount = lastAmount
            )
        }

        private fun bindCompact(context: Context, views: RemoteViews, content: WidgetContent) {
            views.setTextViewText(R.id.tvCompactLabel, content.totalLabel)
            views.setTextViewText(R.id.tvCompactAmount, totalState(context, content))
        }

        private fun totalState(context: Context, content: WidgetContent): String = when {
            content.sourceCount == 0 -> context.getString(R.string.widget_no_banks)
            content.totalAmount != null -> content.totalAmount
            else -> context.getString(R.string.widget_balance_unknown)
        }

        private fun bindWide(context: Context, views: RemoteViews, content: WidgetContent) {
            views.setTextViewText(R.id.tvWideLabel, content.totalLabel)
            views.setTextViewText(R.id.tvWideAmount, totalState(context, content))
            bindLastTransaction(views, content)
        }

        private fun bindBalanceList(
            context: Context,
            views: RemoteViews,
            content: WidgetContent,
            narrow: Boolean,
            rowLimit: Int
        ) {
            views.removeAllViews(R.id.balancesContainer)
            val rowLayout = if (narrow) R.layout.widget_bank_narrow_row else R.layout.widget_bank_row
            for (row in content.balanceRows.take(rowLimit)) {
                val rowView = RemoteViews(context.packageName, rowLayout)
                rowView.setTextViewText(R.id.tvBankLabel, row.label)
                rowView.setTextViewText(R.id.tvBankBalance, row.amount)
                views.addView(R.id.balancesContainer, rowView)
            }
        }

        private fun bindNoBalances(views: RemoteViews, content: WidgetContent) {
            val hasBalances = content.balanceRows.isNotEmpty()
            views.setViewVisibility(
                R.id.balancesContainer,
                if (hasBalances) View.VISIBLE else View.GONE
            )
            views.setViewVisibility(
                R.id.tvNoBalances,
                if (hasBalances) View.GONE else View.VISIBLE
            )
        }

        private fun bindLastTransaction(views: RemoteViews, content: WidgetContent) {
            views.setTextViewText(R.id.tvLastTitle, content.lastTitle)
            views.setTextViewText(R.id.tvLastAmount, content.lastAmount)
        }

        private fun formatAmount(context: Context, amount: Number): String =
            context.getString(R.string.widget_amount_format, formatter.format(amount))

        private fun layoutMode(options: Bundle): LayoutMode {
            val width = options.getInt(
                AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,
                DEFAULT_SIZE_DP
            )
            val height = widgetHeight(options)
            return layoutMode(width, height)
        }

        private fun layoutMode(width: Int, height: Int): LayoutMode {
            val area = width.toLong() * height.toLong()
            return when {
                area < COMPACT_MAX_AREA_DP2 ||
                    height < SHORT_HEIGHT_DP && width < SHORT_WIDGET_MAX_WIDTH_DP -> LayoutMode.COMPACT
                area >= DETAILED_MIN_AREA_DP2 -> LayoutMode.DETAILED
                width >= height -> LayoutMode.WIDE
                else -> LayoutMode.NARROW
            }
        }

        private fun widgetHeight(options: Bundle): Int = options.getInt(
            AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,
            DEFAULT_SIZE_DP
        )

        private fun narrowRowLimit(height: Int): Int = when {
            height < 150 -> 2
            height < 200 -> 3
            height < 280 -> 4
            else -> 6
        }

        private fun detailedRowLimit(height: Int): Int = when {
            height < 100 -> 1
            height < 180 -> 2
            height < 240 -> 3
            height < 300 -> 5
            else -> 8
        }
    }
}
