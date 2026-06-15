package com.aura.ui.conversation

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore as AndroidMediaStore
import com.aura.db.ContactDao
import com.aura.db.ContactEntity
import com.aura.db.MessageDao
import com.aura.db.MessageEntity
import android.media.MediaPlayer
import com.aura.disappearing.DisappearingManager
import com.aura.media.MediaTransfer
import com.aura.media.VoiceRecorder
import com.aura.pairing.PairingManager
import com.aura.reaction.ReactionManager
import com.aura.settings.DisappearingTimer
import com.aura.transport.MessageSender
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
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

@HiltViewModel
class ConversationViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @ApplicationContext private val context: Context,
    private val contactDao: ContactDao,
    private val messageDao: MessageDao,
    private val messageSender: MessageSender,
    private val mediaTransfer: MediaTransfer,
    private val disappearingManager: DisappearingManager,
    private val reactionManager: ReactionManager,
    private val voiceRecorder: VoiceRecorder,
    private val pairingManager: com.aura.pairing.PairingManager
) : ViewModel() {

    val contactId: String = savedStateHandle.get<String>("contactId").orEmpty()

    val contact: StateFlow<ContactEntity?> = contactDao.observeByNodeId(contactId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** This device's own verification code, shown on the verify screen (null otherwise). */
    val myVerifyCode: StateFlow<String?> = contact
        .map { c -> if (c?.pairState == PairState.VERIFY) pairingManager.myVerifyCode(contactId) else null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _verifyError = MutableStateFlow(false)
    val verifyError: StateFlow<Boolean> = _verifyError

    /** Submit the code read off the peer's phone. */
    fun submitVerify(code: String) {
        viewModelScope.launch {
            val ok = pairingManager.submitVerifyCode(contactId, code).getOrDefault(false)
            _verifyError.value = !ok
        }
    }

    val messages: StateFlow<List<MessageEntity>> = messageDao.observeConversation(contactId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Live conversation streak (consecutive active days), recomputed as messages change. */
    val streak: StateFlow<Int> = messages
        .map { msgs -> com.aura.streak.Streaks.compute(msgs.map { it.timestampMs }, System.currentTimeMillis()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val mediaProgress: StateFlow<Map<String, Float>> = mediaTransfer.progress

    fun sendMedia(uri: Uri, type: String) {
        viewModelScope.launch { mediaTransfer.sendMedia(contactId, uri, type) }
    }

    fun setTimer(timer: DisappearingTimer) {
        viewModelScope.launch { disappearingManager.setTimer(contactId, timer) }
    }

    /** Decrypt media for preview; cached by caller. */
    suspend fun mediaBytes(path: String): ByteArray? =
        mediaTransfer.decryptForPreview(contactId, path)

    /** Export a media item to the public gallery (explicit user action). */
    fun saveToGallery(message: MessageEntity, onResult: (Boolean) -> Unit) {
        val path = message.mediaPath ?: return onResult(false)
        viewModelScope.launch {
            val bytes = mediaTransfer.decryptForPreview(contactId, path)
            if (bytes == null) { onResult(false); return@launch }
            val ok = withContext(Dispatchers.IO) {
                val isVideo = message.type == "video"
                val collection = if (isVideo)
                    AndroidMediaStore.Video.Media.EXTERNAL_CONTENT_URI
                else AndroidMediaStore.Images.Media.EXTERNAL_CONTENT_URI
                val values = ContentValues().apply {
                    put(AndroidMediaStore.MediaColumns.DISPLAY_NAME, "aurora-${message.id}")
                    put(AndroidMediaStore.MediaColumns.MIME_TYPE, if (isVideo) "video/mp4" else "image/jpeg")
                    put(AndroidMediaStore.MediaColumns.RELATIVE_PATH,
                        if (isVideo) "Movies/Aurora" else "Pictures/Aurora")
                }
                val uri = context.contentResolver.insert(collection, values)
                if (uri != null) {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                    true
                } else false
            }
            onResult(ok)
        }
    }

    /** Decrypt a media item to a temp file and fire a system share sheet. */
    fun shareMedia(message: MessageEntity) {
        val path = message.mediaPath ?: return
        viewModelScope.launch {
            val bytes = mediaTransfer.decryptForPreview(contactId, path) ?: return@launch
            val isVideo = message.type == "video"
            val uri = withContext(Dispatchers.IO) {
                val dir = java.io.File(context.cacheDir, "shared").apply { mkdirs() }
                val file = java.io.File(dir, "aurora-${message.id}.${if (isVideo) "mp4" else "jpg"}")
                file.writeBytes(bytes)
                androidx.core.content.FileProvider.getUriForFile(
                    context, "${context.packageName}.fileprovider", file
                )
            }
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = if (isVideo) "video/mp4" else "image/jpeg"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(
                android.content.Intent.createChooser(intent, "Share").apply {
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }
    }

    fun send(body: String, replyTo: MessageEntity? = null) {
        if (body.isBlank()) return
        viewModelScope.launch {
            messageDao.insert(
                MessageEntity(
                    id               = UUID.randomUUID().toString(),
                    contactNodeIdHex = contactId,
                    fromMe           = true,
                    body             = body.trim(),
                    timestampMs      = System.currentTimeMillis(),
                    status           = "pending",
                    read             = true,
                    replyToId        = replyTo?.id,
                    replyPreview     = replyTo?.let { previewOf(it) }
                )
            )
            // Attempt immediate delivery; the sync loop retries if the peer is offline.
            messageSender.flushPending()
        }
    }

    /** A one-line quote for a replied-to message. */
    fun previewOf(m: MessageEntity): String = when (m.type) {
        "image" -> "📷 Photo"
        "video" -> "📹 Video"
        "audio" -> "🎤 Voice message"
        else    -> m.body
    }

    // ── Reactions ──────────────────────────────────────────────────────────

    fun react(message: MessageEntity, emoji: String) {
        // Toggle off if tapping the same reaction again.
        val next = if (message.myReaction == emoji) null else emoji
        viewModelScope.launch { reactionManager.react(contactId, message.id, next) }
    }

    // ── Voice messages ─────────────────────────────────────────────────────

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording

    fun startRecording() {
        // MediaRecorder prepare()/start() do file/codec I/O — never on the main thread.
        viewModelScope.launch(Dispatchers.IO) {
            val ok = voiceRecorder.start()
            _isRecording.value = ok
        }
    }

    fun stopAndSendRecording() {
        viewModelScope.launch(Dispatchers.IO) {
            val result = voiceRecorder.stop()
            _isRecording.value = false
            result?.let { (bytes, durationMs) -> mediaTransfer.sendAudio(contactId, bytes, durationMs) }
        }
    }

    fun cancelRecording() {
        viewModelScope.launch(Dispatchers.IO) {
            voiceRecorder.cancel()
            _isRecording.value = false
        }
    }

    // ── Voice playback ─────────────────────────────────────────────────────

    private var player: MediaPlayer? = null
    private val _playingId = MutableStateFlow<String?>(null)
    val playingId: StateFlow<String?> = _playingId

    fun togglePlay(message: MessageEntity) {
        val path = message.mediaPath ?: return
        if (_playingId.value == message.id) { stopPlayback(); return }
        viewModelScope.launch {
            val bytes = mediaTransfer.decryptForPreview(contactId, path) ?: return@launch
            withContext(Dispatchers.IO) {
                stopPlayback()
                val file = File(context.cacheDir, "play-${message.id}.m4a").apply { writeBytes(bytes) }
                player = MediaPlayer().apply {
                    setDataSource(file.absolutePath)
                    prepare()
                    start()
                    setOnCompletionListener {
                        _playingId.value = null
                        release()
                        player = null
                        file.delete()
                    }
                }
                _playingId.value = message.id
            }
        }
    }

    fun stopPlayback() {
        runCatching { player?.release() }
        player = null
        _playingId.value = null
    }

    override fun onCleared() {
        stopPlayback()
        if (_isRecording.value) voiceRecorder.cancel()
    }

    fun rename(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch { contactDao.rename(contactId, name.trim()) }
    }

    /**
     * Remove this contact on BOTH sides: signs and posts a `contactremove` to the
     * peer (so their copy is wiped and they're told they lost the contact), then
     * cryptographically erases our own copy — messages, encrypted media, ratchet
     * state, and the contact row (see [com.aura.pairing.PairingManager.deleteContact],
     * which reuses [com.aura.db.ContactEraser]). The screen leaves itself: once the
     * contact row is gone the [contact] flow emits null and the UI navigates back —
     * the same exit taken when the peer removes us, so there's a single code path.
     */
    fun deleteContact() {
        viewModelScope.launch { pairingManager.deleteContact(contactId) }
    }

    /** Clear the unread state for this conversation (called while it's on screen). */
    fun markRead() {
        viewModelScope.launch { messageDao.markConversationRead(contactId) }
    }

}

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
                            Text(contact?.displayName ?: "…")
                            if (streak >= com.aura.streak.Streaks.MIN_DISPLAY_DAYS) {
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "🔥 $streak",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        if (currentTimer != DisappearingTimer.OFF) {
                            Text(
                                "⏱ Disappearing: ${currentTimer.label}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
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
                    IconButton(onClick = { onStartCall(viewModel.contactId, false) }) {
                        Icon(Icons.Default.Call, contentDescription = "Voice call")
                    }
                    IconButton(onClick = { onStartCall(viewModel.contactId, true) }) {
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
        val bmp = remember(bytes) {
            bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        }
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
    val bmp = remember(bytes) { bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Color.Black)
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null
            ) { onClose() }
    ) {
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = message.body,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
        } else {
            CircularProgressIndicator(
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

private fun formatRemaining(ms: Long): String {
    val totalSec = ms / 1000
    return when {
        totalSec < 60      -> "${totalSec}s"
        totalSec < 3600    -> "${totalSec / 60}m"
        totalSec < 86400   -> "${totalSec / 3600}h"
        else               -> "${totalSec / 86400}d"
    }
}
