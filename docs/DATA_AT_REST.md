# Data at Rest

Everything persisted on the device, and the key protecting each. Key lifecycle is in
[`KEY_MANAGEMENT.md`](KEY_MANAGEMENT.md).

## Inventory

| Store | Contents | Protected by | Notes |
|---|---|---|---|
| **SQLCipher database** | Messages, contacts, ratchet & prekey state, call/disappearing metadata | DB key under Keystore master key (AES-256) | Schema versioned & exported (`app/schemas/`); DB v6 is the migration anchor |
| **EncryptedSharedPreferences** | Identity private keys, app settings | Keystore-held master key | PQ keys too large for the TEE directly |
| **Encrypted media** | Photos, videos, voice messages | Ratchet-derived media key | Decrypted in-memory only; no plaintext on disk; inside `FLAG_SECURE` window |
| **Encrypted backup (opt-in)** | User-selected export | Argon2id(passphrase) → XChaCha20-Poly1305 | Passphrase never stored |
| **Android Keystore** | Master key (wraps the above) | Hardware/TEE | Root of the at-rest hierarchy |

## What is NOT stored
- No server-side message content, contact graph, or history (rendezvous is in-memory, no logs).
- No plaintext media files.
- No backup passphrase.
- No third-party push tokens (wake is via the contentless `/tap`+`/wait` long-poll).

## Deletion semantics
- **Cryptographic erase:** destroying keys (not bytes) renders all on-disk ciphertext
  unrecoverable instantly — the basis for contact deletion, duress wipe, and the decoy PIN.
- **Disappearing messages:** per-conversation timers erase messages on both devices.
- **Contact deletion** cascades to messages, media, and ratchet keys, and signs a
  `contactremove` so the peer wipes its copy too.

## Hardening at rest
- `FLAG_SECURE` blocks screenshots/screen-recording and content in the recents preview.
- App lock with a **decoy PIN** (opens an empty app) and optional **duress wipe**.

## Residual risk
At-rest protection assumes the device is **locked and uncompromised**. An unlocked or
rooted/malware-infected device (ADV-X / ADV-5 in [`THREAT_MODEL.md`](THREAT_MODEL.md)) defeats
these protections — endpoint security is an explicit non-goal.
