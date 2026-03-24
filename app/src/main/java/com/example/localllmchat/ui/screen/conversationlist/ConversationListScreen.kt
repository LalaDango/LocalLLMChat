package com.example.localllmchat.ui.screen.conversationlist

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.localllmchat.data.local.ConversationEntity
import com.example.localllmchat.data.local.PresetEntity
import com.example.localllmchat.data.repository.ChatRepository
import com.example.localllmchat.data.repository.PresetRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationListScreen(
    chatRepository: ChatRepository,
    presetRepository: PresetRepository,
    onConversationClick: (Long) -> Unit,
    onSettingsClick: () -> Unit,
    onNewConversation: (Long) -> Unit,
    onNavigateToPresetList: () -> Unit,
    onNavigateToPresetEdit: (Long?) -> Unit,
    viewModel: ConversationListViewModel = viewModel(
        factory = ConversationListViewModel.Factory(chatRepository, presetRepository)
    )
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("LocalLLMChat") },
                actions = {
                    IconButton(onClick = onSettingsClick) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings"
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    viewModel.createNewConversation { conversationId ->
                        onNewConversation(conversationId)
                    }
                }
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "New Chat"
                )
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            PresetSection(
                presets = uiState.presets,
                onPresetClick = { preset ->
                    viewModel.createConversationFromPreset(preset) { id ->
                        onNewConversation(id)
                    }
                },
                onShowAll = onNavigateToPresetList,
                onAddNew = { onNavigateToPresetEdit(null) }
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f)
            ) {
                when {
                    uiState.isLoading -> {
                        CircularProgressIndicator(
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                    uiState.conversations.isEmpty() -> {
                        Text(
                            text = "No conversations yet.\nTap + to start a new chat.",
                            modifier = Modifier.align(Alignment.Center),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    else -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(
                                items = uiState.conversations,
                                key = { it.id }
                            ) { conversation ->
                                ConversationItem(
                                    conversation = conversation,
                                    onClick = { onConversationClick(conversation.id) },
                                    onDelete = { viewModel.deleteConversation(conversation.id) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PresetSection(
    presets: List<PresetEntity>,
    onPresetClick: (PresetEntity) -> Unit,
    onShowAll: () -> Unit,
    onAddNew: () -> Unit
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "プリセット",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (presets.isNotEmpty()) {
                Text(
                    text = "すべて表示",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable(onClick = onShowAll)
                )
            }
        }

        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(bottom = 8.dp)
        ) {
            items(
                items = presets,
                key = { it.id }
            ) { preset ->
                PresetCard(
                    preset = preset,
                    onClick = { onPresetClick(preset) }
                )
            }
            item {
                AddPresetCard(onClick = onAddNew)
            }
        }
    }
}

@Composable
private fun PresetCard(
    preset: PresetEntity,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .width(100.dp)
            .height(72.dp)
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = preset.emoji,
                fontSize = 24.sp
            )
            Text(
                text = preset.name,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun AddPresetCard(
    onClick: () -> Unit
) {
    OutlinedCard(
        modifier = Modifier
            .width(100.dp)
            .height(72.dp)
            .clickable(onClick = onClick),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = "Add Preset",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "追加",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ConversationItem(
    conversation: ConversationEntity,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = conversation.presetEmoji ?: "🟢",
                fontSize = 20.sp,
                modifier = Modifier.padding(end = 12.dp)
            )
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = conversation.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = formatDate(conversation.updatedAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

private fun formatDate(timestamp: Long): String {
    val dateFormat = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())
    return dateFormat.format(Date(timestamp))
}
