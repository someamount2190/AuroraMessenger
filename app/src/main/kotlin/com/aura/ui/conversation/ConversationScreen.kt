package com.aura.ui.conversation

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
import android.media.MediaMetadataRetriever
import android.view.Surface
import android.view.TextureView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import com.aura.db.PairState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import com.aura.transport.rtc.RtcState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.aura.media.ByteArrayMediaDataSource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import com.aura.db.ContactDao
import com.aura.db.ContactEntity
import com.aura.db.MessageDao
import com.aura.db.MessageEntity
import android.media.MediaPlayer
import com.aura.disappearing.DisappearingMessages
import com.aura.media.MediaTransfer
import com.aura.media.VoiceRecorder
import com.aura.pairing.PairingCoordinator
import com.aura.reaction.Reactions
import com.aura.settings.DisappearingTimer
import com.aura.streak.Streaks
import com.aura.transport.MessageSender
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationScreen(
    contactId: String,
    onBack: () -> Unit,
    onStartCall: (String, Boolean) -> Unit = { _, _ -> },
    viewModel: ConversationViewModel = hiltViewModel()
) {
    val contact by viewModel.contact.collectAsState()
    val messages by viewModel.messages.collectAsState()
    val myVerifyCode by viewModel.myVerifyCode.collectAsState()
    val verifyError by viewModel.verifyError.collectAsState()
    val streak by viewModel.streak.collectAsState()
    val mediaProgress by viewModel.mediaProgress.collectAsState()
    val isRecording by viewModel.isRecording.collectAsState()
    val playingId by viewModel.playingId.collectAsState()
    val callActive by viewModel.callActive.collectAsState()
    val connection by viewModel.connection.collectAsState()
    // Mark the conversation read on open and whenever new messages land while it's visible.
    LaunchedEffect(messages) { if (messages.isNotEmpty()) viewModel.markRead() }
    // Leave the screen if the contact disappears (we deleted it, or the peer removed us).
    // Guarded by a "was loaded" latch so the momentary null on first composition (before
    // the contact flow emits) doesn't bounce us straight back out.
    var contactWasLoaded by remember { mutableStateOf(false) }
    LaunchedEffect(contact) {
        if (contact != null) contactWasLoaded = true
        else if (contactWasLoaded) onBack()
    }
    var draft by remember { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }
    var timerMenuOpen by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var viewerMessage by remember { mutableStateOf<MessageEntity?>(null) }
    var replyingTo by remember { mutableStateOf<MessageEntity?>(null) }
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val ctx = androidx.compose.ui.platform.LocalContext.current

    var pendingRecord by remember { mutableStateOf(false) }
    val audioPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted && pendingRecord) viewModel.startRecording(); pendingRecord = false }
    val tryRecord = {
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            ctx, android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) viewModel.startRecording()
        else { pendingRecord = true; audioPermLauncher.launch(android.Manifest.permission.RECORD_AUDIO) }
    }

    // Calls must hold mic (and camera, for video) BEFORE the call screen opens the
    // devices — otherwise the first call comes up with a dead/black camera because the
    // capture starts before the permission grant. So request here, then start.
    fun isGranted(p: String) = androidx.core.content.ContextCompat.checkSelfPermission(ctx, p) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED
    var pendingCallVideo by remember { mutableStateOf<Boolean?>(null) }
    val callPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val video = pendingCallVideo ?: return@rememberLauncherForActivityResult
        pendingCallVideo = null
        val audioOk = result[android.Manifest.permission.RECORD_AUDIO] ?: isGranted(android.Manifest.permission.RECORD_AUDIO)
        if (audioOk != true) return@rememberLauncherForActivityResult   // a call can't run without the mic
        val camOk = result[android.Manifest.permission.CAMERA] ?: isGranted(android.Manifest.permission.CAMERA)
        onStartCall(viewModel.contactId, video && camOk == true)        // camera denied → fall back to a voice call
    }
    val launchCall: (Boolean) -> Unit = { video ->
        val perms = if (video) arrayOf(android.Manifest.permission.CAMERA, android.Manifest.permission.RECORD_AUDIO)
                    else arrayOf(android.Manifest.permission.RECORD_AUDIO)
        if (perms.all { isGranted(it) }) onStartCall(viewModel.contactId, video)
        else { pendingCallVideo = video; callPermLauncher.launch(perms) }
    }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { viewModel.sendMedia(it, "image") } }
    val videoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { viewModel.sendMedia(it, "video") } }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    val currentTimer = DisappearingTimer.fromKey(contact?.disappearingTimer)

  Box(modifier = Modifier.fillMaxSize()) {
    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onBackground,
        topBar = {
            TopAppBar(
                colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
                    containerColor = androidx.compose.ui.graphics.Color.Transparent
                ),
                title = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                contact?.displayName ?: "…",
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            if (streak >= Streaks.MIN_DISPLAY_DAYS) {
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "🔥 $streak",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1
                                )
                            }
                        }
                        // One compact status line under the name. Disappearing timer and P2P
                        // reachability share it (each on its own when present), each kept to a
                        // single line so the app bar can't balloon. The full failure guidance
                        // lives in a banner above the messages, not here.
                        if (currentTimer != DisappearingTimer.OFF) {
                            Text(
                                "⏱ Disappearing: ${currentTimer.label}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                        }
                        val connText = when (connection) {
                            RtcState.CONNECTED -> "🔒 Connected"
                            RtcState.SIGNALING, RtcState.GATHERING, RtcState.CHECKING -> "Connecting…"
                            RtcState.FAILED -> "Not connected directly"
                            RtcState.IDLE -> null
                        }
                        if (connText != null) {
                            Text(
                                connText,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                color = when (connection) {
                                    RtcState.CONNECTED -> MaterialTheme.colorScheme.primary
                                    RtcState.FAILED -> MaterialTheme.colorScheme.error
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { launchCall(false) }, enabled = !callActive) {
                        Icon(Icons.Default.Call, contentDescription = "Voice call")
                    }
                    IconButton(onClick = { launchCall(true) }, enabled = !callActive) {
                        Icon(Icons.Default.Videocam, contentDescription = "Video call")
                    }
                    IconButton(onClick = { timerMenuOpen = true }) {
                        Icon(Icons.Default.Timer, contentDescription = "Disappearing timer")
                    }
                    DropdownMenu(expanded = timerMenuOpen, onDismissRequest = { timerMenuOpen = false }) {
                        DisappearingTimer.entries.forEach { timer ->
                            DropdownMenuItem(
                                text = { Text(if (timer == currentTimer) "● ${timer.label}" else timer.label) },
                                onClick = { timerMenuOpen = false; viewModel.setTimer(timer) }
                            )
                        }
                    }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Rename contact") },
                            onClick = { menuOpen = false; showRename = true }
                        )
                        DropdownMenuItem(
                            text = { Text("Delete contact", color = MaterialTheme.colorScheme.error) },
                            onClick = { menuOpen = false; showDelete = true }
                        )
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
                .imePadding()
        ) {
          if (contact?.pairState == PairState.VERIFY) {
            // Communication is blocked until both sides verify each other's code.
            VerifyPanel(
                myCode = myVerifyCode,
                awaitingPartner = contact?.iVerified == true,
                error = verifyError,
                onSubmit = viewModel::submitVerify
            )
          } else {
            // Honest, full-width guidance when a direct path couldn't be established
            // (the app-bar status is kept to a terse one-liner). Shown only on failure.
            if (connection == RtcState.FAILED) {
                Text(
                    "Couldn't connect directly — you may both be on mobile data. Messages will go through once one of you is on Wi-Fi.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(messages, key = { it.id }) { message ->
                        if (message.type == "call") {
                            CallLogRow(message)
                        } else {
                            MessageBubble(
                                message = message,
                                progress = mediaProgress[message.id],
                                isPlaying = playingId == message.id,
                                loadMedia = { path -> viewModel.mediaBytes(path) },
                                onOpenViewer = { viewerMessage = message },
                                onReact = { emoji -> viewModel.react(message, emoji) },
                                onReply = { replyingTo = message },
                                onTogglePlay = { viewModel.togglePlay(message) }
                            )
                        }
                    }
                }
                // Empty new chat: gentle hint to rename (replaces the old name prompt).
                if (messages.isEmpty() && contact?.nicknameSet == false) {
                    Text(
                        "You're connected. Open the menu in the top corner and choose Rename contact to give them a name.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(32.dp)
                    )
                }
            }

            // Reply quote above the composer.
            replyingTo?.let { rt ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Replying to ${if (rt.fromMe) "yourself" else (contact?.displayName ?: "them")}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            viewModel.previewOf(rt),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                    IconButton(onClick = { replyingTo = null }) {
                        Icon(Icons.Default.Close, contentDescription = "Cancel reply")
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isRecording) {
                    IconButton(onClick = { viewModel.cancelRecording() }) {
                        Icon(Icons.Default.Close, contentDescription = "Cancel recording",
                            tint = MaterialTheme.colorScheme.error)
                    }
                    Text(
                        "● Recording voice message…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { viewModel.stopAndSendRecording() }) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send voice",
                            tint = MaterialTheme.colorScheme.primary)
                    }
                } else {
                    var attachMenuOpen by remember { mutableStateOf(false) }
                    IconButton(onClick = { attachMenuOpen = true }) {
                        Icon(Icons.Default.AddPhotoAlternate, contentDescription = "Attach media")
                    }
                    DropdownMenu(expanded = attachMenuOpen, onDismissRequest = { attachMenuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Photo") },
                            onClick = { attachMenuOpen = false; imagePicker.launch("image/*") }
                        )
                        DropdownMenuItem(
                            text = { Text("Video") },
                            onClick = { attachMenuOpen = false; videoPicker.launch("video/*") }
                        )
                    }
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Message") },
                        maxLines = 4,
                        shape = RoundedCornerShape(24.dp)
                    )
                    if (draft.isBlank()) {
                        IconButton(onClick = { tryRecord() }) {
                            Icon(Icons.Default.Mic, contentDescription = "Record voice",
                                tint = MaterialTheme.colorScheme.primary)
                        }
                    } else {
                        IconButton(
                            onClick = {
                                viewModel.send(draft, replyingTo)
                                draft = ""
                                replyingTo = null
                            }
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Send",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
          }
        }
    }

    if (showRename) {
        var nameDraft by remember { mutableStateOf(contact?.displayName.orEmpty()) }
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text("Rename contact") },
            text = {
                OutlinedTextField(
                    value = nameDraft,
                    onValueChange = { nameDraft = it },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.rename(nameDraft)
                    showRename = false
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showRename = false }) { Text("Cancel") }
            }
        )
    }

    if (showDelete) {
        val name = contact?.displayName ?: "this contact"
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("Delete contact?") },
            text = {
                Text(
                    "This permanently removes $name along with the entire conversation, " +
                    "shared media, and encryption keys on this device. It cannot be undone."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDelete = false
                    viewModel.deleteContact()
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDelete = false }) { Text("Cancel") }
            }
        )
    }

    // (No mandatory naming dialog — the empty chat shows a rename hint instead.)

    // Fullscreen image viewer overlay — inside the Box so it stacks over the
    // Scaffold and stays within the Activity's FLAG_SECURE window.
    viewerMessage?.let { msg ->
        FullscreenImageViewer(
            message = msg,
            loadMedia = { path -> viewModel.mediaBytes(path) },
            onClose = { viewerMessage = null },
            onSave = {
                viewModel.saveToGallery(msg) { ok ->
                    scope.launch {
                        snackbarHostState.showSnackbar(if (ok) "Saved to gallery" else "Could not save")
                    }
                }
            },
            onShare = { viewModel.shareMedia(msg) }
        )
    }
  }
}

