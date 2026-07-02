# Crypto Re-Engineering — Locked Migration Plan

> Status: **LOCKED & IN PROGRESS.** Plan of record for replacing every hand-rolled
> cryptographic construction in Aurora with vetted library implementations and moving the
> post-quantum parameters to the FIPS-final standards.
>
> **Done (committed, all tests green):** Phase 0 (deps → BC 1.84 + Tink; liboqs removed),
> Phase 1 (`Hkdf` → BC HKDF-SHA-256 + RFC 5869 KATs), Phase 2 (`SymmetricCipher` → Tink
> XChaCha20-Poly1305), Phase 3 (`HybridSigner` → BC ML-DSA-65 + Ed25519), Phase 4a
> (`HybridKem` → X-Wing ML-KEM-768; prekeys/identity/backup/pairing on single-blob KEM
> keys; **liboqs + native `.so` fully removed** → whole PQC stack now pure-JVM on CI).
>
> **Also done:** Phase 4b (legacy no-FS fallback removed — pairing now fails closed without a
> verified PQXDH bundle) and the test-vector / audit-scope doc refresh (§7).
>
> **Phase 5 (post-quantum asymmetric ratchet): WIRED IN — now the ONLY ratchet.** The X-Wing
> KEM Double Ratchet is the single per-contact ratchet — `KemDoubleRatchet` + `KemRatchetCodec`
> + store-backed `KemRatchetManager` drive `MessageSender`/`TcpMessageServer`/`MediaTransfer`
> **and** call/RTC signaling (`CallSignalCodec`/`RtcSignalCodec`), persisted to the `kem_ratchet`
> table (DB v10); pairing seeds initiator/responder and the initiator auto-bootstraps on pairing
> completion. The old symmetric `RatchetManager` has been **retired**: its SAS fingerprint and
> media-at-rest key are folded into the KEM ratchet's session blob, and the `ratchet_state` /
> `ratchet_skipped` tables are dropped (MIGRATION_9_10).
> So Aurora's messages now have **post-compromise security (healing)** on the wire. It remains
> **bespoke protocol crypto** that should get dedicated review before production reliance
> (see [`PQ_RATCHET_DESIGN.md`](PQ_RATCHET_DESIGN.md)).

Companion reading: [`CRYPTO_SPEC.md`](CRYPTO_SPEC.md) (current design),
[`CRYPTO_TEST_VECTORS.md`](CRYPTO_TEST_VECTORS.md) (validation sources — updated by this plan),
[`AUDIT_SCOPE.md`](AUDIT_SCOPE.md) (residual risks this closes).

---

## 1. Goals and non-negotiables

From the approved decisions:

1. **Strictly use libraries.** No hand-rolled primitives survive: no hand-rolled HChaCha20,
   no hand-rolled HKDF/HMAC, no hand-rolled KEM combiner. Every cryptographic *operation* is
   a library call.
2. **Drop liboqs entirely.** Move off the JNI/native `liboqs-java` to **pure-Java
   BouncyCastle**. This is the single biggest structural win: the whole crypto stack becomes
   pure-JVM, which removes native `.so` payloads from the APK and — critically — lets the
   **entire** crypto test suite (including PQC KATs) run on the CI/JVM tier instead of a
   device-only tier.
3. **FIPS post-quantum parameters.** Kyber-1024 → **ML-KEM** (FIPS 203), Dilithium-3 →
   **ML-DSA** (FIPS 204).
4. **X-Wing hybrid KEM** (ML-KEM-768 + X25519, `draft-connolly-cfrg-xwing-kem`).
5. **Keep Aurora's QR-pairing + SAS.** We are not adopting Signal's registration / PreKey /
   server model.
6. **Ratchet = Aurora-driven state machine on library primitives, + a post-quantum
   asymmetric ratchet step** (see §6). The protocol orchestration stays ours and pure-JVM
   testable; the dangerous crypto underneath it is all library code.
7. **Clean break.** Do not prioritise backward compatibility. Existing paired sessions are
   invalidated on upgrade; migration of old sessions is explicitly out of scope and deferred.

### Why no "library Double Ratchet" instead

