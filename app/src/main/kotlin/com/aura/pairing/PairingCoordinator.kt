package com.aura.pairing

import kotlinx.coroutines.flow.SharedFlow
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Recognition-then-verify pairing.
 *
 * A scan no longer auto-creates a usable contact, and there is no read-aloud code.
 * The flow is:
 *  1. **Scan** — the scanner encapsulates against the QR's KEM key (authentic, read
 *     in person) → shared secret S, stores a `REQUESTED` contact holding S, and posts
 *     a signed `pairreq` (keys + KEM ciphertext) to the peer's queue. On the scanner's
 *     home screen the contact shows "Awaiting handshake" with a Cancel button; it
 *     can't be opened yet.
 *  2. **Accept / Reject** — the requested phone gets a notification, then sees the
 *     contact with Accept / Reject. Reject (`pairreject`) or a scanner Cancel
 *     (`paircancel`) deletes the contact on both sides. Accept seeds the ratchet from
 *     S and sends `pairaccept` (carrying the accepter's Dilithium key); the scanner
 *     seeds the same root on receipt. Both contacts move to `VERIFY`.
 *  3. **Mutual verify** — opening a `VERIFY` contact shows the code screen: each phone
 *     displays its own 6-digit SAS code (derived locally from the shared root, never
 *     sent) and must type the other's, read off their screen. A correct entry sends a
 *     signed `pairverify`; once both sides have entered the other's code, both flip to
 *     `ACTIVE` and the chat opens with a success toast. A man-in-the-middle derives a
 *     different root, so the codes won't match and entry fails.
 *
 * This class is a thin **facade** over the three role collaborators that implement the
 * flow — [ScannerPairing], [ReceiverPairing], [VerifyPairing] — all sharing the
 * [PairingEvents] bus it re-exposes. The public surface and behaviour are unchanged;
 * callers (SyncEngine, the QR/home/conversation screens) talk only to this facade.
 */
@Singleton
class PairingCoordinator @Inject constructor(
    private val bus: PairingEvents,
    private val scanner: ScannerPairing,
    private val receiver: ReceiverPairing,
    private val verify: VerifyPairing
) {
    /** Emits a contact nodeId to navigate to (open the verify screen, then the chat). */
    val incomingPaired: SharedFlow<String> = bus.incomingPaired

    /** One-shot pairing outcomes for toasts. */
    val events: SharedFlow<PairEvent> = bus.events

    // ── Scanner side ──────────────────────────────────────────────────────────
    suspend fun pairFromQr(qrContent: String): Result<Unit> = scanner.pairFromQr(qrContent)
    suspend fun cancelOutgoing(contactNodeIdHex: String): Result<Unit> = scanner.cancelOutgoing(contactNodeIdHex)
    suspend fun handlePairAccept(json: JSONObject): Result<Unit> = scanner.handlePairAccept(json)
    suspend fun handlePairReject(json: JSONObject): Result<Unit> = scanner.handlePairReject(json)

    // ── Receiver side ───────────────────────────────────────────────────────────
    suspend fun handlePairRequest(json: JSONObject): Result<Unit> = receiver.handlePairRequest(json)
    suspend fun acceptIncoming(contactNodeIdHex: String): Result<Unit> = receiver.acceptIncoming(contactNodeIdHex)
    suspend fun rejectIncoming(contactNodeIdHex: String, block: Boolean): Result<Unit> = receiver.rejectIncoming(contactNodeIdHex, block)
    suspend fun handlePairCancel(json: JSONObject): Result<Unit> = receiver.handlePairCancel(json)
    suspend fun deleteContact(contactNodeIdHex: String): Result<Unit> = receiver.deleteContact(contactNodeIdHex)
    suspend fun handleContactRemove(json: JSONObject): Result<Unit> = receiver.handleContactRemove(json)

    // ── Mutual verification (both sides) ───────────────────────────────────────
    suspend fun myVerifyCode(contactNodeIdHex: String): String? = verify.myVerifyCode(contactNodeIdHex)
    suspend fun submitVerifyCode(contactNodeIdHex: String, code: String): Result<Boolean> = verify.submitVerifyCode(contactNodeIdHex, code)
    suspend fun handlePairVerify(json: JSONObject): Result<Unit> = verify.handlePairVerify(json)

    /** Interactive pairing has no half-finished background sends to retry. */
    suspend fun retryPendingPairingSends() { /* no-op */ }
}
