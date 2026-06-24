# Aurora Messenger — Test Architecture

Status: **design** (no tests exist yet). This document specifies the full test
architecture down to the class/method level. It is the blueprint for building the
suite; nothing here is implemented yet.

Guiding principle: Aurora is a **post-quantum E2E crypto messenger**, so the suite is
weighted toward the security-critical core (crypto, ratchet, pairing, wipe) and toward
**adversarial** tests (tamper / replay / MITM / downgrade), not just happy-path.

---

## 1. Test tiers & where each class lives

We use a five-tier pyramid. Each source class is assigned to exactly one *primary* tier
(it may also appear in an integration test).

| Tier | What | Runs on | Source set | Speed |
|---|---|---|---|---|
| **T1 Pure-JVM unit** | All crypto (incl. PQC) + logic, no Android | plain JVM | `crypto/src/test`, `app/src/test` | ms–s |
| **T3 Storage unit** | Room DAOs + Store adapters | Robolectric / device | `app/src/test` (Robolectric) or `app/src/androidTest` | s |
| **T4 Domain/integration** | Managers with fakes; two-party in-process | JVM (fakes) / device (Hilt) | `app/src/test` + `app/src/androidTest` | s |
| **T5 ViewModel & UI** | ViewModels (Flows) + Compose screens | JVM (VM) / device (UI) | `app/src/test` + `app/src/androidTest` | s–min |

### No more native tier — the whole crypto stack is pure-JVM
The crypto re-engineering removed liboqs/JNI: the post-quantum primitives are now pure-Java
**BouncyCastle** (X-Wing = ML-KEM-768 + X25519, ML-DSA-65) and **Google Tink** (XChaCha20-
Poly1305). So there is **no T2 "native" tier** any more — `HybridKem`, `HybridSigner`,
`NodeIdentityGenerator`, `PrekeyManager`, `KemDoubleRatchet` and the end-to-end pairing
simulation all run as **ordinary T1 JVM tests on CI**, alongside `Hkdf`, `SymmetricCipher`,
etc. The `@Tag("native")`/`assumeTrue(NativeCrypto.available)` guards are gone; `./gradlew -p
crypto test` runs the entire crypto suite (incl. PQC KATs) with zero setup. The only remaining
`androidTest` crypto is the optional on-device demo/attack suite (validates the same code under
the real Android runtime).

---

## 2. Tooling

Add to `crypto/build.gradle.kts` (testImplementation) and `app/build.gradle.kts`:

| Library | Purpose |
|---|---|
| `junit:junit:4.13.2` (+ `kotlin-test`) | base runner/assertions |
| `org.jetbrains.kotlinx:kotlinx-coroutines-test` | `runTest`, `StandardTestDispatcher`, virtual time |
| `app.cash.turbine:turbine` | assert on `Flow`/`StateFlow` emissions |
| `io.mockk:mockk` | mocking (sparingly — prefer hand-written fakes for Stores/DAOs) |
| `com.google.truth:truth` *(or AssertJ)* | fluent assertions |
| `org.robolectric:robolectric` | Android-context classes off-device (T3/T5 VM) |
| `androidx.room:room-testing` | Room migration tests |
| `androidx.arch.core:core-testing` | `InstantTaskExecutorRule` |
| `androidx.compose.ui:ui-test-junit4` + manifest | Compose UI (T5, androidTest) |
| `com.google.dagger:hilt-android-testing` | Hilt integration graph (T4 androidTest) |
| `net.jqwik:jqwik` *(optional)* | property-based / fuzz for codecs & ratchet |

### Shared test infrastructure (build once, in `app/src/test/.../testutil/` and `crypto/src/test/.../testutil/`)
- **`MainDispatcherRule`** — swaps `Dispatchers.Main` for a test dispatcher (all ViewModel tests).
- **`FakeKemSessionStore` / `FakePrekeyStore`** — in-memory `MutableMap` implementations of the
  `KemSessionStore` / `PrekeyStore` interfaces. Lets T1 ratchet/prekey tests run with zero DB.
- **DAO fakes** — `FakeContactDao`, `FakeMessageDao`, `FakeRatchetDao`, `FakePrekeyDao`,
  `FakeMeshPeerDao` backed by maps + `MutableStateFlow` for the `observe*()` Flows.
- **`CryptoFixtures`** — deterministic key pairs / identities / known-answer test vectors
  (see §9). Generates Alice & Bob `NodeIdentity` once per run.