We researched this exhaustively (libsignal, Kodium, vodozemac, Virgil, Olm/libolm,
`double-ratchet-2`, SPQR, the academic/hobby long tail). The conclusion is firm: **no
audited, maintained, pure-JVM, post-quantum Double Ratchet library exists that can be seeded
from our own pairing root.** libsignal's ratchet is `pub(crate)`/internal-only and all-or-
nothing (would need a Rust fork + the whole Signal model); Kodium is a days-old, single-
author, self-admittedly-unaudited 1.0.0 with a vendored unaudited ML-KEM; vodozemac and the
Rust crates are classical-only or PQ-but-Rust (reintroducing the native dependency and the
JNI bridge we are removing). Adopting any of them either fails Path B or trades our reviewed
code for *less*-reviewed code. So we keep the orchestration in-tree and make every primitive
a library. The highest-risk new element — the PQ asymmetric ratchet step (§6) — is isolated
into its own phase and flagged for dedicated review.

---

## 2. The locked stack

| Layer | Today (hand-rolled / liboqs) | **Target (library)** |
|---|---|---|
| AEAD | `SymmetricCipher`: hand-rolled HChaCha20 + BC `ChaCha20Poly1305` | **Tink `XChaCha20Poly1305`** (`com.google.crypto.tink:tink-android:1.18.0`) |
| KDF | `Hkdf`: hand-rolled HKDF over hand-rolled HMAC-SHA3-256 | **BC `HKDFBytesGenerator`** with **SHA-256** (RFC 5869) |
| Hash / nodeId | BC `SHA3Digest(256)` | **unchanged** — SHA3-256 (FIPS 202, has CAVP vectors) |
| Hybrid KEM | `HybridKem`: liboqs `Kyber1024` + BC X25519, custom HKDF combiner | **BC X-Wing** (ML-KEM-768 + X25519), `org.bouncycastle.pqc.crypto.xwing` |
| Hybrid signature | `HybridSigner`: liboqs `Dilithium3` + BC Ed25519, length-prefixed concat | **BC composite `ML-DSA-65 + Ed25519`** (primary) / manual ML-DSA-65 ‖ Ed25519 (documented fallback) |
| Ed25519-only fast path (QR / rendezvous) | BC `Ed25519Signer` | **unchanged** — already a library, kept for bandwidth |
| Ratchet | `RatchetManager`: symmetric chains, hand-rolled-HKDF derivations | **Aurora state machine on BC-HKDF + Tink-AEAD, + X-Wing asymmetric ratchet step** (§6) |
| Prekeys (PQXDH) | `PrekeyManager`: hybrid Kyber+X25519 prekeys | **X-Wing prekeys** (§5.5) |

### 2.1 Dependencies (`gradle/libs.versions.toml`)

```diff
- liboqs          = "0.3.0"
- bcprov          = "1.78.1"
+ bcprov          = "1.84"
+ tink            = "1.18.0"
```

```diff
- liboqs   = { module = "org.openquantumsafe:liboqs-java", version.ref = "liboqs" }
- bcprov   = { module = "org.bouncycastle:bcprov-jdk15to18", version.ref = "bcprov" }
+ bcprov   = { module = "org.bouncycastle:bcprov-jdk18on",   version.ref = "bcprov" }
+ tink     = { module = "com.google.crypto.tink:tink-android", version.ref = "tink" }
```

Notes (verified against Maven Central `maven-metadata.xml` and bc-java/tink-java source):
- **`bcprov-jdk18on:1.84`** is the latest released non-FIPS line (2026-04-14). All PQC —
  ML-KEM, ML-DSA, composite signatures, **and** X-Wing — ships in `bcprov` alone; we do **not**
  need `bcpkix`/`bcutil` (those are only for X.509/PKIX/CMS). Switch artifact from the legacy
  `-jdk15to18` (Java 5–8) to `-jdk18on` (Java 8+, multi-release).
- **Tink `1.18.0`** is the current Maven release (2025-06-18). BC's XChaCha20 engine exists
  only on the unreleased `main` branch (targets 1.85), so BC cannot supply XChaCha in 2026 —
  Tink does, libsodium-compatible.
