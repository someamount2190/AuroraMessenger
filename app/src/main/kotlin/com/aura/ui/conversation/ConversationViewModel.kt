package com.aura.ui.conversation

import android.content.ContentValues
import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.provider.MediaStore
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aura.db.ContactDao
import com.aura.db.ContactEntity
import com.aura.db.MessageDao
import com.aura.db.MessageEntity
import com.aura.db.PairState
import com.aura.call.CallController
import com.aura.call.CallState
import com.aura.di.IoDispatcher
import com.aura.disappearing.DisappearingMessages
import com.aura.pairing.PairingCoordinator
import com.aura.media.MediaTransfer
import com.aura.media.VoiceRecorder
import com.aura.reaction.Reactions
import com.aura.settings.DisappearingTimer
import com.aura.transport.MessageSender
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
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
    private val disappearingManager: DisappearingMessages,
    private val reactionManager: Reactions,
    private val voiceRecorder: VoiceRecorder,
    private val pairingManager: PairingCoordinator,
    private val callManager: CallController,
    private val rtcTransport: com.aura.transport.rtc.PeerTransport,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : ViewModel() {

    val contactId: String = savedStateHandle.get<String>("contactId").orEmpty()

    /** Live P2P data-channel state for this contact — drives the header's reachability
     *  indicator and surfaces connection failures (per the WebRTC transport patch). */
    val connection: StateFlow<com.aura.transport.rtc.RtcState> = rtcTransport.state(contactId)

    init {
        // When the conversation is open and the contact is fully paired, attempt a direct
        // peer-to-peer connection so the header reflects reachability right away — this is
        // also the moment "pairing reflects the result", since pairing lands in the chat.
        viewModelScope.launch {
            contactDao.observeByNodeId(contactId).first { it?.pairState == PairState.ACTIVE }
            rtcTransport.connectAsync(contactId)
        }
    }

    /** True while any call is active anywhere — used to disable the call buttons so a
     *  second call (this contact or another) can't be started over an ongoing one. */
    val callActive: StateFlow<Boolean> = callManager.call
        .map { it.state != CallState.IDLE &&
               it.state != CallState.ENDED }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

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
            val ok = withContext(ioDispatcher) {
                val isVideo = message.type == "video"
                val collection = if (isVideo)
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "aurora-${message.id}")
                    put(MediaStore.MediaColumns.MIME_TYPE, if (isVideo) "video/mp4" else "image/jpeg")
                    put(MediaStore.MediaColumns.RELATIVE_PATH,
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
            val uri = withContext(ioDispatcher) {
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
        viewModelScope.launch(ioDispatcher) {
            val ok = voiceRecorder.start()
            _isRecording.value = ok
        }
    }

    fun stopAndSendRecording() {
        viewModelScope.launch(ioDispatcher) {
            val result = voiceRecorder.stop()
            _isRecording.value = false
            result?.let { (bytes, durationMs) -> mediaTransfer.sendAudio(contactId, bytes, durationMs) }
        }
    }

    fun cancelRecording() {
        viewModelScope.launch(ioDispatcher) {
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
            withContext(ioDispatcher) {
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
     * state, and the contact row (see [com.aura.pairing.PairingCoordinator.deleteContact],
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
