package com.shuaji.cards.data.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.shuaji.cards.requireShuajiApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.ZoneId

/**
 * 非精确闹钟回调：再读 Room 快照，未刷满才发通知并标记去重；结束后强制重排下一档。
 */
class AnnualFeeReminderReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent?,
    ) {
        if (intent?.action != ACTION_FIRE) return
        val cardId = intent.getLongExtra(EXTRA_CARD_ID, -1L)
        val thresholdDays = intent.getIntExtra(EXTRA_THRESHOLD_DAYS, -1)
        val dueDateToken = intent.getLongExtra(EXTRA_DUE_DATE_TOKEN, -1L)
        if (cardId < 0L || thresholdDays < 0 || dueDateToken < 0L) return

        val app = context.requireShuajiApplication()
        val pendingResult = goAsync()
        app.container.reminderScope.launch {
            try {
                try {
                    val store = app.container.reminderStore
                    if (!store.isEnabled()) return@launch
                    val cards =
                        app.container.repository
                            .observeCards()
                            .first()
                    val card = cards.firstOrNull { it.card.id == cardId }
                    val decision =
                        AnnualFeeReminderPlanner.decideFire(
                            card = card,
                            thresholdDays = thresholdDays,
                            dueDateTokenFromIntent = dueDateToken,
                            alreadyNotifiedForDue =
                                store.wasNotified(cardId, thresholdDays, dueDateToken),
                            now = Clock.systemUTC().instant(),
                            zoneId = ZoneId.systemDefault(),
                        )
                    if (decision.shouldNotify) {
                        val posted = app.container.reminderNotifier.notifyProgress(decision)
                        if (posted) {
                            store.markNotified(cardId, thresholdDays, dueDateToken)
                        }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: RuntimeException) {
                    Log.w(TAG, "fire failed cardId=$cardId", error)
                }
                // 重排须在 finish 之前完成：finish 后冷启动进程可能被立刻回收。
                try {
                    app.container.rescheduleRemindersFromStore()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: RuntimeException) {
                    Log.w(TAG, "post-fire reschedule failed cardId=$cardId", error)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "AnnualFeeReminder"
        const val ACTION_FIRE = "com.shuaji.cards.action.ANNUAL_FEE_REMINDER_FIRE"
        const val EXTRA_CARD_ID = "card_id"
        const val EXTRA_THRESHOLD_DAYS = "threshold_days"
        const val EXTRA_DUE_DATE_TOKEN = "due_date_token"
    }
}