- The `:crypto` module's BC dependency moves to `bcprov-jdk18on`; Tink is added to `:crypto`
  too (the AEAD lives there). `tink-android` is fine for the pure-JVM `:crypto` module's
  runtime needs; if its Android packaging causes friction in the JVM module we use
  `com.google.crypto.tink:tink:1.18.0` there and `tink-android` in `:app`.

---

## 3. Component-by-component design

The **public method signatures of every crypto class are preserved** so the DI graph
(`AppModule.kt`) and all 27 call sites stay byte-for-byte unchanged. Only class *internals*
and wire-format *constants* change. This is the key to a contained blast radius.

### 3.1 `SymmetricCipher` → Tink XChaCha20-Poly1305

- Replace the private `hChaCha20(...)` + `ChaCha20Poly1305` internals with Tink's
  `com.google.crypto.tink.subtle.XChaCha20Poly1305` (an `Aead` taking a 32-byte key;
  `encrypt(plaintext, aad)` returns `nonce(24) ‖ ciphertext ‖ tag(16)`).
- **Wire format is identical** (`[24B nonce][ct‖tag]`, libsodium/IETF layout), so
  `KEY_BYTES=32`, `NONCE_BYTES=24`, `MAC_BYTES=16` and the `[nonce‖…]` framing are unchanged
  — **no caller changes, no stored-data format change for the AEAD layer**.
- Keep `suspend` + `CryptoResult` + `ioDispatcher` wrapper; keep `generateKey()`.
- The existing `SymmetricCipherKatTest` (clean-room HChaCha20 cross-check) becomes a
  cross-implementation check between **Tink and the same independent reference**, and we add
  the **Wycheproof `xchacha20_poly1305_test.json`** vectors (now trivially runnable pure-JVM).

### 3.2 `Hkdf` → BC HKDFBytesGenerator (SHA-256)

- Replace hand-rolled extract/expand/HMAC with:
  ```kotlin
  val hkdf = HKDFBytesGenerator(SHA256Digest())
  hkdf.init(HKDFParameters(ikm, salt, info))   // salt == null → HashLen zeros (RFC 5869)
  val okm = ByteArray(outputLen); hkdf.generateBytes(okm, 0, outputLen)
  ```
- **Digest changes SHA3-256 → SHA-256.** Rationale: (a) FIPS-aligned and the RFC 5869
  standard; (b) it gives us **external KATs** — RFC 5869 Appendix A covers HMAC-SHA-256, which
  HKDF-over-SHA3-256 never had. This is a clean-break-only change (every derived secret
  changes), which is acceptable per goal #7.
- Keep the `derive(ikm, salt?, info, outputLen)` signature so `PairingCrypto`, `HybridKem`,
  `RatchetManager` callers are unchanged.
- `sha3_256(...)` and `hmacSha3_256(...)` helpers: `sha3_256` stays (still used for nodeId and
  salts). `hmacSha3_256` is removed if unused after the HKDF swap (confirm no external caller).

### 3.3 `HybridKem` → BC X-Wing

- Replace liboqs `Kyber1024` + the hand-rolled `aura_kem_v1` HKDF combiner with BC X-Wing via
  the lightweight API (X-Wing is **not** JCA-registered — no `getInstance("XWing")`):
  ```kotlin
  // keygen
  val kpg = XWingKeyPairGenerator().apply { init(XWingKeyGenerationParameters(rng)) }
  val kp = kpg.generateKeyPair()
  // encapsulate (sender, recipient public key)
  val enc = XWingKEMGenerator(rng).generateEncapsulated(kp.public)
  val ss = enc.secret; val ct = enc.encapsulation
  // decapsulate (recipient private key)
  val ss2 = XWingKEMExtractor(priv as XWingPrivateKeyParameters).extractSecret(ct)
  ```
- **X-Wing performs the hybrid combine internally** (draft-07, SHA3-256-based, binding the
  ML-KEM ciphertext and X25519 values into the 32-byte shared secret). So we **delete** the
  bespoke `INFO_ENCAP`/transcript-binding HKDF combiner — the library subsumes it. We may
  still HKDF the X-Wing SS with an Aurora context label for domain separation, but the MITM-
  substitution binding is no longer our responsibility.
