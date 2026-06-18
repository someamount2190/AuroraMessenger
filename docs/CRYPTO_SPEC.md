# Cryptographic Specification

Implementation-accurate description of Aurora's cryptography, extracted from the
`crypto/` module (`com.aura.crypto`) and `app/.../pairing/`. All multi-byte length
prefixes are **4-byte big-endian**. Base64 is unpadded (`NO_WRAP`) on the wire.

> Pin review to a tag (e.g. `v0.2.2-pre`); constants below are quoted from source.

## 1. Primitives & sizes

| Role | Algorithm | Key / output sizes | Provider |
|---|---|---|---|
| KEM | **Kyber-1024 (ML-KEM)** + **X25519** | Kyber pub 1568 B, X25519 pub 32 B; shared secret 32 B (after combine) | liboqs-java; Bouncy Castle |
| Signature | **Dilithium-3 (ML-DSA)** + **Ed25519** | Dilithium pub 1952 B / sig 3293 B; Ed25519 pub 32 B / sig 64 B | liboqs-java; Bouncy Castle |
| AEAD | **XChaCha20-Poly1305** | key 32 B, nonce 24 B, tag 16 B | Bouncy Castle |
| KDF | **HKDF (RFC 5869) over HMAC-SHA3-256** | output default 32 B; default salt = 32 zero bytes | Bouncy Castle |
| Hash | **SHA3-256** | 32 B | Bouncy Castle |
| Backup KDF | **Argon2id** | → 32 B key | Bouncy Castle |

### Wire formats
- **Hybrid KEM public key:** `[4B len][Kyber pub 1568][X25519 pub 32]`
- **Hybrid KEM ciphertext:** `[4B len][Kyber ct][X25519 ephemeral pub 32]`
- **Hybrid verify key:** `[4B len][Dilithium pub 1952][Ed25519 pub 32]`
- **Hybrid signature:** `[4B len=3293][Dilithium-3 sig 3293][Ed25519 sig 64]` (= 3361 B). **Both** halves must verify.
- **AEAD frame:** `[24B nonce][ciphertext ‖ 16B Poly1305 tag]`; optional AAD is bound into the MAC and not transmitted.

The hybrid KEM combines `kyberSharedSecret ‖ x25519SharedSecret` through HKDF bound to the
info label `aura_kem_v1`, yielding a 32-byte secret. An attacker must break **both** Kyber
and X25519 to recover it.

## 2. Domain-separation labels (verbatim)

Every KDF/derivation is domain-separated. Substituting any field changes the output and is
caught downstream (e.g. by SAS mismatch).

| Label | Use |
|---|---|
| `aura_kem_v1` | Hybrid KEM secret combine |
| `aura-pair-root-v2` | Legacy (identity-only) pairing root |
| `aura-pair-root-v3\|<init>\|<resp>\|<spkId>\|<opkId>\|<hasOpk>\|` | Forward-secret (PQXDH) root info header |
| `aura-ratchet-v1\|chain\|low` / `...\|chain\|high` | Initial receive/send chain keys (by node order) |
| `aura-ratchet-v1\|mk` | Per-message key from chain key |
| `aura-ratchet-v1\|ck` | Chain-key advance |
| `aura-sas-v1` | 8-byte SAS fingerprint from root |
| `aura-sas-id-v1\|<targetNodeIdHex>` | Per-identity SAS code binding |
| `aura-prekey-v1\|<nodeIdHex>\|<kind>\|<id>\|<pubB64>` | Signed message for a published prekey |

## 3. Identity

On first launch the device generates a hybrid KEM keypair and a hybrid signing keypair.
Identity is bound to the keys:

```
nodeId = SHA3-256( kemPublicKey.toBytes() ‖ signingPublicKey.toBytes() )
```

So a node cannot be impersonated by substituting keys, and the rendezvous can enforce the
binding (see [`PROTOCOL_RENDEZVOUS.md`](PROTOCOL_RENDEZVOUS.md) → STRICT_BINDING).

## 4. QR pairing payload

Compact JSON, QR byte mode, EC level L:
```
{ "v":1, "nodeId":<hex>, "kem":<b64 hybrid KEM pub>, "ed25519":<b64 32B>, "pk":1, "rdv?":[urls] }
```
Bulky prekeys are **not** in the QR (they would overflow past the Kyber key); the scanner
fetches the signed bundle from the rendezvous. `pk >= 1` advertises PQXDH support.

## 5. PQXDH pairing handshake

```mermaid
sequenceDiagram
  participant S as Scanner (initiator)
  participant R as Rendezvous
  participant H as Host (responder)
  H->>R: publish signed prekey bundle (SPK + OPKs)
  Note over S: scan QR (authentic KEM + Ed25519 keys)
  S->>R: GET /prekeys/<host>  (pops one OPK)
  R-->>S: { spk, opk }
  S->>S: verify each prekey's Ed25519 sig vs QR key
  S->>S: encapsulate to IK, SPK, OPK → sIK,sSPK,sOPK + cts
  S->>S: root = fsRoot(...)  (store root; never sent)
  S->>R: signal pairreq (keys + ciphertexts + ids, signed)
  R-->>H: wake; H drains pairreq
  H->>H: verify sig; consume own SPK/OPK privs; decapsulate
  H->>H: root = fsRoot(...)  (same 32B root)
  Note over S,H: both move to VERIFY; do mutual SAS (§7)
```

