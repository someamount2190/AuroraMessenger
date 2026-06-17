# Test Implementation Status

Tracks what's built against the plan in [`TEST_ARCHITECTURE.md`](TEST_ARCHITECTURE.md).
Last updated: 2026-06-16.

## Summary

| | Authored | Verified green | Skipped (env-gated) |
|---|---|---|---|
| `crypto` module | 82 | **60** | 22 (need native liboqs) |
| `app` module | 42 | **42** | — |
| **Total** | **124** | **102** | 22 |

Run:
- `./gradlew -p crypto test` → 60 pass, 22 skip (T2 skips without native).
- `./gradlew :app:testDebugUnitTest` → 42 pass (Robolectric works in CI/JVM).

## Done & green ✅

**T1 — crypto pure-JVM** (`crypto/src/test`): `HkdfTest` (11), `SymmetricCipherTest` (10),
`RatchetManagerTest` (20 — incl. out-of-order, single-use skipped keys, replay, forged-frame-
doesn't-burn-chain, skip-flood >512, SAS match + MITM mismatch, skipped-cap, concurrency),
`CryptoUtilsTest` (7), `B64Test` (4), `CryptoResultTest` (8). Fakes: `FakeRatchetStore`, `FakePrekeyStore`.

**T3 — storage** (`app/src/test`, Robolectric + in-memory Room): `RoomDaoTest` (14, all 5 DAOs),
`StoreAdapterConformanceTest` (4 — same contract run against the fakes *and* the Room adapters).

**T4 — security managers** (verifiable subset, Robolectric): `MediaStoreTest` (5 — at-rest
encryption, no-plaintext-on-disk), `ContactEraserTest` (2 — cryptographic erase, prior frames
unreadable after wipe).

**App pure-logic**: `WireFramesTest` (6), `StreaksTest` (8), `CheckinSigningTest` (3 — the
canonical signing-string contract shared with the Node rendezvous server).

## Done but skip-guarded ⏭️ (run on emulator or a host with native liboqs)

**T2 — crypto needing liboqs** (`crypto/src/test`, `@Before assumeTrue(NativeCrypto.available)`):
`HybridKemTest` (7 — KEM round-trip, hybrid-binding, codec), `HybridSignerTest` (7 — both-parts-
must-verify anti-downgrade, Ed25519 fallback), `NodeIdentityTest` (4 — nodeId binding, tamper-
reject), `PrekeyManagerTest` (4 — signed bundle, OPK single-use). These **skip** on a plain JVM
(no native) and **run for real** on an Android emulator (jniLibs) or a host with desktop liboqs
on `java.library.path`.

## Pending — need native liboqs AND/OR emulator to author-and-verify ⛔

These are fully specified (method-level) in `TEST_ARCHITECTURE.md` but not yet written, because
they exercise real Kyber/Dilithium (native) and/or Hilt/Compose on a device, so they can't be
authored-and-verified in a plain JVM sandbox:

- **T4 native managers**: `PairingManager` (handshake + SAS + MITM, contact lifecycle),
  `MessageSender` / `TcpMessageServer` (seal→wire→open round-trip, offline queue),
  `ReactionManager`, `DisappearingManager`, `SecureWipe` (Keystore-backed).
- **T5 ViewModels** (`MainDispatcherRule` + Turbine + fake/mock managers).
- **T5 Compose UI** (`androidTest`, `createAndroidComposeRule`).
- **Two-party E2E capstone** (`androidTest` + Hilt): pair → verify → text → media → react →
  disappearing → delete, both DBs converge.

Recommended next step for these: run on the existing emulators (they have the native libs), where
the T2 suite also flips from skipped to executed.

## Infra added
- `crypto/build.gradle.kts`: `kotlin-test-junit`, `kotlinx-coroutines-test`, `org.json` (test).
- `app/build.gradle.kts`: `kotlin-test-junit`, `kotlinx-coroutines-test`, real `org.json`,
  `aura-crypto`, Robolectric, `androidx.test:core`, Turbine; `testOptions.unitTests.isReturnDefaultValues = true`.
