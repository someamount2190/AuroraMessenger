# Monolith Audit

A structural-health pass over Aurora's source, focused on **monolithic files** —
single files that have grown to carry several responsibilities at once. This is a
maintainability/auditability concern, not a security finding: large mixed-concern
files are harder to review, harder to test in isolation, and raise the chance that
a security-relevant change is made in the wrong place. It complements the
component view in [`ARCHITECTURE.md`](ARCHITECTURE.md).

> Scope: `app/src/main` and `crypto/src/main` (Kotlin). Pinned to the commit this
> document was committed against; re-run the metrics below after large refactors.
> No behaviour was changed by this audit — it is analysis only.

## How "monolith" is judged here

A file is flagged when it combines two or more of:

- **Size** — well past the point where the whole file fits in a reviewer's head
  (rule of thumb: a single source file > ~400 lines, or a single function > ~80).
- **Mixed concerns** — UI + business logic + IO + crypto in the same unit, or
  several unrelated domain models/DAOs sharing one file.
- **God class / God composable** — one declaration owning a large state surface
  and many methods that operate on disjoint subsets of it.
- **Long functions / deep nesting** — single functions that themselves span
  hundreds of lines or nest 5+ levels deep.

None of these are bugs. They are levers on review cost and regression risk.

## Codebase shape

Measured over `app/src/main` + `crypto/src/main` (Kotlin):

| File size bucket | Count |
|---|---|
| ≥ 400 lines | 9 |
| 250–399 lines | 16 |
| 120–249 lines | 22 |
| < 120 lines | 44 |

The long tail is healthy — most files are small and single-purpose. The mass sits
in a handful of screen/coordinator files at the top. The crypto core
(`crypto/src/main`) is *not* a monolith concern: its files are bounded and
single-responsibility (`HybridSigner` 326, `HybridKem` 275, `RatchetManager` 217),
which is consistent with the Android-free, independently-reviewable core the
architecture doc describes. The monolith pressure is concentrated in the **UI and
the pairing/call coordinators**.

## Decomposition status

All nine flagged files have been decomposed.

| File | Before | After | New units |
|---|---:|---:|---|
| `ConversationScreen.kt` | 1133 | 565 | MessageBubble, MediaViewer, VerifyPanel |
| `SettingsScreen.kt` | 650 | 252 | SettingsComponents (6 sections + dev + dialogs) |
| `PairingCoordinator.kt` | 528 | 69 (facade) | PairEvent, PairingEvents, Scanner/Receiver/VerifyPairing |
| `HomeScreen.kt` | 477 | 124 | HomeSearch, ContactList, HomeWidgets |
| `CallController.kt` | 469 | 459 | CallModels (CallState, CallInfo) |
| `AuroraDatabase.kt` | 468 | 52 | Contacts, Messages, Ratchet, Prekeys, MeshPeers |
| `AuroraRendezvousServer.kt` | 417 | 361 | NodeRegistry, SignalQueues, PrekeyDirectory, IpRateLimiter |
| `RendezvousClient.kt` | 411 | 402 | `signedDrainAuth` helper (DRY) |
| `AuroraApp.kt` | 386 | 249 | AuroraNavGraph |

Two classes of work:

1. **File-level moves** (the UI screens + the Room file) — self-contained units
   moved between files in the same package; visibility widens from file-private to
   `internal`, no logic moves.
2. **Class decomposition** (the coordinators/server) — extracting collaborators
   from stateful classes. Done conservatively and behaviour-neutrally:
   - **server** state is partitioned by resource into plain collaborators owned by
     the HTTP boundary (no Hilt, no external API change);
   - **PairingCoordinator** becomes a facade over scanner/receiver/verify
     collaborators sharing an events bus — its public surface and the wire protocol
     are unchanged;
   - **CallController** keeps its cohesive state machine (it already delegates
     media/signaling/log/ringing); only the model types were lifted out;
   - **RendezvousClient** keeps its cohesive HTTP surface; only the duplicated
     drain-auth headers were unified.

