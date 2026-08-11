package com.shuaji.cards.data.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.shuaji.cards.requireShuajiApplication

/**
 * 开机后系统会清掉未触发闹钟；时区/系统时间变化也会让「本地零点」触发点失真。
 * 触达 Application 并请求提醒协调器重排。
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
        // Application.onCreate 会 start 协调器；这里再踢一脚覆盖时区变化等。
        app.container.requestReminderReschedule()
    }
}
