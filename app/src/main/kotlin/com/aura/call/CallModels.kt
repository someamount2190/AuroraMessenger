package com.aura.call

/** Lifecycle of a call: idle → outgoing/incoming → connecting → connected → ended. */
enum class CallState { IDLE, OUTGOING, INCOMING, CONNECTING, CONNECTED, ENDED }

/** A snapshot of the current call, exposed to the UI by [CallController.call]. */
data class CallInfo(
    val state: CallState,
    val peerNodeIdHex: String? = null,
    val peerName: String? = null,
    val isCaller: Boolean = false,
    val isVideo: Boolean = true,
    /** Wall-clock millis the media connected (0 until CONNECTED). Drives call timers. */
    val connectedAtMs: Long = 0L
)
