# Group Chat — Degraded Carry Relay (Piece 3) — Spec

**Status:** **Engine implemented on branch `feature/group-chat` (2026-06-21).** All three pieces
below are built, wired, and green (unit-tested; cross-CGNAT delivery still needs the 2-device test
the rest of the data-channel work awaits). Commits: piece-3 foundation `2b028c1`, piece 1
`a8492cf`, piece 2 `afc0c5f`, piece-3 activation `5b4df63`. **Remaining surface: UI** (group
creation, group conversation rendering with sender names, member management) — not yet built.

Part of the group-chat feature, which splits into three independent pieces:
1. **Group object + admin-signed membership** (`ctl`-based, fully-connected membership).
2. **Pairwise fan-out** messaging (reuse the per-contact double ratchet).
3. **Degraded carry relay** — best-effort, in-group, store-carry-forward delivery to offline
   members. **This doc specs piece 3.**

**Read with:** `KOTLIN_SOP.md`, `WEBRTC_DATA_TRANSPORT_PATCH.md` (transports this rides on),
`SHADOWMESH_ROLLOUT.md` (the *general* relay — explicitly NOT this), `README.md`.

**One-line:** When the sender can't reach a member directly, it hands that member's already-sealed
envelope to other currently-reachable members, who hold it and deliver it when they next connect
to the target. Best-effort, one-hop, TTL'd, dedup'd. The server is never involved.

---

## A. Goal & scope

Make a group message reach a member who was offline when it was sent, **without a server mailbox
and without the full ShadowMesh mesh**, by using co-members as opportunistic couriers for opaque
(end-to-end-sealed) envelopes they cannot read.

**In scope:** a persistent per-device carry queue; offloading undelivered envelopes to reachable
co-members; deferred one-hop delivery; dedup; TTL-based garbage collection; abuse caps.

**Out of scope (deliberate — this is the *degraded* relay):**
- **Not ShadowMesh.** No relaying for strangers, no ICE relay candidates, no global mesh. Carrying
  is bounded to **your own groups** and consented by joining one (≠ ShadowMesh's Terms §9 opt-in).
- **No multi-hop epidemic flooding / anti-entropy reconciliation.** One hop: origin → carrier →
  target. (A future ShadowMesh could subsume this; this does not depend on it.)
- **No guaranteed delivery.** If no carrier ever bridges origin→target over time, the message
  falls back to the origin's own direct retry (today's behaviour). We say so honestly.

**Honest residual:** delivery requires a **temporal path** — a carrier that connects to the origin
(or already holds the envelope) and later connects to the target. It hugely improves odds vs
"both online at once"; it is not a guarantee.

## B. Dependencies & assumptions (from pieces 1 & 2)

- **Fully-connected membership.** Pairwise fan-out requires the sender to share a double-ratchet
  session with every member, i.e. every member is a **verified mutual contact** of every other
  member. This is also what makes *any* member a viable courier for *any* other. (This is a
  piece-1 decision; recorded here because the relay relies on it. It preserves the SAS-verified
  trust model for every pair — the on-brand choice. If membership ever relaxes to
  not-fully-connected, a courier can only carry for targets it is itself paired with.)
- **A group message = N−1 sealed envelopes.** Each is a normal `msg` frame sealed `aura-msg-v1|A|X`
  for member X, plus a `groupId` field. The envelope is **opaque to everyone but X.**
- **Per-member delivery tracking** (`group_delivery`) exists from piece 2.

## C. Why this is courier-safe (the key property)

The carried unit is the **recipient-addressed sealed `msg` frame** (`from:A, to:B, n, sealed`).
- A courier C **cannot read it** — it's sealed with A↔B's ratchet; C has no key.
- A courier C **cannot forge it** — C can't produce a valid A→B seal.
- B **can open it** when C delivers it, because it's sealed with **A's** ratchet (B looks up
  contact A and opens as normal). C is purely a postman; `from` stays A.

This works because the wire protocol is already **content-addressed, not connection-bound**:
`processMsg` routes on the frame's `from`/`to`, and does not assume `from == the socket peer`
(the existing synchronous `relay` hop relies on the same property). The carry relay is the
**asynchronous, store-and-forward sibling** of that existing one-hop `relay`.