private val REACTION_EMOJI = listOf("❤️", "😂", "👍", "😮", "😢", "🔥")

/**
 * Mutual-code verification gate shown for a VERIFY-state contact. Each phone displays
 * its own code and you type the one shown on the other phone; once both have entered
 * the other's, the chat unlocks. The codes are derived locally from the shared key, so
 * a man-in-the-middle (different key) produces non-matching codes.
 */
@Composable
private fun VerifyPanel(
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

/** A centered call-log entry in the conversation (missed calls in red). */
@Composable
private fun CallLogRow(message: MessageEntity) {
    val missed = message.callStatus == "missed"
    val container = if (missed) MaterialTheme.colorScheme.errorContainer
                    else MaterialTheme.colorScheme.surfaceVariant
    val content = if (missed) MaterialTheme.colorScheme.onErrorContainer
                  else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.Center
    ) {
        androidx.compose.material3.Surface(shape = RoundedCornerShape(16.dp), color = container) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Filled.Call,
                    contentDescription = null,
                    tint = content,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(message.body, style = MaterialTheme.typography.bodySmall, color = content)
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    message: MessageEntity,
    progress: Float?,
    isPlaying: Boolean,
    loadMedia: suspend (String) -> ByteArray?,
    onOpenViewer: () -> Unit,
    onReact: (String) -> Unit,
    onReply: () -> Unit,
    onTogglePlay: () -> Unit
) {
    val onColor = if (message.fromMe) MaterialTheme.colorScheme.onPrimary
                  else MaterialTheme.colorScheme.onSurface
    var menuOpen by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = if (message.fromMe) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Column(horizontalAlignment = if (message.fromMe) Alignment.End else Alignment.Start) {
            Column(
                modifier = Modifier
                    .widthIn(max = 280.dp)
                    .clip(
                        RoundedCornerShape(
                            topStart = 16.dp, topEnd = 16.dp,
                            bottomStart = if (message.fromMe) 16.dp else 4.dp,
                            bottomEnd = if (message.fromMe) 4.dp else 16.dp
                        )
                    )
                    .background(
                        if (message.fromMe) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
                    .combinedClickable(
                        onClick = { if (message.type == "image" || message.type == "video") onOpenViewer() },
                        onLongClick = { menuOpen = true }
                    )
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                // Quoted reply block.
                message.replyPreview?.let { quote ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(onColor.copy(alpha = 0.12f))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(quote, style = MaterialTheme.typography.bodySmall,
                            color = onColor.copy(alpha = 0.8f), maxLines = 1)
                    }
                    Spacer(Modifier.height(4.dp))
                }

                when (message.type) {
                    "image", "video" -> MediaContent(message, progress, loadMedia, onOpenViewer, onColor)
                    "audio"          -> AudioContent(message, isPlaying, onTogglePlay, onColor)
                    else             -> Text(message.body, color = onColor,
                        style = MaterialTheme.typography.bodyMedium)
                }

                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.align(Alignment.End)) {
                    message.expiresAtMs?.let { expiry ->
                        val remaining = expiry - System.currentTimeMillis()
                        if (remaining > 0) {
                            Text("⏱ ${formatRemaining(remaining)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = onColor.copy(alpha = 0.7f))
                            Spacer(Modifier.width(6.dp))
                        }
                    }
                    if (message.fromMe) {
                        Text(
                            text = when (message.status) {
                                "delivered" -> "✓✓"; "sent" -> "✓"; else -> "…"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = onColor.copy(alpha = 0.7f)
                        )
                    }
                }
            }

            // Reaction chips below the bubble.
            val reactions = listOfNotNull(message.theirReaction, message.myReaction)
            if (reactions.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(reactions.joinToString(" "), style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        // Long-press menu: reaction row + Reply.
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                REACTION_EMOJI.forEach { emoji ->
                    Text(
                        emoji,
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .clip(CircleShape)
                            .clickable { menuOpen = false; onReact(emoji) }
                            .padding(4.dp)
                    )
                }
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Reply") },
                onClick = { menuOpen = false; onReply() }
            )
        }
    }
}

@Composable
private fun AudioContent(
    message: MessageEntity,
    isPlaying: Boolean,
    onTogglePlay: () -> Unit,
    onColor: androidx.compose.ui.graphics.Color
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
        IconButton(onClick = onTogglePlay, modifier = Modifier.size(36.dp)) {
            Icon(
                if (isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                contentDescription = if (isPlaying) "Stop" else "Play",
                tint = onColor
            )
        }
        Spacer(Modifier.width(6.dp))
        Icon(Icons.Default.GraphicEq, contentDescription = null, tint = onColor.copy(alpha = 0.7f))
        Spacer(Modifier.width(8.dp))
        Text(
            formatDuration(message.durationMs ?: 0L),
            style = MaterialTheme.typography.bodyMedium,
            color = onColor
        )
    }
}

private fun formatDuration(ms: Long): String {
    val totalSec = (ms / 1000).toInt()
    return "%d:%02d".format(totalSec / 60, totalSec % 60)
}

@Composable
private fun MediaContent(
    message: MessageEntity,
    progress: Float?,
    loadMedia: suspend (String) -> ByteArray?,
    onOpenViewer: () -> Unit,
    onColor: androidx.compose.ui.graphics.Color
) {
    val path = message.mediaPath
    val bytes by produceState<ByteArray?>(initialValue = null, path) {
        value = if (path != null) loadMedia(path) else null
    }

    Box(contentAlignment = Alignment.Center) {
        // Images decode directly; videos render their first frame as the thumbnail.
        // Both are off the main thread (video frame extraction in particular is heavy).
        val bmpState by produceState<Bitmap?>(initialValue = null, bytes, message.type) {
            val b = bytes
            value = when {
                b == null -> null
                message.type == "video" -> withContext(Dispatchers.IO) { videoFrame(b) }
                else -> withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(b, 0, b.size) }
            }
        }
        val bmp = bmpState   // local capture so the null check smart-casts
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = message.body,
                modifier = Modifier
                    .size(200.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onOpenViewer() },
                contentScale = ContentScale.Crop
            )
        } else {
            Box(
                modifier = Modifier.size(200.dp).clip(RoundedCornerShape(8.dp))
                    .background(onColor.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                if (progress != null && progress < 1f) {
                    CircularProgressIndicator(progress = { progress }, color = onColor)
                } else {
                    Text(message.body, color = onColor)
                }
            }
        }
        if (message.type == "video" && bmp != null) {
            Icon(
                Icons.Default.PlayCircle,
                contentDescription = "Video",
                tint = onColor,
                modifier = Modifier.size(48.dp)
            )
        }
    }
    Text(
        "Tap to view",
        style = MaterialTheme.typography.labelSmall,
        color = onColor.copy(alpha = 0.6f)
    )
}

