# Test Implementation Status

Tracks what's built against the plan in [`TEST_ARCHITECTURE.md`](TEST_ARCHITECTURE.md).
Last updated: 2026-06-23 (post crypto re-engineering — pure-JVM, no liboqs/native tier).

## Summary

| | Verified green | Skipped |
|---|---|---|
| `crypto` module | **146** | 0 |
| `app` module | **83** | 0 |
| **Total** | **229** | 0 |

Run (both pure-JVM/Robolectric, no native deps, CI-friendly):
- `./gradlew -p crypto test` → 146 pass.
- `./gradlew :app:testDebugUnitTest` → 83 pass.

## Crypto — all pure-JVM now ✅

The liboqs/JNI removal means the **entire** crypto suite (incl. the post-quantum primitives)
runs on CI; there is no longer a device-only "native" tier or any `assumeTrue` skip guards.

- **Primitives:** `HkdfTest` + `HkdfRfc5869KatTest` (RFC 5869 KATs), `SymmetricCipherTest` +
  `SymmetricCipherKatTest` (Tink XChaCha vs. clean-room reference), `HybridKemTest` (X-Wing),
  `HybridSignerTest` (ML-DSA-65 + Ed25519, incl. the 3309-byte FIPS size), `NodeIdentityTest`,
  `PrekeyManagerTest`, utils.
- **Known-answer / known-bug vectors:** `PqcKatTest` (deterministic ML-KEM-768 / ML-DSA-65 /
  X-Wing sizes + regression digests), `WycheproofTest` (x25519 / ed25519 / xchacha /
  mlkem_768 / **mldsa_65 verify** edge + known-bug corpora — incl. ML-DSA context binding,
  modified-signature, zero-key, and incorrect-length rejection), `ClassicalKatTest`
  (Ed25519 RFC 8032, X25519 RFC 7748).
- **KEM Double Ratchet (Phase 5):** `KemDoubleRatchetTest` (round-trips, out-of-order,
  simultaneous steps, tamper/replay, **post-compromise healing**), `KemRatchetCodecTest`
  (wire frame + session persistence round-trips), `KemRatchetManagerTest` (store-backed,
  initiator-first auto-bootstrap, healing through persistence). `RatchetManagerTest` still
  covers the symmetric SAS/media path.

## App ✅

- **Storage** (Robolectric + in-memory Room): `RoomDaoTest` (incl. the `kem_ratchet`
  backup/load/delete round-trip), `StoreAdapterConformanceTest`.
- **Integration sims** (Robolectric): `EndToEndPairMessageSimTest` (real MessageSender +
  TcpMessageServer over the in-memory transport, driving the live KEM ratchet),
  `PqxdhHandshakeSimTest` (the real X-Wing PQXDH handshake — both peers derive the same root),
  plus the two-peer rendezvous/transport sims.
- **Security/pure-logic:** `MediaStoreTest`, `ContactEraserTest`, `WireFramesTest`,
  `StreaksTest`, `CheckinSigningTest` (the signing-string contract shared with the Node server).

## Pending — device/UI only ⛔

Still best authored on an emulator (Hilt graph / Compose / real Keystore), not a JVM sandbox:
ViewModel (Turbine) + Compose UI tests, the Keystore-backed `SecureWipe` / `Backups` round-trip,
and an on-device end-to-end capstone (pair → verify → text → media → delete). The optional
`androidTest` crypto demo/attack suite validates the (now pure-JVM) primitives under the real
Android runtime.

## Infra added
- `crypto/build.gradle.kts`: `kotlin-test-junit`, `kotlinx-coroutines-test`, `org.json` (test).
- `app/build.gradle.kts`: `kotlin-test-junit`, `kotlinx-coroutines-test`, real `org.json`,
  `aura-crypto`, Robolectric, `androidx.test:core`, Turbine; `testOptions.unitTests.isReturnDefaultValues = true`.
