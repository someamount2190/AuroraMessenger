package com.aura.ux

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tiny app-wide event bus that fires when an inbound message lands (text or
 * media), carrying the sender's contact node-id. The home screen subscribes and
 * runs the "light through glass" sweep around *that sender's* contact box — a
 * quiet ambient cue tied to the contact who messaged. Buffered + drop-oldest so
 * a burst of arrivals never blocks the networking thread.
 */
@Singleton
class MessagePulse @Inject constructor() {
    private val _pulses = MutableSharedFlow<String>(
        extraBufferCapacity = 8,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )
    /** Emits the contact node-id (hex) of the sender on each inbound message. */
    val pulses: SharedFlow<String> = _pulses

    /** Signal that an inbound message just arrived from [contactNodeIdHex]. Non-blocking. */
    fun pulse(contactNodeIdHex: String) { _pulses.tryEmit(contactNodeIdHex) }
}
