package com.aura.network

import android.util.Base64
import com.aura.crypto.HybridPublicKey
import com.aura.crypto.HybridSigner
import com.aura.crypto.NodeIdentity
import com.aura.crypto.toHex
import com.aura.di.IoDispatcher
import com.aura.server.AuroraRendezvousServer
import com.aura.server.RendezvousServerController
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.CertificatePinner
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** A signed signal drain was rejected (e.g. the server lost our registration). */
class DrainUnauthorizedException : Exception("signal drain unauthorized — re-check-in needed")

/**
 * Client side of the rendezvous protocol. Phase 0 uses it for the two-emulator
 * check-in test (driven from Settings); Phase 3 wires it to run on launch and
 * every 5 minutes.
 */
@Singleton
class RendezvousClient @Inject constructor(
    private val signer: HybridSigner,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    private val http = pinned(
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
    ).build()

    // Short-timeout client for candidate reachability probes during pairing.
    private val probeHttp = pinned(
        OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
    ).build()

    // Long-read client for the parked /wait long-poll. The server holds the request
    // ~25s, so the read timeout must comfortably exceed that.
    private val waitHttp = pinned(
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(35, TimeUnit.SECONDS)
    ).build()

    /**
     * Apply certificate pinning when the production server is configured for TLS.
     * While [PINNED_HOST]/[PINNED_CERT_SHA256] are blank, pinning is off so the
     * cleartext droplet and LAN/Server-Mode (http to arbitrary IPs) keep working.
     * Once the rendezvous server is fronted with TLS: fill in the host + its SPKI
     * SHA-256 pin below and change [com.aura.settings.AuroraSettings.DEFAULT_SERVER_ADDRESS]
     * to "https://<host>". Pinning then blocks interception even with a rogue CA.
     */
    private fun pinned(builder: OkHttpClient.Builder): OkHttpClient.Builder {
        if (PINNED_HOST.isNotBlank() && PINNED_CERT_SHA256.isNotBlank()) {
            val cp = CertificatePinner.Builder()
                .add(PINNED_HOST, "sha256/$PINNED_CERT_SHA256")
            // Backup pin (the issuing intermediate) so a forced leaf-key rotation
            // can't lock every client out before an app update ships.
            if (PINNED_CERT_SHA256_BACKUP.isNotBlank()) {
                cp.add(PINNED_HOST, "sha256/$PINNED_CERT_SHA256_BACKUP")
            }
            builder.certificatePinner(cp.build())
        }
        return builder
    }

    /**
     * Sign a fresh timestamp and attach the `X-Drain-*` headers that prove we own
     * [identity]'s nodeId. Shared by every authenticated GET/POST against our own
     * queue/channel (signal drain, /wait long-poll, prekey publish) so the signing
     * and header layout live in exactly one place.
     */
    private suspend fun Request.Builder.signedDrainAuth(identity: NodeIdentity): Request.Builder {
        val ts = System.currentTimeMillis()
        val sig = signer.signEd25519Only(
            AuroraRendezvousServer.drainMessage(identity.nodeId.toHex(), ts),
            identity.privatePart.signingPrivateKey
        ).getOrThrow()
        return addHeader("X-Drain-Ts", ts.toString())
            .addHeader("X-Drain-Pub", Base64.encodeToString(identity.publicPart.signingPublicKey.ed25519PublicKey, Base64.NO_WRAP))
            .addHeader("X-Drain-Sig", Base64.encodeToString(sig, Base64.NO_WRAP))
    }

    data class FindCandidate(
        val ip: String,
        val port: Int,
        val timestampMs: Long,
        val signatureB64: String
    )

    /**
     * Sign and POST a check-in for [identity] to [serverBaseUrl].
     * [advertisedOverride] ("ip:port") replaces the auto-detected address —
     * see [com.aura.settings.AuroraSettings.advertisedAddress].
     */
    suspend fun checkIn(
        serverBaseUrl: String,
        identity: NodeIdentity,
        listenPort: Int = DEFAULT_LISTEN_PORT,
        advertisedOverride: String? = null
    ): Result<String> = withContext(ioDispatcher) {
        runCatching {
            val nodeIdHex = identity.nodeId.toHex()
            var ip = RendezvousServerController.localIpAddress()
                ?: error("could not determine local IP")
            var port = listenPort
            if (!advertisedOverride.isNullOrBlank()) {
                val parts = advertisedOverride.split(":")
                require(parts.size == 2 && parts[1].toIntOrNull() in 1..65535) {
                    "advertised address must be ip:port"
                }
                ip = parts[0]
                port = parts[1].toInt()
            }
            val timestamp = System.currentTimeMillis()

            val message = AuroraRendezvousServer.checkinMessage(nodeIdHex, ip, port, timestamp)
            // Post-quantum: sign the check-in with the full Dilithium-3 + Ed25519
            // hybrid. Peers verify this against the Dilithium key learned off-QR at
            // pairing (verifyCandidates), so a quantum forgery can't redirect them.
            val signature = signer
                .sign(message, identity.privatePart.signingPrivateKey)
                .getOrThrow()

            val body = JSONObject()
                .put("nodeId", nodeIdHex)
                .put("ip", ip)
                .put("port", port)
                .put("timestamp", timestamp)
                .put("ed25519Pub", Base64.encodeToString(identity.publicPart.signingPublicKey.ed25519PublicKey, Base64.NO_WRAP))
                .put("dilithiumPub", Base64.encodeToString(identity.publicPart.signingPublicKey.dilithiumPublicKey, Base64.NO_WRAP))
                // KEM key lets the server bind nodeId == SHA3-256(kemPub ‖ signPub), so a
                // rogue can't register/overwrite someone else's nodeId with their own key.
                .put("kemPub", Base64.encodeToString(identity.publicPart.kemPublicKey.toBytes(), Base64.NO_WRAP))
                .put("signature", Base64.encodeToString(signature, Base64.NO_WRAP))
                .toString()

            val request = Request.Builder()
                .url("$serverBaseUrl/checkin")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()

            http.newCall(request).execute().use { resp ->
                val respBody = resp.body?.string().orEmpty()
                check(resp.isSuccessful) { "checkin failed: HTTP ${resp.code} $respBody" }
                "registered as $ip:$port (HTTP ${resp.code})"
            }
        }
    }

    /**
     * GET /find/{nodeIdHex} and return all 10 candidates. The caller verifies
     * which candidate's signature matches the target node's Ed25519 public key.
     */
    suspend fun find(serverBaseUrl: String, nodeIdHex: String): Result<List<FindCandidate>> =
        withContext(ioDispatcher) {
            runCatching {
                val request = Request.Builder()
                    .url("$serverBaseUrl/find/$nodeIdHex")
                    .get()
                    .build()

                http.newCall(request).execute().use { resp ->
                    val respBody = resp.body?.string().orEmpty()
                    check(resp.isSuccessful) { "find failed: HTTP ${resp.code} $respBody" }
                    val arr = JSONObject(respBody).getJSONArray("candidates")
                    (0 until arr.length()).map { i ->
                        val c = arr.getJSONObject(i)
                        FindCandidate(
                            ip           = c.getString("ip"),
                            port         = c.getInt("port"),
                            timestampMs  = c.getLong("timestamp"),
                            signatureB64 = c.getString("signature")
                        )
                    }
                }
            }
        }

    /**
     * Probe a rendezvous base URL with a short-timeout GET /checkin.
     * Used by the scanner to pick the first reachable candidate from a host QR.
     */
    suspend fun probe(serverBaseUrl: String): Boolean = withContext(ioDispatcher) {
        runCatching {
            val request = Request.Builder().url("$serverBaseUrl/checkin").get().build()
            probeHttp.newCall(request).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    /** Return the first candidate URL that responds, or null if none do. */
    suspend fun firstReachable(candidates: List<String>): String? {
        for (url in candidates) if (probe(url)) return url
        return null
    }

    /** POST an opaque payload to [nodeIdHex]'s signal queue (pairing, call signaling). */
    suspend fun postSignal(serverBaseUrl: String, nodeIdHex: String, payload: String): Result<Unit> =
        withContext(ioDispatcher) {
            runCatching {
                val request = Request.Builder()
                    .url("$serverBaseUrl/signal/$nodeIdHex")
                    .post(payload.toRequestBody("application/json".toMediaType()))
                    .build()
                http.newCall(request).execute().use { resp ->
                    check(resp.isSuccessful) { "signal post failed: HTTP ${resp.code}" }
                }
            }
        }

    /**
     * Drain our signal queue. The request is Ed25519-signed over a fresh timestamp so
     * the server can confirm we own this nodeId — without it, anyone who knew a nodeId
     * could steal or delete its queued pairing/call signals. Throws
     * [DrainUnauthorizedException] on a 401 so the caller can re-register and retry.
     */
    suspend fun getSignals(serverBaseUrl: String, identity: NodeIdentity): Result<List<String>> =
        withContext(ioDispatcher) {
            runCatching {
                val nodeIdHex = identity.nodeId.toHex()
                // Carried as headers (not query) so an older server that ignores them
                // still sees a clean /signal/{nodeId} path and drains normally.
                val request = Request.Builder()
                    .url("$serverBaseUrl/signal/$nodeIdHex")
                    .signedDrainAuth(identity)
                    .get()
                    .build()
                http.newCall(request).execute().use { resp ->
                    if (resp.code == 401) throw DrainUnauthorizedException()
                    val body = resp.body?.string().orEmpty()
                    check(resp.isSuccessful) { "signal get failed: HTTP ${resp.code}" }
                    val arr = JSONObject(body).getJSONArray("payloads")
                    (0 until arr.length()).map { arr.getString(it) }
                }
            }
        }

    /**
     * Park a long-poll on our wake channel. The server holds the request open until a
     * tap/signal arrives for us (returns wake=true) or its hold window elapses
     * (wake=false), at which point the caller re-parks. Signed exactly like
     * [getSignals] so only we can park our own channel. Throws
     * [DrainUnauthorizedException] on 401 (re-check-in needed). Returns false on an
     * older server that lacks /wait (404) so the caller falls back to plain polling.
     */
    suspend fun waitForWake(serverBaseUrl: String, identity: NodeIdentity): Result<Boolean> =
        withContext(ioDispatcher) {
            runCatching {
                val nodeIdHex = identity.nodeId.toHex()
                val request = Request.Builder()
                    .url("$serverBaseUrl/wait/$nodeIdHex")
                    .signedDrainAuth(identity)
                    .get()
                    .build()
                waitHttp.newCall(request).execute().use { resp ->
                    if (resp.code == 401) throw DrainUnauthorizedException()
                    if (resp.code == 404) return@use false   // server without /wait
                    val body = resp.body?.string().orEmpty()
                    check(resp.isSuccessful) { "wait failed: HTTP ${resp.code}" }
                    JSONObject(body).optBoolean("wake", false)
                }
            }
        }

    /**
     * Contentless wake: nudge [peerNodeIdHex] to come online so a direct delivery can
     * land. Best-effort — the server stores nothing, it only completes the peer's
     * parked /wait. Failures are swallowed (peer may simply not be parked).
     */
    suspend fun tap(serverBaseUrl: String, peerNodeIdHex: String): Result<Unit> =
        withContext(ioDispatcher) {
            runCatching {
                val request = Request.Builder()
                    .url("$serverBaseUrl/tap/$peerNodeIdHex")
                    .post(ByteArray(0).toRequestBody())
                    .build()
                http.newCall(request).execute().use { /* fire-and-forget */ }
            }
        }

    /**
     * Filter [candidates] down to the one whose check-in signature verifies for
     * [nodeIdHex] — the real IP. The rest feed the mesh peer table.
     *
     * When [dilithiumPub] is known (exchanged off-QR at pairing) the full hybrid
     * signature is verified → post-quantum authentication of the address binding.
     * Until then, only the Ed25519 component is checked (classical fallback for the
     * brief window before the pair-ack arrives).
     */
    fun verifyCandidates(
        nodeIdHex: String,
        ed25519Pub: ByteArray,
        dilithiumPub: ByteArray?,
        candidates: List<FindCandidate>
    ): FindCandidate? = candidates.firstOrNull { c ->
        val message = AuroraRendezvousServer.checkinMessage(nodeIdHex, c.ip, c.port, c.timestampMs)
        val sig = Base64.decode(c.signatureB64, Base64.NO_WRAP)
        if (dilithiumPub != null)
            signer.verifyHybridSync(message, sig, com.aura.crypto.HybridVerifyKey(dilithiumPub, ed25519Pub))
        else
            signer.verifyHybridEd25519PartSync(message, sig, ed25519Pub)
    }

    /**
     * One peer's forward-secret prekeys, fetched from the rendezvous server: the
     * signed prekey (always present) and one popped one-time prekey (null once the
     * server's pool for that peer is exhausted — the handshake then runs SPK-only,
     * still forward-secret). Signatures are verified by the caller against the peer's
     * Ed25519 key read from the authentic in-person QR.
     */
    data class PrekeyBundle(
        val spkId: String,
        val spkPub: HybridPublicKey,
        val spkSig: ByteArray,
        val opkId: String?,
        val opkPub: HybridPublicKey?,
        val opkSig: ByteArray?
    )

    /**
     * Publish our signed prekey bundle so initiators can fetch it. Authenticated with
     * the same signed `X-Drain-*` headers as the signal drain, so only the key holder
     * behind [identity]'s nodeId can publish for it. Best-effort: failures (older
     * server without /prekeys → 404, network) are surfaced as a failed Result and
     * simply mean peers fall back to the legacy handshake.
     */
    suspend fun publishPrekeys(
        serverBaseUrl: String,
        identity: NodeIdentity,
        bundleJson: String
    ): Result<Unit> = withContext(ioDispatcher) {
        runCatching {
            val nodeIdHex = identity.nodeId.toHex()
            val request = Request.Builder()
                .url("$serverBaseUrl/prekeys/$nodeIdHex")
                .signedDrainAuth(identity)
                .post(bundleJson.toRequestBody("application/json".toMediaType()))
                .build()
            http.newCall(request).execute().use { resp ->
                check(resp.isSuccessful) { "prekey publish failed: HTTP ${resp.code}" }
            }
        }
    }

    /**
     * Fetch a peer's prekey bundle for a forward-secret pairing. The server pops one
     * one-time prekey per fetch. Returns null when the peer has published no bundle
     * (404) — the caller then runs the legacy identity-key-only handshake.
     */
    suspend fun fetchPrekeyBundle(serverBaseUrl: String, nodeIdHex: String): Result<PrekeyBundle?> =
        withContext(ioDispatcher) {
            runCatching {
                val request = Request.Builder().url("$serverBaseUrl/prekeys/$nodeIdHex").get().build()
                http.newCall(request).execute().use { resp ->
                    if (resp.code == 404) return@use null
                    val body = resp.body?.string().orEmpty()
                    check(resp.isSuccessful) { "prekey fetch failed: HTTP ${resp.code}" }
                    val j = JSONObject(body)
                    val spk = j.getJSONObject("spk")
                    val opk = if (j.isNull("opk")) null else j.optJSONObject("opk")
                    PrekeyBundle(
                        spkId  = spk.getString("id"),
                        spkPub = HybridPublicKey.fromBytes(Base64.decode(spk.getString("pub"), Base64.NO_WRAP)),
                        spkSig = Base64.decode(spk.getString("sig"), Base64.NO_WRAP),
                        opkId  = opk?.getString("id"),
                        opkPub = opk?.let { HybridPublicKey.fromBytes(Base64.decode(it.getString("pub"), Base64.NO_WRAP)) },
                        opkSig = opk?.let { Base64.decode(it.getString("sig"), Base64.NO_WRAP) }
                    )
                }
            }
        }

    companion object {
        /** Port the Phase 4 direct-TCP message listener will bind to. */
        const val DEFAULT_LISTEN_PORT = 8765

        // ── TLS certificate pinning (production rendezvous server) ──
        /** TLS host to pin. Blank = pinning disabled (LAN/Server-Mode cleartext only). */
        const val PINNED_HOST = "api.auroramessenger.com"
        /**
         * Base64 SHA-256 of the leaf cert's SubjectPublicKeyInfo (OkHttp "sha256/…").
         * Stable across Let's Encrypt renewals because the server keeps the key
         * (`reuse_key = True`). Rotating the key requires shipping a new pin here.
         */
        const val PINNED_CERT_SHA256 = "zeDyjquggZvLuU4dIuP1sOVPYdG8V2ZybM7s5fxzlg0="
        /** Issuing-intermediate SPKI pin — backup so a leaf rotation isn't fatal. */
        const val PINNED_CERT_SHA256_BACKUP = "s/tdAOmUzd8syaTuqfgGvFcn6DzA5Cmb+Vby1ST+U3Y="
    }
}
