package com.shuaji.cards

import android.app.Application
import com.shuaji.cards.data.AppContainer
import com.shuaji.cards.data.DefaultAppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 应用入口。手动依赖容器，避免引入额外 DI 框架。
 *
 * onCreate 初始化容器，并运行一次 `resetOverdueCycles`：
 * 对已到结算日的卡清空周期流水并把结算日推进到未来。
 */
class ShuajiApplication : Application() {
    lateinit var container: AppContainer
        private set

    /** Application 级别的协程 scope，绑定 process 生命周期。 */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        container = DefaultAppContainer(this)
        // 启动时自动续期检查（任何 nextDueDateMillis < now 的卡 → 删流水 + 推 1 年）。
        // 续期 + emit 收口在 AppContainer 内，这里只依赖接口、不向下转型。
        appScope.launch {
            container.runStartupCycleReset(System.currentTimeMillis())
        }
    }
}
