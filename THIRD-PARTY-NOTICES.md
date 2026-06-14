# Third-Party Notices

Aurora Messenger is licensed under the GNU Affero General Public License v3.0
(see `LICENSE`). It builds on the following third-party components, each under its
own license. All are permissively licensed and compatible with AGPL-3.0.

| Component | Purpose | License |
|---|---|---|
| liboqs / liboqs-java (Open Quantum Safe) | Kyber-1024 KEM + Dilithium-3 signatures (post-quantum) | MIT |
| Bouncy Castle (bcprov) | X25519, Ed25519, SHA-3, Argon2id | MIT (Bouncy Castle License) |
| SQLCipher for Android (net.zetetic) | Encrypted database | BSD-style (Zetetic) |
| AndroidX Room | Database ORM / migrations | Apache-2.0 |
| AndroidX Security-Crypto | EncryptedSharedPreferences / Keystore-backed vaults | Apache-2.0 |
| AndroidX SQLite | SQLite support | Apache-2.0 |
| Jetpack Compose (UI, Foundation, Material3, Navigation, Lifecycle, Activity) | UI toolkit | Apache-2.0 |
| AndroidX Core-KTX, ShareTarget | Core utilities, direct-share shortcuts | Apache-2.0 |
| Hilt / Dagger | Dependency injection | Apache-2.0 |
| Kotlin Coroutines | Concurrency | Apache-2.0 |
| OkHttp | HTTP client (rendezvous) | Apache-2.0 |
| NanoHTTPD | In-app rendezvous server | BSD-3-Clause |
| WebRTC (io.github.webrtc-sdk) | Voice/video calls | BSD-3-Clause (+ third-party notices) |
| ZXing core + zxing-android-embedded | QR encode/scan | Apache-2.0 |
| JUnit (test only) | Unit tests | EPL-1.0 |

The standalone rendezvous server (`rendezvous-server/index.js`) uses only the
Node.js standard library (`http`, `crypto`), with no third-party runtime dependencies.

Full license texts are available from each project's repository. This list is
provided for attribution and is not legal advice.
