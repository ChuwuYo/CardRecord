package com.shuaji.cards.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.shuaji.cards.R
import com.shuaji.cards.data.AnnualFeeCycleEvent
import com.shuaji.cards.data.SettingsDoneEvent
import com.shuaji.cards.requireShuajiApplication
import com.shuaji.cards.ui.component.ObserveAsEvents
import com.shuaji.cards.ui.screen.CardDetailScreen
import com.shuaji.cards.ui.screen.CardEditScreen
import com.shuaji.cards.ui.screen.CardFolderScreen
import com.shuaji.cards.ui.screen.CardListScreen
import com.shuaji.cards.ui.screen.SettingsScreen
import kotlinx.serialization.Serializable

@Serializable
data object CardList

@Serializable
data class CardEdit(
    val cardId: Long? = null,
)

@Serializable
data class CardDetail(
    val cardId: Long,
)

@Serializable
data object Folders

@Serializable
data object Settings

@Composable
fun ShuajiApp() {
    val context = LocalContext.current
    val resources = LocalResources.current
    val app = context.requireShuajiApplication()
    val cycleEvents = app.container.annualFeeCycleEvents
    val settingsEvents = app.container.settingsEvents
    val snackbarHostState = remember { SnackbarHostState() }
    val autoResetErrorMessage = stringResource(R.string.cycle_auto_reset_error_message)

    ObserveAsEvents(cycleEvents) { event ->
        val message =
            when (event) {
                is AnnualFeeCycleEvent.Normalized ->
                    resources.getQuantityString(
                        R.plurals.cycle_auto_reset_message,
                        event.count,
                        event.count,
                    )
                is AnnualFeeCycleEvent.Failed -> autoResetErrorMessage
            }
        snackbarHostState.showSnackbar(
            message = message,
            duration = SnackbarDuration.Short,
        )
    }

    val errorPrefix = stringResource(R.string.settings_snackbar_error_prefix)
    ObserveAsEvents(settingsEvents) { event: SettingsDoneEvent ->
        val message = if (event.isError) "$errorPrefix${event.message}" else event.message
        snackbarHostState.showSnackbar(
            message = message,
            duration = SnackbarDuration.Long,
        )
    }

    val navController = rememberNavController()
    val currentDestination =
        navController
            .currentBackStackEntryAsState()
            .value
            ?.destination
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
    ) {
        NavHost(
            navController = navController,
            startDestination = CardList,
        ) {
            composable<CardList> { entry ->
                CardListScreen(
                    onAddCard = { navController.fromCurrent(entry) { navigate(CardEdit()) } },
                    onCardClick = { id -> navController.fromCurrent(entry) { navigate(CardDetail(id)) } },
                    onManageFolders = { navController.fromCurrent(entry) { navigate(Folders) } },
                    onOpenSettings = { navController.fromCurrent(entry) { navigate(Settings) } },
                )
            }
            composable<CardEdit> { entry ->
                CardEditScreen(
                    cardId = entry.toRoute<CardEdit>().cardId,
                    onBack = { navController.fromCurrent(entry) { popBackStack() } },
                )
            }
            composable<CardDetail> { entry ->
                val id = entry.toRoute<CardDetail>().cardId
                CardDetailScreen(
                    onBack = { navController.fromCurrent(entry) { popBackStack() } },
                    onEdit = { navController.fromCurrent(entry) { navigate(CardEdit(id)) } },
                )
            }
            composable<Folders> { entry ->
                CardFolderScreen(
                    onBack = { navController.fromCurrent(entry) { popBackStack() } },
                )
            }
            composable<Settings> { entry ->
                SettingsScreen(
                    onBack = { navController.fromCurrent(entry) { popBackStack() } },
                )
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing
                            .union(WindowInsets.ime)
                            .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
                    ).padding(horizontal = 16.dp)
                    .padding(bottom = currentDestination.globalSnackbarBottomPadding()),
        )
    }
}

private inline fun NavHostController.fromCurrent(
    entry: NavBackStackEntry,
    block: NavHostController.() -> Unit,
) {
    if (currentBackStackEntry?.id == entry.id) block()
}

private fun NavDestination?.globalSnackbarBottomPadding() =
    if (this != null && (hasRoute<CardList>() || hasRoute<CardDetail>())) 88.dp else 16.dp
