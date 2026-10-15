package com.shuaji.cards.data.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

class AnnualFeeReminderScheduler(
    private val context: Context,
    private val store: AnnualFeeReminderStore,
) {
    private val alarmManager =
        context.applicationContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    fun replaceAll(alarms: List<AnnualFeeReminderPlanner.PlannedAlarm>) {
        val appContext = context.applicationContext
        setRescheduleReceiverEnabled(appContext, alarms.isNotEmpty())
        val previous = store.readScheduledKeys()
        for (key in previous) {
            val parsed = AnnualFeeReminderStore.parseScheduleKey(key) ?: continue
            cancel(appContext, parsed.first, parsed.second, parsed.third)
        }
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

    private fun setRescheduleReceiverEnabled(
        appContext: Context,
        enabled: Boolean,
    ) {
        val receiver = ComponentName(appContext, AnnualFeeReminderBootReceiver::class.java)
        val target =
            if (enabled) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
        val packageManager = appContext.packageManager
        if (packageManager.getComponentEnabledSetting(receiver) != target) {
            packageManager.setComponentEnabledSetting(receiver, target, PackageManager.DONT_KILL_APP)
        }
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
