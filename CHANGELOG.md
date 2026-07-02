# Changelog

All notable changes to **Aurora Messenger** are documented here. The format is based on
[Keep a Changelog](https://keepachangelog.com/), and the project uses a single pre-alpha
line (`0.2.1-pre`) while the design stabilises.

## [Unreleased]

Nothing yet.

## [0.3.0-pre] — 2026-07-02

The cryptographic core has been re-engineered end to end. **This release is a clean
break: 0.3.0 cannot message 0.2.x.** On first launch over an existing install the app
detects the legacy identity and resets identity, contacts and sessions to a clean
slate — you and your contacts must both update, then pair again.

### Changed
- **Pure-JVM FIPS-track crypto stack.** liboqs/JNI is fully removed; the core now runs on
  Bouncy Castle 1.84 + Google Tink. **X-Wing** (FIPS 203 **ML-KEM-768** + X25519) replaces
  Kyber-1024 for key agreement, **ML-DSA-65** (FIPS 204) replaces Dilithium-3 as the
  post-quantum signature half (still paired with Ed25519), XChaCha20-Poly1305 moves to
  Tink, and HKDF moves to the RFC 5869 HMAC-SHA-256 standard. No native code remains
  anywhere in the stack.
- **One post-quantum KEM Double Ratchet for all sealed traffic.** Messages, media and
  call/WebRTC signaling now run through an X-Wing KEM Double Ratchet with
  **post-compromise healing** — each direction change mixes fresh KEM entropy into the
  session, so a stolen session state stops decrypting once both sides ratchet. The old
  symmetric-only ratchet is retired. It is bespoke protocol crypto (per
  `docs/PQ_RATCHET_DESIGN.md`) and remains gated on dedicated external review.
- **Pairing fails closed without forward secrecy.** A verified PQXDH prekey bundle is now
  required; the legacy no-forward-secrecy fallback is removed, so a network attacker can
  no longer strip forward secrecy by suppressing prekeys.

### Fixed
- Audit and bug-hunt findings: decoy-PIN data isolation, a verify-wipe race, one-time
  prekey drain, a decoy-candidate leak, and rendezvous signal flush ordering.
- Dropped key material (root/chain/skipped message keys) is zeroized in the KEM ratchet.
- Legacy or malformed KEM ratchet blobs fail closed across the v9→v10 database migration.
- Backups now carry the KEM session (backup DATA_VERSION 2; v1 backups still import), and
  "delete all data" clears KEM sessions too.
- Tink duplicate-class dex failure resolved; `FLAG_SECURE` applies to release builds only.
- The rendezvous server's `/find` decoy padding matches the ML-DSA-65 signature size.

### Added
- **Independent-authority test vectors:** NIST **ACVP** KATs (ML-KEM-768
  keygen/encaps/decaps, ML-DSA-65 keygen), **Wycheproof** corpora (X25519, Ed25519,
  XChaCha20-Poly1305, ML-KEM-768, ML-DSA-65 verify), and RFC 8032 / 7748 / 5869 KATs —
  260 tests green across `crypto` + `app`, all pure-JVM/CI-runnable.
- End-to-end simulations: the real X-Wing PQXDH handshake and a two-peer
  pair→message flow driving the live KEM ratchet.
- GitHub Actions CI running the full suite (post-quantum crypto included) per push.

### Internal
- Nine oversized source files decomposed (conversation, home, settings, database,
  rendezvous server, pairing coordinator, call models, navigation); DI scopes tightened.
- Crypto documentation refreshed and reconciled with the shipped stack (`CRYPTO_SPEC`,
  `CRYPTO_MIGRATION_PLAN`, `PQ_RATCHET_DESIGN`, `CRYPTO_TEST_VECTORS`, `AUDIT_SCOPE`,
  threat model, key management, module README).

## [0.2.6-pre] — 2026-06-21

Photos, videos and voice messages now reach contacts across carrier networks.

### Fixed
- **Media now travels the peer-to-peer path.** Messages and calls moved onto the WebRTC
  data channel in 0.2.3 so they cross carrier NAT, but media (photos/videos/voice) was left
  on the older direct-TCP route, which can't reach a phone behind mobile-carrier NAT — so on
  mobile data it silently failed to send. Media now prefers the same NAT-traversing data
  channel and falls back to direct TCP only on a shared network.
- **A queued photo could be re-sent as a plain "📷 Photo" text.** The message retry loop no
  longer touches media rows (which carry their own chunked transfer), so an undelivered photo
  is retried as the actual image, not its caption.

### Added
- **Media is retried.** An unsent photo/video/voice message is now re-attempted automatically
  once a direct connection becomes possible, instead of being a one-shot send.

### Internal
- Media is streamed over the data channel as small reliable chunks with send-buffer
  backpressure and reassembled per message, sharing one transport-agnostic store-and-ack path
  with the TCP route.

## [0.2.5-pre] — 2026-06-19

Reliability fixes for connections, calls, and background battery use.

### Fixed
- **Calls.** An unanswered outgoing call stops ringing on its own instead of showing
  "Calling…" forever; a second incoming call while you're already on one no longer disrupts
  the call in progress; ending or declining a call now tears down cleanly.
- **Connection status.** The chat header distinguishes "couldn't connect directly — you may
  both be on mobile data" from "they're offline," and a stalled connection reports the real
  outcome instead of hanging on "Connecting…". A brief network blip no longer flashes a failure.
- **Battery.** With no network, Aurora waits quietly and resumes the instant connectivity
  returns, instead of repeatedly reaching for the server in the background.

## [0.2.4-pre] — 2026-06-19

Call-handling fix and UI polish.

### Fixed
- **Returning to an in-progress call.** Coming back via the app icon, the ongoing-call
  notification, or the floating bubble now reliably restores the call screen (previously it
  could land on the home screen with the call still running but out of view).

### Changed
- The chat-header connection status is a single tidy line; the full "couldn't connect
  directly" guidance shows as a banner above the conversation. Long contact names and the
  server-status line truncate cleanly instead of wrapping.

## [0.2.3-pre] — 2026-06-18

Peer-to-peer connections that traverse carrier NAT.

### Added
- **WebRTC data-channel transport.** Messages connect over a WebRTC data channel (ICE +
  self-hosted STUN, no relay) so two phones reach each other across mobile / carrier-NAT
  networks — IPv6 first, falling back to IPv4, with direct TCP as a same-network fast path.
  The chat header shows the live connection state and reports honestly when a direct path
  isn't possible.

### Infrastructure
- **Self-hosted STUN.** A coturn STUN-only service (never a relay) runs on the rendezvous
  host so devices can discover their public address for hole punching. It never carries
  message content.

## [0.2.2-pre] — 2026-06-17

An onboarding patch that makes sure a new install can actually receive messages.

### Added
- **Onboarding permission gate.** A new step at the end of first-run setup explains, in
  plain language, the permissions Aurora needs and why — and won't let you into the app
  until the two delivery-critical ones are granted:
  - **Notifications** — so you're told about a new message, an incoming call, or a contact
    request. Without it those arrive silently and are easily missed (this is what made a
    fresh install look like it "never received" a pairing handshake).
  - **Run in the background** (battery/Doze exemption) — so messages and calls reach you
    when Aurora is closed or the screen is off, and so the device publishes its prekey
    bundle for forward-secret pairing.

  **Microphone** (voice messages/calls) and **Camera** (QR scanning/video calls) are
  presented as optional and can be enabled now or on first use. **Photos & videos** is
  shown for transparency only — sharing uses Android's system picker, so there's no
  gallery permission to grant. The screen re-checks on return from system dialogs and
  routes to app settings if a permission was permanently denied.

### Infrastructure
- **Rendezvous `/health` endpoint** — a minimal, identifier-free liveness probe
  (status + uptime, CORS-open) powering the new public status page. The server's nginx
  access logging for the API host was turned off so it no longer retains any
  IP↔node↔timestamp history, matching the zero-log promise.

## [0.2.1-pre] — 2026-06-17

A pairing-reliability patch.

### Fixed
- **A host showing their QR now sees an incoming request.** Previously, if you opened
  "Show my code" and waited, a friend scanning your code seemed to do nothing: the request
  was received and stored, but it only appeared on the home list *behind* the QR screen,
  and the in-app notification is suppressed while the app is foreground — so the host saw
  nothing and pairing looked broken. The host is now pulled to the request (with a
  "Someone wants to connect" prompt) the moment it arrives. (The handshake itself, incl.
  one side backgrounding mid-pairing, was already working — it resumes via the rendezvous
  wake.)
- **The in-app version footer was hardcoded** to `0.1.0`; it now shows the real build
  version (`BuildConfig.VERSION_NAME`).

## [0.2.0-pre] — 2026-06-17

The second pre-alpha. It adds in-app video playback, keeps calls alive when you leave the
app, and lands a large internal architecture cleanup — on top of everything that had
accumulated since the first tag.

### Added
- **In-app video playback.** Sent and received videos now play inside the app: the
  conversation shows a real first-frame thumbnail with a play badge, and tapping opens a
  fullscreen player with transport controls — play/pause, a scrubbable seek bar, and
  elapsed/total time. Video is decrypted and played straight from memory (no plaintext
  file is written to disk) and stays inside the screenshot-blocked (`FLAG_SECURE`) window.
- **Minimise a call with Back.** A call keeps running when you leave its screen: the app
  shows a draggable floating remote-video bubble (tap to return) and an ongoing
  "call in progress" notification with an End control, instead of dropping the call or
  closing the app.
- **Delete a contact, on both sides.** A "Delete contact" action in the conversation menu
  cryptographically erases the contact locally (messages, media, ratchet keys) and signs
  a `contactremove` to the peer, whose copy is wiped too; the peer is told it lost the
  contact via a notification (when backgrounded) or a toast (when foreground).
- **Standalone, reproducible build.** Dependencies resolve from an in-repo local Maven
  repository (`libs/maven`) instead of an external sibling folder, so a fresh clone of
  this repository builds on its own. The post-quantum `liboqs-java` wrapper is bundled
  there.
- **`com.aura:aura-crypto` as its own module.** The whole `com.aura.crypto` package is now
  a self-contained, pure-JVM Kotlin library under `/crypto`, with no Android or database
  dependency: it persists through `PrekeyStore` / `RatchetStore` interfaces it defines, and
  the app supplies Room-backed adapters. Built and published into `libs/maven`
  (`./gradlew -p crypto publish`); the app depends on the published coordinate.

### Fixed
- **Calls dropped when you left the app.** Leaving a call (Back to the launcher, screen
  off) let the OS cut the microphone/camera and deprioritise the process under battery
  saver / Doze, silently disconnecting the call. While a call is live the keep-alive
  foreground service now asserts the `microphone` (and `camera`, for video) service type,
  so mic/camera and call-grade process priority survive backgrounding; it reverts to the
  low-priority keep-alive type when the call ends.
- **Calls crashed on connect.** A `CallStyle` ongoing-call notification was posted from
  inside a WebRTC observer callback running on the native signalling thread; the system
  rejected it, and the escaping Java exception aborted the whole process through WebRTC's
  JNI bridge. Observer work is now dispatched off the signalling thread, and the
  notification is plain and non-throwing.
- **Calls crashed on hang-up.** `endCall`/`cleanup` could fire from several paths at once
  (local End, remote bye, ICE-failed callback) and dispose the same `PeerConnection` twice
  ("Pure virtual function called!"). Disposal is now idempotent.
- **Accepted calls wouldn't connect.** Trickle-ICE candidates that arrived before the
  callee accepted were dropped; they are now buffered and replayed, so an accepted call
  reaches connected (and an accepted-but-unconnected call logs "ended", not "missed").
- **Video-renderer use-after-free** during call teardown (the renderer was released before
  its sink was removed) — disposal order corrected.
- **Video calls were unreliable until the app had been restarted several times.** Camera
  and microphone permissions were requested *after* the call screen had already opened the
  devices, so an early call came up with a dead/black camera — and camera-open errors were
  silently swallowed. Permissions are now requested *before* the call starts at every
  in-app entry point (outgoing call and incoming accept), and camera errors are logged.
  A fresh install now does a working video call on the first try.
- The empty-chat hint wrongly told users to tap the name to rename; it now points at the
  conversation menu.

### Changed
- **Internal architecture cleanup (behaviour-preserving).** Renamed the `*Manager` god/
  coordinator classes to role-based names (`CallController`, `PairingCoordinator`,
  `IdentityStore`, `AppLock`, …), split the call and pairing managers and the Home/
  Conversation/Settings screens into focused collaborators and view-models, stopped
  shadowing the platform `MediaStore` (now `EncryptedMediaStore`), and enforced Kotlin
  conventions (constructor-injected coroutine dispatchers, no swallowed cancellation, no
  `!!`, imports over fully-qualified references).
- **Test suite.** Added JVM/Robolectric unit tests for the crypto core and app layer plus
  native instrumented crypto/attack tests, with `docs/TEST_ARCHITECTURE.md` and
  `docs/TEST_STATUS.md`.
- Video calls use software VP8/VP9 codecs for cross-device portability. (The native
  post-quantum / Bouncy Castle stack is unchanged.)
- `android.util.Base64` in the crypto core was replaced with `java.util.Base64` as part of
  making the module platform-independent; it is byte-compatible, so existing stored
  ratchet/prekey state remains readable.

### Removed
- The external `../shadowmesh_v22_fixed/libs/maven` build dependency (replaced by the
  in-repo `libs/maven`).

## [0.1.0-pre] — 2026-06-14

First public pre-alpha (Android, sideload). Source published to GitHub under AGPL-3.0,
with a signed APK attached as a release asset.

### Messaging & calls
- Direct **peer-to-peer** encrypted messaging over TCP — the rendezvous server only helps
  two devices find each other's address and never sees message content.
- Photos, videos, and voice messages (encrypted at rest); message reactions; replies.
- **Disappearing messages** with a per-contact timer (off / 1 hour / 24 hours / 7 days).
- **Voice and video calls** over WebRTC, peer-to-peer and end-to-end encrypted; no call
  logs, no recording.

### Pairing & identity
- No account and no phone number — your identity is a key pair generated on-device.
- **QR pairing with mutual SAS verification**: scan, accept or reject, then each side
  confirms the 6-digit code shown on the other's screen, defeating a man-in-the-middle.
- "Host & show my code" one-scan onboarding that embeds reachable rendezvous addresses.

### Cryptography
- Hybrid **post-quantum** primitives: Kyber-1024 + X25519 (KEM), Dilithium-3 + Ed25519
  (signatures), XChaCha20-Poly1305 (AEAD), HKDF-SHA3-256 — an attacker must break both a
  classical and a quantum-era algorithm.
- **Forward secrecy** via a per-contact double symmetric ratchet seeded at pairing; the
  root secret is destroyed afterward, so a key seized today cannot decrypt past traffic.
- **PQXDH handshake**: server-hosted signed prekey bundles (a signed prekey plus one-time
  prekeys) give the pairing handshake post-quantum forward secrecy.

### Security & privacy
- SQLCipher-encrypted local database; identity and database keys held in the
  hardware-backed Android Keystore.
- `FLAG_SECURE` (no screenshots or screen recording), app lock, decoy PIN, and
  wipe-on-duress.
- Zero-log rendezvous server with 15-minute auto-expiring node-to-address records.
- Optional **ShadowMesh** opt-in relay for extra metadata privacy (off by default).

### Infrastructure
- Dependency-free Node.js rendezvous server (`rendezvous-server/`), deployed to a
  DigitalOcean droplet behind Nginx with TLS (Let's Encrypt) and **certificate pinning**
  at `api.auroramessenger.com`.

[Unreleased]: https://github.com/someamount2190/AuroraMessenger/compare/v0.2.2-pre...HEAD
[0.2.2-pre]: https://github.com/someamount2190/AuroraMessenger/compare/v0.2.1-pre...v0.2.2-pre
[0.2.1-pre]: https://github.com/someamount2190/AuroraMessenger/compare/v0.2.0-pre...v0.2.1-pre
[0.2.0-pre]: https://github.com/someamount2190/AuroraMessenger/compare/v0.1.0-pre...v0.2.0-pre
[0.1.0-pre]: https://github.com/someamount2190/AuroraMessenger/releases/tag/v0.1.0-pre