## D. Protocol

### D.1 Frames

```
carry      { t:"carry", groupId, target:<B nodeId>, ttlMs, inner:<msg frame for B> }
              inner = { t:"msg", from:A, to:B, id, ts, n, sealed, groupId }   // opaque to C
carryack   { t:"carryack", id, target:<B> }   // optional (Phase 3): "B has it, drop your copy"
```
Delivery from courier to target reuses the **existing `msg` path** — the courier writes `inner`
verbatim; B's `processMsg`/`processFrame` handles it unchanged and returns the normal `ack`.

### D.2 Send (origin A)

```
A sends group message M (fan-out):
  for each member X:
     seal envelope_X (msg frame, sealed A→X) and try direct delivery (RTC → TCP)
     delivered  -> mark group_delivery(M, X) = delivered
     missed (X offline/unreachable):
        - keep envelope_X pending (today's behaviour; A keeps retrying directly), AND
        - pick up to K currently-reachable co-members as carriers; send each a
          carry{ target:X, inner:envelope_X, ttlMs }
```
`K` small (default 2–3). "Currently reachable" = members A has an open RTC channel to or can TCP-reach.

### D.3 Carry (courier C)

```
C receives carry{ target:B, inner, ttlMs }:
  - validate: I'm a member of groupId AND B is a co-member of groupId  (else drop)
  - enforce caps (per-origin, per-group, total)                        (else drop oldest/refuse)
  - store row in carry_queue (encrypted at rest via SQLCipher), createdMs, ttlMs, attempts=0
```

### D.4 Deliver (courier C → target B)

```
C drains carry_queue opportunistically:
  - piggyback: whenever C opens any connection to B, flush B's queued inner frames first
  - proactive: SyncEngine.tick calls flushCarryQueue() — per distinct target, best-effort dial
    (reuse pace()/backoff; low frequency; battery-aware)
  for each queued inner for B:
     write inner over the C↔B channel (RtcTransport.send / exchangeFrame)
     got ack(id)            -> drop the carry_queue row (delivered)
     no ack / unreachable   -> attempts++, leave for next sweep
  expire any row past createdMs+ttlMs (drop)
```

### D.5 Convergence without perfect bookkeeping (the degraded part)

- **Dedup at B:** B may receive M directly from A and/or from several carriers. `messageDao.byId`
  → already stored → re-ack idempotently, skip. (Existing pattern.)
- **No required delivery-confirmation gossip.** Carriers need not learn that B got it from someone
  else; **TTL + dedup bound the waste.** Optional `carryack` (Phase 3) propagates "B has it" back to
  the origin / other carriers to cut duplicate carrying — a refinement, not a correctness need.

## E. Data-model deltas

- New `carry_queue` entity (SQLCipher-encrypted, so the opaque blob is also encrypted at rest):
  `@Entity carry_queue(msgId, target, groupId, originNodeId, innerJson, createdMs, ttlMs,
  attempts, lastAttemptMs)`, primary key `(msgId, target)`.
- **DB version bump + migration + committed schema json** (SOP §6).
- No new columns on `messages` for this piece (group fields land in piece 1/2).

## F. Code touchpoints (when code follows — existing packages, no new module)

- `transport/WireFrames.kt` doc + routing: add `carry` / `carryack`.
- `transport/TcpMessageServer.kt`: handle `carry` in `handleConnection` and a `carry` branch in
  `processFrame` (so it works over both TCP and the RTC data channel, like media did).
- `transport/rtc/RtcDataSession.kt` / `RtcTransport.kt`: route inbound `carry` to a handler
  (mirror the `mediaHandler` wiring just added).
- New `transport/CarryRelay.kt` (`@Singleton`): receive+store carry frames; `flushCarryQueue()`
  delivers queued inner frames to reachable targets; TTL GC; caps. Registered in `AppWiring`.