- **Data classes change** (`HybridPublicKey`, `HybridPrivateKey`, `HybridCiphertext`): the
  two-part Kyber+X25519 representation collapses to the **X-Wing encoded blobs**
  (pub ≈ 1216 B = 1184 ML-KEM-768 + 32 X25519; ct ≈ 1120 B = 1088 + 32; ss = 32). Use BC's
  `XWing*KeyParameters.getEncoded()`. Keep `toBytes()/fromBytes()` so wire framing helpers
  survive; bump any embedded version tag.
- The `generateKyberKeyPair()`/`generateX25519KeyPair()` sub-methods are removed; callers only
  use `generateKeyPair()`/`encapsulate()`/`decapsulate()` (verified: that's all the call
  sites use).
- **⚠ Risk:** BC's X-Wing is pinned to **draft-07**, a moving target, and is the lowest-
  maturity BC feature (not JCA-registered). Pin `bcprov` to an exact version; treat the X-Wing
  wire output as *not yet a stabilised standard* and re-verify on any BC bump. Documented in §8.

### 3.4 `HybridSigner` → BC composite ML-DSA-65 + Ed25519

- Full hybrid signature → **BC composite signature** (`org.bouncycastle.jcajce.provider.
  asymmetric.compositesignatures`, e.g. the `MLDSA65-Ed25519` registration,
  `draft-ietf-lamps-pq-composite-sigs`). Composite binds the two component signatures with
  domain separation (stronger than naive concatenation — resists stripping). Exact JCA
  algorithm string / `KeyPairGenerator` name to be confirmed against the 1.84 javadoc at
  implementation; the capability is present in released 1.84.
  - **Documented fallback** (if the composite JCA surface proves awkward): manual
    **ML-DSA-65 ‖ Ed25519**, i.e. `Signature.getInstance("ML-DSA","BC")` with
    `MLDSAParameterSpec.ml_dsa_65` (sig = **3309 B**) length-prefixed with a BC `Ed25519Signer`
    sig (64 B). This is the exact shape of today's `HybridSigner`, just Dilithium→ML-DSA — two
    library calls, no hand-rolled crypto. Lock composite as primary, this as the safety net.
- **Ed25519-only fast path is unchanged** (already BC `Ed25519Signer`): `signEd25519Only`,
  `verifyEd25519Only`, `verifyEd25519OnlySync`, `verifyHybridEd25519PartSync` stay — QR and
  rendezvous can't carry a 3.3 KB PQ signature. These are libraries, not hand-rolled.
- The hybrid sig length constant changes `DILITHIUM3_SIG_BYTES = 3293` → ML-DSA-65
  (3309 B) or the composite's combined size. `deserializeHybridSig`'s length check updates
  accordingly.

### 3.5 `PrekeyManager` → X-Wing prekeys

- Prekeys become **X-Wing** keypairs instead of Kyber+X25519 hybrids. `generateLocked` uses
  `HybridKem.generateKeyPair()` (now X-Wing) — so most of this class is unchanged because it
  already delegates to `HybridKem`.
- Persisted prekey columns (`kyberPubB64`/`x25519PubB64`/`kyberPrivB64`/`x25519PrivB64`)
  collapse to a single X-Wing pub/priv blob each. Room schema change → clean break (wipe
  prekeys on upgrade; they regenerate). The published-bundle JSON shape (`{spk, opks}`) and
  the Ed25519 `prekeyMessage` signing are unchanged.
- Bump `aura-prekey-v1` → `aura-prekey-v2`.

### 3.6 `PairingCrypto` → unchanged logic, new Hkdf

- `legacyRoot`/`fsRoot`/`sasEquals`/`nodeIdMatches` are pure HKDF/SHA3 math — they keep working
  on the new `Hkdf` (SHA-256) and the new key encodings unchanged in shape.
- Bump root labels for the clean break: `aura-pair-root-v2` → `v3-legacy` is retired (the
  legacy identity-only fallback is a known downgrade risk in `AUDIT_SCOPE`; **this migration is
  the moment to drop the no-FS fallback entirely** — recommend removing `legacyRoot` and
  requiring a PQXDH bundle, closing that residual risk). `aura-pair-root-v3` → `aura-pair-root-v4`.