/**
 * Fullscreen image viewer rendered as an in-window overlay (NOT a Dialog) so it
 * stays inside the Activity's FLAG_SECURE window — a separate Dialog window
 * would not inherit the screenshot block. Basic buttons: close, save, share.
 */
@Composable
private fun FullscreenImageViewer(
    message: MessageEntity,
    loadMedia: suspend (String) -> ByteArray?,
    onClose: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit
) {
    val path = message.mediaPath
    val bytes by produceState<ByteArray?>(initialValue = null, path) {
        value = if (path != null) loadMedia(path) else null
    }
    val isVideo = message.type == "video"
    val bmp = remember(bytes, isVideo) {
        if (!isVideo) bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } else null
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Color.Black)
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null
            ) { onClose() }
    ) {
        val b = bytes
        when {
            isVideo && b != null -> VideoPlayer(
                bytes = b,
                modifier = Modifier.align(Alignment.Center).fillMaxWidth()
            )
            bmp != null -> Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = message.body,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
            else -> CircularProgressIndicator(
                color = androidx.compose.ui.graphics.Color.White,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        // Top bar: close (left), share + save (right).
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "Close",
                    tint = androidx.compose.ui.graphics.Color.White)
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onShare) {
                Icon(Icons.Default.Share, contentDescription = "Share",
                    tint = androidx.compose.ui.graphics.Color.White)
            }
            IconButton(onClick = onSave) {
                Icon(Icons.Default.Save, contentDescription = "Save to gallery",
                    tint = androidx.compose.ui.graphics.Color.White)
            }
        }
    }
}

