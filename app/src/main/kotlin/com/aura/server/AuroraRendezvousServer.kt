package com.aura.server

import com.aura.crypto.HybridPublicKey
import com.aura.crypto.HybridSigner
import com.aura.crypto.HybridVerifyKey
import com.aura.crypto.Hkdf
import com.aura.crypto.hexToBytes
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import android.util.Base64

/**
 * Phase 0: mini rendezvous server running inside the app (NanoHTTPD).
 *
 * Endpoints:
 *   POST /checkin          — verifies signature, stores nodeId→IP, 15-minute TTL
 *   GET  /find/{nodeId}    — returns 10 candidates: the real one + 9 padding, each with
 *                            a signature field; only the real one verifies against the
 *                            requested node's public key
 *   POST /signal/{nodeId}  — queues an encrypted WebRTC signaling payload (Phase 7)
 *   GET  /signal/{nodeId}  — drains queued signaling payloads
 *
 * Privacy rules (from the build spec):
 *   - In-memory store only. No disk writes. No request/content logging.
 *   - The server never sees message content — only nodeId→IP mappings.
 */
class AuroraRendezvousServer(
    port: Int = DEFAULT_PORT,
    private val signer: HybridSigner = HybridSigner()
) : NanoHTTPD(port) {

    data class RegisteredNode(
        val nodeIdHex:    String,
        val ip:           String,
        val port:         Int,
        val timestampMs:  Long,
        val ed25519PubB64: String,
        val signatureB64: String,
        val storedAtMs:   Long
    )

    /** A published prekey bundle: the signed-prekey JSON + a queue of one-time-prekey JSONs. */
    private class PrekeyStore(
        val spkJson: String,
        val opks: ConcurrentLinkedQueue<String>,
        @Volatile var storedAtMs: Long
    )

    private val nodes   = ConcurrentHashMap<String, RegisteredNode>()
    private val signals = ConcurrentHashMap<String, ConcurrentLinkedQueue<String>>()
    private val prekeys = ConcurrentHashMap<String, PrekeyStore>()
    private val rng     = SecureRandom()

    // Phase 3 rate limiting: 1 checkin per nodeId per minute, 10 lookups per IP per minute.
    private val lastCheckinByNode = ConcurrentHashMap<String, Long>()
    private val findTimestampsByIp = ConcurrentHashMap<String, MutableList<Long>>()
    // Signal-queue hardening: last activity per queue (for expiry) + per-IP POST limit.
    private val signalActivityAt = ConcurrentHashMap<String, Long>()
    private val signalPostTimestampsByIp = ConcurrentHashMap<String, MutableList<Long>>()

    val registeredNodeCount: Int
        get() { purgeExpired(); return nodes.size }

    // ── Routing ───────────────────────────────────────────────────────────

    override fun serve(session: IHTTPSession): Response {
        purgeExpired()
        val uri = session.uri.trimEnd('/')
        return try {
            when {
                session.method == Method.POST && uri == "/checkin" ->
                    handleCheckin(session)

                session.method == Method.GET && uri.startsWith("/find/") -> {
                    if (!allowFind(session.remoteIpAddress ?: "?"))
                        jsonError(Response.Status.TOO_MANY_REQUESTS, "rate limited")
                    else handleFind(uri.removePrefix("/find/"))
                }

                session.method == Method.POST && uri.startsWith("/signal/") ->
                    handleSignalPost(uri.removePrefix("/signal/"), session)

                session.method == Method.GET && uri.startsWith("/signal/") ->
                    handleSignalGet(uri.removePrefix("/signal/"), session)

                session.method == Method.POST && uri.startsWith("/prekeys/") ->
                    handlePrekeyPublish(uri.removePrefix("/prekeys/"), session)

                session.method == Method.GET && uri.startsWith("/prekeys/") ->
                    handlePrekeyFetch(uri.removePrefix("/prekeys/"))

                session.method == Method.GET && uri == "/source" ->
                    // AGPL-3.0 §13: point remote users at the Corresponding Source.
                    jsonResponse(Response.Status.OK, JSONObject().put("source", SOURCE_URL).put("license", "AGPL-3.0-or-later"))

                session.method == Method.GET && uri == "/checkin" ->
                    // Lets `curl http://localhost:8080/checkin` confirm the server is alive
                    // without registering anything (Phase 0 smoke test).
                    jsonResponse(Response.Status.OK, JSONObject().put("status", "ok").put("hint", "POST a signed payload to check in"))

                else -> jsonError(Response.Status.NOT_FOUND, "unknown endpoint")
            }
        } catch (e: Exception) {
            // No logging of payloads — return a generic error only.
            jsonError(Response.Status.INTERNAL_ERROR, "server error")
        }
    }

    // ── POST /checkin ─────────────────────────────────────────────────────
    //
    // Body: { nodeId, ip, port, timestamp, ed25519Pub, signature }
    //   signature = Ed25519 over canonical "aura-checkin-v1|nodeId|ip|port|timestamp"
    //
    // The signature proves possession of the key the node claims; the *binding*
    // of that key to a contact happens client-side at /find verification time,
    // against the public key learned during QR pairing.

    private fun handleCheckin(session: IHTTPSession): Response {
        val body = readBody(session) ?: return jsonError(Response.Status.BAD_REQUEST, "missing body")
        val json = JSONObject(body)

        val nodeIdHex   = json.optString("nodeId")
        val ip          = json.optString("ip")
        val port        = json.optInt("port", -1)
        val timestamp   = json.optLong("timestamp", -1)
        val pubB64      = json.optString("ed25519Pub")
        val dilithiumB64 = json.optString("dilithiumPub")
        val kemPubB64   = json.optString("kemPub")
        val sigB64      = json.optString("signature")

        if (nodeIdHex.length != 64 || ip.isEmpty() || port !in 1..65535 ||
            timestamp <= 0 || pubB64.isEmpty() || sigB64.isEmpty()
        ) return jsonError(Response.Status.BAD_REQUEST, "invalid checkin fields")

        // nodeId↔key binding (strict here — every client talking to the in-app server is
        // this same app version, which always sends the KEM key). nodeId MUST equal
        // SHA3-256(kemPub ‖ signPub), so a rogue can't squat/overwrite someone else's
        // nodeId with its own key.
        if (!nodeIdBindsToKeys(nodeIdHex, kemPubB64, pubB64, dilithiumB64))
            return jsonError(Response.Status.UNAUTHORIZED, "nodeId does not match keys")

        // Reject stale or far-future timestamps (replay window).
        val now = System.currentTimeMillis()
        if (kotlin.math.abs(now - timestamp) > CHECKIN_FRESHNESS_MS)
            return jsonError(Response.Status.BAD_REQUEST, "timestamp outside freshness window")

        // Rate limit: one check-in per nodeId per minute (refreshes are free of charge
        // for an attacker otherwise — they could churn the node table).
        val lastCheckin = lastCheckinByNode[nodeIdHex] ?: 0L
        if (now - lastCheckin < CHECKIN_RATE_LIMIT_MS)
            return jsonError(Response.Status.TOO_MANY_REQUESTS, "rate limited")

        val message = checkinMessage(nodeIdHex, ip, port, timestamp)
        val pub = Base64.decode(pubB64, Base64.NO_WRAP)
        val sig = Base64.decode(sigB64, Base64.NO_WRAP)
        // Post-quantum hybrid signature when a Dilithium key is supplied; the older
        // Ed25519-only form is still accepted for robustness.
        val sigOk = if (dilithiumB64.isNotEmpty()) {
            signer.verifyHybridSync(message, sig, HybridVerifyKey(Base64.decode(dilithiumB64, Base64.NO_WRAP), pub))
        } else {
            signer.verifyEd25519OnlySync(message, sig, pub)
        }
        if (!sigOk)
            return jsonError(Response.Status.UNAUTHORIZED, "signature verification failed")
        lastCheckinByNode[nodeIdHex] = now

        nodes[nodeIdHex] = RegisteredNode(
            nodeIdHex     = nodeIdHex,
            ip            = ip,
            port          = port,
            timestampMs   = timestamp,
            ed25519PubB64 = pubB64,
            signatureB64  = sigB64,
            storedAtMs    = now
        )

        return jsonResponse(
            Response.Status.OK,
            JSONObject().put("status", "registered").put("ttlSeconds", TTL_MS / 1000)
        )
    }

    // ── GET /find/{nodeId} ────────────────────────────────────────────────
    //
    // Returns 10 candidates. The real record carries the node's own checkin
    // signature; the requester re-derives "aura-checkin-v1|nodeId|ip|port|timestamp"
    // and verifies it against the paired public key. Padding entries are other
    // real nodes' records (with THEIR signatures, which won't verify for this
    // nodeId) plus dummies — an observer cannot tell which IP is real. The
    // padding doubles as future mesh bootstrap peers (meshPeerTable).

    private fun handleFind(nodeIdHex: String): Response {
        val target = nodes[nodeIdHex]
            ?: return jsonError(Response.Status.NOT_FOUND, "node not registered")

        val candidates = mutableListOf<JSONObject>()
        candidates += candidateJson(target.ip, target.port, target.timestampMs, target.signatureB64)

        // Pad with DUMMIES ONLY — never other registered nodes' real records — so a
        // /find lookup can't be used to harvest the IP:port of other Aurora users.
        // Only the real candidate's signature verifies for this nodeId; the dummies are
        // same-shaped noise (signature sized to a real hybrid signature so length can't
        // give the real one away).
        while (candidates.size < CANDIDATE_COUNT) {
            candidates += candidateJson(
                ip          = randomPlausibleIp(),
                port        = 1024 + rng.nextInt(64000),
                timestampMs = System.currentTimeMillis() - rng.nextInt(300_000),
                sigB64      = Base64.encodeToString(ByteArray(HYBRID_SIG_BYTES).also(rng::nextBytes), Base64.NO_WRAP)
            )
        }

        candidates.shuffle()
        return jsonResponse(
            Response.Status.OK,
            JSONObject().put("candidates", JSONArray(candidates as List<Any>))
        )
    }

    // ── /signal/{nodeId} ──────────────────────────────────────────────────

    private fun handleSignalPost(nodeIdHex: String, session: IHTTPSession): Response {
        if (nodeIdHex.length != 64) return jsonError(Response.Status.BAD_REQUEST, "bad nodeId")
        if (!allowSignalPost(session.remoteIpAddress ?: "?"))
            return jsonError(Response.Status.TOO_MANY_REQUESTS, "rate limited")
        val body = readBody(session) ?: return jsonError(Response.Status.BAD_REQUEST, "missing body")
        if (body.length > MAX_SIGNAL_BYTES) return jsonError(Response.Status.BAD_REQUEST, "payload too large")
        val q = signals.getOrPut(nodeIdHex) { ConcurrentLinkedQueue() }
        q.add(body)
        while (q.size > MAX_QUEUE_LEN) q.poll()            // bounded queue: drop oldest
        signalActivityAt[nodeIdHex] = System.currentTimeMillis()
        return jsonResponse(Response.Status.OK, JSONObject().put("status", "queued"))
    }

    private fun handleSignalGet(nodeIdHex: String, session: IHTTPSession): Response {
        // Authenticated drain: only the key holder behind this nodeId can read/empty
        // its queue, so knowing a nodeId no longer lets anyone steal or delete the
        // pending pairing/call signals waiting there.
        if (!verifyDrain(nodeIdHex, session))
            return jsonError(Response.Status.UNAUTHORIZED, "drain authentication required")
        val queue = signals[nodeIdHex]
        val arr = JSONArray()
        if (queue != null) {
            while (true) arr.put(queue.poll() ?: break)
        }
        signalActivityAt[nodeIdHex] = System.currentTimeMillis()
        return jsonResponse(Response.Status.OK, JSONObject().put("payloads", arr))
    }

    /** Verify a signed drain: ts fresh, pub == the key that checked in, signature valid. */
    private fun verifyDrain(nodeIdHex: String, session: IHTTPSession): Boolean {
        val node = nodes[nodeIdHex] ?: return false      // must be registered (pubkey known)
        val headers = session.headers                    // NanoHTTPD lowercases header names
        val ts = headers["x-drain-ts"]?.toLongOrNull() ?: return false
        if (kotlin.math.abs(System.currentTimeMillis() - ts) > CHECKIN_FRESHNESS_MS) return false
        val pubB64 = headers["x-drain-pub"] ?: return false
        if (pubB64 != node.ed25519PubB64) return false
        val sigB64 = headers["x-drain-sig"] ?: return false
        return try {
            signer.verifyEd25519OnlySync(
                drainMessage(nodeIdHex, ts),
                Base64.decode(sigB64, Base64.NO_WRAP),
                Base64.decode(pubB64, Base64.NO_WRAP)
            )
        } catch (e: Exception) { false }
    }

    // ── /prekeys/{nodeId} (forward-secret PQXDH bundles) ──────────────────
    //
    // Stores only PUBLIC prekeys + their signatures — never private material, never
    // content. POST is authenticated like the drain (only the key holder behind nodeId
    // may publish for it). GET pops one one-time prekey per request; when the pool is
    // empty it returns just the signed prekey (the handshake stays forward-secret via
    // the rotating SPK).

    private fun handlePrekeyPublish(nodeIdHex: String, session: IHTTPSession): Response {
        if (nodeIdHex.length != 64) return jsonError(Response.Status.BAD_REQUEST, "bad nodeId")
        if (!verifyDrain(nodeIdHex, session))
            return jsonError(Response.Status.UNAUTHORIZED, "prekey publish auth required")
        val body = readBody(session) ?: return jsonError(Response.Status.BAD_REQUEST, "missing body")
        if (body.length > MAX_BODY_BYTES) return jsonError(Response.Status.BAD_REQUEST, "payload too large")
        val json = JSONObject(body)
        val spk = json.optJSONObject("spk") ?: return jsonError(Response.Status.BAD_REQUEST, "missing spk")
        val opksArr = json.optJSONArray("opks") ?: JSONArray()
        val q = ConcurrentLinkedQueue<String>()
        for (i in 0 until minOf(opksArr.length(), PREKEY_OPK_MAX)) {
            opksArr.optJSONObject(i)?.let { q.add(it.toString()) }
        }
        prekeys[nodeIdHex] = PrekeyStore(spk.toString(), q, System.currentTimeMillis())
        return jsonResponse(Response.Status.OK, JSONObject().put("status", "stored"))
    }

    private fun handlePrekeyFetch(nodeIdHex: String): Response {
        val store = prekeys[nodeIdHex]
            ?: return jsonError(Response.Status.NOT_FOUND, "no prekey bundle")
        val opk = store.opks.poll()   // pop one one-time prekey (null when exhausted)
        val resp = JSONObject()
            .put("spk", JSONObject(store.spkJson))
            .put("opk", if (opk != null) JSONObject(opk) else JSONObject.NULL)
        return jsonResponse(Response.Status.OK, resp)
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun purgeExpired() {
        val cutoff = System.currentTimeMillis() - TTL_MS
        nodes.values.removeIf { it.storedAtMs < cutoff }
        lastCheckinByNode.values.removeIf { it < cutoff }
        prekeys.entries.removeIf { it.value.storedAtMs < cutoff }
        // Drop signal queues with no recent activity (covers never-drained queues too).
        val stale = signals.keys.filter { (signalActivityAt[it] ?: 0L) < cutoff }
        stale.forEach { signals.remove(it); signalActivityAt.remove(it) }
    }

    /** Allow at most 10 /find lookups per requester IP per minute. */
    private fun allowFind(requesterIp: String): Boolean {
        val now = System.currentTimeMillis()
        val timestamps = findTimestampsByIp.getOrPut(requesterIp) { mutableListOf() }
        synchronized(timestamps) {
            timestamps.removeIf { now - it > 60_000 }
            if (timestamps.size >= FIND_RATE_LIMIT_PER_MIN) return false
            timestamps.add(now)
            return true
        }
    }

    /** Allow at most [SIGNAL_POST_RATE_LIMIT_PER_MIN] signal posts per IP per minute. */
    private fun allowSignalPost(requesterIp: String): Boolean {
        val now = System.currentTimeMillis()
        val timestamps = signalPostTimestampsByIp.getOrPut(requesterIp) { mutableListOf() }
        synchronized(timestamps) {
            timestamps.removeIf { now - it > 60_000 }
            if (timestamps.size >= SIGNAL_POST_RATE_LIMIT_PER_MIN) return false
            timestamps.add(now)
            return true
        }
    }

    /** True iff nodeId == SHA3-256(kemPub.toBytes() ‖ signPub.toBytes()) for these keys. */
    private fun nodeIdBindsToKeys(nodeIdHex: String, kemPubB64: String, ed25519B64: String, dilithiumB64: String): Boolean {
        if (kemPubB64.isEmpty() || dilithiumB64.isEmpty() || ed25519B64.isEmpty()) return false
        return try {
            val kemPub = HybridPublicKey.fromBytes(Base64.decode(kemPubB64, Base64.NO_WRAP))
            val signPub = HybridVerifyKey(Base64.decode(dilithiumB64, Base64.NO_WRAP), Base64.decode(ed25519B64, Base64.NO_WRAP))
            MessageDigest.isEqual(Hkdf.instance.sha3_256(kemPub.toBytes() + signPub.toBytes()), hexToBytes(nodeIdHex))
        } catch (e: Exception) {
            false
        }
    }

    private fun candidateJson(ip: String, port: Int, timestampMs: Long, sigB64: String) =
        JSONObject()
            .put("ip", ip)
            .put("port", port)
            .put("timestamp", timestampMs)
            .put("signature", sigB64)

    private fun randomPlausibleIp(): String {
        // RFC-1918-looking dummies so padding blends with emulator/LAN addresses.
        return when (rng.nextInt(3)) {
            0 -> "10.${rng.nextInt(256)}.${rng.nextInt(256)}.${1 + rng.nextInt(254)}"
            1 -> "192.168.${rng.nextInt(256)}.${1 + rng.nextInt(254)}"
            else -> "172.${16 + rng.nextInt(16)}.${rng.nextInt(256)}.${1 + rng.nextInt(254)}"
        }
    }

    private fun readBody(session: IHTTPSession): String? {
        val length = session.headers["content-length"]?.toIntOrNull() ?: return null
        if (length <= 0 || length > MAX_BODY_BYTES) return null
        val buf = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = session.inputStream.read(buf, read, length - read)
            if (n == -1) break
            read += n
        }
        return String(buf, 0, read, Charsets.UTF_8)
    }

    private fun jsonResponse(status: Response.Status, json: JSONObject): Response =
        newFixedLengthResponse(status, "application/json", json.toString())

    private fun jsonError(status: Response.Status, message: String): Response =
        jsonResponse(status, JSONObject().put("error", message))

    companion object {
        const val DEFAULT_PORT = 8080
        /** AGPL-3.0 §13 source pointer (update to your fork's repo if you modify Aurora). */
        const val SOURCE_URL = "https://github.com/someamount2190/AuroraMessenger"
        const val TTL_MS = 15 * 60 * 1000L          // 15-minute registration TTL (Privacy Policy §3.2)
        const val CHECKIN_FRESHNESS_MS = 5 * 60 * 1000L
        const val CHECKIN_RATE_LIMIT_MS = 60 * 1000L
        const val FIND_RATE_LIMIT_PER_MIN = 10
        const val SIGNAL_POST_RATE_LIMIT_PER_MIN = 30
        const val MAX_QUEUE_LEN = 64
        const val CANDIDATE_COUNT = 10
        /** Cap on one-time prekeys retained per node (bounds memory). */
        const val PREKEY_OPK_MAX = 100
        // Hybrid check-in signature size: [4B len][Dilithium-3 sig 3293][Ed25519 sig 64].
        private const val HYBRID_SIG_BYTES = 4 + 3293 + 64
        private const val MAX_BODY_BYTES = 64 * 1024
        private const val MAX_SIGNAL_BYTES = 32 * 1024

        /** Canonical byte string both client and server sign/verify for check-ins. */
        fun checkinMessage(nodeIdHex: String, ip: String, port: Int, timestampMs: Long): ByteArray =
            "aura-checkin-v1|$nodeIdHex|$ip|$port|$timestampMs".toByteArray(Charsets.UTF_8)

        /** Canonical byte string signed to authenticate a signal-queue drain. */
        fun drainMessage(nodeIdHex: String, timestampMs: Long): ByteArray =
            "aura-drain-v1|$nodeIdHex|$timestampMs".toByteArray(Charsets.UTF_8)
    }
}
