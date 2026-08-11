package com.shuaji.cards.data.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * AlarmManager 非精确调度。官方「用户指定某时刻之后做事」路径：
 * [AlarmManager.setAndAllowWhileIdle]（非 setExact*）。
 *
 * 每张卡只挂下一档，闹钟数量 ≈ 卡片数，直接 while-idle 即可，
 * 避免远未来 `set` 后无手递、Doze 下拖到维护窗口才到的问题。
 *
 * minSdk 26，可直接使用 while-idle 与 IMMUTABLE PendingIntent。
 */
class AnnualFeeReminderScheduler(
    private val context: Context,
    private val store: AnnualFeeReminderStore,
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
        // 先落空集，再每挂成功一只就写入，避免中途失败后 prefs 仍指向未挂上的闹钟。
        val nextKeys = linkedSetOf<String>()
        store.writeScheduledKeys(nextKeys)
        for (alarm in alarms) {
            val key =
                AnnualFeeReminderStore.scheduleKey(
                    alarm.cardId,
                    alarm.thresholdDays,
                    alarm.dueDateToken,
                )
            schedule(appContext, alarm)
            nextKeys += key
            store.writeScheduledKeys(nextKeys)
        }
    }

    fun cancelAllTracked() {
        replaceAll(emptyList())
    }

    private fun schedule(
        appContext: Context,
        alarm: AnnualFeeReminderPlanner.PlannedAlarm,
    ) {
        val pi = pendingIntent(appContext, alarm.cardId, alarm.thresholdDays, alarm.dueDateToken)
        alarmManager.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            alarm.triggerAtMillis,
            pi,
        )
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
        return PendingIntent.getBroadcast(
            appContext,
            requestCode(cardId, thresholdDays),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        fun requestCode(
            cardId: Long,
            thresholdDays: Int,
        ): Int = ((cardId * 31) + thresholdDays).toInt()
    }
}
