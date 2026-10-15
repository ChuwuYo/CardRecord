package com.shuaji.cards.data.reminder

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

class AnnualFeeReminderStore(
    context: Context,
) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, false)

    fun setEnabled(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_ENABLED, enabled) }
    }

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