- **`TwoParty` harness** — wires two in-process stacks (Alice, Bob) sharing a
  `FakeRendezvous` so pairing + messaging can be driven end-to-end without sockets.
- **`FakeRendezvous`** — in-memory implementation of check-in/find/signal/prekeys used by
  T4 integration so no network is touched.

---

## 3. Source-set layout

This is the **actual** layout (every file below exists and runs in CI). Everything runs as
pure-JVM/Robolectric in the two CI jobs; there is no instrumented (`androidTest`) suite.

```
crypto/src/test/kotlin/com/aura/crypto/   (all pure-JVM, incl. PQC)
  HkdfTest.kt  HkdfRfc5869KatTest.kt   SymmetricCipherTest.kt  SymmetricCipherKatTest.kt
  HybridKemTest.kt  HybridSignerTest.kt  NodeIdentityTest.kt  PrekeyManagerTest.kt
  KemDoubleRatchetTest.kt  KemRatchetCodecTest.kt  KemRatchetManagerTest.kt
  PqcKatTest.kt  AcvpKatTest.kt  WycheproofTest.kt  ClassicalKatTest.kt   (vectors)
  CryptoAttacks.kt  HybridCryptoAttacksTest.kt   (adversarial)
  CryptoUtilsTest.kt  B64Test.kt  CryptoResultTest.kt  CryptoStackDemo.kt
  testutil/ (FakeKemSessionStore, FakePrekeyStore)

app/src/test/kotlin/com/aura/            (JVM + Robolectric)
  pairing/PairingCryptoTest.kt           pairing/PairingCryptoAttacks.kt
  pairing/VerifyPairingTest.kt           security/AppLockTest.kt
  security/SecureWipeTest.kt             backup/BackupsTest.kt
  settings/AuroraSettingsBlocklistTest.kt  disappearing/DisappearingMessagesTest.kt
  transport/SignalCodecAadTest.kt        transport/WireFramesTest.kt
  db/ContactEraserTest.kt                db/RoomDaoTest.kt
  db/StoreAdapterConformanceTest.kt      db/AuroraDatabaseMigrationTest.kt
  media/EncryptedMediaStoreTest.kt       call/CallLogTest.kt
  network/BackoffTest.kt                 network/TwoPeerRendezvousTest.kt
  server/CheckinSigningTest.kt           streak/StreaksTest.kt
  transport/rtc/RtcMediaChunkingTest.kt  transport/rtc/TwoPeerTransportTest.kt
  sim/EndToEndPairMessageSimTest.kt      sim/PqxdhHandshakeSimTest.kt
  testutil/ (TestIdentity, FakeStores, InMemoryRendezvous, InMemoryPeerTransport)
```

> Still uncovered (known gaps, not yet written): `PairingCoordinator`/`Scanner`/`Receiver`
> orchestration, `SyncEngine` routing, `CallController`/`RtcTransport` state machines, the real
> `RendezvousClient` HTTP, and Compose UI/ViewModels. The sections below that describe these read
> as a target, not current reality — treat [`TEST_STATUS.md`](TEST_STATUS.md) as the authoritative
> coverage inventory.

---

## 4. Tier 1 — crypto, pure JVM (no native). **Build these first.**

### `Hkdf` (`derive`, `hmacSha3_256`, `sha3_256`)
- KAT: `sha3_256` / `hmacSha3_256` match published SHA3-256 / HMAC test vectors.
- `derive` length: output is exactly `outputLen`; default length correct.
- `derive` determinism: same `ikm`+`info`+`len` → identical bytes; different `info` → different output (domain separation).
- `derive` with empty/zero ikm doesn't throw; distinct salts diverge.

### `SymmetricCipher` (`encrypt`, `decrypt`, `generateKey`)
- Round-trip: `decrypt(encrypt(pt, k, aad), k, aad) == pt` across sizes (0, 1, block-edge, large).
- **Tamper:** flip any ciphertext byte → `decrypt` returns failure (Poly1305 rejects).
- **Wrong key** → failure. **Wrong/empty AAD mismatch** → failure (AAD is authenticated).
- **Nonce uniqueness:** two `encrypt` of same pt+key produce different ciphertexts (random nonce) and both decrypt.
- `generateKey` returns `KEY_BYTES` length, high-entropy (no repeats across N calls).
- XChaCha20 KAT against RFC/draft test vectors (internal `qr`/`toLE`/`fromLE` exercised via public API).

