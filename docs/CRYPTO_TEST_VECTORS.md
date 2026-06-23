# Crypto Test Vectors & Validation Sources

A reviewer-facing companion to [`CRYPTO_SPEC.md`](CRYPTO_SPEC.md): for every primitive
Aurora uses, **which authoritative test vectors / known-answer tests (KATs) apply**,
where to get them, and the caveat for *this* codebase. It also marks the constructions
that have **no external vector** (project-specific) and must be covered by internal
cross-implementation tests instead.

> TL;DR — Aurora calls liboqs by the **round-3** mechanism names `Kyber1024` /
> `Dilithium3` (not the FIPS-final `ML-KEM-1024` / `ML-DSA-65`). The 3293-byte Dilithium
> signature (`HYBRID_SIG_BYTES = 4 + 3293 + 64`) is the decisive proof. **Validate the
> PQC layer against round-3 KATs, not FIPS-203/204 ACVP vectors — they will not match.**

## The round-3 vs FIPS distinction (read first)

`HybridKem.kt` / `HybridSigner.kt` request `KeyEncapsulation("Kyber1024")` and
`Signature("Dilithium3")`. These are NIST **round-3** mechanisms. liboqs-java 0.3.0
(April 2025 — *not* the unrelated 2020 liboqs **C** 0.3.0) bundles a modern liboqs that
keeps the round-3 `Kyber1024`/`Dilithium3` mechanisms **alongside** the FIPS
`ML-KEM-1024`/`ML-DSA-65` ones, selectable by name.

- Decisive discriminator: round-3 **Dilithium-3 sig = 3293 B**; FIPS-204 **ML-DSA-65 = 3309 B**.
  Aurora emits 3293 → round-3. (liboqs ML-DSA page confirms 3309.)
- Round-3 vectors ≠ FIPS vectors: FIPS 203/204 added domain separation, so the KATs differ.
- **⚠ Maintenance risk:** round-3 Kyber was removed in liboqs ≥ 0.13 and round-3 Dilithium
  in ≥ 0.15. A future liboqs-java built on a newer liboqs may stop exposing
  `Kyber1024`/`Dilithium3` entirely. Pin/track the bundled liboqs version; plan the
  migration to `ML-KEM-1024`/`ML-DSA-65` (and switch to FIPS KATs) deliberately.

## Per-primitive vector map

