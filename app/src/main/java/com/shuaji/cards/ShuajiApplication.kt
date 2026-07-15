package com.shuaji.cards

import android.app.Application
import com.shuaji.cards.data.AppContainer
import com.shuaji.cards.data.DefaultAppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * 应用入口。手动依赖容器，避免引入额外 DI 框架。
 *
 * onCreate 初始化容器，并启动进程生命周期感知的周期归一化协调器。
 */
class ShuajiApplication : Application() {
    lateinit var container: AppContainer
        private set

    /** Application 级别的协程 scope，绑定 process 生命周期。 */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        container = DefaultAppContainer(this)
        container.startAnnualFeeCycleCoordinator(appScope)
    }
}