### `KemRatchetManager` / `KemDoubleRatchet` (with `FakeKemSessionStore`) — the highest-value T1 target
The single per-contact ratchet for messages, media, and call/RTC signaling.
- **Seed & roles:** after `seed(root, iAmInitiator)`, `isSeeded` true; the initiator sends first,
  the responder cannot send until it has received (`sealNext` → null before the first open).
- **Forward-secret round-trip:** initiator `sealNext` → responder `open` yields plaintext, both ways through persistence.
- **Out-of-order / replay:** seal n=0,1,2; open 2 then 0 then 1 → all succeed (skipped-key cache); replay of a consumed frame → null.
- **Tamper leaves state intact:** a forged frame returns null AND `decrypt` commits nothing (next valid frame still opens).
- **Skip/healing bounds:** epoch skip cap **1024**, skip-flood gap **512** refused; **post-compromise healing** (a stolen session snapshot cannot read post-heal traffic).
- **SAS:** both peers (same root) compute the same `sasCodeFor(target)`; a *different* root → different code (MITM detection); 6 digits; survives ratchet steps.
- **`mediaKey`** present after seed, 32 bytes, stable across ratchet steps, local-only (the two peers hold *different* media keys).
- **`wipe`/`clear`:** after `wipe`, `isSeeded` false and `open` returns null; `clear` removes all contacts.
- **Concurrency:** a per-contact `Mutex` serializes seals/opens (messages + signaling share one session).

### `CryptoUtils` / `B64` / `CryptoResult`
- `toHex`/`hexToBytes` round-trip + reject odd-length/invalid hex; `intTo4Bytes`/`readInt4` round-trip incl. negative & boundary ints (big-endian order asserted).
- `B64.encode/decode` round-trip; URL-safe vs standard alphabet matches the wire format.
- `CryptoResult.getOrNull`/`getOrThrow`: Success returns value; Failure → null / throws expected type.

---

## 5. Crypto — post-quantum (now pure-JVM T1; no liboqs)

### `HybridKem` (`generateKeyPair`, `encapsulate`, `decapsulate` — X-Wing)
- KEM round-trip: `decapsulate(encapsulate(pub).ciphertext, priv)` yields the **same shared secret** as encapsulate produced.
- **Hybrid binding:** corrupting the ML-KEM region **or** the trailing X25519 region of the X-Wing ciphertext → different/again-failing shared secret (both halves contribute; neither alone suffices).
- KAT/regression: `PqcKatTest` (deterministic ML-KEM-768/X-Wing sizes + pinned digests) and `WycheproofTest` (mlkem_768 decap incl. implicit-rejection).
- Wrong private key → different secret (no false agreement).
- `HybridPublicKey.toBytes/fromBytes` and `HybridCiphertext.toBytes/fromBytes` round-trip; truncated/oversized bytes → throws, not silent.
- Shared-secret length == 32; high entropy.

### `HybridSigner` (`sign`, `verify`, `signEd25519Only`, `verify*Sync` variants)
- Sign→verify true for the right message+key.
- **Both-must-verify:** a signature whose ML-DSA-65 part is valid but Ed25519 part is forged → `verify` false (and vice-versa). This is the core anti-downgrade property.
- Wrong key / altered message → false.
- `signEd25519Only` ↔ `verifyEd25519Only`/`verifyEd25519OnlySync` consistent; `verifyHybridEd25519PartSync` accepts a full hybrid sig's Ed25519 portion (the pairing fallback path) and rejects a tampered one.
- `HybridVerifyKey.toBytes/fromBytes` round-trip; cross-key rejection.

### `NodeIdentityGenerator` / `NodePublicIdentity`
- `generate` yields kem+sign keys and `nodeId == SHA3-256(kemPub ‖ signPub)` (recompute and compare).
- `NodePublicIdentity.toBytes/fromBytes` round-trip; the recovered nodeId is unchanged.
- Substituting keys changes the nodeId (identity-binding property).

### `PrekeyManager` (`publicBundle`, `consume`, `wipeAll`) with `FakePrekeyStore`
- `publicBundle` includes a signed SPK + N OPKs; signatures verify under the identity's signing key (uses `prekeyMessage`).
- **OPK single-use:** `consume(spk, opk)` returns the matching secrets once; consuming the same opk again → null (popped).
- `consume` with unknown/empty opk → SPK-only path (still works, returns null opk).
- `wipeAll` empties the store.

