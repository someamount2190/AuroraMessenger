# aura-crypto

Aurora's post-quantum cryptographic core as a **standalone, pure-JVM library**
(`com.aura:aura-crypto`). It has **no Android dependency and no native code**: it talks
only to [Bouncy Castle](https://www.bouncycastle.org/) (X-Wing ML-KEM-768 + X25519,
ML-DSA-65, Ed25519, SHA-3, HKDF) and [Google Tink](https://developers.google.com/tink)
(XChaCha20-Poly1305), and it persists through storage *interfaces* it defines — the host
app supplies the actual storage. Keeping the cryptography isolated this way makes it
auditable and reusable on its own, outside the Android app, and pure-JVM means the whole
stack — post-quantum primitives included — runs on the CI test tier.

> liboqs/JNI has been **fully removed** (see
> [`docs/CRYPTO_MIGRATION_PLAN.md`](../docs/CRYPTO_MIGRATION_PLAN.md)); there are no
> native `.so` files anywhere in the stack.

## What's inside

| File | Responsibility |
|---|---|
| `HybridKem` | Key agreement: **X-Wing** — **ML-KEM-768** (FIPS 203) + **X25519** |
| `HybridSigner` | Signatures: **ML-DSA-65** (FIPS 204) + **Ed25519** (both must verify) |
| `SymmetricCipher` | **XChaCha20-Poly1305** authenticated encryption (Tink) |
| `Hkdf` | HKDF (RFC 5869) over **HMAC-SHA-256**, plus **SHA3-256** for `nodeId` and salts |
| `KemDoubleRatchet` | Post-quantum **KEM Double Ratchet**: per-message forward secrecy + post-compromise healing via X-Wing ratchet steps (see [`docs/PQ_RATCHET_DESIGN.md`](../docs/PQ_RATCHET_DESIGN.md)) |
| `KemRatchetManager` | Store-backed driver for the ratchet — the single per-contact ratchet for all sealed traffic; also owns the SAS fingerprint + media-at-rest key |
| `PrekeyManager` | PQXDH prekey bundles (identity + signed prekey + one-time prekeys) |
| `NodeIdentity` | `nodeId = SHA3-256(kemPub ‖ signPub)` identity derivation |
| `Stores` | `KemSessionStore` / `PrekeyStore` persistence interfaces + their records |
| `CryptoResult`, `CryptoUtils`, `B64` | Result types and shared helpers |

## Dependencies

`api` (exposed transitively to consumers):

- `org.bouncycastle:bcprov-jdk18on:1.84` — X-Wing, ML-DSA-65, X25519/Ed25519, SHA-3, HKDF
- `com.google.crypto.tink:tink:1.18.0` — XChaCha20-Poly1305 (Bouncy Castle ships no
  XChaCha engine); plain JVM jar, not the Android AAR
- `org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0`

`compileOnly`: `org.json:json` — provided by the Android platform at runtime.

## Build & publish

This module is **not** a subproject of the app build (`settings.gradle.kts` only
includes `:app`). It is built separately and published into the repo's in-repo Maven
repository, then consumed by the app as `com.aura:aura-crypto:0.1.0`:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat -p crypto publish   # → ../libs/maven/com/aura/aura-crypto/0.1.0/
```

Compile against the JVM that runs Gradle (Android Studio's JBR 17); there is no
toolchain auto-detection because the JBR isn't a discoverable system JDK.

## Lineage

These primitives are ported from the earlier **ShadowMesh** project and extracted here
so Aurora builds standalone, with no external sibling folders. See the root
[`README.md`](../README.md) for the full design rationale.

## License

GNU Affero General Public License v3.0 (`AGPL-3.0-or-later`), same as the rest of Aurora.
Built by **Christian Lim Correa**, a Filipino developer.