> Verification: the full `:app` module compiles and the JVM/Robolectric unit
> suite is green after the refactor — **75 app tests, 0 failures** (incl.
> `RoomDaoTest`, `PairingCryptoTest`/`PairingCryptoAttacks`, `CallLogTest`,
> `CheckinSigningTest`), plus the standalone `:crypto` suite. A `SessionStart`
> hook (`.claude/hooks/session-start.sh`) provisions the Android SDK so
> `gradle :app:testDebugUnitTest` runs in web sessions.

## Findings, by priority

Priority weighs size × number of mixed concerns × how security-sensitive the file
is. A 500-line UI screen is a maintenance cost; a 500-line file that performs
KEM encapsulation, signature checks, and ratchet seeding is both a maintenance
cost *and* a review hazard, so it ranks higher.

| Rank | File | Lines | Largest unit | Why it's flagged |
|---|---|---:|---|---|
| 1 | `ui/conversation/ConversationScreen.kt` | 1133 | `ConversationScreen` ~435 L | Biggest file in the tree; a chat screen that also embeds a full media gallery + video player |
| 2 | `pairing/PairingCoordinator.kt` | 528 | `handlePairRequest` ~85 L | Security-critical; crypto + signing + DB + network + state machine in one class |
| 3 | `ui/settings/SettingsScreen.kt` | 650 | `SettingsScreen` ~390 L | One composable spanning appearance → privacy → security → dev tools |
| 4 | `call/CallController.kt` | 469 | `handleInner` ~88 L | Call state machine + WebRTC + signaling + failure-recovery timers |
| 5 | `server/AuroraRendezvousServer.kt` | 417 | `handleCheckin` ~64 L | HTTP routing + signature verification + storage + rate-limiting |
| 6 | `network/RendezvousClient.kt` | 411 | `checkIn` ~54 L | Three HTTP clients + request signing + JSON + pinning in one class |
| 7 | `db/AuroraDatabase.kt` | 468 | (whole file) | 8 entities + 5 DAOs (~69 queries) + schema/migration + DB build in one file |
| 8 | `ui/home/HomeScreen.kt` | 477 | `ContactList` ~109 L | Search + contact list + pairing-action buttons + server status card |
| 9 | `ui/AuroraApp.kt` | 386 | `AuroraAppContent` ~260 L | Nav graph + 8 `LaunchedEffect`s + call overlay + lock-state logic |

### 1. `ConversationScreen.kt` — 1133 lines

Verified declarations: `ConversationScreen` (131), `VerifyPanel` (576),
`CallLogRow` (644), `MessageBubble` (674), `AudioContent` (797),
`MediaContent` (828), `FullscreenImageViewer` (897), `videoFrame` (965),
`VideoPlayer` (985), plus `formatDuration`/`formatRemaining` helpers.

This is one file doing the work of three or four:

- **The chat screen itself** — top bar (name, streak, disappearing-timer and
  P2P-reachability status lines), failure banners, the message list, the reply
  quote, and the composer (text + attach menu + voice recording). The
  `ConversationScreen` composable alone runs ~435 lines (131–565).
- **A message-rendering kit** — `MessageBubble`, `AudioContent`, `MediaContent`,
  `CallLogRow`, reaction chips, delivery ticks, expiry countdown.
- **A full media viewer** — `FullscreenImageViewer` and a complete `VideoPlayer`
  (149 lines, 985–1133) with its own `TextureView`/`Surface` lifecycle,
  `MediaPlayer` wiring, auto-play, and a scrubbable transport bar.

The video player in particular has nothing to do with "conversation" — it's a
reusable, FLAG_SECURE-aware media component that happens to live here. The
in-memory-only playback (no plaintext temp file) is a deliberate security
property worth keeping visible; pulling it into its own file makes that property
easier to find and review, not harder.

**Suggested decomposition** (mechanical, behaviour-neutral — these are already
`private` composables in the same package):
- `conversation/MessageBubble.kt` ← `MessageBubble`, `AudioContent`,
  `MediaContent`, `CallLogRow`, `REACTION_EMOJI`, `formatDuration`,
  `formatRemaining`.
- `conversation/MediaViewer.kt` ← `FullscreenImageViewer`, `VideoPlayer`,
  `videoFrame`.
