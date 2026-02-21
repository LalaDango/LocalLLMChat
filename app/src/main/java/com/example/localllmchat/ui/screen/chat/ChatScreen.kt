package com.example.localllmchat.ui.screen.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Summarize
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import com.example.localllmchat.data.repository.ChatRepository
import com.example.localllmchat.data.repository.SettingsRepository
import com.example.localllmchat.ui.theme.AssistantMessageBg
import com.example.localllmchat.ui.theme.ThinkBlockBg
import com.example.localllmchat.ui.theme.ThinkBlockText
import com.example.localllmchat.ui.theme.SummarizedAssistantMessageBg
import com.example.localllmchat.ui.theme.SummarizedUserMessageBg
import com.example.localllmchat.ui.theme.UserMessageBg
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
                    context.contentResolver, it, fileName, mimeType
                )
                viewModel.setAttachment(processed)
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
                            text = uiState.conversation?.title ?: "Chat",
                            maxLines = 1
                        )
                        if (uiState.conversationTotalTokens > 0) {
                            SessionTokenCounter(
                                currentTokens = uiState.sessionTokenCount,
                                maxTokens = uiState.contextWindowSize,
                                totalTokens = uiState.conversationTotalTokens
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

                    MessageBubble(
                        message = message,
                        isSummarizing = uiState.summarizingMessageId == message.id,
                        isTranslating = uiState.translatingMessageId == message.id,
                        onCopyMessage = { content ->
                            copyToClipboard(context, content)
                        },
                        onInfoClick = {
                            selectedMessageForInfo = message
                        },
                        onSummarize = {
                            viewModel.summarizeMessage(message.id, message.content)
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
                        modifier = (if (cachedHeight != null) {
                            Modifier.defaultMinSize(minHeight = with(density) { cachedHeight.toDp() })
                        } else {
                            Modifier
                        }).onGloballyPositioned { coordinates ->
                            val height = coordinates.size.height
                            if (height > 0 && messageHeightCache[message.id] != height) {
                                messageHeightCache[message.id] = height
                            }
                        }
                    )
                }

                if (uiState.isLoading) {
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
                        } else {
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
                attachment = uiState.attachment,
                onRemoveAttachment = viewModel::clearAttachment,
                modifier = Modifier.padding(16.dp)
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
                    color = AssistantMessageBg,
                    shape = RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomStart = 4.dp,
                        bottomEnd = 16.dp
                    )
                )
        ) {
            val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
            val thinkTextColor = ThinkBlockText.toArgb()

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
                                    .background(ThinkBlockBg)
                                    .border(
                                        width = 1.dp,
                                        color = ThinkBlockText.copy(alpha = 0.3f),
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                    .padding(8.dp)
                            ) {
                                Column {
                                    Text(
                                        text = "Thinking...",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = ThinkBlockText.copy(alpha = 0.7f)
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
        if (isUser) SummarizedUserMessageBg else SummarizedAssistantMessageBg
    } else {
        if (isUser) UserMessageBg else AssistantMessageBg
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
            val thinkTextColor = ThinkBlockText.toArgb()

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
                                    .background(ThinkBlockBg)
                                    .border(
                                        width = 1.dp,
                                        color = ThinkBlockText.copy(alpha = 0.3f),
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                    .padding(8.dp)
                            ) {
                                Column {
                                    Text(
                                        text = "Thinking...",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = ThinkBlockText.copy(alpha = 0.7f)
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
                    if (message.isSummarized) {
                        // Show "Show Original" button for summarized messages
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
                    } else {
                        // Show "Summarize" button for non-summarized messages
                        if (isSummarizing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            IconButton(
                                onClick = onSummarize,
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Summarize,
                                    contentDescription = "Summarize",
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                )
                            }
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
    attachment: ProcessedAttachment?,
    onRemoveAttachment: () -> Unit,
    modifier: Modifier = Modifier
) {
    val canSend = (text.isNotBlank() || attachment != null) && !isLoading

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        if (attachment != null) {
            AttachmentPreview(
                attachment = attachment,
                onRemove = onRemoveAttachment,
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
