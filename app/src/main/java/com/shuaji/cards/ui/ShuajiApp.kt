package com.shuaji.cards.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.shuaji.cards.R
import com.shuaji.cards.ShuajiApplication
import com.shuaji.cards.data.AnnualFeeCycleEvent
import com.shuaji.cards.data.SettingsDoneEvent
import com.shuaji.cards.ui.screen.CardDetailScreen
import com.shuaji.cards.ui.screen.CardEditScreen
import com.shuaji.cards.ui.screen.CardFolderScreen
import com.shuaji.cards.ui.screen.CardListScreen
import com.shuaji.cards.ui.screen.SettingsScreen

object Routes {
    const val LIST = "list"
    const val CREATE = "create"
    const val EDIT = "edit/{cardId}"
    const val DETAIL = "detail/{cardId}"
    const val FOLDERS = "folders"
    const val SETTINGS = "settings"

    fun edit(cardId: Long?) = if (cardId == null) CREATE else "edit/$cardId"

    fun detail(cardId: Long) = "detail/$cardId"
}

/**
 * 顶层 app 容器：Scaffold + NavHost + 全局 SnackbarHost。
 *
 * SnackbarHost 放在最外层，这样不论用户在哪个 page 都能弹消息：
 * - `ShuajiApplication` 启动生命周期协调器，归一化结果通过
 *   `annualFeeCycleEvents` 推到 UI，**不论用户当前在 list / detail / edit
 *   都能看到「X 张卡已自动续期」提示**。
 * - `SettingsViewModel` 导出 / 导入完成时，**通过 `settingsEvents` 推到全局**——
 *   设置页离开组合树后，顶层收集器仍可在当前页面展示结果。
 */
@Composable
fun ShuajiApp() {
    val context = LocalContext.current
    val app = context.applicationContext as ShuajiApplication
    val cycleEvents = app.container.annualFeeCycleEvents
    val settingsEvents = app.container.settingsEvents
    val snackbarHostState = remember { SnackbarHostState() }
    val autoResetMessage = stringResource(R.string.cycle_auto_reset_message)
    val autoResetErrorMessage = stringResource(R.string.cycle_auto_reset_error_message)

    // 订阅自动续期事件 → 弹 Snackbar
    LaunchedEffect(cycleEvents) {
        cycleEvents.collect { event ->
            val message =
                when (event) {
                    is AnnualFeeCycleEvent.Normalized -> autoResetMessage.format(event.count)
                    is AnnualFeeCycleEvent.Failed -> autoResetErrorMessage
                }
            snackbarHostState.showSnackbar(
                message = message,
                duration = SnackbarDuration.Short,
            )
        }
    }

    // 订阅设置页结果事件 → 全局弹 Snackbar。
    // 用 Long 持续时间——导出 / 导入这种"操作结果回执"用户多看一眼更稳
    // （SnackbarDuration.Long = 10s，Short = 4s）。
    // 错误事件加本地化前缀，便于与成功结果区分。
    val errorPrefix = stringResource(R.string.settings_snackbar_error_prefix)
    LaunchedEffect(settingsEvents) {
        settingsEvents.collect { event: SettingsDoneEvent ->
            val message = if (event.isError) "$errorPrefix${event.message}" else event.message
            snackbarHostState.showSnackbar(
                message = message,
                duration = SnackbarDuration.Long,
            )
        }
    }

    val navController = rememberNavController()
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        // 不传 padding 给 NavHost —— 各 page 自己的 Scaffold 处理 WindowInsets，
        // 避免外层 padding 跟内层 Scaffold padding 重复计算。
        NavHost(
            navController = navController,
            startDestination = Routes.LIST,
        ) {
            composable(Routes.LIST) {
                CardListScreen(
                    onAddCard = { navController.navigate(Routes.CREATE) },
                    onCardClick = { id -> navController.navigate(Routes.detail(id)) },
                    onManageFolders = { navController.navigate(Routes.FOLDERS) },
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                )
            }
            composable(Routes.CREATE) {
                CardEditScreen(
                    cardId = null,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(
                route = Routes.EDIT,
                arguments = listOf(navArgument("cardId") { type = NavType.LongType }),
            ) { backStack ->
                val id = backStack.arguments?.getLong("cardId")
                CardEditScreen(
                    cardId = id,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(
                route = Routes.DETAIL,
                arguments = listOf(navArgument("cardId") { type = NavType.LongType }),
            ) { backStack ->
                val id = backStack.arguments?.getLong("cardId") ?: return@composable
                CardDetailScreen(
                    cardId = id,
                    onBack = { navController.popBackStack() },
                    onEdit = { navController.navigate(Routes.edit(id)) },
                )
            }
            composable(Routes.FOLDERS) {
                CardFolderScreen(
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}
