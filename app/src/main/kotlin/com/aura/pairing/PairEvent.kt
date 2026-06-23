package com.aura.pairing

/** One-shot pairing outcomes for toasts. */
sealed interface PairEvent {
    val nodeIdHex: String
    data class Accepted(override val nodeIdHex: String) : PairEvent  // scanner: peer accepted, now verify
    data class Success(override val nodeIdHex: String) : PairEvent   // both verified → chat open
    data class Declined(override val nodeIdHex: String) : PairEvent  // peer rejected our request
    data class Failed(override val nodeIdHex: String) : PairEvent    // verification gave up (blocked)
    data class ContactRemoved(override val nodeIdHex: String, val name: String) : PairEvent  // peer deleted us
    data class IncomingRequest(override val nodeIdHex: String) : PairEvent  // someone scanned our code and wants to connect
}
