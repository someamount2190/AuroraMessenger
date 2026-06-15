# Aurora Messenger

**A post-quantum, end-to-end encrypted messenger for Android.** No phone number, no
account, no server that can read your messages. You pair by scanning a QR code, then
confirm each other with a short verification code; messages and calls flow directly
device-to-device.

Aurora is built so that the confidentiality of a conversation does not depend on
trusting any server, on the long-term secrecy of any single key, or on the assumption
that today's public-key cryptography will remain unbroken.

> **Status:** mature work-in-progress. The cryptographic core, pairing, messaging,
> media, calls, hardening, and infrastructure are implemented and tested across
> emulators. Some end-to-end paths (live two-device calls and handshakes) require
> physical devices to fully validate, owing to emulator networking constraints
> (shared ICE candidates, QR camera) rather than implementation gaps.

## Why it's different

- **Hybrid post-quantum cryptography.** Every key exchange combines **Kyber-1024**
  with **X25519**; every signature combines **Dilithium-3 (ML-DSA)** with **Ed25519**.
  An attacker must break *both* the post-quantum and the classical primitive, so
  Aurora resists a future quantum adversary without giving up today's well-audited
  classical guarantees.
- **Forward secrecy, end to end.** Each conversation runs through a per-contact
  symmetric double-ratchet: every message draws a one-time key that is immediately
  discarded, and the pairing root secret is destroyed after seeding. A key recovered
  from a device today cannot decrypt past traffic.
- **Forward-secret handshake (PQXDH).** Pairing uses a post-quantum X3DH-style
  handshake with server-published, signed prekey bundles: a long-term identity key,
  a rotating signed prekey, and single-use one-time prekeys. Because the ephemeral
  prekeys are destroyed after use, an attacker who records a handshake and *later*
  compromises the long-term identity key still cannot reconstruct the session. This
  closes the "harvest-now, decrypt-later" key-compromise gap while staying fully
  post-quantum.
- **Trust on first use, then mutual verification.** Contacts pair by scanning a QR
  code bound to the device's key identity. It is strongest when scanned in person, but it
  can also be shared and scanned from an image. Pairing then requires an explicit
  accept/reject step and a **mutual short-authentication-string (SAS)** check: each
  device shows a 6-digit code derived from the shared session, and you each enter the
  other's. A man-in-the-middle ends up with a different session on each side, so the
  codes won't match and pairing fails, which is what makes sharing the QR over an
  untrusted channel safe. Verification codes are derived locally and never transmitted.
- **Near-zero-knowledge networking.** A lightweight rendezvous server only maps a
  short-lived `nodeId → address` so peers can find each other. It holds no message
  content, keeps no activity logs, exists only in memory, and returns decoy
  candidates alongside the real address. Messages and calls then flow peer-to-peer.

## Features

- Text and media messaging (photos/video, app-private encrypted storage)
- Voice and video calls (WebRTC) with real incoming-call ringing and lock-screen handling;
  an in-app ongoing-call bar and a floating system overlay keep a live call visible (and
  one tap away) while you use the rest of the phone, and a second call can't start while one is active
- Message replies, reactions, and disappearing messages (per-conversation timers)
- App lock with a decoy PIN, optional **duress wipe**, and screenshot/recents protection (`FLAG_SECURE`)
- **Cryptographic erase**: destroying keys renders all on-disk ciphertext unrecoverable instantly
- Opt-in, passphrase-protected **encrypted backups** (Argon2id + XChaCha20-Poly1305)
- One-scan host onboarding (see below)

## How pairing works

A scan no longer silently creates a usable contact. The flow is **recognition →
verification**:

1. **Scan**: you scan a contact's QR. Your app fetches their signed prekey bundle and
   runs the forward-secret handshake; the contact appears as *awaiting handshake*.
2. **Accept / Reject**: the other device shows an incoming request to accept or reject.
3. **Mutual verify**: each device displays its own 6-digit SAS code; you each read and
   enter the other's. A man-in-the-middle derives a different root, so the codes won't
   match and pairing fails closed.
4. **Active**: once both are verified, the chat opens.

Identity is `nodeId = SHA3-256(kemPublicKey ‖ signingPublicKey)`, so a node cannot be
impersonated by substituting keys.

### One-scan host onboarding

