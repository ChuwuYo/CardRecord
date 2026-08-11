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

/** 本地通知渠道与发送。 */
class AnnualFeeReminderNotifier(
    private val context: Context,
) {
    fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.reminder_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.reminder_channel_description)
            }
        manager.createNotificationChannel(channel)
    }

    /** 系统层面是否允许本应用发通知（含 Android 13+ 运行时权限与渠道总开关）。 */
    fun canPostNotifications(): Boolean = canPostNotifications(context)

    /**
     * @return true 表示已尝试投递（调用方才可写去重）；false 表示当前无法投递，勿 markNotified。
     */
    fun notifyProgress(decision: AnnualFeeReminderPlanner.FireDecision): Boolean {
        if (!canPostNotifications()) return false
        ensureChannel()
        val title = context.getString(R.string.reminder_notification_title)
        val remaining = decision.remainingDays.toInt().coerceAtLeast(0)
        val text =
            context.resources.getQuantityString(
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
            // 权限竞态或 OEM 拒绝：不得 markNotified，留给下次重排重试。
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

        /** 设置页与 Notifier 共用，避免两处判断漂移。 */
        fun canPostNotifications(context: Context): Boolean {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val granted =
                    ContextCompat.checkSelfPermission(
                        context,
                        android.Manifest.permission.POST_NOTIFICATIONS,
                    ) == PackageManager.PERMISSION_GRANTED
                if (!granted) return false
            }
            return NotificationManagerCompat.from(context).areNotificationsEnabled()
        }
    }
}