- `conversation/VerifyPanel.kt` ← `VerifyPanel`.
- `ConversationScreen.kt` keeps the screen scaffold, top bar, composer, dialogs.

That alone takes the file from 1133 to roughly 450 lines without touching logic.

### 2. `PairingCoordinator.kt` — 528 lines (highest review-risk)

This is the most security-sensitive monolith. The `PairingCoordinator` class
owns the whole pairing/verification state machine *and* performs the crypto
inline: QR parsing, PQXDH/legacy KEM encapsulation and decapsulation, signature
generation and verification, ratchet seeding, SAS-code derivation/validation,
DB mutation, signal posting, and notifications — across ~15 handler methods
sharing one set of injected dependencies.

The two longest functions, `pairFromQr` (~90 L) and `handlePairRequest`
(~85 L), each interleave three distinct concerns (signature check → prekey
consumption → root derivation → persistence) in a single linear path, and the
forward-secret encapsulation helper (`tryFsEncapsulate`) sits as a private method
that can't be exercised in isolation.

This is exactly the kind of file where the auditing docs (`CRYPTO_SPEC.md`,
`KEY_MANAGEMENT.md`) and the code should line up unit-for-unit. Suggested split:
- a **transition layer** (the state-machine handlers: accept/reject/cancel/verify),
- a **pairing-crypto layer** (FS vs legacy encapsulation, root derivation, prekey
  consumption, secret wiping) that can be unit-tested against
  `PairingCryptoTest`/`PairingCryptoAttacks` directly,
- a thin **signal dispatch** helper to collapse the near-duplicate "sign + post a
  simple control signal" methods (`cancelOutgoing`, `rejectIncoming`,
  `handlePairCancel`, `handlePairReject`, `handleContactRemove`).

Decomposing here pays off twice: smaller units *and* a tighter map from the crypto
spec to testable code. **Recommend doing this one carefully and with the existing
pairing tests green before/after — it is the highest-value, highest-care target.**

### 3. `SettingsScreen.kt` — 650 lines

`SettingsScreen` is a single ~390-line composable covering appearance (theme mode
+ palette), privacy, security (PIN, decoy PIN, duress wipe, root-detection
notice), network/server, data (backup export/restore), and a `DeveloperSection`.
Backup export/restore mixes file IO and passphrase handling directly into the
composable body. Several near-identical `AlertDialog`s (set-PIN, export, restore,
clear) repeat the same state-management shape.

Low-risk split: one `*Card`/section composable per concern
(`AppearanceSection`, `SecuritySection`, `BackupSection`, `DeveloperSection` is
already separate), and a small dialog helper to deduplicate the PIN/passphrase
dialogs. Pure UI, no behaviour change.

### 4. `CallController.kt` — 469 lines

A God-class call lifecycle: `CallState` machine, WebRTC session ownership,
offer/answer/ICE/bye signaling, and **four** independent recovery timers
(ring timeout, no-answer backstop, connect timeout, reconnect watchdog) plus
ICE-restart logic. `handleInner` (~88 L) folds offer/answer/ice/bye — including
glare resolution and ICE-restart detection — into one `when`. Candidate
extractions: a signaling state-machine unit, an `IceRecoveryHandler` (the timers
+ restart policy), and a `CallLogger` (the `logCall` MessageEntity construction).

### 5. `AuroraRendezvousServer.kt` — 417 lines

The embeddable server mixes HTTP routing (`serve`, a long if/else dispatch),
request parsing, signature verification (`nodeIdBindsToKeys`, `verifyDrain`),
node registration + TTL purge, the bounded signal queue, prekey-bundle storage,
per-IP rate limiting, and response formatting. `handleCheckin` (~64 L) is the
hotspot. Natural seams: a `Router`, a `SignatureVerifier`, a `RateLimiter`, and
per-resource stores (registration / signal queue / prekey). The near-duplicate
`allowFind`/`allowSignalPost` rate-limit methods can be unified.

### 6. `RendezvousClient.kt` — 411 lines