- `nodeIdMatches` updates to the new `HybridPublicKey`/`HybridVerifyKey` encodings.

---

## 4. Wire-format & parameter version bumps (clean break)

Every cross-peer constant is bumped so a new client never tries to interpret old material:

| Constant | Old | New |
|---|---|---|
| KEM context | `aura_kem_v1` | (subsumed by X-Wing; if kept, `aura_kem_v2`) |
| Ratchet derivation prefix | `aura-ratchet-v1` | `aura-ratchet-v2` |
| PQXDH FS root | `aura-pair-root-v3` | `aura-pair-root-v4` |
| Legacy root | `aura-pair-root-v2` | **removed** (no-FS fallback dropped) |
| Prekey signing | `aura-prekey-v1` | `aura-prekey-v2` |
| SAS | `aura-sas-v1` / `aura-sas-id-v1` | `aura-sas-v2` / `aura-sas-id-v2` |
| nodeId | SHA3-256(kemPub‖signPub) | same hash, new key encodings → new IDs |

On upgrade: contacts/prekeys/ratchets are wiped; users re-pair. App messaging communicates
"re-pair required after the security upgrade." Documented in `AUDIT_SCOPE` known-limitations.

---

## 5. Phasing

Ordered low-risk → high-risk so value lands early and the dangerous piece is isolated.

- **Phase 0 — Dependency swap & scaffolding.** Update `libs.versions.toml`, add Tink, switch
  to `bcprov-jdk18on:1.84`, remove `liboqs`. Register BC provider once at startup. Build green
  with the old code paths still compiling where possible.
- **Phase 1 — `Hkdf` → BC HKDF-SHA-256.** Smallest, self-contained. Add RFC 5869 KATs.
- **Phase 2 — `SymmetricCipher` → Tink XChaCha.** Wire format identical → no caller churn.
  Retarget `SymmetricCipherKatTest`; add Wycheproof XChaCha vectors.
- **Phase 3 — `HybridSigner` → ML-DSA-65 + Ed25519 (composite).** Add FIPS-204 / Wycheproof
  `mldsa_65` vectors (now CI-tier). Keep Ed25519-only path.
- **Phase 4 — `HybridKem` → X-Wing; `PrekeyManager` → X-Wing prekeys; `PairingCrypto`
  bumps.** Drop the legacy no-FS fallback. Add ML-KEM-768 / X-Wing draft vectors. End-to-end
  pairing sim updated. **At the end of Phase 4 the symmetric ratchet still runs as today** —
  the app is fully migrated to libraries + FIPS PQC, pure-JVM, no liboqs.
- **Phase 5 — PQ asymmetric ratchet step (§6).** Highest risk; gated on its own spec + review.
  Adds post-compromise security (healing). Can ship after Phase 4 independently.

---

## 6. Phase 5 — the post-quantum asymmetric ratchet (design sketch, needs dedicated review)

The pre-Phase-5 `RatchetManager` (since retired) was a **symmetric** ratchet: two hash chains
seeded once at pairing. It gave forward secrecy but **no post-compromise security** (what was
the open "healing" gap in `AUDIT_SCOPE`). Phase 5 adds an **asymmetric (KEM) ratchet** in the
well-studied "Double
Ratchet with a KEM replacing the DH step" pattern, using **X-Wing** as the KEM so it is hybrid
post-quantum:

- Each peer holds a current **ratchet KEM keypair** (X-Wing) and publishes the public half in
  message headers.
- When a peer begins a new sending chain, it **encapsulates to the peer's latest ratchet
  public key**, mixes the resulting shared secret into the root via HKDF to derive the new
  sending chain key, and includes `(X-Wing ciphertext, its own fresh ratchet public key)` in
  the header. The receiver decapsulates, mixes identically, and rotates its own keypair.
- Symmetric chains, skipped-key cache, `MAX_SKIP_AHEAD`, persistence — **unchanged**.

**This is the only genuinely novel protocol element and the highest-risk item in the whole
migration.** A KEM/PQ asymmetric ratchet is exactly what Signal spent years and F* formal
verification on (SPQR). Constraints for Phase 5:
- Written against a **named, written spec** (this section expanded into `CRYPTO_SPEC.md`), not
  ad hoc.
