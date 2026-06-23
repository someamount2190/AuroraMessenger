package com.aura.ui.conversation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Mutual-code verification gate shown for a VERIFY-state contact. Each phone displays
 * its own code and you type the one shown on the other phone; once both have entered
 * the other's, the chat unlocks. The codes are derived locally from the shared key, so
 * a man-in-the-middle (different key) produces non-matching codes.
 */
@Composable
internal fun VerifyPanel(
    myCode: String?,
    awaitingPartner: Boolean,
    error: Boolean,
    onSubmit: (String) -> Unit
) {
    var entry by remember { mutableStateOf("") }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Verify this contact", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "Read your code to them, and type the code shown on their phone. This confirms no one is in the middle.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(28.dp))
        Text(
            "Your code",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            myCode ?: "······",
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            letterSpacing = 6.sp
        )
        Spacer(Modifier.height(28.dp))
        if (awaitingPartner) {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text(
                "Code accepted. Waiting for them to enter yours…",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
        } else {
            OutlinedTextField(
                value = entry,
                onValueChange = { v -> if (v.length <= 6 && v.all(Char::isDigit)) entry = v },
                label = { Text("Their code") },
                singleLine = true,
                isError = error,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
            )
            if (error) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "That code didn't match. Try again.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Spacer(Modifier.height(16.dp))
            Button(onClick = { onSubmit(entry) }, enabled = entry.length == 6) { Text("Verify") }
        }
    }
}
