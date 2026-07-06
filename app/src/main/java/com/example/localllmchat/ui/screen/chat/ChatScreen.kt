package com.example.localllmchat.ui.screen.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import android.graphics.Typeface
import android.widget.TextView
import androidx.compose.ui.viewinterop.AndroidView
import io.noties.markwon.Markwon
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.html.HtmlPlugin
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Summarize
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.localllmchat.data.local.MessageEntity
import com.example.localllmchat.data.remote.ToolCall
import com.example.localllmchat.data.repository.ChatRepository
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.example.localllmchat.data.repository.SettingsRepository
import com.example.localllmchat.util.FileProcessor
import com.example.localllmchat.util.ProcessedAttachment
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    conversationId: Long,
    chatRepository: ChatRepository,
    settingsRepository: SettingsRepository,
    onBack: () -> Unit,
    viewModel: ChatViewModel = viewModel(
        factory = ChatViewModel.Factory(conversationId, chatRepository, settingsRepository)
    )
) {
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            val cursor = context.contentResolver.query(it, null, null, null, null)
            val fileName = cursor?.use { c ->
                val nameIndex = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                c.moveToFirst()
                c.getString(nameIndex)
            } ?: "unknown"
            val mimeType = context.contentResolver.getType(it) ?: "application/octet-stream"

            try {
                val processed = FileProcessor.processFile(
                    context.contentResolver, it, fileName, mimeType,
                    maxTextBytes = uiState.maxAttachmentTextKb * 1024
                )
                viewModel.addAttachment(processed)
            } catch (e: Exception) {
                scope.launch {
                    snackbarHostState.showSnackbar(
                        e.message ?: "ファイルの読み込みに失敗しました"
                    )
                }
            }
        }
    }

    var selectedMessageForInfo by remember { mutableStateOf<MessageEntity?>(null) }
    var showOriginalTextFor by remember { mutableStateOf<MessageEntity?>(null) }

    selectedMessageForInfo?.let { message ->
        TokenInfoDialog(
            message = message,
            onDismiss = { selectedMessageForInfo = null }
        )
    }

    showOriginalTextFor?.let { message ->
        OriginalTextDialog(
            content = message.content,
            onDismiss = { showOriginalTextFor = null }
        )
    }

    uiState.askUserDialog?.let { dialogState ->
        AskUserQuestionDialog(
            state = dialogState,
            onAnswer = viewModel::answerAskUserQuestion,
            onCancel = viewModel::cancelAskUserQuestion
        )
    }

    uiState.capacityWarning?.let { warning ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissCapacityWarning() },
            title = { Text("コンテキスト容量の警告") },
            text = {
                Text(
                    "送信するとコンテキスト上限を超える可能性があります" +
                        "（予測 約${warning.projected} / 上限 ${warning.capacity} トークン）。\n" +
                        "メッセージの要約・除外で履歴を減らしてから送信してください。"
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissCapacityWarning() }) {
                    Text("OK")
                }
            }
        )
    }

    if (uiState.showSummarizeDialog) {
        SummarizeConfigDialog(
            initialConfig = uiState.summarizeInitialConfig,
            preview = uiState.summarizePreview,
            isLoading = uiState.isSummarizePreviewLoading,
            errorMessage = uiState.summarizePreviewError,
            onGenerate = { config -> viewModel.generateSummarizePreview(config) },
            onConfirm = { viewModel.confirmSummarizePreview() },
            onDismiss = { viewModel.closeSummarizeDialog() }
        )
    }

    // メッセージ高さキャッシュ（AndroidView再計測によるジャンプ防止）
    val messageHeightCache = remember { mutableStateMapOf<Long, Int>() }

    // 自動スクロールフラグ（デフォルトON）
    var shouldAutoScroll by remember { mutableStateOf(true) }

    // ストリーミング開始時にON、終了時にOFF（出力後は自由スクロール可能）
    LaunchedEffect(uiState.isLoading) {
        if (uiState.isLoading) {
            shouldAutoScroll = true
        } else {
            shouldAutoScroll = false
        }
    }

    // ユーザーの物理ドラッグのみ検知（プログラムによるscrollToItemでは反応しない）
    val isDragged by listState.interactionSource.collectIsDraggedAsState()

    // 底判定（derivedStateOf で常に最新値を保持）
    val isAtBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()
            !listState.canScrollForward || (lastVisible != null &&
                lastVisible.index >= info.totalItemsCount - 1 &&
                lastVisible.offset + lastVisible.size <= info.viewportEndOffset + 300)
        }
    }

    // 判定統合: ストリーミング中のみ底復帰ON、ドラッグ中に底から離れたらOFF
    LaunchedEffect(isAtBottom, isDragged) {
        if (isAtBottom && uiState.isLoading) {
            // ストリーミング中のみ: 底に戻ったら自動追従を復帰
            shouldAutoScroll = true
        } else if (isDragged && !isAtBottom) {
            shouldAutoScroll = false
        }
    }

    // スクロール実行: shouldAutoScroll が true の間、レイアウト変化を追跡して底に吸着
    LaunchedEffect(shouldAutoScroll) {
        if (shouldAutoScroll) {
            snapshotFlow {
                val info = listState.layoutInfo
                info.totalItemsCount to (info.visibleItemsInfo.lastOrNull()?.size ?: 0)
            }.collect { (count, _) ->
                if (count > 0) {
                    listState.scrollToItem(count - 1, Int.MAX_VALUE)
                }
            }
        }
    }

    LaunchedEffect(uiState.error) {
        uiState.error?.let { error ->
            snackbarHostState.showSnackbar(error)
            viewModel.clearError()
        }
    }

    LaunchedEffect(uiState.attachmentWarning) {
        uiState.attachmentWarning?.let { warning ->
            snackbarHostState.showSnackbar(warning)
        }
    }

    LaunchedEffect(uiState.summarizeToast) {
        uiState.summarizeToast?.let { toast ->
            snackbarHostState.showSnackbar(toast)
            viewModel.clearSummarizeToast()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = run {
                                val conv = uiState.conversation
                                if (conv?.presetEmoji != null && conv.presetName != null) {
                                    "${conv.presetEmoji} ${conv.presetName}"
                                } else {
                                    conv?.title ?: "Chat"
                                }
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (uiState.conversationTotalTokens > 0) {
                            SessionTokenCounter(
                                currentTokens = uiState.sessionTokenCount,
                                maxTokens = uiState.measuredKvCapacity ?: uiState.contextWindowSize,
                                totalTokens = uiState.conversationTotalTokens,
                                isFullPrefill = uiState.isFullPrefill
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .imePadding()
        ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(
                    items = uiState.messages,
                    key = { it.id }
                ) { message ->
                    val density = LocalDensity.current
                    val cachedHeight = messageHeightCache[message.id]
                    val siblingInfo = uiState.siblingInfoMap[message.id]
                    val isEditing = uiState.editingMessageId == message.id
                    val isLastAssistant = message.role == "assistant" &&
                        message.toolCallsJson == null &&
                        message.id == uiState.messages.lastOrNull { it.role == "assistant" && it.toolCallsJson == null }?.id
                    val editingIdx = uiState.editingMessageId?.let { editId ->
                        uiState.messages.indexOfFirst { it.id == editId }
                    }
                    val messageIdx = uiState.messages.indexOf(message)
                    val isAfterEditing = editingIdx != null && messageIdx > editingIdx

                    // Inline editing mode
                    if (isEditing) {
                        EditableMessageBubble(
                            editText = uiState.editingText,
                            onTextChange = viewModel::updateEditText,
                            onSubmit = viewModel::submitEdit,
                            onCancel = viewModel::cancelEdit
                        )
                    } else if (message.role == "assistant" && message.toolCallsJson != null) {
                        // 推論（<think>）だけでなく平文＋tool_call 混在の応答も本文を表示する
                        if (message.content.isNotBlank()) {
                            MessageBubble(
                                message = message,
                                isSummarizing = false,
                                isTranslating = false,
                                onCopyMessage = { content -> copyToClipboard(context, content) },
                                onInfoClick = { selectedMessageForInfo = message },
                                onSummarize = {},
                                onShowOriginal = {},
                                onExcludeToggle = {},
                                onTranslate = {},
                                siblingInfo = siblingInfo,
                                onSwitchBranch = viewModel::switchBranch,
                                modifier = Modifier.alpha(if (isAfterEditing) 0.35f else 1f)
                            )
                        }
                        ToolCallBubble(
                            message = message,
                            allMessages = uiState.messages,
                            onExcludeToggle = viewModel::toggleExcludeToolGroup
                        )
                    } else if (message.role == "tool") {
                        ToolCallBubble(
                            message = message,
                            allMessages = uiState.messages,
                            onExcludeToggle = viewModel::toggleExcludeToolGroup
                        )
                    } else {
                    MessageBubble(
                        message = message,
                        isSummarizing = false,
                        isTranslating = uiState.translatingMessageId == message.id,
                        onCopyMessage = { content ->
                            copyToClipboard(context, content)
                        },
                        onInfoClick = {
                            selectedMessageForInfo = message
                        },
                        onSummarize = {
                            if (message.isSummarized) {
                                viewModel.openResummarizeDialog(message.id, message.content, message.summarizeConfigJson)
                            } else {
                                viewModel.openSummarizeDialog(message.id, message.content)
                            }
                        },
                        onShowOriginal = {
                            showOriginalTextFor = message
                        },
                        onExcludeToggle = {
                            viewModel.toggleExcludeMessage(message.id, message.isExcluded)
                        },
                        onTranslate = {
                            viewModel.translateMessage(message.id, message.content)
                        },
                        siblingInfo = siblingInfo,
                        onSwitchBranch = viewModel::switchBranch,
                        onEdit = if (message.role == "user" && !uiState.isLoading) {
                            { viewModel.editMessage(message.id) }
                        } else null,
                        onRegenerate = if (isLastAssistant && !uiState.isLoading) {
                            { viewModel.regenerateResponse() }
                        } else null,
                        modifier = ((if (cachedHeight != null) {
                            Modifier.defaultMinSize(minHeight = with(density) { cachedHeight.toDp() })
                        } else {
                            Modifier
                        }).onGloballyPositioned { coordinates ->
                            val height = coordinates.size.height
                            if (height > 0 && messageHeightCache[message.id] != height) {
                                messageHeightCache[message.id] = height
                            }
                        }).alpha(if (isAfterEditing) 0.35f else 1f)
                    )
                    }
                }

                if (uiState.isLoading) {
                    // Tool execution indicator (separate item, shown above streaming)
                    val toolStatus = uiState.toolExecutionStatus
                    if (toolStatus != null) {
                        item(key = "tool_status") {
                            ToolExecutionIndicator(status = toolStatus)
                        }
                    }

                    item(key = "streaming") {
                        if (uiState.streamingContent.isNotEmpty() || uiState.streamingReasoning.isNotEmpty()) {
                            val displayContent = buildString {
                                if (uiState.streamingReasoning.isNotEmpty()) {
                                    append("<think>")
                                    append(uiState.streamingReasoning)
                                    append("</think>")
                                }
                                append(uiState.streamingContent)
                            }
                            StreamingMessageBubble(content = displayContent)
                        } else if (toolStatus == null) {
                            // Loading spinner only when not in tool execution phase
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                    }
                }
            }

            val isEditMode = uiState.editingMessageId != null
            ChatInput(
                text = uiState.inputText,
                onTextChange = viewModel::updateInputText,
                onSend = viewModel::sendMessage,
                onAttachClick = {
                    filePickerLauncher.launch(
                        arrayOf(
                            "text/*",
                            "application/json",
                            "application/octet-stream",
                            "application/md",
                            "image/jpeg",
                            "image/png"
                        )
                    )
                },
                isLoading = uiState.isLoading,
                textAttachment = uiState.textAttachment,
                imageAttachments = uiState.imageAttachments,
                onRemoveTextAttachment = viewModel::removeTextAttachment,
                onRemoveImageAttachment = viewModel::removeImageAttachment,
                availableTools = uiState.availableTools,
                toolDescriptions = uiState.toolDescriptions,
                disabledTools = uiState.disabledTools,
                modelSupportsTools = uiState.modelSupportsTools,
                onToggleTool = viewModel::toggleTool,
                modifier = Modifier
                    .padding(16.dp)
                    .alpha(if (isEditMode) 0.4f else 1f)
                    .then(if (isEditMode) Modifier.clickable(enabled = false) {} else Modifier)
            )
        }

        }
    }
}

@Composable
private fun StreamingMessageBubble(content: String) {
    val parsedParts = remember(content) { parseMessageContent(content) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 340.dp)
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomStart = 4.dp,
                        bottomEnd = 16.dp
                    )
                )
        ) {
            val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
            val thinkTextColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()

            Column(modifier = Modifier.padding(12.dp)) {
                parsedParts.forEach { part ->
                    when (part) {
                        is MessagePart.RegularText -> {
                            SelectableText(
                                text = part.text,
                                textColor = textColor,
                                textSizeSp = 14f
                            )
                        }
                        is MessagePart.ThinkText -> {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                    .border(
                                        width = 1.dp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                    .padding(8.dp)
                            ) {
                                Column {
                                    Text(
                                        text = "Thinking...",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    SelectableText(
                                        text = part.text,
                                        textColor = thinkTextColor,
                                        textSizeSp = 13f,
                                        isItalic = true
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText("message", text)
    clipboard.setPrimaryClip(clip)
    Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
}

// Data class to represent parsed message parts
private sealed class MessagePart {
    data class RegularText(val text: String) : MessagePart()
    data class ThinkText(val text: String) : MessagePart()
}

// Parse message content to separate think blocks from regular text
private fun parseMessageContent(content: String): List<MessagePart> {
    val parts = mutableListOf<MessagePart>()

    // First, handle orphan </think> at the start (no opening tag)
    val orphanClosePattern = Regex("^([\\s\\S]*?)</think>", RegexOption.IGNORE_CASE)
    var workingContent = content

    val orphanMatch = orphanClosePattern.find(workingContent)
    if (orphanMatch != null) {
        // Check if there's no <think> before this </think>
        val beforeClose = orphanMatch.groupValues[1]
        if (!beforeClose.contains("<think>", ignoreCase = true)) {
            val thinkContent = beforeClose.trim()
            if (thinkContent.isNotEmpty()) {
                parts.add(MessagePart.ThinkText(thinkContent))
            }
            workingContent = workingContent.substring(orphanMatch.range.last + 1)
        }
    }

    // Now handle complete <think>...</think> blocks
    val completePattern = Regex("<think>([\\s\\S]*?)</think>", RegexOption.IGNORE_CASE)

    var lastEnd = 0
    completePattern.findAll(workingContent).forEach { matchResult ->
        // Add regular text before this think block
        if (matchResult.range.first > lastEnd) {
            val regularText = workingContent.substring(lastEnd, matchResult.range.first).trim()
            if (regularText.isNotEmpty()) {
                parts.add(MessagePart.RegularText(regularText))
            }
        }
        // Add think block content
        val thinkContent = matchResult.groupValues[1].trim()
        if (thinkContent.isNotEmpty()) {
            parts.add(MessagePart.ThinkText(thinkContent))
        }
        lastEnd = matchResult.range.last + 1
    }

    // Add remaining regular text after last think block
    if (lastEnd < workingContent.length) {
        val remainingText = workingContent.substring(lastEnd).trim()
        if (remainingText.isNotEmpty()) {
            parts.add(MessagePart.RegularText(remainingText))
        }
    }

    // If no parts found, treat entire content as regular text
    if (parts.isEmpty() && content.isNotBlank()) {
        parts.add(MessagePart.RegularText(content.trim()))
    }

    return parts
}

// Extract plain text for copying (remove think tags but keep content)
private fun getPlainTextForCopy(content: String): String {
    return content
        .replace(Regex("<think>", RegexOption.IGNORE_CASE), "")
        .replace(Regex("</think>", RegexOption.IGNORE_CASE), "")
        .trim()
}

// Native TextView with Markwon for Markdown rendering + full text selection menu (includes Google Translate)
@Composable
private fun SelectableText(
    text: String,
    textColor: Int,
    textSizeSp: Float = 14f,
    isItalic: Boolean = false,
    modifier: Modifier = Modifier
) {
    AndroidView(
        factory = { context ->
            val markwon = Markwon.builder(context)
                .usePlugin(TablePlugin.create(context))
                .usePlugin(HtmlPlugin.create())
                .usePlugin(StrikethroughPlugin.create())
                .build()

            TextView(context).apply {
                setTextIsSelectable(true)
                setTextColor(textColor)
                textSize = textSizeSp
                if (isItalic) {
                    setTypeface(typeface, Typeface.ITALIC)
                }
                tag = markwon
            }
        },
        update = { textView ->
            val markwon = textView.tag as Markwon
            markwon.setMarkdown(textView, text)
        },
        modifier = modifier.fillMaxWidth()
    )
}

@Composable
private fun MessageBubble(
    message: MessageEntity,
    isSummarizing: Boolean,
    isTranslating: Boolean,
    onCopyMessage: (String) -> Unit,
    onInfoClick: () -> Unit,
    onSummarize: () -> Unit,
    onShowOriginal: () -> Unit,
    onExcludeToggle: () -> Unit,
    onTranslate: () -> Unit,
    siblingInfo: ChatRepository.SiblingInfo? = null,
    onSwitchBranch: (Long) -> Unit = {},
    onEdit: (() -> Unit)? = null,
    onRegenerate: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val isUser = message.role == "user"
    val displayContent = if (message.isSummarized && message.summaryText != null) {
        message.summaryText
    } else {
        message.content
    }
    val parsedParts = remember(displayContent) { parseMessageContent(displayContent) }
    val plainTextContent = remember(displayContent) { getPlainTextForCopy(displayContent) }
    val hasUsageData = message.promptTokens != null ||
            message.completionTokens != null ||
            message.totalTokens != null

    val bgColor = if (message.isSummarized) {
        if (isUser) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    } else {
        if (isUser) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (message.isExcluded) 0.45f else 1f),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 340.dp)
                .background(
                    color = bgColor,
                    shape = RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomStart = if (isUser) 16.dp else 4.dp,
                        bottomEnd = if (isUser) 4.dp else 16.dp
                    )
                )
        ) {
            val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
            val thinkTextColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()

            Column(
                modifier = Modifier.padding(12.dp)
            ) {
                parsedParts.forEach { part ->
                    when (part) {
                        is MessagePart.RegularText -> {
                            SelectableText(
                                text = part.text,
                                textColor = textColor,
                                textSizeSp = 14f
                            )
                        }
                        is MessagePart.ThinkText -> {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                    .border(
                                        width = 1.dp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                    .padding(8.dp)
                            ) {
                                Column {
                                    Text(
                                        text = "Thinking...",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    SelectableText(
                                        text = part.text,
                                        textColor = thinkTextColor,
                                        textSizeSp = 13f,
                                        isItalic = true
                                    )
                                }
                            }
                        }
                    }
                }

                // Inline translation display
                if (message.translatedText != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "\u2500\u2500 \u7ffb\u8a33 \u2500\u2500",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    SelectableText(
                        text = message.translatedText,
                        textColor = textColor,
                        textSizeSp = 14f
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Branch navigation
                    if (siblingInfo != null && siblingInfo.totalSiblings > 1) {
                        BranchNavigator(
                            siblingInfo = siblingInfo,
                            onPrevious = {
                                val prevIdx = siblingInfo.currentIndex - 1
                                if (prevIdx >= 0) onSwitchBranch(siblingInfo.siblingIds[prevIdx])
                            },
                            onNext = {
                                val nextIdx = siblingInfo.currentIndex + 1
                                if (nextIdx < siblingInfo.totalSiblings) onSwitchBranch(siblingInfo.siblingIds[nextIdx])
                            }
                        )
                    }
                    // Edit button (user messages only)
                    if (onEdit != null) {
                        IconButton(
                            onClick = onEdit,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Edit,
                                contentDescription = "Edit message",
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            )
                        }
                    }
                    // Regenerate button (last assistant message only)
                    if (onRegenerate != null) {
                        IconButton(
                            onClick = onRegenerate,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Refresh,
                                contentDescription = "Regenerate response",
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            )
                        }
                    }
                    // Translate button
                    if (message.translatedText == null) {
                        if (isTranslating) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            IconButton(
                                onClick = onTranslate,
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Translate,
                                    contentDescription = "Translate to Japanese",
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                )
                            }
                        }
                    }
                    IconButton(
                        onClick = onExcludeToggle,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.VisibilityOff,
                            contentDescription = if (message.isExcluded) "Include in context" else "Exclude from context",
                            modifier = Modifier.size(18.dp),
                            tint = if (message.isExcluded) {
                                MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                            } else {
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            }
                        )
                    }
                    // Summarize button (opens dialog for both new and re-summarize)
                    IconButton(
                        onClick = onSummarize,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Summarize,
                            contentDescription = if (message.isSummarized) "Re-summarize" else "Summarize",
                            modifier = Modifier.size(18.dp),
                            tint = if (message.isSummarized) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                            } else {
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            }
                        )
                    }
                    // Show Original button (only for summarized messages)
                    if (message.isSummarized) {
                        IconButton(
                            onClick = onShowOriginal,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Description,
                                contentDescription = "Show original",
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            )
                        }
                    }
                    CopyButton(onClick = { onCopyMessage(plainTextContent) })
                    if (hasUsageData) {
                        InfoButton(onClick = onInfoClick)
                    }
                }
            }
        }
    }
}

