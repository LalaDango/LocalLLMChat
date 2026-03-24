package com.example.localllmchat.ui.screen.preset

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.localllmchat.data.repository.PresetRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PresetEditScreen(
    presetRepository: PresetRepository,
    presetId: Long?,
    onBack: () -> Unit,
    viewModel: PresetViewModel = viewModel(
        factory = PresetViewModel.Factory(presetRepository)
    )
) {
    val uiState by viewModel.editUiState.collectAsState()
    var showDeleteDialog by remember { mutableStateOf(false) }

    LaunchedEffect(presetId) {
        if (presetId != null && presetId > 0) {
            viewModel.loadPresetForEdit(presetId)
        }
    }

    val isValid = uiState.name.isNotBlank() && uiState.systemPrompt.isNotBlank()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(if (uiState.isEditing) "プリセットを編集" else "新しいプリセット")
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    if (uiState.isEditing) {
                        IconButton(onClick = { showDeleteDialog = true }) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "削除",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = uiState.emoji,
                    onValueChange = { viewModel.updateEmoji(it) },
                    label = { Text("絵文字") },
                    modifier = Modifier.width(80.dp),
                    singleLine = true,
                    placeholder = { Text("📝") }
                )
                Spacer(modifier = Modifier.width(12.dp))
                OutlinedTextField(
                    value = uiState.name,
                    onValueChange = { viewModel.updateName(it) },
                    label = { Text("名前") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text("例: メモ整形") }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = uiState.description,
                onValueChange = { viewModel.updateDescription(it) },
                label = { Text("説明（任意）") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("このプリセットの用途") }
            )

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = uiState.systemPrompt,
                onValueChange = { viewModel.updateSystemPrompt(it) },
                label = { Text("システムプロンプト") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 6,
                placeholder = { Text("このプリセットで使うシステムプロンプトを入力") }
            )

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = { viewModel.savePreset(onSaved = onBack) },
                modifier = Modifier.fillMaxWidth(),
                enabled = isValid && !uiState.isSaving
            ) {
                Text(if (uiState.isSaving) "保存中..." else "保存")
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("プリセットを削除") },
            text = { Text("「${uiState.name}」を削除しますか？\n既存のチャットには影響しません。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        viewModel.deletePreset(onDeleted = onBack)
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("削除")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("キャンセル")
                }
            }
        )
    }
}
