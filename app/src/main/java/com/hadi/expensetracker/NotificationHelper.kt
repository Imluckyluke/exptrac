package com.hadi.expensetracker

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import java.text.DecimalFormat

/**
 * Shows a notification for each newly detected bank transaction with a "Reply" action (typed
 * text becomes the expense title) and a "Skip" action, so the user can log the expense without
 * opening the app.
 */
object NotificationHelper {
    const val CHANNEL_ID = "pending_transactions"
    const val EXTRA_PENDING_ID = "pending_id"
    const val ACTION_REPLY = "com.hadi.expensetracker.ACTION_REPLY_TX"
    const val ACTION_SKIP = "com.hadi.expensetracker.ACTION_SKIP_TX"
    const val KEY_REPLY_TEXT = "key_reply_text"

    private val formatter = DecimalFormat("#,###")

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        context.getString(R.string.notif_channel_name),
                        NotificationManager.IMPORTANCE_HIGH
                    )
                )
            }
        }
    }

    private fun hasNotificationPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    fun showPendingTransactionNotification(context: Context, pending: PendingSms, sourceLabel: String) {
        ensureChannel(context)
        if (!hasNotificationPermission(context)) return

        val amountText = pending.guessedAmount?.let {
            context.getString(R.string.widget_amount_format, formatter.format(it))
        } ?: context.getString(R.string.widget_balance_unknown)
        val contentText = if (pending.guessedTitle.isNotBlank()) {
            "$sourceLabel · $amountText · ${pending.guessedTitle}"
        } else {
            "$sourceLabel · $amountText"
        }

        val replyIntent = Intent(context, NotificationReplyReceiver::class.java).apply {
            action = ACTION_REPLY
            putExtra(EXTRA_PENDING_ID, pending.id)
        }
        val replyPendingIntent = PendingIntent.getBroadcast(
            context, (pending.id * 10 + 1).toInt(), replyIntent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val remoteInput = RemoteInput.Builder(KEY_REPLY_TEXT)
            .setLabel(context.getString(R.string.notif_reply_hint))
            .build()
        val replyAction = NotificationCompat.Action.Builder(
            R.drawable.ic_sms, context.getString(R.string.notif_action_reply), replyPendingIntent
        ).addRemoteInput(remoteInput).build()

        val skipIntent = Intent(context, NotificationReplyReceiver::class.java).apply {
            action = ACTION_SKIP
            putExtra(EXTRA_PENDING_ID, pending.id)
        }
        val skipPendingIntent = PendingIntent.getBroadcast(
            context, (pending.id * 10 + 2).toInt(), skipIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val skipAction = NotificationCompat.Action.Builder(
            R.drawable.ic_delete, context.getString(R.string.notif_action_skip), skipPendingIntent
        ).build()

        val openIntent = Intent(context, MainActivity::class.java)
        val openPendingIntent = PendingIntent.getActivity(
            context, (pending.id * 10 + 3).toInt(), openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_sms)
            .setContentTitle(context.getString(R.string.notif_new_transaction_title))
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(pending.body))
            .setContentIntent(openPendingIntent)
            .addAction(replyAction)
            .addAction(skipAction)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        NotificationManagerCompat.from(context).notify(pending.id.toInt(), notification)
    }

    fun cancel(context: Context, pendingId: Long) {
        NotificationManagerCompat.from(context).cancel(pendingId.toInt())
    }
}
