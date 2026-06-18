# Key Management

Lifecycle of every key, from generation to destruction. Primitives and sizes are in
[`CRYPTO_SPEC.md`](CRYPTO_SPEC.md); at-rest layout in [`DATA_AT_REST.md`](DATA_AT_REST.md).

## Identity keys
- **Generated on first launch** (`com.aura.crypto.NodeIdentity` + `com.aura.identity`): a hybrid
  KEM keypair (Kyber-1024 + X25519) and a hybrid signing keypair (Dilithium-3 + Ed25519).
- `nodeId = SHA3-256(kemPub ‖ signPub)` — the identity is a commitment to the keys.
- **Storage:** private keys live in **EncryptedSharedPreferences**, encrypted under a master
  key held in the **Android Keystore** (hardware/TEE-backed). The post-quantum keys are too
  large to live inside the Keystore TEE directly, so this master-key-wraps-ESP design is the
  pragmatic hardware-backed compromise.
- **Rotation:** identity does **not** rotate — because `nodeId` is bound to the keys, rotating
  keys means a **new identity** (re-pairing). A documented trade-off (see threat model
  residual risks).

## Database key
- The **SQLCipher** database key is held under the Keystore-backed master key; the DB (messages,
  contacts, ratchet & prekey state) is AES-256 encrypted at rest.

## Session keys (per contact)
- A 32-byte **pairing root** is derived via PQXDH at pairing (`CRYPTO_SPEC` §5), used to seed the
  double ratchet, then **destroyed** (`root.fill(0)`).
- The ratchet derives **per-message keys** that are used once and discarded (forward secrecy).
- **Limitation:** no post-compromise healing yet — the session rests on the one seeded root.

## Prekeys (PQXDH)
- **Signed prekey (SPK):** one current hybrid keypair, rotated every **7 days** with a **7-day
  grace** overlap (old SPK accepted during overlap).
- **One-time prekeys (OPK):** pool target **20**, published in batches; the server keeps at most
  **100**; each OPK is **deleted on first consume** — an initiator who records a handshake and
  later steals the identity key still cannot reconstruct the one-time secret.
- Each prekey is Ed25519-signed (`aura-prekey-v1|...`) and verified by the scanner against the
  QR's authentic key.

## Media & backup keys
- **Media:** a ratchet-derived media key encrypts media at rest; media is decrypted in-memory for
  display, never written as plaintext.
- **Backups (opt-in):** a backup key is derived from the user passphrase via **Argon2id**; the
  backup is sealed with **XChaCha20-Poly1305**. The passphrase is never stored.

## Destruction — cryptographic erase
`SecureWipe` destroys the **keys**, not the bytes: flash wear-leveling makes byte-scrubbing
unreliable, and keyless ciphertext is noise instantly. This backs contact deletion, the
**duress wipe**, and the **decoy PIN**. Erasing the master key renders the DB, ESP, and media
permanently unrecoverable.