Three `OkHttpClient` instances (different timeout profiles), certificate pinning
config, request signing, JSON (de)serialization, and candidate verification in
one class. Error handling is inconsistent (some paths return `Result`/`null`,
others throw `DrainUnauthorizedException`). The auth-header construction is
repeated across `getSignals`/`waitForWake`/`publishPrekeys`. Pinning config
(`PINNED_HOST`, `PINNED_CERT_SHA256`) is security-relevant and would be easier to
review/CI-check as its own small object. Candidate seams: an HTTP/auth helper, a
`PrekeyClient`, and a `SignalQueueClient`.

### 7. `AuroraDatabase.kt` — 468 lines

Not a "God function" problem — a *one-file-for-everything* problem. It holds 8
`@Entity`/projection data classes, 5 DAO interfaces (~69 query methods total),
the `@Database` declaration with auto-migrations, and the SQLCipher `build()`.
Standard Room hygiene is one file per entity + DAO (or per aggregate:
contact / message / ratchet / prekey / mesh), leaving `AuroraDatabase.kt` with
just the `@Database` class and builder. Secondary smell: `MessageEntity.type`
and several `ContactEntity` boolean flags are stringly/loosely typed where an
enum/sealed type would make `when` branches exhaustive.

### 8. `HomeScreen.kt` — 477 lines

`ContactList` (~109 L) packs avatar, name, streak badge, state-dependent subtitle,
and trailing pairing-action buttons (Accept/Reject/Cancel) / unread badge into one
deeply-nested row, and `SearchRow` largely duplicates that row's structure. Extract
a `ContactRow` (shared by list and search), a `PairingActions` composable, and a
`ServerStatusCard` (already separate) — leaving `HomeScreen` as layout + wiring.

### 9. `AuroraApp.kt` — 386 lines

`AuroraAppContent` is a ~260-line composable holding the entire `NavHost` graph,
eight `LaunchedEffect` subscriptions (pairing toasts, call errors, share-intent
routing, paired-conversation navigation, call-screen auto-present), the minimized
call overlay, and lock-state branching. The call-state check
(`INCOMING/OUTGOING/CONNECTING/CONNECTED`) is duplicated across three sites.
Extract the nav graph into its own composable and group the effects into named
handler composables.

## Cross-cutting observations

- **The pattern is consistent**: screens own their helper composables *and* their
  reusable widgets *and*, in two cases, an entire media/transport subsystem. Most
  of the fix is moving already-`private` declarations into sibling files in the
  same package — near-zero behavioural risk.
- **Coordinators mix policy with mechanism**: `PairingCoordinator` and
  `CallController` both interleave a state machine with the crypto/transport
  mechanics it drives. These are the files where decomposition also *improves
  testability* (isolating the mechanism behind a seam the existing test suites can
  target), which is why they outrank larger pure-UI files.
- **The crypto core is the counter-example to follow.** It is already split into
  bounded, single-responsibility, Android-free units with dedicated tests. The
  same discipline applied to the coordinators is the target end-state.

## Design notes & deliberate non-splits

A few judgement calls worth recording, since "smaller" is not always "better":

- **`CallController` stays a cohesive state machine.** It already delegates its
  cross-cutting concerns — media (`WebRtcSession`), signaling (`CallSignalCodec`),
  log formatting (`CallLog`), ringing (`Ringer`). The remaining size is intrinsic
  state-machine complexity over tightly-coupled mutable fields (recovery timers,
  ICE-restart guard, connected-at clock). Carving those into a separate
  `IceRecoveryHandler` would require threading the controller's state back through
  callbacks — scattering one state machine across two files for a worse result. Only
  the model types (`CallState`/`CallInfo`) were lifted out.
- **`RendezvousClient` stays one HTTP surface.** It is stateless and cohesive; the
  only real smell was the thrice-duplicated drain-auth headers, now unified. Splitting
  it into `PrekeyClient`/`SignalQueueClient` behind a facade would add indirection
  without separating any genuinely independent concern.
- **`PairingCoordinator` *was* worth splitting** because it had three genuinely
  independent protocol roles (the file's own `── Scanner/Receiver/Verify side ──`
  banners) that share only an event bus — a clean facade boundary.

Future refactors should keep recommending decomposition only, and explicitly **not**
bundle logic changes into the same commits as structural moves.
