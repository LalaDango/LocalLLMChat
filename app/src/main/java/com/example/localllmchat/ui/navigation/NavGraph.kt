package com.example.localllmchat.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.example.localllmchat.data.repository.ChatRepository
import com.example.localllmchat.data.repository.SettingsRepository
import com.example.localllmchat.ui.screen.chat.ChatScreen
import com.example.localllmchat.ui.screen.conversationlist.ConversationListScreen
import com.example.localllmchat.ui.screen.settings.SettingsScreen

sealed class Screen(val route: String) {
    data object ConversationList : Screen("conversation_list")
    data object Chat : Screen("chat/{conversationId}") {
        fun createRoute(conversationId: Long) = "chat/$conversationId"
    }
    data object Settings : Screen("settings")
}

@Composable
fun NavGraph(
    navController: NavHostController,
    chatRepository: ChatRepository,
    settingsRepository: SettingsRepository
) {
    NavHost(
        navController = navController,
        startDestination = Screen.ConversationList.route
    ) {
        composable(Screen.ConversationList.route) {
            ConversationListScreen(
                chatRepository = chatRepository,
                onConversationClick = { conversationId ->
                    navController.navigate(Screen.Chat.createRoute(conversationId))
                },
                onSettingsClick = {
                    navController.navigate(Screen.Settings.route)
                },
                onNewConversation = { conversationId ->
                    navController.navigate(Screen.Chat.createRoute(conversationId))
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
                onBack = { navController.popBackStack() }
            )
        }
    }
}
