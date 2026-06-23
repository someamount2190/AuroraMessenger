# Crypto Test Vectors & Validation Sources

A reviewer-facing companion to [`CRYPTO_SPEC.md`](CRYPTO_SPEC.md): for every primitive
Aurora uses, **which authoritative test vectors / known-answer tests (KATs) apply**,
where to get them, and the caveat for *this* codebase. It also marks the constructions
that have **no external vector** (project-specific) and must be covered by internal
cross-implementation tests instead.

> TL;DR — **Post crypto re-engineering (see [`CRYPTO_MIGRATION_PLAN.md`](CRYPTO_MIGRATION_PLAN.md)):**
> the PQC layer is now **FIPS-final and pure-Java BouncyCastle** — **ML-KEM-768 + X25519 via
> X-Wing** (KEM) and **ML-DSA-65** (signatures); liboqs/JNI is gone. ML-DSA-65 emits a
> **3309-byte** signature (`HYBRID_SIG_BYTES = 4 + 3309 + 64`). **Validate against
> FIPS-203/204 vectors** (and the X-Wing draft vectors) — no longer round-3. Because the
> whole stack is pure-JVM, **every KAT below can run on the CI tier** (no device required).

## FIPS-final, pure-JVM (read first)

`HybridKem.kt` uses BouncyCastle **X-Wing** (`org.bouncycastle.pqc.crypto.xwing`,
ML-KEM-768 + X25519); `HybridSigner.kt` uses BouncyCastle **ML-DSA-65**
(`org.bouncycastle.pqc.crypto.mldsa`, `MLDSAParameters.ml_dsa_65`). These are the
NIST FIPS-final schemes (FIPS 203 / 204).

- Decisive discriminator: FIPS-204 **ML-DSA-65 sig = 3309 B** (round-3 Dilithium-3 was 3293).
  Aurora now emits 3309 → FIPS. Asserted by `HybridSignerTest.mldsa65_signatureSize_isFips204`.
- **X-Wing caveat:** BouncyCastle tracks an **in-progress IETF draft** (currently
  `draft-connolly-cfrg-xwing-kem-07`), not a finalized standard. Pin the BC version exactly
  and re-verify the X-Wing wire/output on any bump; the X-Wing combine is internal to the
  library (Aurora no longer hand-rolls a KEM combiner).
- **No more device-only PQC tier:** liboqs is removed, so ML-KEM/ML-DSA/X-Wing KATs run as
  ordinary pure-JVM tests in `crypto/src/test`.

## Per-primitive vector map

