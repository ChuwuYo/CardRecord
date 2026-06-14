package com.shuaji.cards.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.shuaji.cards.ui.screen.CardDetailScreen
import com.shuaji.cards.ui.screen.CardEditScreen
import com.shuaji.cards.ui.screen.CardFolderScreen
import com.shuaji.cards.ui.screen.CardListScreen

object Routes {
    const val LIST = "list"
    const val CREATE = "create"
    const val EDIT = "edit/{cardId}"
    const val DETAIL = "detail/{cardId}"
    const val FOLDERS = "folders"

    fun edit(cardId: Long?) = if (cardId == null) CREATE else "edit/$cardId"

    fun detail(cardId: Long) = "detail/$cardId"
}

@Composable
fun ShuajiApp() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Routes.LIST) {
        composable(Routes.LIST) {
            CardListScreen(
                onAddCard = { navController.navigate(Routes.CREATE) },
                onCardClick = { id -> navController.navigate(Routes.detail(id)) },
                onManageFolders = { navController.navigate(Routes.FOLDERS) },
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
    }
}