/** First decodable frame of a video blob, for the conversation thumbnail. */
private fun videoFrame(bytes: ByteArray): Bitmap? {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(ByteArrayMediaDataSource(bytes))
        retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
    } catch (e: Exception) {
        null
    } finally {
        runCatching { retriever.release() }
    }
}

/**
 * In-window video player for the fullscreen viewer. Plays the decrypted blob straight
 * from memory (no plaintext temp file) onto a [TextureView], which stays inside the
 * Activity's FLAG_SECURE window so frames are screenshot-blocked. Auto-plays once the
 * surface is ready; tapping the video toggles play/pause (and consumes the tap so it
 * doesn't dismiss the viewer). The [MediaPlayer] is released when the viewer closes.
 */
@Composable
private fun VideoPlayer(bytes: ByteArray, modifier: Modifier = Modifier) {
    val player = remember { MediaPlayer() }
    var surface by remember { mutableStateOf<Surface?>(null) }
    var prepared by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    var ratio by remember { mutableStateOf(0f) }     // 0 = unknown until prepared
    var durationMs by remember { mutableStateOf(0) }
    var positionMs by remember { mutableStateOf(0) }
    var scrubbing by remember { mutableStateOf(false) }

    fun togglePlay() {
        if (player.isPlaying) {
            player.pause(); playing = false
        } else {
            // Replay from the start if we're sitting at the end.
            if (durationMs > 0 && positionMs >= durationMs) { player.seekTo(0); positionMs = 0 }
            runCatching { player.start(); playing = true }
        }
    }

    DisposableEffect(bytes) {
        runCatching {
            player.reset()
            player.setDataSource(ByteArrayMediaDataSource(bytes))
            player.isLooping = false
            player.setOnPreparedListener { mp ->
                ratio = if (mp.videoHeight > 0) mp.videoWidth.toFloat() / mp.videoHeight else 16f / 9f
                durationMs = mp.duration.coerceAtLeast(0)
                prepared = true
            }
            player.setOnCompletionListener {
                playing = false
                positionMs = durationMs
            }
            player.prepareAsync()
        }
        onDispose { runCatching { player.release() } }
    }

    // Attach the surface and auto-start once both the player and surface are ready.
    LaunchedEffect(surface, prepared) {
        val s = surface
        if (prepared && s != null) {
            runCatching {
                player.setSurface(s)
                if (!player.isPlaying && !playing) { player.start(); playing = true }
            }
        }
    }

    // Advance the seek bar while playing (paused/scrubbing freezes it).
    LaunchedEffect(playing, scrubbing) {
        while (playing && !scrubbing) {
            runCatching { positionMs = player.currentPosition }
            delay(200)
        }
    }

    Box(modifier, contentAlignment = Alignment.Center) {
        AndroidView(
            modifier = if (ratio > 0f) Modifier.fillMaxWidth().aspectRatio(ratio)
                       else Modifier.fillMaxWidth(),
            factory = { ctx ->
                TextureView(ctx).apply {
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                            surface = Surface(st)
                        }
                        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                            surface = null
                            return true
                        }
                        override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                    }
                }
            }
        )

        // Tapping the frame toggles play/pause and consumes the tap so it doesn't
        // dismiss the viewer; the bottom bar gives explicit transport controls.
        Box(
            Modifier
                .matchParentSize()
                .clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null
                ) { togglePlay() }
        )

        // Big centre play affordance while paused.
        if (!playing) {
            Icon(
                Icons.Default.PlayCircle,
                contentDescription = "Play",
                tint = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.9f),
                modifier = Modifier.size(72.dp)
            )
        }

        // Transport bar: play/pause + scrubbable seek slider + elapsed / total time.
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.45f))
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { togglePlay() }) {
                Icon(
                    if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (playing) "Pause" else "Play",
                    tint = androidx.compose.ui.graphics.Color.White
                )
            }
            Text(
                formatDuration(positionMs.toLong()),
                style = MaterialTheme.typography.labelSmall,
                color = androidx.compose.ui.graphics.Color.White
            )
            Slider(
                value = positionMs.coerceIn(0, durationMs).toFloat(),
                onValueChange = { v -> scrubbing = true; positionMs = v.toInt() },
                onValueChangeFinished = {
                    runCatching { player.seekTo(positionMs) }
                    scrubbing = false
                },
                valueRange = 0f..durationMs.coerceAtLeast(1).toFloat(),
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
            )
            Text(
                formatDuration(durationMs.toLong()),
                style = MaterialTheme.typography.labelSmall,
                color = androidx.compose.ui.graphics.Color.White
            )
        }
    }
}

private fun formatRemaining(ms: Long): String {
    val totalSec = ms / 1000
    return when {
        totalSec < 60      -> "${totalSec}s"
        totalSec < 3600    -> "${totalSec / 60}m"
        totalSec < 86400   -> "${totalSec / 3600}h"
        else               -> "${totalSec / 86400}d"
    }
}
