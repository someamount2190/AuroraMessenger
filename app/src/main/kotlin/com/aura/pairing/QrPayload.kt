package com.aura.pairing

import android.util.Base64
import com.aura.crypto.HybridPublicKey
import com.aura.crypto.NodeIdentity
import com.aura.crypto.toHex
import org.json.JSONArray
import org.json.JSONObject

/**
 * The QR pairing payload: JSON { v, nodeId, kem, ed25519, rdv? }.
 *
 * `kem` is the full hybrid KEM public key (Kyber-1024 + X25519) — what the
 * scanner encapsulates against. `ed25519` verifies rendezvous check-in
 * signatures. `rdv` (optional) is an ordered list of rendezvous base URLs the
 * issuer is hosting on — the scanner probes them and adopts the first reachable
 * one, so no server address has to be typed. The JSON goes into the QR directly.
 */
data class QrPayload(
    val nodeIdHex: String,
    val kemPublicKey: HybridPublicKey,
    val ed25519Pub: ByteArray,
    /** Ordered rendezvous base URLs the host is reachable on (empty for a plain code). */
    val rendezvousCandidates: List<String> = emptyList(),
    /**
     * Prekey-protocol version the issuer supports. 0 = legacy (no forward-secret
     * prekeys); ≥1 = the issuer publishes a signed prekey bundle to the rendezvous
     * server, so a scanner should fetch it and run the forward-secret (PQXDH)
     * handshake. Carried as a tiny optional field — old decoders ignore it (and a
     * new decoder reading an old code simply sees 0 and falls back to the legacy
     * handshake). The bulky prekeys themselves never ride in the QR (they'd overflow
     * its capacity past the Kyber identity key); only this capability marker does.
     */
    val pkVersion: Int = 0
) {
    val isHostCode: Boolean get() = rendezvousCandidates.isNotEmpty()

    companion object {
        const val VERSION = 1
        /** Prekey-protocol version this build emits in its own QR. */
        const val PREKEY_PROTOCOL = 1

        fun encode(
            identity: NodeIdentity,
            rendezvousCandidates: List<String> = emptyList()
        ): String {
            val json = JSONObject()
                .put("v", VERSION)
                .put("nodeId", identity.nodeId.toHex())
                .put("kem", Base64.encodeToString(identity.publicPart.kemPublicKey.toBytes(), Base64.NO_WRAP))
                .put("ed25519", Base64.encodeToString(identity.publicPart.signingPublicKey.ed25519PublicKey, Base64.NO_WRAP))
                .put("pk", PREKEY_PROTOCOL)
            if (rendezvousCandidates.isNotEmpty()) {
                json.put("rdv", JSONArray(rendezvousCandidates))
            }
            return json.toString()
        }

        /** Parse and validate scanned QR content. Throws IllegalArgumentException on bad input. */
        fun decode(content: String): QrPayload {
            val json = try { JSONObject(content) } catch (e: Exception) {
                throw IllegalArgumentException("Not an Aurora code")
            }
            require(json.optInt("v", -1) == VERSION) { "Unsupported Aurora code version" }
            val nodeIdHex = json.optString("nodeId")
            require(nodeIdHex.length == 64 && nodeIdHex.all { it.isDigit() || it in 'a'..'f' }) {
                "Invalid node ID in code"
            }
            val kem = HybridPublicKey.fromBytes(
                Base64.decode(json.optString("kem"), Base64.NO_WRAP)
            )
            val ed25519 = Base64.decode(json.optString("ed25519"), Base64.NO_WRAP)
            require(ed25519.size == 32) { "Invalid signing key in code" }

            val rdv = mutableListOf<String>()
            json.optJSONArray("rdv")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val url = arr.optString(i)
                    if (url.startsWith("http://") || url.startsWith("https://")) rdv.add(url)
                }
            }
            return QrPayload(nodeIdHex, kem, ed25519, rdv, json.optInt("pk", 0))
        }
    }

    override fun equals(other: Any?) = other is QrPayload && nodeIdHex == other.nodeIdHex
    override fun hashCode() = nodeIdHex.hashCode()
}
