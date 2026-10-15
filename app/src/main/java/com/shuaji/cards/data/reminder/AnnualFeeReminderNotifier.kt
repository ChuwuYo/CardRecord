package com.shuaji.cards.data.reminder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.shuaji.cards.MainActivity
import com.shuaji.cards.R
import com.shuaji.cards.core.AppLocales

class AnnualFeeReminderNotifier(
    private val context: Context,
) {
    fun ensureChannel() = ensureChannel(AppLocales.localize(context))

    private fun ensureChannel(strings: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                strings.getString(R.string.reminder_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = strings.getString(R.string.reminder_channel_description)
            }
        manager.createNotificationChannel(channel)
    }

    fun canPostNotifications(): Boolean = canPostNotifications(context)

    fun notifyProgress(decision: AnnualFeeReminderPlanner.FireDecision): Boolean {
        if (!canPostNotifications()) return false
        val strings = AppLocales.localize(context)
        ensureChannel(strings)
        val title = strings.getString(R.string.reminder_notification_title)
        val remaining = decision.remainingDays.toInt().coerceAtLeast(0)
        val text =
            strings.resources.getQuantityString(
                R.plurals.reminder_notification_body,
                remaining,
                decision.bank,
                decision.name,
                remaining,
                decision.currentCount,
                decision.requiredCount,
            )
        val contentIntent =
            PendingIntent.getActivity(
                context,
                decision.cardId.toInt(),
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        PendingIntent.FLAG_IMMUTABLE
                    } else {
                        0
                    },
            )
        val notification =
            NotificationCompat
                .Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(contentIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
        return try {
            NotificationManagerCompat.from(context).notify(
                notificationId(decision.cardId, decision.thresholdDays),
                notification,
            )
            true
        } catch (error: SecurityException) {
            Log.w(TAG, "notify blocked", error)
            false
        }
    }

    companion object {
        const val CHANNEL_ID = "annual_fee_reminders"
        private const val TAG = "AnnualFeeReminder"

        fun notificationId(
            cardId: Long,
            thresholdDays: Int,
        ): Int = ((cardId * 31) + thresholdDays).toInt()

        fun canPostNotifications(context: Context): Boolean {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                return false
            }
            val manager = NotificationManagerCompat.from(context)
            if (!manager.areNotificationsEnabled()) return false
            val channel = manager.getNotificationChannel(CHANNEL_ID)
            return channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE
        }
    }
}