| Primitive (as used) | Authoritative vectors | Format / location | Caveat for this codebase |
|---|---|---|---|
| **X-Wing** (ML-KEM-768 + X25519) | [draft-connolly-cfrg-xwing-kem](https://datatracker.ietf.org/doc/draft-connolly-cfrg-xwing-kem/) test vectors; BC `XWingTest` | draft App. test vectors (seed → pk/sk/ct/ss) | **Pure-JVM** (BC `org.bouncycastle.pqc.crypto.xwing`). ⚠ draft-07 — pin BC version; combine is internal. |
| **ML-KEM-768** (FIPS 203) | NIST ACVP / [Wycheproof `mlkem_*`](https://github.com/C2SP/wycheproof); BC `MLKEMTest` | ACVP JSON; encap/decap KATs | Pure-JVM. The PQ half of X-Wing (also exercisable directly via BC). |
| **ML-DSA-65** (FIPS 204) | NIST ACVP / [Wycheproof `mldsa_65_*`](https://github.com/C2SP/wycheproof); BC `MLDSATest` | ACVP JSON; sig = **3309 B** | Pure-JVM (BC `org.bouncycastle.pqc.crypto.mldsa`). |
| **X25519** | [RFC 7748 §5.2](https://www.rfc-editor.org/rfc/rfc7748.html) | scalar/u hex+dec; iterated at 1 / 1k / 1M | Pure-JVM. BC ref test: `math.ec.rfc7748.test.X25519Test`. |
| **Ed25519** | [RFC 8032 §7.1](https://www.rfc-editor.org/rfc/rfc8032.html); ref `sign.input` (1024 cases); Wycheproof `ed25519_test.json` | sk/pk/msg/sig hex | Pure-JVM. Used standalone (pairing sigs) + as the classical half of the hybrid. |
| **ChaCha20-Poly1305** | [RFC 8439 §2.8.2](https://www.rfc-editor.org/rfc/rfc8439.html) | key/nonce(12B)/aad/pt→ct+tag | Pure-JVM. The **inner** primitive of Aurora's XChaCha. |
| **XChaCha20-Poly1305** (24-B nonce) | [draft-irtf-cfrg-xchacha-03 App A](https://datatracker.ietf.org/doc/html/draft-irtf-cfrg-xchacha-03); libsodium; **Wycheproof `xchacha20_poly1305_test.json`** (ivSize 192) | draft is **expired / Informational**, never an RFC | Now provided by **Google Tink** (`subtle.XChaCha20Poly1305`); BC still ships no XChaCha engine. **Covered** by `SymmetricCipherKatTest` (Tink vs. an independent clean-room reference, see below). |
| **HKDF** (HMAC-SHA-256) | [RFC 5869 App A](https://www.rfc-editor.org/rfc/rfc5869.html#appendix-A) | 7 cases — HMAC-SHA-256 / SHA-1 | Now **HKDF-SHA-256** (BC `HKDFBytesGenerator`), which RFC 5869 App A **does** cover. **Implemented:** `HkdfRfc5869KatTest` (cases 1–3 + null-salt). |
| **SHA3-256** | [FIPS 202](https://nvlpubs.nist.gov/nistpubs/fips/nist.fips.202.pdf); [NIST CAVP](https://csrc.nist.gov/projects/cryptographic-algorithm-validation-program/secure-hashing) (use the **byte-oriented** zip) | `SHA3_256ShortMsg.rsp`, `SHA3_256LongMsg.rsp`, Monte-Carlo | Pure-JVM (BC `KeccakDigestTest`). Also backs `nodeId = SHA3-256(...)`. |
| **Argon2id** | [RFC 9106 §5.3](https://www.rfc-editor.org/rfc/rfc9106.html#section-5.3); [phc-winner-argon2 `kats/`](https://github.com/P-H-C/phc-winner-argon2) | §5.3 expected tag `0d 64 0d f5 …` | The RFC vector uses **secret + associated-data** inputs; if the backup KDF omits them that exact vector won't match — use the reference repo's parameter-free KATs. BC tests against CFRG draft-03 (numerically == RFC 9106). |

## Constructions with NO external vector (test internally)

These are Aurora-specific compositions; no published KAT exists. Cover them with
round-trip tests **plus** an independent re-implementation cross-check (compute the same
value in a second library / language and assert equality):

- ~~Hybrid KEM combine~~ — **no longer Aurora's:** X-Wing performs the combine internally
  (a library construction with its own draft vectors), so there is nothing project-specific
  to cross-check here anymore.
- ~~HKDF-over-HMAC-SHA3-256~~ — **resolved:** HKDF moved to HMAC-**SHA-256**, which has RFC
  5869 App A vectors (`HkdfRfc5869KatTest`). No bespoke-composition gap remains.
- **Double-ratchet derivation** — `aura-ratchet-v2|chain|{low,high}`, `|mk`, `|ck`
- **SAS** — `aura-sas-v1` (8-B fingerprint) → `aura-sas-id-v1|<nodeId>` (4-B code)
- **PQXDH root** — `aura-pair-root-v4` (FS; the legacy no-FS root has been removed)

## Project Wycheproof (directly relevant — it targets BouncyCastle)

In **`testvectors_v1/`** of [C2SP/wycheproof](https://github.com/C2SP/wycheproof):
`x25519_test.json`, `ed25519_test.json`, `chacha20_poly1305_test.json`,
**`xchacha20_poly1305_test.json`**, `hmac_sha3_256_test.json`, `aes_gcm_test.json`, and
`mlkem_768_test.json` / `mldsa_65_*` (FIPS-final — **now directly applicable** post-migration).
Schema: `{tcId, flags, result: valid|invalid|acceptable}` — edge-case / known-bug vectors
(twists, low-order points, the ML-KEM `strcmp` implicit-rejection bug), complementary to
happy-path KATs.

**Implemented** in `crypto/src/test/.../WycheproofTest.kt` against vendored `testvectors_v1`
files under `src/test/resources/wycheproof/`: **x25519** (518 XDH edge cases — X-Wing's
classical half), **ed25519** (valid + invalid verify incl. malleability — Aurora's signing
path), **xchacha20_poly1305** (24-byte-nonce AEAD groups via `SymmetricCipher`/Tink), and
**mlkem_768** (decapsulation incl. the implicit-rejection "Strcmp" bug, via keygen-from-seed →
decaps == K). The independent-authority complement to the deterministic regression KATs in
`PqcKatTest`.

## Recommended test plan (by tier)

The stack is now entirely pure-JVM, so **there is no device-only PQC tier** — every KAT
below runs in `crypto/src/test` on CI:

- **Pure-JVM (`crypto/src/test`, CI):** SHA3-256 (FIPS-202 CAVP), ChaCha20-Poly1305
  (RFC 8439), **XChaCha20-Poly1305** (done — Tink, see below), **HKDF-SHA-256** (done —
  RFC 5869, `HkdfRfc5869KatTest`), X25519 (RFC 7748, incl. the 1M-iter slow test), Ed25519
  (RFC 8032 + Wycheproof malleability), Argon2id (RFC 9106 §5.3), and now the PQC KATs:
  **ML-KEM-768 / ML-DSA-65** (FIPS 203/204 ACVP + Wycheproof) and **X-Wing** (draft-07
  vectors). ML-DSA-65 sig size (3309 B) is already asserted in `HybridSignerTest`.
- **Cross-library (CI):** the remaining project-specific derivations (ratchet, SAS, PQXDH
  root), pinned against an independent reference value.

### Implemented

- `crypto/src/test/.../ClassicalKatTest.kt` — **authoritative** known-answer tests:
  **Ed25519 RFC 8032 §7.1** (TEST1 empty-message + TEST2, through Aurora's own
  `signEd25519Only`/`verifyEd25519Only`, plus a wrong-key rejection) and **X25519 RFC 7748
  §5.2** (both single vectors + the one-iteration iterative value) on the BC primitive that is
  X-Wing's classical half. Self-validating: each pins a full cryptographic relation, so a
  mistranscribed byte fails the test rather than passing silently.
- `crypto/src/test/.../HkdfRfc5869KatTest.kt` — **RFC 5869 Appendix A** KATs (HMAC-SHA-256
  cases 1–3 + null/empty-salt equivalence) against the BC-backed `Hkdf`. The move from
  HKDF-SHA3-256 to HKDF-SHA-256 is what makes these authoritative external vectors apply.
- `crypto/src/test/.../SymmetricCipherKatTest.kt` — cross-implementation KAT for
  **XChaCha20-Poly1305**, now backed by **Google Tink** (`subtle.XChaCha20Poly1305`). BC
  still ships no XChaCha engine, and hand-transcribing the draft's 130-byte ciphertext proved
  error-prone, so the test checks Tink against an **independent reference of the same
  construction** — a clean-room HChaCha20 (straight from draft §2.2) feeding BouncyCastle's
  trusted RFC-8439 `ChaCha20Poly1305` (12-byte nonce) with inner nonce `0⁴ ‖ nonce[16:24]` —
  requiring both directions across empty / 1 KiB / varied-AAD inputs, plus tamper and
  wrong-AAD rejection. Two independently-written XChaCha constructions agreeing byte-for-byte
  is the known-answer check.
- `crypto/src/test/.../HybridSignerTest.kt` — asserts the **ML-DSA-65 = 3309 B** FIPS-204
  signature size, plus hybrid sign/verify and per-component tamper rejection (pure-JVM).
- `crypto/src/test/.../HybridKemTest.kt` — **X-Wing** encapsulate/decapsulate agreement and
  ciphertext-tamper divergence across the ML-KEM and X25519 regions (pure-JVM).
- `crypto/src/test/.../PqcKatTest.kt` — deterministic KAT/regression harness for **ML-KEM-768**,
  **ML-DSA-65**, and **X-Wing**: a fixed reproducible RNG drives keygen/encaps so outputs are
  deterministic; asserts exact FIPS sizes (incl. ML-DSA-65 = 3309 B), round-trip correctness,
  run-to-run determinism, and a **pinned SHA-256 digest** of each public output as a
  cross-version tripwire (catches a BC bump or X-Wing draft revision silently changing output).
