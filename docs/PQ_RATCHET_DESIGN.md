# Phase 5 — Post-Quantum Asymmetric Ratchet (Design Spec, FOR REVIEW)

> Status: **DESIGN — NOT YET IMPLEMENTED.** This is the named, written specification that
> [`CRYPTO_MIGRATION_PLAN.md`](CRYPTO_MIGRATION_PLAN.md) §6 requires *before* any Phase 5
> code is written. It is **bespoke protocol crypto** and must not be self-certified: it is
> gated on dedicated review (ideally external) before Aurora relies on it. Phase 5 is
> **severable** — Phases 0–4b already ship a fully library-based, FIPS-PQC, pure-JVM app
> with the existing symmetric ratchet; this adds post-compromise security on top.

## 1. Why

Aurora's current ratchet ([`RatchetManager`](../crypto/src/main/kotlin/com/aura/crypto/RatchetManager.kt))
is a **symmetric** double ratchet: two hash chains seeded once from the pairing root. It
gives **forward secrecy** (a key seized today can't read yesterday's traffic) but **no
post-compromise security ("healing")** — an attacker who learns the current chain state reads
everything from then on. This is the open "healing" gap in [`AUDIT_SCOPE.md`](AUDIT_SCOPE.md).

Phase 5 adds an **asymmetric (KEM) ratchet** in the well-studied "Double Ratchet with a KEM
replacing the DH step" pattern, using **X-Wing** (ML-KEM-768 + X25519) as the KEM so healing
is itself hybrid post-quantum. Fresh KEM entropy is mixed into the root on each direction
change, so a compromise heals once both parties have taken a fresh ratchet step.

## 2. Scope & non-goals

- **In scope:** pairwise (1:1) sessions; the asymmetric ratchet step; integration with the
  existing symmetric chains and skipped-key handling; persistence via `RatchetStore`.
- **Non-goals:** groups/multi-device (Aurora is 2-party, one device per identity); changing
  pairing/SAS (unchanged); changing the AEAD/KDF primitives (Tink XChaCha / BC HKDF-SHA-256,
  already in place); a formally-verified construction (we adopt a standard pattern and gate
  on review — we are not inventing novel theory).

## 3. Construction (KEM Double Ratchet)

We follow the Signal Double Ratchet structure, substituting a **KEM ratchet** for the DH
ratchet (as in the literature on KEM-based / post-quantum Double Ratchets, e.g. the
"generic KEM DR" and Apple PQ3 style). Three KDF chains:

- **Root chain** — advanced by each KEM ratchet step; outputs new sending/receiving chain keys.
- **Sending chain / Receiving chain** — symmetric hash chains (unchanged from today): each
  message draws a one-time message key, then the chain key is replaced by its successor.

### 3.1 Per-peer state (extends `RatchetState`)

```
rootKey            RK            32B   current root key
sendChainKey       CKs           32B   (as today)
recvChainKey       CKr           32B   (as today)
sendN, recvN       counters            message numbers in current chains (as today)
selfRatchetSk      X-Wing priv   32B   our current ratchet KEM private key (seed)
selfRatchetPk      X-Wing pub  ~1216B  our current ratchet KEM public key (advertised in headers)
peerRatchetPk      X-Wing pub  ~1216B  peer's latest ratchet KEM public key we've seen
prevSendN          PN            counter   # messages sent in the previous sending chain
skipped cache                          (as today, keyed by (peerRatchetPk-id, n))
```

Roles at seeding stay deterministic by node-id ordering (as today) so both sides agree which
chain is "send" vs "receive".

### 3.2 KEM ratchet step

A **sending** ratchet step happens the first time a party sends after receiving a message
that carried a *new* `peerRatchetPk` (i.e. a direction change), exactly like the DH ratchet
turn in Signal:

