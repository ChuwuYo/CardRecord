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
 * onCreate 里除了 init container 之外，**还跑一次 `resetOverdueCycles`**
 * ——把"凡是 nextDueDateMillis 过了的卡"自动续期（删流水 + 推 1 年）。
 * 凡是存在 `nextDueDateMillis` 这个用户能填的字段，必须有「到期自动行为」，
 * 不然就是「写而不读」的死字段（v1.4.0 我漏了这条原则，已在 v1.4.2 补上）。
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