---

## 6. Tier 3 — storage (Room DAOs + Store adapters)

Run as `androidTest` (real SQLite; SQLCipher optional via a test key) or Robolectric with an
unencrypted in-memory Room build.

### Per-DAO CRUD + Flow tests (`ContactDao`, `MessageDao`, `RatchetDao`, `PrekeyDao`, `MeshPeerDao`)
- `ContactDao`: `upsert`/`byNodeId` round-trip; `observeAll`/`observeByNodeId` emit on change (Turbine); `rename`, `setPairState`, `markVerifyReady`, `setVerify`, `incVerifyAttempts` mutate exactly the intended columns; `deleteByNodeId`/`deleteAll` cascade expectations; `pendingPairingSends`/`activeForBackup` filter correctly.
- `MessageDao`: `insert`/`byId`; `observeConversation` ordering; `observeLatestPerContact` & `observeUnreadByContact` aggregation; `setStatus`/`setSealed`/`setExpiry`/`setMyReaction`/`setTheirReaction`; `expired(now)` boundary; `mediaPathsForContact`; `deleteForContact`/`deleteByIds`.
- `RatchetDao`: the KEM session blob — `kemUpsert`/`kemSession`/`kemDelete`/`kemDeleteAll`/`allKemForBackup` round-trip.
- `PrekeyDao`: `currentSpk` returns newest SPK; `unusedOpks(n)`/`unusedOpkCount`; `pruneOldSpks(cutoff)`.
- `MeshPeerDao`: `upsertAll`/`all`/`observeCount`.

### Store-adapter conformance (`RoomKemSessionStore`, `RoomPrekeyStore`)
- A **shared contract test** runs the *same* assertions against (a) `FakeKemSessionStore` and
  (b) `RoomKemSessionStore` over an in-memory DB, proving the adapter faithfully implements
  `KemSessionStore` (blob round-trip / upsert / delete / deleteAll is lossless). Same for prekeys.

### Migrations (`MigrationTest`)
- Each Room migration (… → current, the DB-v? chain) opens an old schema, runs the migration, asserts no data loss. `AutoMigration` validated against the exported `app/schemas/`.

---

## 7. Tier 4 — domain managers & integration

Each manager is tested with **fakes** for its injected deps (the constructor-injection design
makes this clean — see the dependency map). Adversarial cases called out.

### `PairingManager` — recognition→verify lifecycle (highest-value manager test)
Drive the **state machine** with fakes (`FakeContactDao`, real `RatchetManager`+fakes, real
crypto T2, `FakeRendezvous`, `FakeNotifier`):
- `pairFromQr(valid)` → contact created in `requested` state; signed prekey fetched; handshake run.
- `handlePairRequest` → `incoming`; `acceptIncoming` → moves to `verify`, both sides seed a ratchet.
- `myVerifyCode` matches peer's `sasCodeFor`; `submitVerifyCode(correct)` → `active`; `submitVerifyCode(wrong)` → false, increments attempts, stays in `verify`.
- **MITM:** if the two sides seed from *different* roots, the SAS codes differ → `submitVerifyCode` fails (the core security property).
- `rejectIncoming(block=true)` adds to blocklist; `handlePairReject`/`handlePairCancel` clean up state.
- `deleteContact` → signs `contactremove`, wipes ratchet+rows; `handleContactRemove` (verified) wipes + notifies; an *unsigned/forged* remove is ignored.
- Every `handle*(json)` rejects malformed/oversized JSON without throwing (returns `Result.failure`).

### `MessageSender` (`flushPending`, `resolvePeerAddress`, `sendControl`, `sendMediaChunked`, `exchangeFrame`, `hasPendingOutbound`)
- Seals via ratchet then frames over the wire; `exchangeFrame` round-trips through `FakeRendezvous`/`FakeTcp`.
- Offline → message stays `pending`; `flushPending` delivers on reconnect and flips status; `flushMutex` prevents double-send under concurrency.
- `resolvePeerAddress` picks the verified candidate (ties into `verifyCandidates`); falls back across candidates.
- Blocked node → not sent.

