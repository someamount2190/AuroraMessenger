# Cryptographic Specification

Implementation-accurate description of Aurora's cryptography, extracted from the
`crypto/` module (`com.aura.crypto`) and `app/.../pairing/`. All multi-byte length
prefixes are **4-byte big-endian**. Base64 is unpadded (`NO_WRAP`) on the wire.

> Reflects the crypto re-engineering (FIPS PQC + pure-JVM libraries): see
> [`CRYPTO_MIGRATION_PLAN.md`](CRYPTO_MIGRATION_PLAN.md). Pin review to a tag; constants below
> are quoted from source.

## 1. Primitives & sizes

| Role | Algorithm | Key / output sizes | Provider |
|---|---|---|---|
| KEM | **X-Wing** = **ML-KEM-768** (FIPS 203) + **X25519** | pub ≈ 1216 B, ct ≈ 1120 B, shared secret 32 B | Bouncy Castle (`pqc.crypto.xwing`) |
| Signature | **ML-DSA-65** (FIPS 204) + **Ed25519** | ML-DSA pub 1952 B / sig 3309 B; Ed25519 pub 32 B / sig 64 B | Bouncy Castle |
| AEAD | **XChaCha20-Poly1305** | key 32 B, nonce 24 B, tag 16 B | **Google Tink** |
| KDF | **HKDF (RFC 5869) over HMAC-SHA-256** | output default 32 B; default salt = 32 zero bytes | Bouncy Castle |
| Hash | **SHA3-256** (FIPS 202) | 32 B | Bouncy Castle |
| Backup KDF | **Argon2id** | → 32 B key | Bouncy Castle |

The post-quantum stack is **pure-JVM** (no liboqs/JNI); the whole suite — including PQC
known-answer tests — runs on the CI tier (see [`CRYPTO_TEST_VECTORS.md`](CRYPTO_TEST_VECTORS.md)).

> ⚠ X-Wing tracks an in-progress IETF draft (`draft-connolly-cfrg-xwing-kem`, currently -07),
> not a finalised standard — pin the Bouncy Castle version and re-verify on any bump.

### Wire formats
- **KEM public key:** `[4B len][X-Wing pub ≈1216]` (a single opaque X-Wing blob).
- **KEM ciphertext:** `[4B len][X-Wing ct ≈1120]`.
- **Hybrid verify key:** `[4B len][ML-DSA-65 pub 1952][Ed25519 pub 32]`.
- **Hybrid signature:** `[4B len=3309][ML-DSA-65 sig 3309][Ed25519 sig 64]` (= 3377 B). **Both** halves must verify.
- **AEAD frame:** `[24B nonce][ciphertext ‖ 16B Poly1305 tag]`; optional AAD is bound into the MAC and not transmitted. (Libsodium/IETF XChaCha layout, via Tink.)

X-Wing performs the hybrid KEM combine **internally** (a SHA3-256-based KDF over the ML-KEM-768
and X25519 outputs, binding both ciphertexts), so Aurora no longer composes the secret itself.
An attacker must break **both** ML-KEM-768 and X25519 to recover it.

## 2. Domain-separation labels (verbatim)

Every KDF/derivation is domain-separated. Substituting any field changes the output and is
caught downstream (e.g. by SAS mismatch).

| Label | Use |
|---|---|
| `aura-pair-root-v4\|<init>\|<resp>\|<spkId>\|<opkId>\|<hasOpk>\|` | Forward-secret (PQXDH) root info header |
| `aura-ratchet-v2\|root` | KEM-ratchet root step (HKDF salt=root, ikm=X-Wing ss → 64 B → new root ‖ chain key) |
| `aura-ratchet-v2\|mk` / `aura-ratchet-v2\|ck` | Per-message key / chain-key advance (KEM ratchet) |
| `aura-ratchet-v2\|bootstrap` | Seed for the deterministic responder bootstrap ratchet key |
| `aura-ratchet-v1\|chain\|{low,high}`, `\|mk`, `\|ck` | SAS/media seeding only (legacy symmetric `RatchetManager`; not the message ratchet) |
| `aura-sas-v1` | 8-byte SAS fingerprint from root |
| `aura-sas-id-v1\|<targetNodeIdHex>` | Per-identity SAS code binding |
| `aura-prekey-v1\|<nodeIdHex>\|<kind>\|<id>\|<pubB64>` | Signed message for a published prekey |

(The pre-FIPS labels `aura_kem_v1`, `aura-pair-root-v2/v3`, `aura-pairreq-v1` are **removed** —
clean break.)

## 3. Identity

On first launch the device generates an X-Wing KEM keypair and an ML-DSA-65 + Ed25519 signing
keypair. Identity is bound to the keys:

```
nodeId = SHA3-256( kemPublicKey.toBytes() ‖ signingPublicKey.toBytes() )
```

So a node cannot be impersonated by substituting keys, and the rendezvous can enforce the
binding (see [`PROTOCOL_RENDEZVOUS.md`](PROTOCOL_RENDEZVOUS.md) → STRICT_BINDING). The key
*formats* changed with the FIPS migration, so nodeIds differ from pre-FIPS builds — a one-time
upgrade reset (`StartupMigrations`) clears the dead legacy identity so the user re-pairs.

## 4. QR pairing payload

