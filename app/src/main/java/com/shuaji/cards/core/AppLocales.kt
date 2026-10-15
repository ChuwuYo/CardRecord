package com.shuaji.cards.core

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.core.content.edit
import androidx.core.os.LocaleListCompat

object AppLocales {
    private const val PREFS = "app_locales"
    private const val KEY_LANGUAGE_TAGS = "language_tags"

    fun record(
        context: Context,
        locales: LocaleListCompat,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(KEY_LANGUAGE_TAGS, locales.toLanguageTags())
        }
    }

    fun localize(context: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return context
        val tags =
            context
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_LANGUAGE_TAGS, null)
                .orEmpty()
        if (tags.isEmpty()) return context
        val configuration =
            Configuration(context.resources.configuration).apply {
                setLocales(LocaleList.forLanguageTags(tags))
            }
        return context.createConfigurationContext(configuration)
    }
}