Add Contact → **Host & show my code** turns the initiator into the rendezvous beacon:
it embeds an ordered list of reachable rendezvous URLs in the QR. The other person
scans once: their app probes the candidates, adopts the first that responds, and
pairs with no manual server setup. A reachability banner tells the host how far
they're reachable (Same Wi-Fi instantly; Internet only when the router maps a port,
which the app attempts via NAT-PMP automatically, falling back to a hosted rendezvous
server on carrier-grade NAT).

## Networking model

- **Transport:** direct TCP between peers (XChaCha20-Poly1305 sealed frames), with an
  offline queue and an optional opt-in single-hop relay.
- **Rendezvous server:** in-memory only, **15-minute TTL**, never logs payloads.
  Check-ins are hybrid-signed; `/find` always returns exactly 10 candidates
  (real + dummies, shuffled) and only the real one verifies against the target's key;
  queue drains and the wake long-poll are authenticated so only the key holder can read
  their own signals. Endpoints: `/checkin`, `/find/:nodeId`, `/signal/:nodeId`,
  `/tap/:nodeId` + `/wait/:nodeId` (contentless wake/push), `/prekeys/:nodeId` (signed
  PQXDH bundles), `/source` (AGPL source pointer).
- **Production server:** the hosted rendezvous service runs behind **TLS with
  certificate pinning** in the app, so interception is blocked even against a rogue
  certificate authority. A dependency-free Node.js implementation lives in
  `rendezvous-server/`; an embeddable in-app server (`com.aura.server`) is used for
  local-network / host mode.

## Project layout

| Path | Contents |
|---|---|
| `app/` | Android app (Kotlin, Jetpack Compose, Hilt, Room) |
| `crypto/` | `com.aura:aura-crypto` — standalone, Android-free post-quantum crypto core (hybrid KEM/signatures, HKDF, ratchet, prekeys) |
| `app/src/main/kotlin/com/aura/server` | In-app rendezvous server (NanoHTTPD) |
| `rendezvous-server/` | Standalone Node.js rendezvous server (zero runtime deps) |
| `libs/maven/` | In-repo Maven repository: vendored `liboqs-java` + the published `aura-crypto` artifact |
| `app/schemas/` | Room schema exports (migration baseline, committed) |

