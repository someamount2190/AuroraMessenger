# Cryptography Audit — AuroraMessenger

**Scope:** the `crypto/` core (`com.aura.crypto`), pairing/handshake (`app/.../pairing`),
message/call/media transport sealing (`app/.../transport`, `app/.../call`, `app/.../media`),
key storage (`app/.../identity`, backups), and both rendezvous servers
(`com.aura.server.AuroraRendezvousServer`, `rendezvous-server/index.js`).

**Method:** manual source review against the stated design in `docs/CRYPTO_SPEC.md`,
`docs/THREAT_MODEL.md`, and `docs/AUDIT_SCOPE.md`. No dynamic testing or fuzzing.

**Reviewer note on the existing posture:** the project already documents its two biggest
limitations honestly — no post-compromise security and a legacy non-forward-secret
downgrade path. Those are restated below as known/accepted, and this audit focuses on what
the docs do *not* already call out.

---

## Summary

The cryptographic core is well-constructed: a genuine hybrid post-quantum design (Kyber-1024
+ X25519 KEM, Dilithium-3 + Ed25519 signatures, both halves required), correct
XChaCha20-Poly1305 AEAD with random 192-bit nonces, transcript-bound PQXDH-style pairing
with a node-id↔key commitment, a forward-secret symmetric ratchet, and consistent domain
separation. End-to-end sealing is applied where it matters, including WebRTC call signaling
(so DTLS fingerprints in the SDP are exchanged under the verified ratchet rather than via
the untrusted server).

No critical or high-severity cryptographic flaws were found. The findings below are
medium/low hardening items, the most material being the rendezvous **drain/publish
authentication token** (replayable, not bound to action or body) and the **production Node
server defaulting `STRICT_BINDING` off**.

| # | Severity | Area | Finding |
|---|----------|------|---------|
| 1 | Medium | Rendezvous auth | Drain/publish token (`X-Drain-Sig`) not bound to action or body; no replay cache → replay within the 5-min window can drain a queue or roll back a prekey bundle |
| 2 | Medium | Rendezvous (prod) | `rendezvous-server/index.js` defaults `STRICT_BINDING=off`, so nodeId↔key binding is not enforced for clients omitting the KEM key → nodeId squatting / reachability DoS |
| 3 | Low | Transport AEAD | Frame metadata (`id`, `ts`, ratchet `n`) is outside the AEAD AAD and mutable by an on-path relay |
| 4 | Low | SAS | ~20-bit SAS leans entirely on the user actually comparing it; acceptable but load-bearing |
| 5 | Info | Ratchet | No post-compromise security (already a documented non-goal) — restated with impact |
| 6 | Info | Media at rest | Stale `EncryptedMediaStore` comment + dead `writeSealed`/`readSealed`; contradicts the live re-encryption path |
| 7 | Info | Backups | Argon2id parameters are on the low side for long-term identity-key protection |
| 8 | Info | KEM / KDF | Kyber implicit rejection and HMAC-over-SHA3 noted for completeness; both sound as used |

---

## Findings

### 1. Rendezvous drain/publish token is replayable and unbound (Medium)

`getSignals`, `waitForWake`, and `publishPrekeys` all authenticate with the **same** signed
string, `drainMessage(nodeId, ts)` = `"aura-drain-v1|<nodeId>|<ts>"`, carried in
`X-Drain-Ts/Pub/Sig` headers (`RendezvousClient.kt:213-365`). Server-side `verifyDrain`
(`AuroraRendezvousServer.kt:256`, `index.js:117`) checks only that `ts` is within
`CHECKIN_FRESHNESS_MS` (5 minutes), that the pub matches the registered key, and that the
Ed25519 signature is valid. There is **no per-token nonce/replay cache** (confirmed: the only
"replay" mention server-side is the check-in timestamp window).

Consequences if a single valid token is observed (trivial on the cleartext LAN/Server-Mode
path; on the hosted server it would require defeating TLS + pinning):

- **Signal-queue theft/denial:** replay the token against `GET /signal/{nodeId}` to drain
  the victim's pairing/call signals (they are deleted on read), starving the victim of
  incoming pairing/call setup.
- **Prekey rollback:** the token is *not* bound to the request body, so it can be replayed
  against `POST /prekeys/{nodeId}` with an attacker-chosen body. Each prekey inside still
  carries its own Ed25519 signature, so forgery isn't possible — but an attacker can
  **re-publish an older, validly-signed bundle**, re-enabling already-consumed one-time
  prekeys. That undermines the one-time-prekey guarantee that finding-its-way into the FS
  story (`PrekeyManager` deletes OPKs on consume specifically to get FS); a rolled-back
  bundle lets the same OPK be handed out again.

