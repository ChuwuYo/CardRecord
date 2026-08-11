package com.shuaji.cards.data.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.shuaji.cards.requireShuajiApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 开机后系统会清掉未触发闹钟；时区/系统时间变化也会让「本地零点」触发点失真。
 * 用 [goAsync] 把重排撑到 AlarmManager 写完，避免冷启动广播返回后进程被杀丢单。
 */
class AnnualFeeReminderBootReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent?,
    ) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_TIMEZONE_CHANGED &&
            action != Intent.ACTION_TIME_CHANGED
        ) {
            return
        }
        val app = context.requireShuajiApplication()
        val pendingResult = goAsync()
        app.container.reminderScope.launch {
            try {
                app.container.rescheduleRemindersFromStore()
            } catch (error: CancellationException) {
                throw error
            } catch (error: RuntimeException) {
                Log.w(TAG, "boot/time reschedule failed", error)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "AnnualFeeReminder"
    }
}
