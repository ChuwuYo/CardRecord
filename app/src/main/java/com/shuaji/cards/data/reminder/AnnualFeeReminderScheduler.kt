package com.shuaji.cards.data.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * AlarmManager 非精确调度。官方「用户指定某时刻之后做事」路径：
 * [AlarmManager.setAndAllowWhileIdle] / [AlarmManager.set]（非 setExact*）。
 *
 * 远未来用 [AlarmManager.set] 交给系统批处理；仅接近触发时用 while-idle，
 * 减轻多卡场景下 while-idle 配额被挤爆的风险。
 */
class AnnualFeeReminderScheduler(
    private val context: Context,
    private val store: AnnualFeeReminderStore,
    private val nowMillisProvider: () -> Long = { System.currentTimeMillis() },
) {
    private val alarmManager =
        context.applicationContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    fun replaceAll(alarms: List<AnnualFeeReminderPlanner.PlannedAlarm>) {
        val appContext = context.applicationContext
        val previous = store.readScheduledKeys()
        for (key in previous) {
            val parsed = AnnualFeeReminderStore.parseScheduleKey(key) ?: continue
            cancel(appContext, parsed.first, parsed.second, parsed.third)
        }
        val nextKeys = linkedSetOf<String>()
        val now = nowMillisProvider()
        for (alarm in alarms) {
            val key =
                AnnualFeeReminderStore.scheduleKey(
                    alarm.cardId,
                    alarm.thresholdDays,
                    alarm.dueDateToken,
                )
            nextKeys += key
            schedule(appContext, alarm, now)
        }
        store.writeScheduledKeys(nextKeys)
    }

    fun cancelAllTracked() {
        replaceAll(emptyList())
    }

    private fun schedule(
        appContext: Context,
        alarm: AnnualFeeReminderPlanner.PlannedAlarm,
        nowMillis: Long,
    ) {
        val pi = pendingIntent(appContext, alarm.cardId, alarm.thresholdDays, alarm.dueDateToken)
        if (shouldUseWhileIdle(alarm.triggerAtMillis, nowMillis)) {
            // 非精确：尊重 Doze；允许在 idle 维护窗口送达。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    alarm.triggerAtMillis,
                    pi,
                )
            } else {
                @Suppress("DEPRECATION")
                alarmManager.set(AlarmManager.RTC_WAKEUP, alarm.triggerAtMillis, pi)
            }
        } else {
            // 远闹钟走普通非精确 set，避免大量 while-idle 占满配额。
            @Suppress("DEPRECATION")
            alarmManager.set(AlarmManager.RTC_WAKEUP, alarm.triggerAtMillis, pi)
        }
    }

    private fun cancel(
        appContext: Context,
        cardId: Long,
        thresholdDays: Int,
        dueDateToken: Long,
    ) {
        alarmManager.cancel(pendingIntent(appContext, cardId, thresholdDays, dueDateToken))
    }

    private fun pendingIntent(
        appContext: Context,
        cardId: Long,
        thresholdDays: Int,
        dueDateToken: Long,
    ): PendingIntent {
        val intent =
            Intent(appContext, AnnualFeeReminderReceiver::class.java).apply {
                action = AnnualFeeReminderReceiver.ACTION_FIRE
                putExtra(AnnualFeeReminderReceiver.EXTRA_CARD_ID, cardId)
                putExtra(AnnualFeeReminderReceiver.EXTRA_THRESHOLD_DAYS, thresholdDays)
                putExtra(AnnualFeeReminderReceiver.EXTRA_DUE_DATE_TOKEN, dueDateToken)
            }
        val flags =
            PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    PendingIntent.FLAG_IMMUTABLE
                } else {
                    0
                }
        return PendingIntent.getBroadcast(
            appContext,
            requestCode(cardId, thresholdDays),
            intent,
            flags,
        )
    }

    companion object {
        /** 距触发时刻在此窗口内才用 while-idle。 */
        const val WHILE_IDLE_WINDOW_MS: Long = 60L * 60L * 1000L

        fun shouldUseWhileIdle(
            triggerAtMillis: Long,
            nowMillis: Long,
        ): Boolean = triggerAtMillis - nowMillis <= WHILE_IDLE_WINDOW_MS

        fun requestCode(
            cardId: Long,
            thresholdDays: Int,
        ): Int = ((cardId * 31) + thresholdDays).toInt()
    }
}