@Composable
private fun BranchNavigator(
    siblingInfo: ChatRepository.SiblingInfo,
    onPrevious: () -> Unit,
    onNext: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onPrevious,
            enabled = siblingInfo.currentIndex > 0,
            modifier = Modifier.size(28.dp)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = "Previous branch",
                modifier = Modifier.size(18.dp),
                tint = if (siblingInfo.currentIndex > 0)
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                else
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
            )
        }
        Text(
            text = "${siblingInfo.currentIndex + 1}/${siblingInfo.totalSiblings}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        IconButton(
            onClick = onNext,
            enabled = siblingInfo.currentIndex < siblingInfo.totalSiblings - 1,
            modifier = Modifier.size(28.dp)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "Next branch",
                modifier = Modifier.size(18.dp),
                tint = if (siblingInfo.currentIndex < siblingInfo.totalSiblings - 1)
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                else
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
            )
        }
    }
}

@Composable
private fun EditableMessageBubble(
    editText: String,
    onTextChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onCancel: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 340.dp)
                .background(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(16.dp)
                )
        ) {
            Column(
                modifier = Modifier.padding(12.dp)
            ) {
                OutlinedTextField(
                    value = editText,
                    onValueChange = onTextChange,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodyMedium,
                    minLines = 2,
                    maxLines = 10
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onCancel) {
                        Text("キャンセル")
                    }
                    TextButton(
                        onClick = onSubmit,
                        enabled = editText.isNotBlank()
                    ) {
                        Text("保存")
                    }
                }
            }
        }
    }
}