**Recommendation:** bind the token to the action and (for publish) a hash of the body, e.g.
`"aura-drain-v1|<method>|<path>|<sha3(body)>|<nodeId>|<ts>"`, and keep a short-lived
server-side set of seen `(nodeId, ts, sig)` tuples to reject replays inside the freshness
window. Tighten the freshness window from 5 min toward ~30–60 s for these idempotent calls.

### 2. Production Node server defaults to non-strict nodeId binding (Medium)

The in-app Kotlin server enforces the binding unconditionally — it rejects a check-in unless
`nodeId == SHA3-256(kemPub ‖ signPub)` and requires all three keys
(`AuroraRendezvousServer.kt:144`, `nodeIdBindsToKeys` returns false on any missing key). The
hosted Node server only checks the binding **when `kemPub` and `dilithiumPub` are present**,
and otherwise requires it only if `STRICT_BINDING=1`, which **defaults to off**
(`index.js:59,264-270`).

So against the production server a client can check in with an arbitrary `nodeId` (omitting
the KEM key) as long as it provides *some* Ed25519 key and a matching self-signature. That
permits **nodeId squatting**: registering a victim's nodeId pointing at a bogus IP. Delivery
to that victim then resolves to the squatted record; `verifyCandidates` on the peer rejects
it (the signature won't verify against the victim's pairing-time Ed25519 key), so no
misdelivery or MITM occurs — but the legitimate record is crowded out, yielding a
**reachability denial** that the strict in-app server does not have. Availability is an
explicit non-goal, but the binding is a *stated control* ("a rogue can't register/overwrite
someone else's nodeId").

**Recommendation:** ship the production server with `STRICT_BINDING=1` now that the current
app always sends `kemPub`+`dilithiumPub`, matching the in-app server.

### 3. Frame metadata outside the AEAD AAD (Low)

The message/control/call/media AADs bind the directed pair only:
`"aura-msg-v1|<from>|<to>"`, etc. (`MessageSender.kt:140`, `TcpMessageServer.kt:156,190`,
`CallSignalCodec.kt:77`, `MediaTransfer.kt:226`). The frame's `id`, `ts`, and the ratchet
counter `n` ride in cleartext and are not authenticated.

Integrity of the *content* is intact (the ciphertext+tag is bound to the right key/chain, and
a wrong `n` simply selects a key that fails to authenticate, leaving the chain unadvanced —
verified in `RatchetManager.open`). The residual exposure is metadata: an on-path relay
(only in the opt-in ShadowMesh relay path) can rewrite a delivered message's `id` (breaking
the sender's delivery-receipt match) or `ts` (cosmetic timestamp skew). No
duplication/replay is possible because dedup-by-id plus one-time message keys cover both the
head-of-chain and skipped-key cases.

**Recommendation:** fold `id`, `ts`, and `n` into the AAD (they are already known to both
ends) so any metadata tampering fails the tag.

### 4. ~20-bit SAS depends on the user comparing it (Low / by-design)

`sasCodeFor` derives a 6-digit (~20-bit, ~1-in-1,048,576) code per identity
(`RatchetManager.sasCodeFor`), constant-time compared (`PairingCrypto.sasEquals`) and
attempt-limited to 5 (`PairingCoordinator.MAX_VERIFY_ATTEMPTS`). Because the KEM key is read
from the in-person QR, the SAS is defence-in-depth rather than the primary MITM barrier, and
the attempt limit makes online grinding infeasible. This is consistent with common SAS sizes
and is fine **given the documented assumption that users actually perform the comparison** —
which is load-bearing and worth surfacing in onboarding UX, not just the threat model.

### 5. No post-compromise security (Informational / documented non-goal)

The ratchet is a symmetric hash ratchet seeded once from the pairing root, which is then
zeroed (`RatchetManager.seedFromSharedSecret`). This gives real forward secrecy (old chain
keys are overwritten on advance), but **no healing**: compromise of a current chain key
exposes all *future* messages on that chain indefinitely, since there is no continuing
DH/KEM re-keying. This is called out in `AUDIT_SCOPE.md` and `CRYPTO_SPEC.md §6` as a planned
next phase; restating because it is the single largest gap versus a Signal-style Double
Ratchet and should stay prominent until addressed.

### 6. Stale media-at-rest comment and dead code (Informational)

`EncryptedMediaStore` documents "both peers share the key so the same ciphertext is stored on
disk AND sent on the wire (no re-encryption)" (`EncryptedMediaStore.kt:77-79`). The live path
contradicts this: `MediaTransfer` seals for the wire with a **fresh ratchet key**
(`sealNext`/`open`, `MediaTransfer.kt:129,189`) and re-encrypts at rest under a **local-only,
per-device random** media key (`RatchetManager.seedFromSharedSecret` generates it locally;
each device's key differs and never travels — which is correct and gives media the same FS as
text). `writeSealed`/`readSealed` are referenced only by tests. The comment is misleading for
an auditor and the methods are dead.

**Recommendation:** correct the comment to describe the re-encryption model and remove
`writeSealed`/`readSealed` (or mark them test-only).

### 7. Backup Argon2id parameters (Informational)

`deriveKey` uses Argon2id v1.3 with t=3, m=64 MiB, p=2 (`Backups.kt:213-216`). Reasonable for
a one-shot derivation on a phone, but these backups contain the long-term **identity private
keys** (Kyber/Dilithium/X25519/Ed25519) and all ratchet state, so an exfiltrated backup is a
high-value offline target whose only protection is the passphrase. Consider raising memory
where device RAM allows and, more importantly, enforcing/encouraging a strong passphrase
(length/entropy meter) since that is the true bound. Also confirm `passphrase: CharArray` is
zeroed by callers after `export`/`import` (the key bytes are filled, the passphrase array is
not).

### 8. KEM implicit rejection & HMAC-over-SHA3 (Informational)

- **Kyber implicit rejection:** a malformed/substituted Kyber ciphertext yields a
  pseudo-random shared secret rather than an explicit failure (FO transform). Correctness
  therefore relies on the downstream transcript binding (`fsRoot`/`legacyRoot` fold every
  ciphertext's SHA3 into the HKDF info) and the SAS to detect a mismatch — both present. Sound
  as designed; just noting the failure mode isn't local to `decapsulate`.
- **HKDF over HMAC-SHA3-256** (`Hkdf.kt`) is a non-standard but internally consistent
  construction (block size 136 = SHA3-256 rate, correct). Interop is only ever with Aurora
  itself, so this is fine; flagged only because "HKDF" usually implies SHA-2 and a reviewer
  should not assume RFC 5869 test vectors apply.

---

## Things checked and found sound

- **XChaCha20-Poly1305**: manual HChaCha20 subkey derivation and the 12-byte
  `00000000‖nonce[16:24]` IETF construction match libsodium; 24-byte random nonce makes
  collision negligible and each ratchet key is one-time regardless. AAD bound into the tag,
  not transmitted. Subkeys zeroed in `finally`.
- **Hybrid KEM combine**: `HKDF(kyberSS ‖ x25519SS, salt=SHA3(recipientKyberPub),
  info=label‖kyberCT‖x25519EphPub)` — binds both ciphertexts, breaking either scheme alone is
  insufficient. X25519 all-zero/low-order output is rejected (`HybridKem.x25519ScalarMult`).
- **Hybrid signatures**: both Dilithium-3 and Ed25519 must verify; length-prefix
  deserialization is strict (exact Dilithium-3 size, exact total length).
- **Identity binding**: `nodeId = SHA3-256(kemPub ‖ signPub)` enforced on
  `NodePublicIdentity.fromBytes`, in `PairingCrypto.nodeIdMatches`, and server-side; overflow-
  safe length checks on parse.
- **Pairing root**: transcript- and identity-bound (`fsRoot`), salted with
  `SHA3(responderKyberPub)`; one-time prekey deleted on consume; downgrade to legacy is warned
  (`WeakPairing`), not silent.
- **Ratchet auth-failure handling**: a forged frame does not advance the chain or persist
  skipped keys (commit only after successful decrypt); skip-ahead bounded (512) and skipped
  cache bounded (1024) — skip-flood guard.
- **Call signaling**: SDP/ICE (incl. DTLS fingerprints) sealed under the ratchet before
  hitting the rendezvous queue — the server never sees call crypto material in the clear.
- **Relay SSRF guard**: `TcpMessageServer.handleRelay` resolves once and refuses loopback /
  private / link-local / site-local / multicast, and relays only when ShadowMesh is opted in.
- **Wire/pre-auth limits**: 1 MiB frame cap, chunked media with reassembly ceiling, per-IP
  and total connection caps.
- **TLS pinning**: SPKI pin + intermediate backup pin configured for the production host.

---

## Recommended priority

1. Bind the rendezvous drain/publish token to action+body and add a replay cache (#1).
2. Enable `STRICT_BINDING=1` on the production rendezvous server (#2).
3. Add `id`/`ts`/`n` to the transport AAD (#3).
4. Fix the media-at-rest comment / remove dead code (#6); revisit Argon2id params + passphrase
   strength (#7).
5. Keep PCS (#5) tracked as the next ratchet milestone.
