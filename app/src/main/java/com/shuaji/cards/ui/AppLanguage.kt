package com.shuaji.cards.ui

import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.shuaji.cards.R

enum class AppLanguage(
    val tag: String?,
    @StringRes val labelRes: Int,
) {
    SYSTEM(null, R.string.settings_language_system),
    SIMPLIFIED_CHINESE("zh-Hans", R.string.settings_language_zh_hans),
    ENGLISH("en", R.string.settings_language_en),
    ;

    companion object {
        fun current(): AppLanguage {
            val tags = AppCompatDelegate.getApplicationLocales().toLanguageTags()
            if (tags.isEmpty()) return SYSTEM
            return entries.firstOrNull { language -> language.tag?.let(tags::startsWith) == true } ?: SYSTEM
        }

        fun apply(language: AppLanguage) {
            val locales =
                language.tag
                    ?.let { LocaleListCompat.forLanguageTags(it) }
                    ?: LocaleListCompat.getEmptyLocaleList()
            AppCompatDelegate.setApplicationLocales(locales)
        }
    }
}