### `MediaTransfer` + `MediaStore`
- `MediaStore.writeEncrypted`/`readDecrypted` round-trip; `writeSealed`/`readSealed`; on-disk bytes contain **no plaintext header** (e.g. no `ftyp`/`moov` for video) — asserts at-rest encryption. `delete`/`wipeAll` remove files; `fileFor` path shape.
- `MediaTransfer.sendMedia`/`sendAudio` chunk + seal + persist; `decryptForPreview` recovers original; tampered chunk rejected.

### `ReactionManager.react`, `DisappearingManager` (`stampExpiryIfNeeded`, `setTimer`)
- React add/remove updates message + sends control frame; mirrors on receive.
- `stampExpiryIfNeeded` sets `expiresAtMs` only when a timer is configured; expired sweep (`MessageDao.expired`) deletes message + media; per-conversation timer override vs global default.

### `ContactEraser.wipe` & `SecureWipe.wipeEverything` (security-critical)
- `ContactEraser.wipe`: removes the contact's messages, KEM ratchet session, and media; afterward `KemRatchetManager.open` for that contact → null (cryptographic erase).
- `SecureWipe.wipeEverything`: identity cleared, DB key destroyed, all DAOs empty, media gone, settings reset; **post-condition: no stored ciphertext is decryptable** (keys gone). `exitProcess` invoked. *(androidTest — touches Keystore/SQLCipher.)*

### `AppLockManager` (`setPin`, `setDecoyPin`, `tryUnlock`, `lock`, `disableLock`) — Robolectric/androidTest
- `tryUnlock(real)` → `UnlockResult` success-real; `tryUnlock(decoy)` → success-decoy (plausible-deniability path); wrong → failure; PBKDF2 hash never stores the PIN in clear.
- Duress wipe wiring (decoy/duress → triggers `SecureWipe` when configured).

