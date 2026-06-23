package com.aura.identity

import com.aura.crypto.NodeIdentity

/**
 * Read access to this device's cryptographic identity. The production [IdentityStore]
 * generates/persists it (KEM + Keystore-backed); abstracting it lets transport/messaging
 * code take a fixed-identity fake in unit tests, where liboqs native and the Keystore
 * aren't available.
 */
interface IdentityProvider {
    /** Load the persisted identity, generating it on first launch. Thread-safe. */
    suspend fun getOrCreate(): NodeIdentity

    /** The cached nodeId as hex, or null before the identity has been loaded. */
    val nodeIdHexOrNull: String?
}
