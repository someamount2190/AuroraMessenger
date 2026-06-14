package com.aura.ui.shadowmesh

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.aura.settings.AuroraSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class ShadowMeshViewModel @Inject constructor(
    private val settings: AuroraSettings
) : ViewModel() {
    val enabled: StateFlow<Boolean> = settings.shadowMeshEnabled
    fun setEnabled(value: Boolean) = settings.setShadowMeshEnabled(value)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShadowMeshScreen(
    onBack: () -> Unit,
    viewModel: ShadowMeshViewModel = hiltViewModel()
) {
    val enabled by viewModel.enabled.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ShadowMesh") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    if (enabled) {
                        Text(
                            "✓ You're part of ShadowMesh.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(
                            onClick = { viewModel.setEnabled(false); onBack() },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Leave ShadowMesh") }
                    } else {
                        Button(
                            onClick = { viewModel.setEnabled(true); onBack() },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Join ShadowMesh") }
                        Spacer(Modifier.height(4.dp))
                        TextButton(
                            onClick = onBack,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Not now") }
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 12.dp)
        ) {
            Text(
                "Strengthen the Network",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(16.dp))

            Para("When you use Aurora, your messages travel directly to the person you are communicating with. There is no server in the middle and no company monitoring what you say.")
            Para("Reaching that person still requires one small step. Aurora runs a minimal server that helps two devices locate each other across the internet. It never sees your messages. It only confirms that two devices need to connect, and then it steps aside. We designed it to have the smallest possible footprint.")
            Para("ShadowMesh is how we reduce reliance on even that.")

            Heading("What ShadowMesh Is")
            Para("ShadowMesh is a distributed network formed by the devices of people who choose to support Aurora. When you opt in, your device helps relay encrypted fragments of other people's messages toward their destination. You never see that content, and neither can anyone else, because every message is encrypted with keys held only by its sender and recipient. Your device simply forwards sealed data to the next point in the path.")
            Para("As more people opt in, the network becomes stronger and more resilient. Each additional device makes it harder to monitor, harder to block, and harder to disrupt.")

            Heading("What It Costs You")
            Para("A small amount of background battery and data. The feature is designed to remain lightweight, and in normal use you should not notice any impact on your device.")

            Heading("What You Get")
            Para("The assurance that you are contributing to a meaningful effort. Every private conversation that remains private because of this network is protected in part by your participation.")

            Heading("What We Promise")
            Para("We will never use your device's contribution to identify you, profile you, or generate commercial benefit from your participation. Aurora is free, and ShadowMesh is free. Neither will ever cost you anything beyond your decision to take part.")
            Para("You can disable this feature at any time. No explanation is required, and no data is retained.")

            Spacer(Modifier.height(20.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))
            Text(
                "By opting in, you are not only using a private messenger. You are helping to build the infrastructure that makes private communication possible for everyone.",
                style = MaterialTheme.typography.bodyMedium,
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Heading(text: String) {
    Spacer(Modifier.height(20.dp))
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary
    )
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun Para(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(bottom = 10.dp)
    )
}
