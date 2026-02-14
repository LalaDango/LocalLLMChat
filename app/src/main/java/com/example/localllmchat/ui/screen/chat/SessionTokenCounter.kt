package com.example.localllmchat.ui.screen.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.localllmchat.ui.theme.TokenWarningRed
import com.example.localllmchat.ui.theme.TokenWarningYellow
import java.text.NumberFormat
import java.util.Locale

@Composable
fun SessionTokenCounter(
    currentTokens: Int,
    maxTokens: Int,
    totalTokens: Int, // cumulative tokens for the entire chat
    modifier: Modifier = Modifier
) {
    val numberFormat = NumberFormat.getNumberInstance(Locale.getDefault())
    val percentage = if (maxTokens > 0) currentTokens.toFloat() / maxTokens else 0f

    val turnColor = when {
        percentage > 0.95f -> TokenWarningRed
        percentage > 0.80f -> TokenWarningYellow
        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
    }

    Column(modifier = modifier) {
        // Primary: cumulative token count for this chat
        Text(
            text = "Context: ${numberFormat.format(totalTokens)} / ${numberFormat.format(maxTokens)} tokens",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )
        // Secondary: last turn usage vs context window
        if (currentTokens > 0) {
            Text(
                text = "This turn: ${numberFormat.format(currentTokens)} / ${numberFormat.format(maxTokens)}",
                style = MaterialTheme.typography.labelSmall,
                color = turnColor
            )
        }
    }
}
