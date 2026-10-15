package com.shuaji.cards.data.reminder

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AnnualFeeReminderSchedulerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val scheduler = AnnualFeeReminderScheduler(context, AnnualFeeReminderStore(context))
    private val receiver = ComponentName(context, AnnualFeeReminderBootReceiver::class.java)

    @Test
    fun rescheduleReceiver_isEnabledOnlyWhileAlarmsAreScheduled() {
        scheduler.replaceAll(
            listOf(
                AnnualFeeReminderPlanner.PlannedAlarm(
                    cardId = 1L,
                    dueDateToken = 0L,
                    thresholdDays = 7,
                    triggerAtMillis = System.currentTimeMillis() + 60_000L,
                ),
            ),
        )
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED, context.packageManager.getComponentEnabledSetting(receiver))

        scheduler.cancelAllTracked()
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, context.packageManager.getComponentEnabledSetting(receiver))
    }
}
