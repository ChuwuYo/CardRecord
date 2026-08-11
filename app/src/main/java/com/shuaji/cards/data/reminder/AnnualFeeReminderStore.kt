package com.shuaji.cards.data.reminder

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * 提醒开关与「本结算周期某档位已通知」去重。
 *
 * 去重 key = cardId + threshold + dueDateToken；续期换 token 后自动允许再提醒。
 */
class AnnualFeeReminderStore(
    context: Context,
) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 默认关，避免 Android 13+ 未授权时空排闹钟；用户在设置中开启并授权后再排。 */
    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, false)

    fun setEnabled(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_ENABLED, enabled) }
    }

    /** 是否已向系统申请过 POST_NOTIFICATIONS（用于区分「从未问过」与「永久拒绝」）。 */
    fun hasAskedNotificationPermission(): Boolean = prefs.getBoolean(KEY_ASKED_NOTIFICATION, false)

    fun markAskedNotificationPermission() {
        prefs.edit { putBoolean(KEY_ASKED_NOTIFICATION, true) }
    }

    fun observeEnabled(): Flow<Boolean> =
        callbackFlow {
            val listener =
                SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                    if (key == null || key == KEY_ENABLED) {
                        trySend(isEnabled())
                    }
                }
            prefs.registerOnSharedPreferenceChangeListener(listener)
            trySend(isEnabled())
            awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
        }

    fun wasNotified(
        cardId: Long,
        thresholdDays: Int,
        dueDateToken: Long,
    ): Boolean = prefs.getBoolean(notifiedKey(cardId, thresholdDays, dueDateToken), false)

    fun markNotified(
        cardId: Long,
        thresholdDays: Int,
        dueDateToken: Long,
    ) {
        prefs.edit { putBoolean(notifiedKey(cardId, thresholdDays, dueDateToken), true) }
    }

    fun readScheduledKeys(): Set<String> = prefs.getStringSet(KEY_SCHEDULED, emptySet()) ?: emptySet()

    fun writeScheduledKeys(keys: Set<String>) {
        prefs.edit { putStringSet(KEY_SCHEDULED, keys.toSet()) }
    }

    /**
     * 丢掉已不属于任何现存卡片当前结算日的去重项。
     *
     * 不能只按「当前已排闹钟」剪枝：同周期 30 天档已通知后只排 10 天档，
     * 若误删 30 天去重会在下次重排时重复提醒。
     *
     * @param liveCardDueTokens (cardId, dueDateToken) 当前库里仍有效的结算日对。
     */
    fun pruneNotifiedKeeping(liveCardDueTokens: Set<Pair<Long, Long>>) {
        val toRemove =
            prefs.all.keys.filter { key ->
                if (!key.startsWith("n:")) return@filter false
                val parts = key.removePrefix("n:").split(':')
                if (parts.size != 3) return@filter true
                val cardId = parts[0].toLongOrNull() ?: return@filter true
                val due = parts[2].toLongOrNull() ?: return@filter true
                (cardId to due) !in liveCardDueTokens
            }
        if (toRemove.isEmpty()) return
        prefs.edit {
            toRemove.forEach { remove(it) }
        }
    }

    /**
     * REPLACE 导入后卡片 ID 全部重分配：清空已排闹钟登记与去重标记，避免旧 ID 脏状态。
     * 保留「是否开启提醒」与「是否问过通知权限」（后者属本机系统交互史，不是备份内容）。
     */
    fun clearScheduleAndNotifiedState() {
        val enabled = isEnabled()
        val asked = hasAskedNotificationPermission()
        prefs.edit {
            clear()
            putBoolean(KEY_ENABLED, enabled)
            if (asked) putBoolean(KEY_ASKED_NOTIFICATION, true)
        }
    }

    companion object {
        private const val PREFS_NAME = "annual_fee_reminders"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_ASKED_NOTIFICATION = "asked_notification_permission"
        private const val KEY_SCHEDULED = "scheduled_keys"

        fun notifiedKey(
            cardId: Long,
            thresholdDays: Int,
            dueDateToken: Long,
        ): String = "n:$cardId:$thresholdDays:$dueDateToken"

        fun scheduleKey(
            cardId: Long,
            thresholdDays: Int,
            dueDateToken: Long,
        ): String = "s:$cardId:$thresholdDays:$dueDateToken"

        fun parseScheduleKey(key: String): Triple<Long, Int, Long>? {
            val parts = key.removePrefix("s:").split(':')
            if (parts.size != 3) return null
            val cardId = parts[0].toLongOrNull() ?: return null
            val threshold = parts[1].toIntOrNull() ?: return null
            val due = parts[2].toLongOrNull() ?: return null
            return Triple(cardId, threshold, due)
        }
    }
}