```
on send-step (we have peerRatchetPk):
    (ct, ss)   = XWing.encapsulate(peerRatchetPk)        # BC XWingKEMGenerator
    (RK, CKs)  = HKDF-SHA256(salt=RK, ikm=ss, info="aura-ratchet-v2|root")   # 64B → split 32/32
    (selfRatchetSk, selfRatchetPk) = XWing.generateKeyPair()   # rotate our keypair
    prevSendN  = sendN ; sendN = 0
    header carries: (selfRatchetPk, ct, prevSendN, n)

on recv-step (header has peerRatchetPk' != stored peerRatchetPk, and ct):
    ss         = XWing.decapsulate(ct, selfRatchetSk)     # BC XWingKEMExtractor
    (RK, CKr)  = HKDF-SHA256(salt=RK, ikm=ss, info="aura-ratchet-v2|root")
    peerRatchetPk = peerRatchetPk'
    recvN = 0
    # then a symmetric recv as today, walking CKr to message n (caching skipped keys)
```

Message keys and within-chain advancement are **unchanged** from the current
`RatchetManager` (`mk`/`ck` derivations, `MAX_SKIP_AHEAD`, bounded skipped cache), only the
labels bump to `aura-ratchet-v2`.

### 3.3 Wire header

Each frame's header (authenticated as AEAD associated data, as today) gains the ratchet
public key, the KEM ciphertext (present only on a step), and `PN`:

```
header = [ver=2][selfRatchetPk len|bytes][hasCt][ct len|bytes][PN][n]
```

`selfRatchetPk` (~1216B) + `ct` (~1120B) add ~2.3 KB to the *first* message of each
direction change (not every message — only when the chain turns). For a chat app this
overhead is acceptable; if it proves heavy we can ratchet less aggressively (every k
messages) at a documented PCS-latency trade-off.

## 4. Seeding (from pairing)

At pairing, both sides already share the 32-byte root (PQXDH `fsRoot`). Phase 5 seeds:
`RK = fsRoot`; the responder publishes an initial `selfRatchetPk` (it can reuse the signed
prekey, or generate fresh) so the initiator can take the first sending step. Exact bootstrap
(who steps first) to be pinned in review — the safe default mirrors Signal: the initiator
performs a sending KEM step against the responder's prekey-derived ratchet key on the first
message.

## 5. Security properties (claims to be reviewed)

- **Forward secrecy** — retained (symmetric chains discard keys; root advanced one-way).
- **Post-compromise security** — a state compromise heals after the next *completed* ratchet
  step in each direction, because fresh X-Wing entropy is mixed into `RK` and the old KEM
  private keys are wiped.
- **Hybrid PQ** — healing entropy is X-Wing (ML-KEM-768 + X25519); breaking it needs both.
- **Authentication** — message authenticity continues to come from the AEAD + the
  pairing-time mutual authentication / SAS; the ratchet headers are AEAD-AAD-bound.
- **Replay/reorder** — bounded skipped-key cache + per-step counters (`PN`, `n`), as today.

## 6. Test plan (must pass before reliance)

In the existing pure-JVM two-peer simulation (`EndToEndPairMessageSimTest` + a new
`PqRatchetSimTest`):

- Round-trip across many direction changes (each forces a KEM step); both sides agree.
- **Healing test:** snapshot Alice's state; continue the conversation through ≥1 full
  round-trip of ratchet steps; assert the snapshot can no longer decrypt subsequent traffic.
- Out-of-order / dropped / duplicated frames across step boundaries; replay rejected.
- Skipped-key bound (`MAX_SKIP_AHEAD`) and skipped-cache cap enforced across steps.
- Tamper (header `ct`/`selfRatchetPk`, body) → auth failure, no state advance.
- Cross-check the root-chain KDF against an independent reference implementation.

## 7. Open questions for review (do not implement until resolved)

1. **Bootstrap:** who takes the first KEM step, and does the responder's initial ratchet key
   come from the signed prekey or a fresh key? (affects the first-message PCS latency)
2. **Ratchet cadence:** every direction change (max PCS, max overhead) vs. every k messages.
3. **Header size:** is ~2.3 KB per direction-change acceptable on the P2P transport / relay?
4. **Migration:** Phase 5 is a new wire `ver=2`; clean break (re-pair) vs. negotiated upgrade.
5. **Severability decision:** if review judges a bespoke KEM DR too risky to self-certify,
   the documented fallback is to stay on the Phase-4 symmetric ratchet (still a strict
   improvement) and instead bridge Signal's audited **SPQR** crate (Rust/JNI, AGPL) — at the
   cost of reintroducing native code. This spec exists partly so that build-vs-bridge call
   can be made on concrete terms.
