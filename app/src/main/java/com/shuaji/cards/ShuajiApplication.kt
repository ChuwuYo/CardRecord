package com.shuaji.cards

import android.app.Application
import com.shuaji.cards.data.AppContainer
import com.shuaji.cards.data.DefaultAppContainer

/**
 * 应用入口。手动依赖容器，避免引入额外 DI 框架。
 */
class ShuajiApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = DefaultAppContainer(this)
    }
}
