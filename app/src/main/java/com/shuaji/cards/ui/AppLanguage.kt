package com.shuaji.cards.ui

import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.shuaji.cards.R

/**
 * 应用内可选语言（per-app language，官方 [AppCompatDelegate] 方案）。
 *
 * **新增一门语言只需三步**：
 * 1. 在此枚举加一项（[tag] 为 BCP-47 语言标签，须与 `res/xml/locales_config.xml` 一致）；
 * 2. 新建 `res/values-<tag>/strings.xml` 放该语言的翻译；
 * 3. 在 `res/xml/locales_config.xml` 加一条 `<locale android:name="<tag>"/>`。
 *
 * 设置页的语言列表直接由 [entries] 驱动，无需改 UI。语言选择由 AppCompat 持久化
 * （`autoStoreLocales`，见 AndroidManifest），不占用 DataStore。
 */
enum class AppLanguage(
    /** BCP-47 语言标签；`null` 表示「跟随系统」。 */
    val tag: String?,
    @StringRes val labelRes: Int,
) {
    SYSTEM(null, R.string.settings_language_system),
    SIMPLIFIED_CHINESE("zh-Hans", R.string.settings_language_zh_hans),
    ;

    companion object {
        /** 当前生效的语言；空 locale 列表（跟随系统）回退到 [SYSTEM]。 */
        fun current(): AppLanguage {
            val tags = AppCompatDelegate.getApplicationLocales().toLanguageTags()
            if (tags.isEmpty()) return SYSTEM
            return entries.firstOrNull { it.tag != null && tags.startsWith(it.tag!!) } ?: SYSTEM
        }

        /** 应用所选语言；AppCompat 会持久化并自动重建 Activity 以生效。 */
        fun apply(language: AppLanguage) {
            val locales =
                language.tag
                    ?.let { LocaleListCompat.forLanguageTags(it) }
                    ?: LocaleListCompat.getEmptyLocaleList()
            AppCompatDelegate.setApplicationLocales(locales)
        }
    }
}