@Composable
private fun CopyButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    IconButton(
        onClick = onClick,
        modifier = modifier.size(32.dp)
    ) {
        Icon(
            imageVector = Icons.Outlined.ContentCopy,
            contentDescription = "Copy message",
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
        )
    }
}

@Composable
private fun InfoButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    IconButton(
        onClick = onClick,
        modifier = modifier.size(32.dp)
    ) {
        Icon(
            imageVector = Icons.Outlined.Info,
            contentDescription = "Token info",
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
        )
    }
}

@Composable
private fun OriginalTextDialog(
    content: String,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Original Text") },
        text = {
            val scrollState = rememberScrollState()
            val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
            ) {
                SelectableText(
                    text = content,
                    textColor = textColor,
                    textSizeSp = 13f
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@Composable
private fun ChatInput(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onAttachClick: () -> Unit,
    isLoading: Boolean,
    textAttachment: ProcessedAttachment.TextAttachment?,
    imageAttachments: List<ProcessedAttachment.ImageAttachment>,
    onRemoveTextAttachment: () -> Unit,
    onRemoveImageAttachment: (Int) -> Unit,
    availableTools: List<String> = emptyList(),
    toolDescriptions: Map<String, String> = emptyMap(),
    disabledTools: Set<String> = emptySet(),
    modelSupportsTools: Boolean = false,
    onToggleTool: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val canSend = (text.isNotBlank() || textAttachment != null || imageAttachments.isNotEmpty()) && !isLoading
    var showToolMenu by remember { mutableStateOf(false) }
    val showToolButton = modelSupportsTools && availableTools.isNotEmpty()
    val allToolsEnabled = availableTools.none { it in disabledTools }
    val someToolsEnabled = availableTools.any { it !in disabledTools }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        if (textAttachment != null || imageAttachments.isNotEmpty()) {
            AttachmentPreviewBar(
                textAttachment = textAttachment,
                imageAttachments = imageAttachments,
                onRemoveTextAttachment = onRemoveTextAttachment,
                onRemoveImageAttachment = onRemoveImageAttachment,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onAttachClick,
                enabled = !isLoading
            ) {
                Icon(
                    imageVector = Icons.Default.AttachFile,
                    contentDescription = "Attach file",
                    tint = if (!isLoading)
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    else
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                )
            }

            if (showToolButton) {
                Box {
                    IconButton(
                        onClick = { showToolMenu = true },
                        enabled = !isLoading
                    ) {
                        Icon(
                            imageVector = Icons.Default.Build,
                            contentDescription = "Tools",
                            tint = if (!isLoading && someToolsEnabled)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        )
                    }
                    DropdownMenu(
                        expanded = showToolMenu,
                        onDismissRequest = { showToolMenu = false }
                    ) {
                        availableTools.forEach { toolName ->
                            val enabled = toolName !in disabledTools
                            // description はモデル向けに長文化したため、表示は先頭の1文のみ
                            val label = (toolDescriptions[toolName] ?: toolName).substringBefore(". ")
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = { onToggleTool(toolName) },
                                leadingIcon = {
                                    Checkbox(
                                        checked = enabled,
                                        onCheckedChange = { onToggleTool(toolName) }
                                    )
                                }
                            )
                        }
                    }
                }
            }

            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Type a message...") },
                maxLines = 6,
                enabled = !isLoading
            )

            IconButton(
                onClick = onSend,
                enabled = canSend
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send",
                    tint = if (canSend)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                )
            }
        }
    }
}

