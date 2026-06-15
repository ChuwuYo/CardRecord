package com.shuaji.cards

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * JUnit 4 Rule：在每个测试方法前把 `Dispatchers.Main` 替换成 [UnconfinedTestDispatcher]，
 * 测试结束后还原。
 *
 * ViewModel 的 `viewModelScope` 默认绑定到 `Dispatchers.Main.immediate`；`runTest` 里
 * 没有 Main dispatcher → 协程无法真正运行 → `advanceUntilIdle()` 也推不动。
 *
 * 选 [UnconfinedTestDispatcher] 而非 [kotlinx.coroutines.test.StandardTestDispatcher] 是
 * **关键决策**：
 * - StandardTestDispatcher 推迟所有 launch 到 advanceUntilIdle() 才执行；
 * - UnconfinedTestDispatcher 立即执行 launch 的 body 到第一个 suspension point。
 *   对 ViewModel + viewModelScope 测试，Unconfined 让 export/import 内部的
 *   suspend 函数（如 emit / mock 的 return）能立即跑完而不需要手动驱动时间。
 *
 * 这也意味着测试里的"backgroundScope.launch { collect }" 在 emit 触发时 collector
 * 一定已注册就绪——避免「emit 提前触发 + collector 还没订阅 = 消息丢」的竞态。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    val testDispatcher: TestDispatcher = UnconfinedTestDispatcher(),
) : TestWatcher() {
    override fun starting(description: Description) {
        super.starting(description)
        Dispatchers.setMain(testDispatcher)
    }

    override fun finished(description: Description) {
        super.finished(description)
        Dispatchers.resetMain()
    }
}
