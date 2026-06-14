package com.aura.ui.qr

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.provider.MediaStore
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aura.crypto.toHex
import com.aura.identity.IdentityManager
import com.aura.network.AddressDiscovery
import com.aura.pairing.QrPayload
import com.aura.server.RendezvousServerController
import com.aura.settings.AuroraSettings
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class MyCodeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    savedStateHandle: SavedStateHandle,
    private val identityManager: IdentityManager,
    private val serverController: RendezvousServerController,
    private val addressDiscovery: AddressDiscovery,
    private val settings: AuroraSettings
) : ViewModel() {

    /** "Host & show my code" mode: become a rendezvous beacon and embed the address. */
    private val host: Boolean = savedStateHandle.get<String>("host") == "true"

    // NOTE: declared before init{} — Kotlin initialises properties in source order,
    // so a val referenced by init must appear above it or it reads its default (0).
    private val rendezvousPort = 8080

    data class State(
        val nodeIdHex: String? = null,
        val qrBitmap: Bitmap? = null,
        val savedMessage: String? = null,
        val hosting: Boolean = false,
        val reach: AddressDiscovery.Reach? = null,
        val lanIp: String? = null,
        val publicIp: String? = null,
        val portMapped: Boolean = false,
        val discovering: Boolean = false
    )

    private val _state = MutableStateFlow(State(hosting = host, discovering = host))
    val state: StateFlow<State> = _state

    init {
        viewModelScope.launch {
            val identity = identityManager.getOrCreate()
            val nodeIdHex = identity.nodeId.toHex()

            val candidates: List<String> = if (host) {
                // Become the beacon: start the in-app server and poll our own
                // signal queue (localhost) so the scanner's pairing message reaches us.
                settings.setServerMode(true)
                serverController.start()
                settings.setServerAddress("http://127.0.0.1:$rendezvousPort")
                val addrs = addressDiscovery.discover(rendezvousPort)
                _state.value = _state.value.copy(
                    reach = addrs.reach, lanIp = addrs.lanIp,
                    publicIp = addrs.publicIp, portMapped = addrs.portMapped
                )
                addrs.candidates
            } else emptyList()

            val payload = QrPayload.encode(identity, candidates)
            val bitmap  = withContext(Dispatchers.Default) { renderQr(payload) }
            _state.value = _state.value.copy(
                nodeIdHex = nodeIdHex, qrBitmap = bitmap, discovering = false
            )
        }
    }

    fun saveToGallery() {
        val bitmap = _state.value.qrBitmap ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "aurora-code-${System.currentTimeMillis()}.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Aurora")
            }
            val uri = context.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
            )
            val message = if (uri != null) {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                "Saved to gallery (Pictures/Aurora)"
            } else {
                "Could not save image"
            }
            _state.value = _state.value.copy(savedMessage = message)
        }
    }

    fun consumeSavedMessage() {
        _state.value = _state.value.copy(savedMessage = null)
    }

    private fun renderQr(content: String, sizePx: Int = 1280): Bitmap {
        // A wider quiet zone (margin 4 = spec minimum) + higher resolution make the
        // dense host code (full Kyber key + addresses) reliably decodable from a
        // saved image. ARGB_8888 keeps the black/white edges crisp.
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L,
            EncodeHintType.CHARACTER_SET to "ISO-8859-1",
            EncodeHintType.MARGIN to 4
        )
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val w = matrix.width
        val h = matrix.height
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (x in 0 until w) {
            for (y in 0 until h) {
                bitmap.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }
        }
        return bitmap
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyCodeScreen(
    onBack: () -> Unit,
    viewModel: MyCodeViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    state.savedMessage?.let { message ->
        scope.launch {
            snackbarHostState.showSnackbar(message)
            viewModel.consumeSavedMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (state.hosting) "Host a connection" else "My code") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            val qr = state.qrBitmap
            if (qr == null) {
                CircularProgressIndicator()
            } else {
                Image(
                    bitmap = qr.asImageBitmap(),
                    contentDescription = "My pairing QR code",
                    modifier = Modifier
                        .size(300.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White)
                        .padding(8.dp)
                )
                Spacer(Modifier.height(24.dp))
                Text(
                    if (state.hosting)
                        "Your friend scans this once and connects — no setup on their side.\nThe code carries only your public keys and this device's address."
                    else
                        "Have a friend scan this code to pair.\nIt contains only your public keys — never private ones.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                if (state.hosting) {
                    Spacer(Modifier.height(12.dp))
                    ReachBanner(state)
                }
                Spacer(Modifier.height(8.dp))
                state.nodeIdHex?.let {
                    Text(
                        "ID: ${it.take(16)}…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(24.dp))
                Button(onClick = viewModel::saveToGallery, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Save, contentDescription = null)
                    Spacer(Modifier.height(0.dp))
                    Text("  Save to gallery")
                }
            }
        }
    }
}

@Composable
private fun ReachBanner(state: MyCodeViewModel.State) {
    val (text, color) = when (state.reach) {
        AddressDiscovery.Reach.INTERNET -> Pair(
            "🌐 Reachable over the internet (port mapped via your router). " +
                "Anyone can scan and connect — even off your Wi-Fi.",
            MaterialTheme.colorScheme.primary
        )
        AddressDiscovery.Reach.LAN_ONLY -> Pair(
            "📶 Reachable on this Wi-Fi only (${state.lanIp ?: "local"}). " +
                "Your router didn't allow automatic internet access" +
                (if (state.publicIp != null) " — for off-network use, forward port 8080 or use a VPS beacon." else "."),
            MaterialTheme.colorScheme.secondary
        )
        else -> Pair(
            "⚠ No network address found. Connect to Wi-Fi to host.",
            MaterialTheme.colorScheme.error
        )
    }
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = color,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth()
    )
}