@Composable
private fun ToolExecutionIndicator(status: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .background(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(12.dp)
                )
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }
    }
}

@Composable
private fun ToolCallBubble(
    message: MessageEntity,
    allMessages: List<MessageEntity>,
    onExcludeToggle: (messageIds: List<Long>, currentlyExcluded: Boolean) -> Unit = { _, _ -> }
) {
    // For assistant messages with toolCallsJson: show tool call info
    // For tool messages: show tool result
    // Both are collapsible
    var expanded by remember { mutableStateOf(false) }

    if (message.role == "assistant" && message.toolCallsJson != null) {
        // Parse tool calls to get tool names
        val toolNames = remember(message.toolCallsJson) {
            try {
                val toolCalls: List<ToolCall> = Gson().fromJson(
                    message.toolCallsJson,
                    object : TypeToken<List<ToolCall>>() {}.type
                )
                toolCalls.mapNotNull { it.function?.name }
            } catch (_: Exception) {
                emptyList()
            }
        }
        val summary = toolNames.joinToString(", ") { it }

        // Find corresponding tool result messages
        val toolResults = remember(message.id, allMessages) {
            val msgIndex = allMessages.indexOfFirst { it.id == message.id }
            if (msgIndex < 0) emptyList()
            else allMessages.drop(msgIndex + 1).takeWhile { it.role == "tool" }
        }

        // Collect all related message IDs for group exclude
        val groupIds = remember(message.id, toolResults) {
            listOf(message.id) + toolResults.map { it.id }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp)
                .alpha(if (message.isExcluded) 0.45f else 1f),
            horizontalArrangement = Arrangement.Start
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 340.dp)
                    .background(
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(12.dp)
                    )
                    .clickable { expanded = !expanded }
                    .padding(10.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = if (expanded) "▼" else "▶",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Text(
                        text = "$summary を実行",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        imageVector = Icons.Outlined.VisibilityOff,
                        contentDescription = "Exclude tool messages",
                        modifier = Modifier
                            .size(18.dp)
                            .clickable { onExcludeToggle(groupIds, message.isExcluded) },
                        tint = if (message.isExcluded)
                            MaterialTheme.colorScheme.error
                        else
                            MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.5f)
                    )
                }

                AnimatedVisibility(visible = expanded) {
                    Column(
                        modifier = Modifier.padding(top = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        // Show tool call details
                        val toolCalls: List<ToolCall> = remember(message.toolCallsJson) {
                            try {
                                Gson().fromJson(
                                    message.toolCallsJson,
                                    object : TypeToken<List<ToolCall>>() {}.type
                                )
                            } catch (_: Exception) {
                                emptyList()
                            }
                        }
                        toolCalls.forEach { tc ->
                            Text(
                                text = "Tool: ${tc.function?.name ?: "unknown"}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            if (tc.function?.arguments?.isNotBlank() == true) {
                                Text(
                                    text = "Args: ${tc.function.arguments}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                                )
                            }
                            // Find matching result
                            val result = toolResults.firstOrNull { it.toolCallId == tc.id }
                            if (result != null) {
                                Text(
                                    text = "Result: ${result.content}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    // role == "tool" messages are displayed as part of the assistant tool_calls bubble above,
    // so we render nothing standalone for them
}

@Composable
private fun AskUserQuestionDialog(
    state: AskUserDialogState,
    onAnswer: (String) -> Unit,
    onCancel: () -> Unit
) {
    var customText by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onCancel,
        title = {
            Text("質問")
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = state.question,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                state.options.forEach { option ->
                    OutlinedButton(
                        onClick = { onAnswer("User selected: $option") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    ) {
                        Text(option)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = customText,
                    onValueChange = { customText = it },
                    label = { Text("自由記述") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = false,
                    maxLines = 3
                )

                TextButton(
                    onClick = {
                        if (customText.isNotBlank()) {
                            onAnswer("User's custom answer: ${customText.trim()}")
                        }
                    },
                    enabled = customText.isNotBlank(),
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("送信")
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text("キャンセル")
            }
        }
    )
}
