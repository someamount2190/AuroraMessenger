package com.aura.pairing

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Default display name for a freshly paired contact, until the user renames it. */
internal const val DEFAULT_NAME = "New contact"

/**
 * Shared pairing event bus. Owns the one-shot [events] (toast outcomes) and
 * [incomingPaired] (navigate-to-contact) streams, so the role collaborators
 * ([ScannerPairing], [ReceiverPairing], [VerifyPairing]) and the [PairingCoordinator]
 * facade all publish to / observe the same instances.
 */
@Singleton
class PairingEvents @Inject constructor() {
    private val _incomingPaired = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val incomingPaired: SharedFlow<String> = _incomingPaired

    private val _events = MutableSharedFlow<PairEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<PairEvent> = _events

    fun emit(event: PairEvent) { _events.tryEmit(event) }

    /** Navigate the UI to this contact (open the verify screen, then the chat). */
    fun navigateToContact(nodeIdHex: String) { _incomingPaired.tryEmit(nodeIdHex) }

    /** Both sides verified → success toast + open the chat. */
    fun activated(nodeIdHex: String) {
        _events.tryEmit(PairEvent.Success(nodeIdHex))
        _incomingPaired.tryEmit(nodeIdHex)
    }
}