### Root derivation (`PairingCrypto`)
- **Legacy (no prekey bundle):** `root = HKDF(ikm = s_KEM, info = "aura-pair-root-v2", len = 32)`.
  Used only as a fallback; **no forward secrecy**. The app raises a `WeakPairing` warning when
  a peer advertised prekeys but none were usable (downgrade signal).
- **Forward-secret (PQXDH):**
  - `ikm = sIK ‖ sSPK ‖ (sOPK or empty)`
  - `info = "aura-pair-root-v3|<init>|<resp>|<spkId>|<opkId>|<hasOpk>|" ‖ SHA3-256(ctIK) ‖ SHA3-256(ctSpk) ‖ (SHA3-256(ctOpk) or empty)`
  - `salt = SHA3-256(responderKyberPub)`
  - `root = HKDF(ikm, salt, info, len = 32)`; `ikm` wiped after.

The info/salt bind the full transcript (both identities, prekey ids, all ciphertexts, the
responder's identity key), so any tamper or key substitution yields a different root → SAS
mismatch → pairing fails closed. Forward secrecy comes from the **one-time prekey being
destroyed on consume**: an attacker who records the handshake and later steals the long-term
identity key still cannot reconstruct `sOPK`.

### Signed pairing messages (Ed25519 over UTF-8 pipe strings)
These travel as opaque `/signal` payloads (the rendezvous never parses them):

| Message | Signed canonical string |
|---|---|
| pairreq (PQXDH) | `aura-pairreq-v2\|<from>\|<to>\|<ctIK>\|<ctSpk>\|<ctOpk or ->\|<spkId>\|<opkId or ->` |
| pairreq (legacy) | `aura-pairreq-v1\|<from>\|<to>\|<ctIK>` |
| pairaccept | `aura-pairaccept-v1\|<from>\|<to>\|<dilithiumPubB64>` |
| pairverify | `aura-pairverify-v1\|<from>\|<to>` |
| paircancel | `aura-paircancel-v1\|<from>\|<to>` |
| pairreject | `aura-pairreject-v1\|<from>\|<to>` |
| contactremove | `aura-contactremove-v1\|<from>\|<to>` |

## 6. Double ratchet (`RatchetManager`)

Seeded from the 32-byte pairing root, which is then destroyed:
- `low = HKDF(root, "aura-ratchet-v1|chain|low", 32)`, `high = HKDF(root, "...|chain|high", 32)`
- Roles by lexicographic node order: the lower nodeId takes `low` as **receive** chain, the
  higher takes it as **send** (symmetric, deterministic on both sides).
- `sasFingerprint = HKDF(root, "aura-sas-v1", 8)`; a random media key is generated.
- `root.fill(0)`.

Per message:
- `messageKey = HKDF(chainKey, "aura-ratchet-v1|mk", 32)`; `nextChainKey = HKDF(chainKey, "aura-ratchet-v1|ck", 32)`.
- Encrypt with XChaCha20-Poly1305 under `messageKey`; advance the chain (old chain key discarded).

Out-of-order: skipped message keys are cached (cap **1024** per contact) and used once; frames
more than **512** ahead of the receive counter are refused (skip-flood guard). This gives
**forward secrecy** (each message key is discarded) but, by design, **no post-compromise
healing** yet — the session still rests on the single seeded root (see non-goals).

## 7. Short Authentication String (SAS)

Each side computes a 6-digit code **bound to a target identity**, locally, never transmitted:
```
bound  = HKDF(ikm = sasFingerprint(8B), info = "aura-sas-id-v1|<targetNodeIdHex>", len = 4)
bits20 = (bound[0]<<12) | (bound[1]<<4) | (bound[2] >> 4)
code   = bits20 mod 1_000_000   (zero-padded to 6 digits)
```
At pairing each device shows its own code and types the other's. A man-in-the-middle derives a
**different root** on each leg, so the fingerprints — and thus the codes — won't match, and
entry fails. ~20 bits of comparison (≈1-in-1,048,576 blind-collision chance per attempt; entry
is attempt-limited).

## 8. Prekeys (`PrekeyManager`)

- **Signed prekey (SPK):** one current hybrid keypair, rotated every **7 days** with a 7-day
  grace overlap. **One-time prekeys (OPK):** pool target 20, published in batches; server
  retains at most 100; **consumed (deleted) on first fetch**.
- Each prekey is published with an **Ed25519 signature** (compact, for bandwidth) over
  `aura-prekey-v1|<nodeId>|<kind>|<id>|<pubB64>`, verified by the scanner against the QR's
  authentic Ed25519 key.
- Bundle JSON: `{ "spk": {id,pub,sig}, "opks": [{id,pub,sig}, ...] }`.

## 9. At-rest & backups (summary)
SQLCipher (AES-256) DB; identity keys in EncryptedSharedPreferences under a Keystore master
key; media under a ratchet-derived key; opt-in backups sealed with XChaCha20-Poly1305 under an
Argon2id-derived key. See [`DATA_AT_REST.md`](DATA_AT_REST.md) and [`KEY_MANAGEMENT.md`](KEY_MANAGEMENT.md).