- `transport/MessageSender.kt` (group send path): on a fan-out miss, offload to K reachable carriers.
- `db/AuroraDatabase.kt`: `carry_queue` DAO (insert, byTarget, expire, count-by-origin).
- `network/SyncEngine.kt`: call `carryRelay.flushCarryQueue()` in `tick()` (next to
  `flushPending` / `flushPendingMedia`); `hasPendingOutbound`-style signal keeps pacing active
  while carry items exist.
- **Reuse:** `exchangeFrame`/`RtcTransport.send` for delivery; the `ack`/dedup model; `pace()`
  backoff; the encrypted-at-rest DB.

## G. Phases

- **P0 — Queue + GC.** `carry_queue` schema + migration; `CarryRelay` receives & stores `carry`
  frames; TTL expiry; caps. No delivery yet.
  *Acceptance:* a received carry persists encrypted, is rejected if not for one of my groups / a
  non-member target / over cap, and is dropped after TTL. *Tests:* pure-JVM TTL/dedup/cap logic
  (testable like `RtcMediaChunkingTest`); Robolectric DAO migration test.
- **P1 — Deliver.** `flushCarryQueue()` delivers queued inner frames to reachable targets; drop on
  ack; dedup at receiver.
  *Acceptance:* a carried envelope reaches B and decrypts as a message **from A** (not C); a second
  copy is deduped. *Tests:* 2-device instrumented (courier delivers an A→B frame B can open).
- **P2 — Offload.** On a fan-out miss, origin hands envelopes to K reachable carriers.
  *Acceptance:* B (offline at send) receives M once a carrier later reaches B; no duplicate stored.
- **P3 — Confirmation (optional).** `carryack` back to origin/other carriers to reduce duplicate
  carrying.
- **P4 — Hardening.** Caps + abuse guards finalized; optional size padding; battery tuning; consent
  copy in Terms/Privacy.

## H. Security & privacy notes

- **Content E2E preserved.** Couriers ferry ciphertext sealed origin→target; cannot read or forge.
- **Encrypted at rest.** `carry_queue` lives in the SQLCipher DB, so carried blobs are encrypted on
  the courier's disk too.
- **Metadata a courier learns:** that A sent *something* to B in group G, plus timing and size.
  Couriers are co-members who already know the membership; content stays sealed. Accepted residual;
  optional size-bucket padding in P4. (The rendezvous server learns *nothing new* — it's not in
  this path.)
- **DoS / abuse:** accept `carry` only for groups you're in with a co-member target; cap
  carry_queue per origin, per group, and total (drop oldest / refuse beyond cap); TTL bounds
  lifetime; receiver dedup bounds replay.
- **Consent:** carrying for your own group's members is implied by joining the group (bounded,
  in-group). This is distinct from — and lighter than — ShadowMesh's relay-for-strangers opt-in.
  Document plainly in Terms/Privacy: Aurora may briefly hold encrypted messages on your device to
  deliver to other members of your groups; it cannot read them; they are deleted on delivery or
  after the TTL.

## I. Decisions log

- This is the **degraded** relay: one-hop, best-effort, TTL'd, in-group — explicitly **not**
  ShadowMesh and **not** a server mailbox (mailbox is the locked red line: server never in the
  data path, server stores nothing).
- Carried unit = the recipient-addressed sealed `msg` frame (courier-opaque); reuses the existing
  content-addressed `from`/`to` routing.
- Async store-carry-forward sibling of the existing synchronous `relay` hop.
- Correctness rests on **TTL + receiver dedup**, not on perfect delivery-confirmation propagation.

## J. Open questions

1. `K` (carriers per missed envelope) and TTL default (48h?) — tune for delivery odds vs storage/battery.
2. carry_queue caps (per origin / per group / total bytes) — values + eviction policy.
3. Should `flushCarryQueue` proactively dial carry targets, or only piggyback on existing
   connections (battery)? Lean: piggyback first, add gentle proactive dialing in P4.
4. P3 `carryack` propagation — worth it, or do TTL + dedup make it unnecessary in practice?
5. Membership not fully-connected (future): do we restrict carrying to target-paired couriers, or
   require fully-connected for groups that want offline delivery?
