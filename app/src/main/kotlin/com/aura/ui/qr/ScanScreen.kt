package com.aura.ui.qr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aura.pairing.PairingCoordinator
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class ScanViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val pairingManager: PairingCoordinator
) : ViewModel() {

    sealed interface State {
        data object Idle : State
        data object Working : State
        data class Error(val message: String) : State
        /** The pairing request was sent; the one-time code dialog now drives the rest. */
        data object RequestSent : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    fun onQrContent(content: String) {
        _state.value = State.Working
        viewModelScope.launch {
            _state.value = pairingManager.pairFromQr(content).fold(
                onSuccess = { State.RequestSent },
                onFailure = { State.Error(it.message ?: "Pairing failed") }
            )
        }
    }

    fun onGalleryImage(uri: Uri) {
        _state.value = State.Working
        viewModelScope.launch {
            val content = withContext(Dispatchers.Default) { decodeQrFromUri(uri) }
            if (content == null) {
                _state.value = State.Error("No QR code found in that image")
            } else {
                onQrContent(content)
            }
        }
    }

    fun reset() { _state.value = State.Idle }

    private fun decodeQrFromUri(uri: Uri): String? {
        return try {
            val bitmap = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it)
            } ?: return null
            decodeQrFromBitmap(bitmap)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Robustly decode a (possibly dense, version-40) Aurora QR from a still image.
     * The host code carries the full hybrid KEM key + rendezvous addresses, so it
     * is a large code — decoding needs every-angle attempts: multiple scales,
     * both binarizers, and the PURE_BARCODE hint for a cleanly-rendered code.
     */
    private fun decodeQrFromBitmap(bitmap: Bitmap): String? {
        // Candidate renderings: original, upscaled (more px/module), downscaled (huge photos).
        val candidates = buildList {
            add(bitmap)
            if (bitmap.width in 1..1399) {
                val w = 1600
                add(Bitmap.createScaledBitmap(bitmap, w, (w.toLong() * bitmap.height / bitmap.width).toInt(), true))
            }
            if (bitmap.width > 2200) {
                val w = 1600
                add(Bitmap.createScaledBitmap(bitmap, w, (w.toLong() * bitmap.height / bitmap.width).toInt(), true))
            }
        }
        val hintSets = listOf(
            mapOf(
                DecodeHintType.TRY_HARDER to true,
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)
            ),
            mapOf(
                DecodeHintType.TRY_HARDER to true,
                DecodeHintType.PURE_BARCODE to true,   // cleanly-rendered code, no surrounding scene
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)
            )
        )
        for (bmp in candidates) {
            val pixels = IntArray(bmp.width * bmp.height)
            bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            val source = RGBLuminanceSource(bmp.width, bmp.height, pixels)
            val binarizers = listOf(HybridBinarizer(source), GlobalHistogramBinarizer(source))
            for (binarizer in binarizers) {
                for (hints in hintSets) {
                    try {
                        return QRCodeReader().decode(BinaryBitmap(binarizer), hints).text
                    } catch (e: Exception) {
                        // Try the next binarizer / hint / scale combination.
                    }
                }
            }
        }
        return null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanScreen(
    onBack: () -> Unit,
    onShowMyCode: () -> Unit = {},
    viewModel: ScanViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    val cameraLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { viewModel.onQrContent(it) }
    }
    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { viewModel.onGalleryImage(it) } }

    LaunchedEffect(state) {
        when (val s = state) {
            is ScanViewModel.State.RequestSent -> {
                viewModel.reset()
                onBack()   // the global "share this code" dialog takes over from here
            }
            is ScanViewModel.State.Error -> {
                snackbarHostState.showSnackbar(s.message)
                viewModel.reset()
            }
            else -> Unit
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add contact") },
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
            Text(
                "Scan your friend's Aurora code",
                style = MaterialTheme.typography.titleLarge
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Point the camera at their code, or pick a saved code image from your gallery.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(32.dp))

            if (state is ScanViewModel.State.Working) {
                CircularProgressIndicator()
                Spacer(Modifier.height(12.dp))
                Text(
                    "Deriving shared secret…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Button(
                    onClick = {
                        cameraLauncher.launch(
                            ScanOptions()
                                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                .setPrompt("Scan an Aurora code")
                                .setBeepEnabled(false)
                                .setOrientationLocked(true)
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.CameraAlt, contentDescription = null)
                    Text("  Scan with camera")
                }
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = { galleryLauncher.launch("image/*") },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.PhotoLibrary, contentDescription = null)
                    Text("  Pick from gallery")
                }

                Spacer(Modifier.height(24.dp))
                HorizontalDivider()
                Spacer(Modifier.height(16.dp))
                Text(
                    "…or show your code",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Let a friend scan your code to add you. No setup needed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = onShowMyCode, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.QrCode2, contentDescription = null)
                    Text("  Show my code")
                }
            }
        }
    }
}
