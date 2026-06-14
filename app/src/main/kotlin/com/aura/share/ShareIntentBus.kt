package com.aura.share

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Content handed to Aurora from another app via the system share sheet. */
data class PendingShare(
    val text: String?,
    val uris: List<Uri>,
    val mimeType: String?
) {
    val isMedia: Boolean get() = uris.isNotEmpty()
    /** "video" if the shared media is video, else "image" (used by MediaTransfer). */
    val mediaType: String get() = if (mimeType?.startsWith("video") == true) "video" else "image"
}

/**
 * Bridges an inbound ACTION_SEND from [com.aura.MainActivity] to the Compose UI,
 * which then asks the user which contact to share with — the same pattern Signal /
 * Messenger / Viber use to appear in the system share sheet.
 */
@Singleton
class ShareIntentBus @Inject constructor() {
    private val _pending = MutableStateFlow<PendingShare?>(null)
    val pending: StateFlow<PendingShare?> = _pending

    fun offer(share: PendingShare) { _pending.value = share }
    fun consume() { _pending.value = null }

    // A direct-share / launcher contact shortcut was tapped → open that conversation.
    private val _openContact = MutableStateFlow<String?>(null)
    val openContact: StateFlow<String?> = _openContact

    fun offerOpenContact(nodeIdHex: String) { _openContact.value = nodeIdHex }
    fun consumeOpenContact() { _openContact.value = null }
}
