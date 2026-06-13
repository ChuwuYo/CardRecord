package com.example.creditcardtracker

import android.app.Application
import com.example.creditcardtracker.data.AppContainer
import com.example.creditcardtracker.data.DefaultAppContainer

/**
 * 应用入口。手动依赖容器，避免引入额外 DI 框架。
 */
class CreditCardApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = DefaultAppContainer(this)
    }
}
