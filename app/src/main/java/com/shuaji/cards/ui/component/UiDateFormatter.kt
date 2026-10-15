package com.shuaji.cards.ui.component

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Resources
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.shuaji.cards.data.DateToken
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@Immutable
class UiDateFormatter(
    locale: Locale,
    is24HourFormat: Boolean,
    private val zoneId: ZoneId,
) {
    private val dateFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
    private val dateTimeFormatter =
        DateTimeFormatter.ofPattern(
            DateFormat.getBestDateTimePattern(locale, if (is24HourFormat) "yMMMdHm" else "yMMMdhma"),
            locale,
        )

    fun date(date: LocalDate): String = dateFormatter.format(date)

    fun dateToken(tokenMillis: Long): String = date(DateToken.toLocalDate(tokenMillis))

    fun annualDue(tokenMillis: Long): String = date(DateToken.annualDueDate(tokenMillis))

    fun dateTime(epochMillis: Long): String = dateTimeFormatter.format(Instant.ofEpochMilli(epochMillis).atZone(zoneId))
}

val LocalUiDateFormatter = staticCompositionLocalOf<UiDateFormatter> { error("LocalUiDateFormatter is not provided") }

@Composable
fun rememberUiDateFormatter(): UiDateFormatter {
    val locale = LocalConfiguration.current.locales[0]
    val context = LocalContext.current
    var timeSettingsChanges by remember { mutableIntStateOf(0) }
    DisposableEffect(context) {
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context,
                    intent: Intent,
                ) {
                    timeSettingsChanges++
                }
            }
        val filter =
            IntentFilter().apply {
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
            }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }
    return remember(locale, context, timeSettingsChanges) {
        val systemLocaleContext = context.createConfigurationContext(Resources.getSystem().configuration)
        UiDateFormatter(locale, DateFormat.is24HourFormat(systemLocaleContext), ZoneId.systemDefault())
    }
}
