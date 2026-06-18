package com.aura.transport.rtc

/**
 * The lifecycle of a peer-to-peer WebRTC data connection, surfaced to the UI so
 * pairing and the chat header can reflect connectivity honestly (per the
 * WEBRTC_DATA_TRANSPORT_PATCH plan, U-3).
 *
 *  IDLE       — no connection attempted.
 *  SIGNALING  — offer/answer being exchanged via the rendezvous /signal queue.
 *  GATHERING  — ICE is collecting candidates (host, IPv6, STUN-reflexive).
 *  CHECKING   — ICE connectivity checks (hole punching) in progress.
 *  CONNECTED  — the data channel is open; the server has fallen off the path.
 *  FAILED     — ICE found no working candidate pair (often both peers on
 *               symmetric carrier NAT with no IPv6 — needs a relay we don't run yet).
 */
enum class RtcState {
    IDLE, SIGNALING, GATHERING, CHECKING, CONNECTED, FAILED;

    val isActive: Boolean get() = this == SIGNALING || this == GATHERING || this == CHECKING
}
