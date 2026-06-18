# Rendezvous Wire Protocol

The rendezvous server only maps a short-lived `nodeId → address` so peers can find each
other and wake a sleeping device. It is **in-memory only** (restart wipes all state),
**logs no payloads/IPs/nodeIds**, and is never in the path of message content. This spec
describes the canonical **standalone Node.js server** (`rendezvous-server/index.js`,
deployed at `api.auroramessenger.com`); the embeddable in-app server (`com.aura.server`,
NanoHTTPD) is byte-compatible with differences noted in §7.

Transport: HTTPS with **certificate pinning** in the client (a rogue CA cannot intercept).
All bodies are JSON unless noted. All signed strings are UTF-8 with `|` delimiters.

## 1. Endpoints

| Method · Path | Auth | Purpose | Response |
|---|---|---|---|
| `POST /checkin` | signed body | Register `nodeId → ip:port`, 15-min TTL | `{status:"registered", ttlSeconds:900}` |
| `GET /checkin` | none | Liveness hint | `{status:"ok", hint:...}` |
| `GET /find/:nodeId` | none | Return **10** candidates (1 real + 9 decoys, shuffled) | `{candidates:[{ip,port,timestamp,signature}×10]}` |
| `POST /signal/:nodeId` | none | Enqueue one opaque (E2E) payload; wakes a parked `/wait` | `{status:"queued"}` |
| `GET /signal/:nodeId` | drain headers | Drain queued payloads | `{payloads:[...]}` |
| `POST /tap/:nodeId` | none | Contentless wake | `{status:"tapped"}` |
| `GET /wait/:nodeId` | drain headers | Long-poll; returns on tap/signal or after 25 s | `{wake:true}` / `{wake:false}` |
| `POST /prekeys/:nodeId` | drain headers | Publish signed PQXDH bundle (public only) | `{status:"stored"}` |
| `GET /prekeys/:nodeId` | none | Fetch bundle; **pops one OPK** | `{spk:{...}, opk:{...}|null}` |
| `GET /health` | none | Liveness probe (no identifiers); CORS `*` | `{status:"ok", uptimeSeconds, since}` |
| `GET /source` | none | AGPL-3.0 §13 source pointer | `{source, license:"AGPL-3.0-or-later"}` |

## 2. Signed messages

Only two canonical strings are signed *for the server* (pairing messages are opaque
`/signal` payloads — see [`CRYPTO_SPEC.md`](CRYPTO_SPEC.md) §5):

| Message | Canonical string | Signed by | Server verifies |
|---|---|---|---|
| check-in | `aura-checkin-v1\|<nodeId>\|<ip>\|<port>\|<timestamp>` | hybrid (Dilithium-3 + Ed25519) | **Ed25519 half only** (server has no Dilithium); peers verify the full hybrid at `/find` time |
| drain / wait / prekeys auth | `aura-drain-v1\|<nodeId>\|<ts>` | Ed25519 | Ed25519, via headers |

**Drain auth headers** (`GET /signal`, `GET /wait`, `POST /prekeys`):
`X-Drain-Ts` (ms), `X-Drain-Pub` (b64 Ed25519 pub — must equal the key that checked in),
`X-Drain-Sig` (b64 Ed25519 sig over the drain string). Freshness window: **±5 min**. So only
the key holder can read their own signals or publish their own prekeys.

## 3. The `/find` decoy format

`GET /find/:nodeId` returns exactly **10** candidates `{ip, port, timestamp, signature}`,
**shuffled**:
- **1 real** = the node's stored check-in with its **real hybrid signature** (3361 B).
- **9 decoys** = random RFC-1918-style private IPs, timestamps up to 5 min in the past, and
  **random padding signatures of identical length** (3361 B).

Only the legitimate caller can tell the real one apart: it re-derives
`aura-checkin-v1|nodeId|ip|port|timestamp` per candidate and verifies the hybrid signature
against the target's known keys — only the real candidate verifies. A passive observer of a
`/find` response cannot identify the real address.

## 4. Constants (production / Node.js)

| Constant | Value |
|---|---|
| `TTL_MS` | 900 000 (15 min) |
| `CHECKIN_FRESHNESS_MS` | 300 000 (5 min) |
| `CHECKIN_RATE_LIMIT_MS` | 60 000 (1/min per nodeId) |
| `FIND_RATE_LIMIT_PER_MIN` | 60 per IP |
| `SIGNAL_POST_RATE_LIMIT_PER_MIN` | 30 per IP |
| `TAP_RATE_LIMIT_PER_MIN` | 30 per IP |
| `MAX_QUEUE_LEN` | 64 (oldest dropped) |
| `CANDIDATE_COUNT` | 10 |
| `MAX_BODY` | 65 536 B (64 KiB) |
| `WAIT_HOLD_MS` | 25 000 (under typical 60 s proxy read timeouts) |
| `PREKEY_OPK_MAX` | 100 per node |
| `HYBRID_SIG_BYTES` | 3361 (4 + 3293 + 64) |
| `PORT` / `HOST` defaults | 8080 / `0.0.0.0` (production drop-in pins `127.0.0.1`, fronted by nginx) |
| `STRICT_BINDING` | env `=1` to require nodeId↔key binding on every check-in |

## 5. Identity binding (STRICT_BINDING)

A check-in may carry `kemPub` + `dilithiumPub`. If present, the server enforces
`nodeId == SHA3-256(kemPub ‖ signPub)` and rejects mismatches (HTTP 401). With
`STRICT_BINDING=1` (set in production), a check-in **without** the binding keys is rejected,
fully closing nodeId squatting. The in-app server is always strict.

## 6. Wake (`/tap` + `/wait`) long-poll

```mermaid
sequenceDiagram
  participant B as Backgrounded device B
  participant R as Rendezvous
  participant A as Peer A
  B->>R: GET /wait/B (authed) — parked up to 25s
  A->>R: POST /tap/B  (or POST /signal/B)
  R-->>B: {wake:true}
  B->>R: GET /signal/B (authed) — drain
  B-->>A: re-park /wait, then direct P2P
  Note over R: on timeout R returns {wake:false}; B re-parks
```

This contentless wake replaces FCM/third-party push: no message content ever touches a push
provider. The honest cost — the server can tell a device is reachable — is disclosed in the
Privacy Policy and analyzed in [`THREAT_MODEL.md`](THREAT_MODEL.md).

## 7. In-app server (`com.aura.server`) differences
Used for local-network / host mode. Byte-compatible check-in/find/drain, but: `/find` rate
limit **10**/min (vs 60), **always** strict binding, and **no** `/tap`, `/wait`, `/health`,
or `/source`. (The host-beacon UI that used it is currently dormant — see
[`AUDIT_SCOPE.md`](AUDIT_SCOPE.md).)

## 8. Logging
The server emits exactly two `console.log` lines at startup (listen address + AGPL source
URL). No request payloads, IPs, nodeIds, queue contents, or prekey material are ever logged;
nginx access logging for the API host is disabled. State is purely in-memory with a 15-min TTL.
