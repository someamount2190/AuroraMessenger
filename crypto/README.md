# aura-crypto

Aurora's post-quantum cryptographic core as a **standalone, pure-JVM library**
(`com.aura:aura-crypto`). It has **no Android dependency**: it talks only to
[liboqs-java](https://github.com/open-quantum-safe/liboqs-java) (post-quantum KEM and
signatures) and [Bouncy Castle](https://www.bouncycastle.org/) (X25519, Ed25519, SHA-3,
ChaCha20-Poly1305), and it persists through storage *interfaces* it defines — the host
app supplies the actual storage. Keeping the cryptography isolated this way makes it
auditable and reusable on its own, outside the Android app.

> The native liboqs `.so` files are **not** part of this artifact; they ship with the
> host app (Android `jniLibs`). This module is just the JVM code that binds to them.

## What's inside

| File | Responsibility |
|---|---|
| `HybridKem` | Key agreement: **Kyber-1024** (post-quantum) + **X25519** (classical) |
| `HybridSigner` | Signatures: **Dilithium-3 / ML-DSA** + **Ed25519** (both must verify) |
| `SymmetricCipher` | **XChaCha20-Poly1305** authenticated encryption |
| `Hkdf` | HKDF over **SHA3-256** |
| `RatchetManager` | Forward-secret symmetric double-ratchet (per-message keys) |
| `PrekeyManager` | PQXDH prekey bundles (identity + signed prekey + one-time prekeys) |
| `NodeIdentity` | `nodeId = SHA3-256(kemPub ‖ signPub)` identity derivation |
| `Stores` | `PrekeyStore` / `RatchetStore` interfaces + their state POJOs |
| `CryptoResult`, `CryptoUtils`, `B64` | Result types and shared helpers |

## Dependencies

`api` (exposed transitively to consumers):

- `org.openquantumsafe:liboqs-java:0.3.0` — JitPack-only; **vendored** in the repo's
  `libs/maven` so the build is standalone.
- `org.bouncycastle:bcprov-jdk15to18:1.78.1`
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