Compact JSON, QR byte mode, EC level L:
```
{ "v":1, "nodeId":<hex>, "kem":<b64 X-Wing pub>, "ed25519":<b64 32B>, "pk":1, "rdv?":[urls] }
```
Bulky prekeys are **not** in the QR; the scanner fetches the signed bundle from the rendezvous.
`pk >= 1` advertises PQXDH support.

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

A verified PQXDH prekey bundle is **mandatory** — the pre-FIPS identity-only (no-forward-secrecy)
fallback has been removed, so pairing **fails closed** if no usable bundle is available
(closing the downgrade-to-no-FS risk).

### Root derivation (`PairingCrypto.fsRoot`)
- `ikm = sIK ‖ sSPK ‖ (sOPK or empty)`
- `info = "aura-pair-root-v4|<init>|<resp>|<spkId>|<opkId>|<hasOpk>|" ‖ SHA3-256(ctIK) ‖ SHA3-256(ctSpk) ‖ (SHA3-256(ctOpk) or empty)`
- `salt = SHA3-256(responderKemPub)`  (the responder's identity X-Wing public key)
- `root = HKDF-SHA-256(ikm, salt, info, len = 32)`; `ikm` wiped after.

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
| pairaccept | `aura-pairaccept-v1\|<from>\|<to>\|<dilithiumPubB64>` (the field name is historical; it carries the ML-DSA-65 public key) |
| pairverify | `aura-pairverify-v1\|<from>\|<to>` |
| paircancel | `aura-paircancel-v1\|<from>\|<to>` |
| pairreject | `aura-pairreject-v1\|<from>\|<to>` |
| contactremove | `aura-contactremove-v1\|<from>\|<to>` |

## 6. Message ratchet — post-quantum KEM Double Ratchet (`KemDoubleRatchet` / `KemRatchetManager`)

Messages and media are sealed with a **KEM Double Ratchet**: Signal's Double Ratchet with an
**X-Wing** KEM step replacing the DH step, so it provides **forward secrecy *and*
post-compromise security ("healing")** — the gap the older symmetric ratchet had. Full design
and security argument in [`PQ_RATCHET_DESIGN.md`](PQ_RATCHET_DESIGN.md); summary:

- **Seeding.** Both peers derive a shared bootstrap ratchet keypair deterministically from the
  pairing root (`aura-ratchet-v2|bootstrap`). The **initiator** is set up to send first; the
  responder to receive. On pairing completion the initiator **auto-bootstraps** (one sealed
  no-op control frame) so either side can then send first.
- **Root chain.** On each direction change the sender encapsulates to the peer's current ratchet
  key (X-Wing), mixing the fresh shared secret into the root: `(root', chainKey) =
  HKDF-SHA-256(salt=root, ikm=ss, info="aura-ratchet-v2|root", 64B)`. The KEM ciphertext rides
  in the message header; the peer decapsulates with the matching private key. Each receiving
  step **rotates the keypair**, so a state compromise heals once both sides have stepped with
  fresh randomness.
- **Symmetric chains.** Per message: `messageKey = HKDF(chainKey, "...|mk")`,
  `nextChainKey = HKDF(chainKey, "...|ck")`; seal with XChaCha20-Poly1305 (Tink); discard the
  old chain key (forward secrecy). The header (ratchet pubkey, optional ct, `pn`, `n`) is bound
  into the AEAD as associated data.
- **Out-of-order.** Skipped message keys are cached per ratchet epoch (cap **1024**); a frame
  more than **512** ahead is refused (skip-flood guard). KEM-DR property: an epoch's first
  (ciphertext-bearing) message must arrive before later ones in that epoch.
- **Persistence.** The whole session (root, chains, ratchet keypair, peer key, skipped cache) is
  stored as one blob per contact (`kem_ratchet` table); `decrypt` commits only on a successful
  auth, so a forged frame can't corrupt live state.

> The legacy symmetric `RatchetManager` (`aura-ratchet-v1`) is **no longer on the message
> path** — it is retained only to derive/store the SAS fingerprint and the media-at-rest key
> from the pairing root.

## 7. Short Authentication String (SAS)

Each side computes a 6-digit code **bound to a target identity**, locally, never transmitted
(seeded from the root by `RatchetManager` at pairing):
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

- **Signed prekey (SPK):** one current X-Wing keypair, rotated every **7 days** with a 7-day
  grace overlap. **One-time prekeys (OPK):** pool target 20, published in batches; server
  retains at most 100; **consumed (deleted) on first fetch**.
- Each prekey is published with an **Ed25519 signature** (compact, for bandwidth) over
  `aura-prekey-v1|<nodeId>|<kind>|<id>|<pubB64>`, verified by the scanner against the QR's
  authentic Ed25519 key.
- Bundle JSON: `{ "spk": {id,pub,sig}, "opks": [{id,pub,sig}, ...] }`.

## 9. At-rest & backups (summary)
SQLCipher (AES-256) DB; identity keys in EncryptedSharedPreferences under a Keystore master
key; media under a `RatchetManager`-derived key; opt-in backups sealed with XChaCha20-Poly1305
under an Argon2id-derived key. Backups carry the identity, contacts, messages, SAS/media keys,
**and the KEM ratchet sessions** (so a device migration resumes conversations without
re-pairing). See [`DATA_AT_REST.md`](DATA_AT_REST.md) and [`KEY_MANAGEMENT.md`](KEY_MANAGEMENT.md).