- Covered by adversarial tests (out-of-order across ratchet steps, dropped headers, replay,
  forced re-key) in the existing two-peer JVM simulation.
- Explicitly flagged for external review before it is relied upon; until then, the Phase-4
  symmetric ratchet remains the shipping default and Phase 5 is opt-in / behind a flag.
- If review concludes a bespoke PQ asymmetric ratchet is too risky to self-certify, the
  fallback is to **stay at the Phase-4 symmetric ratchet** (still a strict improvement: all
  library primitives + FIPS PQC) and revisit a Rust-bridged SPQR later. Phase 5 is severable.

---

## 7. Testing — the big win

Because the new stack is **pure-Java** (BouncyCastle) + **pure-Java** (Tink), the PQC tests
that were previously **device/instrumented-only** (liboqs native) now run on the **CI/JVM
tier**. `CRYPTO_TEST_VECTORS.md` is updated by this plan:

- **Move PQC KATs from the device tier to CI**, and **switch from round-3 to FIPS-final
  vectors**: ML-KEM-768 (FIPS 203 / Wycheproof `mlkem_*`), ML-DSA-65 (FIPS 204 / Wycheproof
  `mldsa_65_*`), X-Wing (draft-connolly test vectors — caveat draft-07).
- **HKDF gains external KATs** for the first time: RFC 5869 Appendix A (HMAC-SHA-256).
- **XChaCha20-Poly1305**: keep the cross-implementation check, add Wycheproof
  `xchacha20_poly1305_test.json`.
- SHA3-256 (FIPS-202 CAVP), X25519 (RFC 7748), Ed25519 (RFC 8032 + Wycheproof) as before.
- The constructions that *still* have no external vector shrink to: the ratchet derivations
  (`aura-ratchet-v2|…`), SAS, and the PQXDH root — covered by round-trip + the two-peer sim.

---

## 8. Risks & open items (carried into implementation)

1. **X-Wing is draft-07 in BC** and not JCA-registered (lightweight API only). Pin the exact
   BC version; re-verify interop/output on any bump; treat the wire format as not-yet-stable.
2. **Composite ML-DSA JCA surface** — confirm the exact algorithm string / spec class against
   the BC 1.84 javadoc in Phase 3; manual ML-DSA-65 ‖ Ed25519 is the locked fallback.
3. **`tink-android` in a pure-JVM module** — if packaging fights the JVM `:crypto` module, use
   `com.google.crypto.tink:tink:1.18.0` there and `tink-android` only in `:app`.
4. **Phase 5 PQ asymmetric ratchet** is bespoke protocol crypto and must not be self-certified;
   it is severable from the rest of the migration (see §6).
5. **Clean break** invalidates all existing pairings; ensure the app surfaces a clear "re-pair
   required" path and that wipe-on-upgrade is complete (contacts, prekeys, ratchets, skipped
   keys).
6. **APK / startup** — removing liboqs drops native `.so`; confirm no remaining native load
   and that BC provider registration happens exactly once.

---

## 9. Blast radius

All crypto is constructor-injected via `AppModule.kt` behind stable public methods
(`encrypt`/`decrypt`/`derive`/`encapsulate`/`decapsulate`/`sign`/`verify`/`sealNext`/`open`).
**Preserving those signatures keeps the ~27 call-site files unchanged**; edits concentrate in:

- `crypto/src/main/kotlin/com/aura/crypto/`: `SymmetricCipher`, `Hkdf`, `HybridKem`,
  `HybridSigner`, `PrekeyManager`, `RatchetManager` (+ their data classes), `NodeIdentity`.
- `app/src/main/kotlin/com/aura/pairing/PairingCrypto.kt` (labels, key encodings).
- `gradle/libs.versions.toml` (deps).
- Room: prekey entity columns (X-Wing blobs) → a schema bump / destructive migration.
- Docs: `CRYPTO_SPEC.md`, `CRYPTO_TEST_VECTORS.md`, `AUDIT_SCOPE.md`.
- Tests: retarget `SymmetricCipherKatTest`, add the now-CI-tier PQC/HKDF KATs, update the
  end-to-end pairing sim.
