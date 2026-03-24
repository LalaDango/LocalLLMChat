package com.example.localllmchat.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.example.localllmchat.data.repository.ChatRepository
import com.example.localllmchat.data.repository.PresetRepository
import com.example.localllmchat.data.repository.SettingsRepository
import com.example.localllmchat.ui.screen.chat.ChatScreen
import com.example.localllmchat.ui.screen.conversationlist.ConversationListScreen
import com.example.localllmchat.ui.screen.preset.PresetEditScreen
import com.example.localllmchat.ui.screen.preset.PresetListScreen
import com.example.localllmchat.ui.screen.settings.SettingsScreen

sealed class Screen(val route: String) {
    data object ConversationList : Screen("conversation_list")
    data object Chat : Screen("chat/{conversationId}") {
        fun createRoute(conversationId: Long) = "chat/$conversationId"
    }
    data object Settings : Screen("settings")
    data object PresetList : Screen("preset_list")
    data object PresetEdit : Screen("preset_edit?presetId={presetId}") {
        fun createRoute(presetId: Long? = null): String {
            return if (presetId != null) "preset_edit?presetId=$presetId" else "preset_edit"
        }
    }
}

@Composable
fun NavGraph(
    navController: NavHostController,
    chatRepository: ChatRepository,
    settingsRepository: SettingsRepository,
    presetRepository: PresetRepository
) {
    NavHost(
        navController = navController,
        startDestination = Screen.ConversationList.route
    ) {
        composable(Screen.ConversationList.route) {
            ConversationListScreen(
                chatRepository = chatRepository,
                presetRepository = presetRepository,
                onConversationClick = { conversationId ->
                    navController.navigate(Screen.Chat.createRoute(conversationId))
                },
                onSettingsClick = {
                    navController.navigate(Screen.Settings.route)
                },
                onNewConversation = { conversationId ->
                    navController.navigate(Screen.Chat.createRoute(conversationId))
                },
                onNavigateToPresetList = {
                    navController.navigate(Screen.PresetList.route)
                },
                onNavigateToPresetEdit = { presetId ->
                    navController.navigate(Screen.PresetEdit.createRoute(presetId))
                }
            )
        }

        composable(
            route = Screen.Chat.route,
            arguments = listOf(
                navArgument("conversationId") { type = NavType.LongType }
            )
        ) { backStackEntry ->
            val conversationId = backStackEntry.arguments?.getLong("conversationId") ?: 0L
            ChatScreen(
                conversationId = conversationId,
                chatRepository = chatRepository,
                settingsRepository = settingsRepository,
                onBack = { navController.popBackStack() }
            )
        }

        composable(Screen.Settings.route) {
            SettingsScreen(
                settingsRepository = settingsRepository,
                onBack = { navController.popBackStack() },
                onNavigateToPresetList = {
                    navController.navigate(Screen.PresetList.route)
                }
            )
        }

        composable(Screen.PresetList.route) {
            PresetListScreen(
                presetRepository = presetRepository,
                onBack = { navController.popBackStack() },
                onPresetClick = { presetId ->
                    navController.navigate(Screen.PresetEdit.createRoute(presetId))
                },
                onAddNew = {
                    navController.navigate(Screen.PresetEdit.createRoute(null))
                }
            )
        }

        composable(
            route = Screen.PresetEdit.route,
            arguments = listOf(
                navArgument("presetId") {
                    type = NavType.LongType
                    defaultValue = -1L
                }
            )
        ) { backStackEntry ->
            val presetId = backStackEntry.arguments?.getLong("presetId") ?: -1L
            PresetEditScreen(
                presetRepository = presetRepository,
                presetId = if (presetId > 0) presetId else null,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
