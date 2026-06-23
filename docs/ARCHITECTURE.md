# Architecture & Trust Boundaries

This document maps Aurora's components, the **control-plane vs data-plane** split, and the
**trust boundaries** that the [threat model](THREAT_MODEL.md) reasons over. Diagrams are
Mermaid (render on GitHub).

## 1. Components

```mermaid
flowchart TB
  subgraph DevA["Device A (Android)"]
    UA["UI · Jetpack Compose"]
    VMA["ViewModels / Coordinators<br/>(PairingCoordinator, CallController, SyncEngine)"]
    CA["com.aura.crypto<br/>(HybridKem, HybridSigner, SymmetricCipher,<br/>Hkdf, RatchetManager, PrekeyManager)"]
    STA["Storage<br/>(SQLCipher DB, EncryptedSharedPreferences,<br/>Android Keystore master key)"]
    NA["Network<br/>(RendezvousClient, MessageSender/Receiver, WebRTC)"]
    WA["WakeService (foreground)"]
  end

  subgraph DevB["Device B (Android)"]
    CB["com.aura.crypto"]
    NB["Network"]
  end

  RZ["Rendezvous server<br/>(api.auroramessenger.com)<br/>nodeId to address, in-memory, no logs"]

  UA --> VMA --> CA
  VMA --> STA
  VMA --> NA
  NA <-->|"control plane: TLS+pinned<br/>checkin / find / signal / wait / prekeys"| RZ
  NB <-->|control plane| RZ
  NA <==>|"data plane: direct P2P<br/>XChaCha20-Poly1305 sealed frames + WebRTC"| NB
  WA -. keeps alive .-> NA
```

The crypto core (`crypto/`, `com.aura:aura-crypto`) is **Android-free** and persists through
storage interfaces (`RatchetStore`, `PrekeyStore`) the app implements — so it can be
reviewed and tested in isolation from the platform.

## 2. Trust-boundary data-flow diagram (DFD)

This is the canonical diagram the STRIDE analysis walks. Dashed boxes are trust boundaries.

```mermaid
flowchart LR
  subgraph TB_A["── Trust boundary: Device A (user-controlled) ──"]
    A_app["Aurora app + crypto core"]
    A_db[("Encrypted DB / Keystore")]
  end

  subgraph TB_SRV["── Trust boundary: rendezvous operator (UNTRUSTED for content) ──"]
    SRV["Rendezvous process<br/>(nodeId to address map, signal queue)"]
  end

  subgraph TB_B["── Trust boundary: Device B (peer) ──"]
    B_app["Aurora app + crypto core"]
  end

  A_app <-->|"1. signed check-in / find / authed signal+wait (TLS, pinned)"| SRV
  SRV <-->|"1. signed check-in / find / authed signal+wait (TLS, pinned)"| B_app
  A_app <==>|"2. E2E sealed messages & calls (direct P2P)"| B_app
  A_app --- A_db
```

**Boundaries & assumptions**
- **Device A / B (trusted to their own user):** holds private keys and plaintext. Endpoint
  compromise is an explicit non-goal.
- **Rendezvous operator (untrusted for content):** sees `nodeId → address` and that a device
  is reachable; never sees message content or private keys. Treated as a potential adversary
  in the threat model (a malicious or compromised operator).
- **Network (untrusted):** passive eavesdropper and active MITM assumed everywhere.

## 3. Control plane vs data plane

The server only helps peers *find* each other; it is never in the path of content.

```mermaid
sequenceDiagram
  participant A as Device A
  participant R as Rendezvous (control plane)
  participant B as Device B
  Note over A,R: Control plane (metadata only, via TLS+pinned)
  A->>R: checkin (signed nodeId to address)
  B->>R: checkin + park /wait
  A->>R: find / signal to B (opaque payload)
  R-->>B: wake (/wait returns)
  B->>R: drain signal (authenticated)
  Note over A,B: Data plane (content) — server NOT involved
  A-->>B: direct P2P: XChaCha20-Poly1305 sealed frames
  A-->>B: WebRTC media (calls), E2E
```

## 4. Key hierarchy & lifecycle

```mermaid
flowchart TD
  HW["Android Keystore (TEE-backed)"] -->|wraps| MK["Master key"]
  MK -->|encrypts| ESP["EncryptedSharedPreferences"]
  ESP --> IK["Hybrid identity keys<br/>(X-Wing: ML-KEM-768+X25519 KEM, ML-DSA-65+Ed25519 sign)"]
  IK --> NID["nodeId = SHA3-256(kemPub ‖ signPub)"]
  MK -->|derives/holds| DBK["SQLCipher DB key"]
  DBK --> DB[("Messages, contacts,<br/>ratchet & prekey state")]
  IK -->|seeds via PQXDH| ROOT["Per-contact pairing root (32B)"]
  ROOT -->|seeds, then destroyed| RAT["Double-ratchet chain keys"]
  RAT --> MKEY["Per-message keys (used once, discarded)"]
  ERASE["Cryptographic erase (SecureWipe)"] -. destroys .-> MK
  ERASE -. destroys .-> IK
  ERASE -. renders unreadable .-> DB
```

Because post-quantum keys are too large for the Keystore TEE directly, they live in
EncryptedSharedPreferences under a Keystore-held master key (the pragmatic hardware-backed
compromise). Erase destroys keys, not bytes — keyless ciphertext is noise instantly.

## 5. Data-at-rest map

```mermaid
flowchart LR
  K1["Keystore master key"] --> P1["EncryptedSharedPreferences:<br/>identity keys, settings"]
  K2["SQLCipher DB key"] --> P2[("SQLCipher DB:<br/>messages, contacts,<br/>ratchet/prekey state")]
  K3["Per-media key (ratchet-derived)"] --> P3["Encrypted media (app-private)"]
  K4["Backup key = Argon2id(passphrase)"] --> P4["Opt-in encrypted backup<br/>(XChaCha20-Poly1305)"]
```

Details in [`DATA_AT_REST.md`](DATA_AT_REST.md) and [`KEY_MANAGEMENT.md`](KEY_MANAGEMENT.md).

## 6. Call setup (WebRTC)

```mermaid
sequenceDiagram
  participant A as Caller
  participant R as Rendezvous
  participant B as Callee
  A->>R: signal: call offer (opaque, E2E)
  R-->>B: wake
  B->>R: drain offer
  B->>R: signal: answer
  R-->>A: deliver answer
  A-->>B: ICE / DTLS-SRTP media (direct P2P, E2E)
  Note over A,B: Media never traverses the rendezvous.<br/>Co-located emulators cannot complete ICE (no TURN) — see AUDIT_SCOPE.
```

Signaling (offer/answer/ICE candidates) is relayed as opaque payloads through the rendezvous
signal queue; media flows peer-to-peer and is end-to-end encrypted by WebRTC's DTLS-SRTP on
top of the authenticated session.
