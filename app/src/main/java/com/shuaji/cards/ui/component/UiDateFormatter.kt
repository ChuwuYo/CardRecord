package com.shuaji.cards.ui.component

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.core.os.ConfigurationCompat
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

@Composable
fun rememberUiDateFormatter(): UiDateFormatter {
    val locale = ConfigurationCompat.getLocales(LocalConfiguration.current)[0] ?: Locale.getDefault()
    val is24HourFormat = DateFormat.is24HourFormat(LocalContext.current)
    return remember(locale, is24HourFormat) { UiDateFormatter(locale, is24HourFormat, ZoneId.systemDefault()) }
}