| Primitive (as used) | Authoritative vectors | Format / location | Caveat for this codebase |
|---|---|---|---|
| **Kyber-1024** (round-3) | [pq-crystals/kyber `round3`](https://github.com/pq-crystals/kyber/tree/round3); liboqs `tests/KATs/kem/kats.json` | `PQCkemKAT_*.rsp` (NIST-DRBG-seeded) + `SHA256SUMS`; liboqs checks sha256-of-output in [`tests/test_kat.py`](https://github.com/open-quantum-safe/liboqs/blob/main/tests/test_kat.py) | Native-only → **device/instrumented tier**, not JVM. Use round-3, not FIPS-203 ACVP. |
| **Dilithium-3** (round-3) | [pq-crystals/dilithium `round3`](https://github.com/pq-crystals/dilithium/tree/round3) | `PQCsignKAT_*.{req,rsp}` + `SHA256SUMS` | Device tier; sig must be 3293 B. |
| **X25519** | [RFC 7748 §5.2](https://www.rfc-editor.org/rfc/rfc7748.html) | scalar/u hex+dec; iterated at 1 / 1k / 1M | Pure-JVM. BC ref test: `math.ec.rfc7748.test.X25519Test`. |
| **Ed25519** | [RFC 8032 §7.1](https://www.rfc-editor.org/rfc/rfc8032.html); ref `sign.input` (1024 cases); Wycheproof `ed25519_test.json` | sk/pk/msg/sig hex | Pure-JVM. Used standalone (pairing sigs) + as the classical half of the hybrid. |
| **ChaCha20-Poly1305** | [RFC 8439 §2.8.2](https://www.rfc-editor.org/rfc/rfc8439.html) | key/nonce(12B)/aad/pt→ct+tag | Pure-JVM. The **inner** primitive of Aurora's XChaCha. |
| **XChaCha20-Poly1305** (24-B nonce) | [draft-irtf-cfrg-xchacha-03 App A](https://datatracker.ietf.org/doc/html/draft-irtf-cfrg-xchacha-03); libsodium; **Wycheproof `xchacha20_poly1305_test.json`** (ivSize 192) | draft is **expired / Informational**, never an RFC | Aurora **hand-rolls** HChaCha20 + ChaCha20-Poly1305 in `SymmetricCipher` because **bcprov 1.78.1 has no XChaCha engine** → the construction needs its own test. **Covered now** by `SymmetricCipherKatTest` (see below). |
| **HKDF** | [RFC 5869 App A](https://www.rfc-editor.org/rfc/rfc5869.html#appendix-A) | 7 cases — **HMAC-SHA-256 / SHA-1 only** | **Does NOT cover Aurora's HKDF-over-HMAC-SHA3-256.** No standard vector for that combination (see below). |
| **HMAC-SHA3-256** | Wycheproof **`hmac_sha3_256_test.json`**; NIST ACVP (dynamic) | JSON; legacy CAVP `.rsp` is SHA-1/2 only | Sub-primitive is testable; the *composed* HKDF-SHA3 is not. |
| **SHA3-256** | [FIPS 202](https://nvlpubs.nist.gov/nistpubs/fips/nist.fips.202.pdf); [NIST CAVP](https://csrc.nist.gov/projects/cryptographic-algorithm-validation-program/secure-hashing) (use the **byte-oriented** zip) | `SHA3_256ShortMsg.rsp`, `SHA3_256LongMsg.rsp`, Monte-Carlo | Pure-JVM (BC `KeccakDigestTest`). Also backs `nodeId = SHA3-256(...)`. |
| **Argon2id** | [RFC 9106 §5.3](https://www.rfc-editor.org/rfc/rfc9106.html#section-5.3); [phc-winner-argon2 `kats/`](https://github.com/P-H-C/phc-winner-argon2) | §5.3 expected tag `0d 64 0d f5 …` | The RFC vector uses **secret + associated-data** inputs; if the backup KDF omits them that exact vector won't match — use the reference repo's parameter-free KATs. BC tests against CFRG draft-03 (numerically == RFC 9106). |

## Constructions with NO external vector (test internally)

These are Aurora-specific compositions; no published KAT exists. Cover them with
round-trip tests **plus** an independent re-implementation cross-check (compute the same
value in a second library / language and assert equality):

- **Hybrid KEM combine** — `HKDF(kyberSS ‖ x25519SS, info = "aura_kem_v1")`
- **HKDF-over-HMAC-SHA3-256** — the extract-then-expand glue (sub-primitives SHA3-256 and
  HMAC-SHA3-256 have vectors; the composition does not). Cross-check vs an independent
  HKDF-SHA3 (e.g. Python `cryptography`/`hashlib`, Go `x/crypto/hkdf`+`sha3`).
- **Double-ratchet derivation** — `aura-ratchet-v1|chain|{low,high}`, `|mk`, `|ck`
- **SAS** — `aura-sas-v1` (8-B fingerprint) → `aura-sas-id-v1|<nodeId>` (4-B code)
- **PQXDH root** — `aura-pair-root-v2` (legacy) / `aura-pair-root-v3` (FS)

## Project Wycheproof (directly relevant — it targets BouncyCastle)

In **`testvectors_v1/`** of [C2SP/wycheproof](https://github.com/C2SP/wycheproof):
`x25519_test.json`, `ed25519_test.json`, `chacha20_poly1305_test.json`,
**`xchacha20_poly1305_test.json`**, `hmac_sha3_256_test.json`, `aes_gcm_test.json`, and
now `mlkem_1024_test.json` / `mldsa_65_*` (FIPS-final — useful only *after* a FIPS
migration). Schema: `{tcId, flags, result: valid|invalid|acceptable}` — edge-case /
known-bug vectors (twists, low-order points, the ML-KEM `strcmp` implicit-rejection bug),
complementary to happy-path KATs. There is **no `hkdf_sha3_*`** file.

## Recommended test plan (by tier)

Aurora's current crypto tests are round-trip / property / adversarial — **no external
KATs yet**. Add them where they can run:

- **Pure-JVM (`crypto/src/test`, CI):** SHA3-256 (FIPS-202 CAVP), HMAC-SHA3-256
  (Wycheproof), ChaCha20-Poly1305 (RFC 8439), **XChaCha20-Poly1305** (done — see below),
  X25519 (RFC 7748, incl. the 1M-iter slow test), Ed25519 (RFC 8032 + Wycheproof
  malleability), Argon2id (RFC 9106 §5.3).
- **Device (`app/src/androidTest`, native `.so` present):** Kyber-1024 + Dilithium-3
  **round-3** KATs from pq-crystals — the only tier where liboqs runs.
- **Cross-library (CI script):** HKDF-SHA3-256 and the project-specific derivations,
  pinned against an independent reference value.

### Implemented

- `crypto/src/test/.../SymmetricCipherKatTest.kt` — cross-implementation KAT for the
  hand-rolled **XChaCha20-Poly1305**. bcprov 1.78.1 ships no XChaCha engine, and
  hand-transcribing the draft's 130-byte ciphertext proved error-prone (two independent
  web transcriptions disagreed and were the wrong length), so the test builds an
  **independent reference of the same construction** — a clean-room HChaCha20 (written
  straight from draft §2.2, independent of the production HChaCha20) feeding BouncyCastle's
  trusted RFC-8439 `ChaCha20Poly1305` (12-byte nonce) with inner nonce `0⁴ ‖ nonce[16:24]`
  — and requires the two implementations to interoperate **both directions** across empty
  / 1 KiB / varied-AAD inputs, plus tamper and wrong-AAD rejection (5 tests, green). Two
  independently-written XChaCha constructions agreeing byte-for-byte is the known-answer
  check.
