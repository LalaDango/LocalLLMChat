package com.example.localllmchat.ui.screen.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.localllmchat.data.local.MessageEntity
import java.text.NumberFormat
import java.util.Locale

@Composable
fun TokenInfoDialog(
    message: MessageEntity,
    onDismiss: () -> Unit
) {
    val numberFormat = NumberFormat.getNumberInstance(Locale.getDefault())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Token Information") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                message.promptTokens?.let {
                    TokenInfoRow("Prompt tokens", numberFormat.format(it))
                }
                message.completionTokens?.let {
                    TokenInfoRow("Completion tokens", numberFormat.format(it))
                }
                message.totalTokens?.let {
                    TokenInfoRow("Total tokens", numberFormat.format(it))
                }
                message.decodingSpeedTps?.let {
                    TokenInfoRow("Decoding speed", String.format(Locale.getDefault(), "%.1f t/s", it))
                }
                message.prefillSpeedTps?.let {
                    TokenInfoRow("Prefill speed", String.format(Locale.getDefault(), "%.1f t/s", it))
                }
                message.activeKvTokens?.let {
                    TokenInfoRow("Active KV", numberFormat.format(it))
                }
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
private fun TokenInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}
