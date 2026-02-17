package com.example.localllmchat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.navigation.compose.rememberNavController
import com.example.localllmchat.ui.navigation.NavGraph
import com.example.localllmchat.ui.theme.LocalLLMChatTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as LocalLLMChatApp

        setContent {
            LocalLLMChatTheme {
                val navController = rememberNavController()
                NavGraph(
                    navController = navController,
                    chatRepository = app.chatRepository,
                    settingsRepository = app.settingsRepository,
                    leapModelManager = app.leapModelManager
                )
            }
        }
    }
}