## Build & run

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat assembleDebug
```

> **Standalone build.** Everything the build needs is vendored in-repo under `libs/maven`
> (an in-repo Maven repository), so a fresh clone builds on its own — no external sibling
> folders required. That repo holds the JitPack-only `liboqs-java` JNI wrapper and Aurora's
> own `com.aura:aura-crypto` artifact. If you change anything under `crypto/`, re-publish it
> into `libs/maven` before rebuilding the app:
>
> ```powershell
> .\gradlew.bat -p crypto publish   # republishes com.aura:aura-crypto into libs/maven
> ```

## Security architecture (summary)

- **`com.aura.crypto`** (the standalone `crypto/` module): `HybridKem` (Kyber-1024 +
  X25519), `HybridSigner` (Dilithium-3 + Ed25519), `SymmetricCipher` (XChaCha20-Poly1305),
  HKDF-SHA3-256, `RatchetManager` (forward-secret double-ratchet), `PrekeyManager`
  (PQXDH prekeys). It has no Android dependency and persists through storage interfaces
  the host app implements.
- **`com.aura.identity`**: node identity generated on first launch, stored in
  EncryptedSharedPreferences (master key in the Android Keystore; post-quantum keys are
  too large to live in the Keystore directly).
- **Storage**: SQLCipher-encrypted database; secure deletion cascades and a full
  cryptographic-erase path (`SecureWipe`).
- **QR payload**: compact JSON `{v, nodeId, kem, ed25519, pk, rdv?}` encoded directly
  (byte mode, EC level L); bulky prekeys are fetched from the rendezvous server rather
  than carried in the QR, which would overflow its capacity past the Kyber key.

## Design notes & background

Aurora didn't start from scratch. It was carved out of an earlier, much larger
project (**ShadowMesh**, a post-quantum *mesh* messaging stack), and most of
Aurora's architecture, especially the cryptographic core (`com.aura.crypto`: the
hybrid KEM and signatures, HKDF, the ratchet), is ported from it rather than
reinvented.

The deliberate decision was one of **scope**. Rather than try to ship the full
mesh, I set ShadowMesh aside for now and took its proven, working parts to build
something smaller and finishable: a direct, two-person, post-quantum encrypted
messenger. A tight scope is what makes Aurora something that can actually reach
people's hands, while still standing on a serious cryptographic foundation.

ShadowMesh itself is **paused, not abandoned.** I haven't yet decided whether it
continues as a standalone project or folds in as the **mesh/relay backend for
Aurora**. The optional ShadowMesh relay already wired into Aurora is the seam where
that would happen. Either way, Aurora is the near-term focus, and it is expected to
**grow well beyond its current scope** over time; today's two-person messenger is a
foundation, not the finished shape.

That crypto core has since been extracted into its own standalone, Android-free module
(`crypto/`, published as `com.aura:aura-crypto`) and vendored in-repo under `libs/maven`,
so the build no longer depends on the ShadowMesh sibling folder — a fresh clone builds on
its own.

### Key design decisions

*Cryptography & protocol*

- **Hybrid post-quantum + classical throughout** (Kyber-1024 + X25519; Dilithium-3 +
  Ed25519). An attacker must break both a quantum-era and a classical algorithm, and it
  hedges against an undiscovered flaw in either. *Cost:* larger keys/signatures, which
  forced the prekey decision below.
- **Forward secrecy comes from ephemerality, not algorithm strength.** Post-quantum
  strength alone doesn't stop "record the handshake now, steal the long-term key later":
  the attacker holds the real key, so nothing is being broken. Only destroying an
  ephemeral one-time prekey makes recorded traffic unrecoverable.
- **Prekeys are server-hosted bundles, not carried in the QR.** The QR is already near
  capacity with the Kyber identity key; this is also how real X3DH works. With no bundle
  available, pairing falls back to the legacy identity-only handshake (weaker, still works).
- **Recognition-then-verify pairing with a mutual SAS**, not a read-aloud passkey. The
  SAS is what makes a QR shared over an untrusted channel safe: a MITM derives different
  roots on each side, so the codes mismatch. Codes are computed locally, never transmitted.
- **Identity is `nodeId = SHA3-256(kemPub ‖ signPub)`.** The address is a commitment to
  the keys, so it can't be claimed with substitute keys, and the server can enforce the
  binding. *Cost:* keys can't rotate without changing identity.
- **Symmetric double-ratchet now; continuous DH/KEM ratchet deferred.** Cheap per-message
  forward secrecy, and PQXDH closes the handshake gap. *Honest cost:* no post-compromise
  "healing" yet; a session still hinges on one root. That ratchet is the next phase.

*Architecture & infrastructure*

- **Direct P2P transport with a near-zero-knowledge rendezvous server.** It only maps
  `nodeId → IP` (15-min TTL, no logs) and returns nine decoys per lookup; messages never
  pass through it. *Cost:* it learns reachability (below).
- **Contentless wake (`/tap` + `/wait` long-poll) instead of FCM/third-party push.** No
  push-provider dependency and no content leaves the device. *Cost:* an always-on
  connection lets the server tell a device is online, which is disclosed in the privacy
  policy rather than denied.
- **Keys in EncryptedSharedPreferences under a Keystore-held master key.** The
  post-quantum keys are too large for the Keystore TEE directly; this is the pragmatic
  hardware-backed compromise.
- **Cryptographic erase over file scrubbing.** Wiping destroys the keys, not the bytes:
  flash wear-leveling makes scrubbing unreliable, and keyless ciphertext is noise
  instantly. Pairs with the decoy PIN and duress wipe.
- **TLS certificate pinning on the rendezvous server**, with a backup pin and a key kept
  stable across renewals, so interception fails even against a rogue CA.
- **DB v6 is the migration anchor** (exported schema + AutoMigration, no blanket
  destructive fallback) so app updates preserve real users' data.

## License

Aurora Messenger is licensed under the **GNU Affero General Public License v3.0**
(`AGPL-3.0-or-later`); see [`LICENSE`](LICENSE). The AGPL is used deliberately: if you
run a modified version of Aurora as a network service (e.g. the rendezvous server), you
must make your modified source available to its users (AGPL §13). Each server exposes a
`/source` endpoint pointing at its Corresponding Source for this reason.

Third-party components and their licenses are listed in
[`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md). Cryptographic primitives are ported
from the ShadowMesh project.

*This software is provided without warranty. It has not undergone an independent
third-party security audit; do not rely on it for high-risk threat models until it has.*

## Author

Built by **Christian Lim Correa**, a Filipino developer. Questions and security reports
are welcome at christiancorrea26@gmail.com.
