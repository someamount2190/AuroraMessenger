# Audit Scope & Self-Assessment

This document orients an independent reviewer: what Aurora is, what it claims, what it
deliberately does **not** claim, the assumptions it rests on, and the residual risks we
already know about. It is the map; the territory is the source and the rest of `docs/`.

> Status: Aurora is **pre-alpha** and has **not** had an independent third-party security
> audit. Do not rely on it for high-risk threat models until it has.

## How to read this package

| Start here | For |
|---|---|
| [`README.md`](../README.md) | Overview, security claims/non-claims, primitives table |
| [`docs/ARCHITECTURE.md`](ARCHITECTURE.md) | Components, trust boundaries, diagrams |
| [`docs/CRYPTO_SPEC.md`](CRYPTO_SPEC.md) | Primitives, formats, handshake, ratchet, SAS |
| [`docs/PROTOCOL_RENDEZVOUS.md`](PROTOCOL_RENDEZVOUS.md) | Rendezvous wire protocol |
| [`docs/THREAT_MODEL.md`](THREAT_MODEL.md) | STRIDE + LINDDUN threat model (threat IDs) |
| [`docs/KEY_MANAGEMENT.md`](KEY_MANAGEMENT.md) · [`docs/DATA_AT_REST.md`](DATA_AT_REST.md) | Key lifecycle & at-rest data |
| [`docs/DEPENDENCIES.md`](DEPENDENCIES.md) · [`docs/BUILD_REPRODUCIBILITY.md`](BUILD_REPRODUCIBILITY.md) | Supply chain & build |
| [`docs/MONOLITH_AUDIT.md`](MONOLITH_AUDIT.md) | Structural-health pass: monolithic files & decomposition plan |
| [`docs/CRYPTO_TEST_VECTORS.md`](CRYPTO_TEST_VECTORS.md) | Per-primitive test-vector / KAT sources & applicability |
| [`docs/CRYPTO_MIGRATION_PLAN.md`](CRYPTO_MIGRATION_PLAN.md) | Locked plan: replace hand-rolled crypto with libraries, move to FIPS PQC + X-Wing |
| [`SECURITY.md`](../SECURITY.md) | Disclosure policy |

## System under review

- **Android client** (`app/`) — Kotlin, Jetpack Compose, Hilt, Room/SQLCipher.
- **Cryptographic core** (`crypto/`, `com.aura:aura-crypto`) — Android-free, hybrid PQ.
- **Rendezvous server** — standalone Node.js (`rendezvous-server/`) and an embeddable
  in-app server (`com.aura.server`); the hosted instance is `api.auroramessenger.com`.

**Pin the audit to a specific tag/commit.** Versions move; cite the `versionName` and
git tag (e.g. `v0.2.2-pre`) under review so findings map to exact source.

## In scope
Cryptographic core; pairing/verification; rendezvous protocol & servers; at-rest
protection; app hardening; build/supply-chain. (See [`SECURITY.md`](../SECURITY.md) for the
report-eligibility version of this list.)

## Out of scope (non-goals — by design, not oversight)
- **Endpoint security.** A compromised, rooted, or malware-infected device defeats E2E
  encryption. Aurora protects data *in transit* and *at rest*, not a hostile OS.
- **Anonymity / unobservability.** Aurora hides message *content* and *identities*, not the
  fact that you run Aurora or your IP from the rendezvous. No onion routing / traffic-analysis
  resistance in the default path.
- **Availability.** The free single-instance rendezvous offers no DoS guarantees.
- **Groups / multi-device.** Aurora is strictly two-party, one device per identity.

## Assumptions
- The Android Keystore / TEE provides a trustworthy hardware-backed master key.
- The user verifies the SAS code out-of-band (in person or over a trusted channel) — this
  is what upgrades QR pairing from TOFU to MITM-resistant.
- The underlying primitives (Kyber/ML-KEM, Dilithium/ML-DSA, X25519, Ed25519,
  XChaCha20-Poly1305, SHA-3, Argon2id) are secure at their chosen parameter sets, and their
  implementations (liboqs, Bouncy Castle, SQLCipher) are correct.
- TLS + certificate pinning protect the rendezvous transport against a rogue CA.

## Known limitations & residual risks
- **No post-compromise security ("healing") yet.** A session rests on a single root secret
  seeded at pairing; the symmetric double-ratchet gives forward secrecy but not recovery
  after a key compromise. A continuous DH/KEM ratchet is the next phase.
- **Legacy handshake fallback.** If no PQXDH prekey bundle is available, pairing falls back
  to an identity-only handshake (no forward secrecy). The app warns ("paired without forward
  secrecy"), but a network attacker who suppresses the bundle can force the downgrade.
- **Rendezvous learns reachability.** It maps `nodeId → address` (15-min TTL, in-memory,
  no logs) and observes that a device is online; disclosed in the Privacy Policy.
- **Direct-P2P over NAT.** Messages flow peer-to-peer; two peers both behind carrier-grade
  NAT may be unable to deliver without a relay. The optional ShadowMesh relay (off by
  default) is the intended fallback; reliability for two cellular peers is unverified.
- **Dormant code:** the "Host & show my code" LAN-beacon path (`host=true`) exists in
  source but is **not reachable from the shipped UI**; the only live "Show my code" is the
  plain shared-server code. (README reconciliation tracked for Phase F.)
- **Build reproducibility** is not yet established (see `BUILD_REPRODUCIBILITY.md`).
- **No independent audit** has been performed.

## Prior validation (not a substitute for audit)
- JVM/Robolectric unit tests + native instrumented crypto/attack tests
  (see [`docs/TEST_ARCHITECTURE.md`](TEST_ARCHITECTURE.md), [`docs/TEST_STATUS.md`](TEST_STATUS.md)).
- Emulator soak (process/FGS survival, no leaks, no app crashes) and a live **real-phone ↔
  emulator pairing** test confirming the cross-network pairing path over the production
  rendezvous.