### `CallManager` **state machine** (no real WebRTC — test the guard logic that caused the prod crashes)
- `startCall` is a **no-op when state != IDLE/ENDED** (the "no second call while one is active" guard) — assert state unchanged.
- `endCall` is **idempotent** (calling twice doesn't double-dispose) — assert the `AtomicBoolean` guard via observable state (regression for the "pure virtual function" teardown crash).
- State transitions: IDLE→CALLING→CONNECTED on accept; CONNECTED→ENDED on `endCall`; `connectedAtMs` set on CONNECTED; `toggleMute`/`toggleVideo` flip and return the new value; `minimize`/`expand` flip `_minimized`.
- *(WebRTC PeerConnection/track lifecycle stays an instrumented/manual concern — flagged, not unit-mocked.)*

### `SyncEngine` routing (`registerSignalHandler`, signal dispatch)
- A registered handler is invoked for its `type`; unknown types ignored; the `contactremove`/`pair*`/call-signal routes dispatch to the right manager (verify with spies). Malformed signal → no crash.

### `RendezvousClient.verifyCandidates` + signing helpers (pure-ish, high value)
- Given 10 candidates (1 real signed by target + 9 decoys), `verifyCandidates` returns **exactly the real one**; all-decoy → null; tampered real candidate → null.
- Hybrid vs Ed25519-only fallback path (dilithiumPub null) both select correctly.
- `AuroraRendezvousServer.checkinMessage`/`drainMessage` produce the exact canonical signing string (`aura-checkin-v1|…`) — byte-for-byte, since the Node server depends on it.
- `RendezvousClient` HTTP methods (`checkIn`/`find`/`postSignal`/`getSignals`/`waitForWake`/`tap`/`publishPrekeys`/`fetchPrekeyBundle`): test against a `MockWebServer` — request shape, signature header, error/timeout handling, 404 graceful fallback.

### Pure helpers
- `QrPayload.encode/decode` round-trip; version handling; reject malformed/over-capacity payloads.
- `WireFrames.write/read` length-prefix framing round-trip; partial/oversized frame handling.
- `Streaks.compute` boundary cases (gap breaks streak, same-day, timezone).
- `AuroraSettings` (Robolectric): block/unblock reflected in `isBlocked` + Flow; `clearAll` resets; setters persist.
- `RootDetector.isLikelyRooted` against fake fs probes (best-effort).

### Two-party integration (`PairAndMessageE2ETest`, androidTest + Hilt)
- Full path with two in-process stacks + `FakeRendezvous`: **pair → SAS verify → exchange text → media → react → disappearing expiry → delete contact**, asserting both DBs converge and ciphertext on the wire is opaque. This is the capstone that exercises the real wiring.

---

## 8. Tier 5 — ViewModels & UI

### ViewModels (JVM, `MainDispatcherRule` + Turbine) — state-as-Flow assertions
For each: `ConversationViewModel`, `HomeViewModel`, `SettingsViewModel`, `LockViewModel`,
`ScanViewModel`, `MyCodeViewModel`, `OnboardingViewModel`, `ShadowMeshViewModel`,
`ShareViewModel`, `CallViewModel`, `AuroraAppViewModel`:
- Inject fake managers; assert the exposed `StateFlow`/`Flow` emits the right states for each
  action (e.g. `ConversationViewModel.send` → message appears + sender invoked; `callActive`
  reflects `CallManager` state and gates the call buttons; `submitVerify` success/failure;
  `deleteContact` triggers auto-back).
- `ScanViewModel.onQrContent` valid/invalid → state machine; `LockViewModel.unlock` maps
  `UnlockResult`; `HomeViewModel` pairing actions delegate.

### Compose UI (androidTest, `createAndroidComposeRule`) — thin, key flows only
- Onboarding completes → identity created. Lock screen accepts/refuses PIN. Conversation
  renders messages, send button enabled/disabled by `callActive`. Incoming-call screen
  shows accept/decline. Keep this tier small (slow, brittle); push logic down to ViewModels.

---

## 9. Cross-cutting suites

### Security / adversarial matrix (collect as a tagged suite `@Tag("security")`)
A single table-driven suite asserting each property end-to-end:
| Property | Mechanism under test |
|---|---|
| Confidentiality at rest | SQLCipher DB + `.enc` media contain no plaintext |
| Tamper-evidence | Poly1305 rejects flipped ciphertext / AAD (cipher, ratchet, media) |
| Replay resistance | ratchet rejects reused counters |
| MITM detection | SAS mismatch on divergent roots (pairing) |
| Anti-downgrade | hybrid signature requires BOTH parts (signer) |
| Forward secrecy | wiped root + advancing chains → old key can't open new/old frames |
| Identity binding | nodeId = hash(keys); substitution detected |
| Address authenticity | `verifyCandidates` accepts only the target-signed candidate |
| Prekey single-use | OPK consumed once |
| Cryptographic erase | post-wipe, nothing decrypts |

### Property-based / fuzz (optional, jqwik)
- Codecs (`QrPayload`, `WireFrames`, `*.toBytes/fromBytes`, hex/Base64): random round-trips + malformed inputs never crash.
- Ratchet: random interleavings of seal/open/skip never desync or leak a key.

### Regression
- One named test per fixed production bug: (a) concurrent-call rejected (`startCall` guard);
  (b) double-`endCall` idempotent (teardown crash); (c) bilateral contact delete both-sides.

---

## 10. Coverage targets & CI

- **crypto module: ≥ 90% line / 100% of public crypto methods** (it's the crown jewels).
- domain managers: ≥ 75%. ViewModels: ≥ 70%. UI: smoke only.
- Gradle tasks: `:crypto:test` (T1, no native, must be green to merge) · `:crypto:test -Pnative` (T2) ·
  `:app:testDebugUnitTest` (T1/T4/T5-VM + Robolectric) · `:app:connectedDebugAndroidTest` (T3/T4-Hilt/T5-UI).
- CI gate: T1 + T4(fakes) + ViewModel on every push; native + androidTest on a nightly/emulator job.

---

## 11. Build order (maps to effort)

1. **T1 crypto** (`Hkdf`, `SymmetricCipher`, `KemRatchetManager`, utils) — no setup, highest risk reduction. *(½ day)*
2. **T2 crypto** (`HybridKem`, `HybridSigner`, `NodeIdentity`, `PrekeyManager`) once liboqs-native is provisioned. *(½ day + native setup)*
3. **Store fakes + adapter conformance + DAO tests.** *(1 day)*
4. **PairingManager + MessageSender + ContactEraser/SecureWipe** with fakes (the security path). *(1–2 days)*
5. **Security/adversarial matrix + regression.** *(½ day)*
6. **ViewModels.** *(1 day)*
7. **Two-party E2E (Hilt) + minimal Compose UI.** *(1–2 days)*

Tiers 1–5 (steps 1–6) need **no production refactor** — the existing constructor-injection
DI makes everything mockable as-is. The optional `SecureChannel` extraction (see
architecture assessment) would further simplify step 4 but is not a prerequisite.
