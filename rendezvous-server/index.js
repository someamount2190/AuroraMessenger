// Aurora standalone rendezvous server (Phase 8 graduation from the in-app NanoHTTPD
// server). Behaviour-identical to com.aura.server.AuroraRendezvousServer:
//
//   POST /checkin        verify hybrid (Dilithium-3 + Ed25519) check-in, store
//                        nodeId→IP, 15-min TTL
//   GET  /find/:nodeId   return exactly 10 candidates (real + padding), each signed
//   POST /signal/:nodeId queue an opaque (encrypted) signaling payload (wakes /wait)
//   GET  /signal/:nodeId drain queued payloads
//   POST /tap/:nodeId    contentless wake — nudge a backgrounded node online
//   GET  /wait/:nodeId   authenticated long-poll; parked until a tap/signal or timeout
//   POST /prekeys/:nodeId  authenticated publish of a signed PQXDH prekey bundle
//   GET  /prekeys/:nodeId  fetch a peer's bundle (pops one one-time prekey)
//
// Zero logs of payloads or message content. In-memory only — restart wipes all
// state. The signed check-in string and 10-candidate /find format match the
// Kotlin client byte-for-byte, so the same app talks to either server. The server
// validates the Ed25519 half of the hybrid check-in (it has no Dilithium); the full
// hybrid signature is stored and served so peers verify it post-quantum themselves.
//
// Run: node index.js   (listens on PORT, default 8080)
// Deploy: a $5 VPS (Iceland / Switzerland recommended). Front with a TLS
// terminator (Caddy/nginx) and pin its certificate in the app (Phase 8).

const http = require('http');
const crypto = require('crypto');
const START_MS = Date.now();

const PORT = process.env.PORT || 8080;
// AGPL-3.0 §13: users interacting with this server over the network must be able to
// obtain its (possibly modified) source. GET /source points them at it. Operators of
// a MODIFIED version must set SOURCE_URL to where THEIR source is published.
const SOURCE_URL = process.env.SOURCE_URL || 'https://github.com/someamount2190/AuroraMessenger';
// Bind IPv4 wildcard by default. An IPv6 (::) bind is reachable in-host but some
// NAT/forwarders (QEMU slirp, certain VPS setups) don't relay to the v4-mapped
// socket; 0.0.0.0 is the reliable choice for a public rendezvous server.
const HOST = process.env.HOST || '0.0.0.0';
const TTL_MS = 15 * 60 * 1000;     // 15-minute registration TTL (Privacy Policy §3.2)
const CHECKIN_FRESHNESS_MS = 5 * 60 * 1000;
const CHECKIN_RATE_LIMIT_MS = 60 * 1000;
const FIND_RATE_LIMIT_PER_MIN = 60;  // headroom for active delivery retries (5s cadence)
const SIGNAL_POST_RATE_LIMIT_PER_MIN = 30;
const MAX_QUEUE_LEN = 64;
const CANDIDATE_COUNT = 10;
const MAX_BODY = 64 * 1024;
// Long-poll "tap" wake (Phase 9). A backgrounded app parks one authenticated GET
// /wait/{nodeId}; the server holds it open (no bytes flow) until a tap/signal lands
// for that node or WAIT_HOLD_MS elapses, then the client re-parks. This is the only
// always-on footprint — the server relays a single contentless wake, never content;
// the real message/call then flows directly peer-to-peer.
const WAIT_HOLD_MS = 25 * 1000;          // under typical 60s proxy read timeouts
const TAP_RATE_LIMIT_PER_MIN = 30;       // per-IP cap on contentless wakes
// Forward-secret PQXDH prekey bundles. Only PUBLIC prekeys + signatures are stored —
// never private material, never content. A node publishes a signed bundle (auth'd like
// the drain); an initiator GETs it and the server pops one one-time prekey per fetch.
const PREKEY_OPK_MAX = 100;              // cap on one-time prekeys retained per node
// nodeId↔key binding. Defaults ON: a check-in MUST carry the KEM + signing keys and prove they
// hash to the claimed nodeId, otherwise anyone could squat/overwrite another node's mapping (and
// the per-nodeId check-in clock would let them lock the victim out). The current app always sends
// keys; an operator can opt out with STRICT_BINDING=0 only if knowingly supporting legacy clients.
const STRICT_BINDING = process.env.STRICT_BINDING !== '0';
// Hybrid check-in signature size: [4B len][ML-DSA-65 sig 3309][Ed25519 sig 64].
// /find padding signatures match this length so the real candidate can't be
// distinguished by signature size. (Was Dilithium-3's 3293 before the FIPS migration.)
const HYBRID_SIG_BYTES = 4 + 3309 + 64;

const nodes = new Map();           // nodeIdHex -> { ip, port, timestamp, sigB64, ed25519Pub, storedAt }
const signals = new Map();         // nodeIdHex -> [payload, ...]
const lastCheckin = new Map();     // nodeIdHex -> ms
const findHits = new Map();        // ip -> [ms, ...]
const signalActivity = new Map();  // nodeIdHex -> ms (last queue activity, for expiry)
const signalPostHits = new Map();  // ip -> [ms, ...]
const tapHits = new Map();         // ip -> [ms, ...]
const waiters = new Map();         // nodeIdHex -> { res, timer } (one parked long-poll)
const prekeys = new Map();         // nodeIdHex -> { spk, opks: [json,...], storedAt }

// Complete a parked /wait for nodeId with a contentless wake (or a timeout no-op).
// Guards against double-send: the waiter is removed before responding.
function resolveWaiter(nodeId, wake) {
  const w = waiters.get(nodeId);
  if (!w) return;
  waiters.delete(nodeId);
  clearTimeout(w.timer);
  try { send(w.res, 200, { wake }); } catch (e) { /* socket already gone */ }
}

function purge() {
  const cutoff = Date.now() - TTL_MS;
  for (const [k, v] of nodes) if (v.storedAt < cutoff) nodes.delete(k);
  for (const [k, v] of lastCheckin) if (v < cutoff) lastCheckin.delete(k);
  // Drop signal queues with no recent activity (also reaps never-drained queues).
  for (const [k, v] of signalActivity) {
    if (v < cutoff) { signals.delete(k); signalActivity.delete(k); }
  }
  for (const [k, v] of prekeys) if (v.storedAt < cutoff) prekeys.delete(k);
  // Reap per-IP rate-limiter buckets whose timestamps have all aged out, so these maps don't
  // grow one entry per distinct/rotating source IP forever (slow memory exhaustion).
  const rlCutoff = Date.now() - 60000;
  for (const m of [findHits, signalPostHits, tapHits]) {
    for (const [k, arr] of m) {
      const fresh = arr.filter((t) => t > rlCutoff);
      if (fresh.length === 0) m.delete(k); else m.set(k, fresh);
    }
  }
}

function allowSignalPost(ip) {
  const now = Date.now();
  const hits = (signalPostHits.get(ip) || []).filter((t) => now - t <= 60000);
  if (hits.length >= SIGNAL_POST_RATE_LIMIT_PER_MIN) { signalPostHits.set(ip, hits); return false; }
  hits.push(now);
  signalPostHits.set(ip, hits);
  return true;
}

function allowTap(ip) {
  const now = Date.now();
  const hits = (tapHits.get(ip) || []).filter((t) => now - t <= 60000);
  if (hits.length >= TAP_RATE_LIMIT_PER_MIN) { tapHits.set(ip, hits); return false; }
  hits.push(now);
  tapHits.set(ip, hits);
  return true;
}

// Verify a signed signal-queue drain: ts fresh, pub matches the key that checked in,
// and the Ed25519 signature over "aura-drain-v1|nodeId|ts" is valid. Auth travels in
// headers so an older client/server stays compatible (clean path, headers ignored).
function verifyDrain(nodeId, headers, node) {
  if (!node || !node.ed25519Pub) return false;
  const ts = Number(headers['x-drain-ts']);
  if (!ts || Math.abs(Date.now() - ts) > CHECKIN_FRESHNESS_MS) return false;
  const pub = headers['x-drain-pub'];
  if (pub !== node.ed25519Pub) return false;
  const sig = headers['x-drain-sig'];
  if (!sig) return false;
  let sigBuf;
  try { sigBuf = Buffer.from(sig, 'base64'); } catch { return false; }
  const msg = Buffer.from(`aura-drain-v1|${nodeId}|${ts}`, 'utf8');
  return verifyEd25519Raw(msg, sigBuf, pub);
}

// Canonical check-in string — MUST match AuroraRendezvousServer.checkinMessage.
function checkinMessage(nodeIdHex, ip, port, timestamp) {
  return Buffer.from(`aura-checkin-v1|${nodeIdHex}|${ip}|${port}|${timestamp}`, 'utf8');
}

// Verify a raw 64-byte Ed25519 signature buffer against a raw 32-byte public key.
function verifyEd25519Raw(message, sig, pubB64) {
  try {
    const rawPub = Buffer.from(pubB64, 'base64');
    if (sig.length !== 64 || rawPub.length !== 32) return false;
    // Wrap the raw key in the SPKI DER prefix Node's verifier expects.
    const der = Buffer.concat([
      Buffer.from('302a300506032b6570032100', 'hex'),
      rawPub,
    ]);
    const keyObj = crypto.createPublicKey({ key: der, format: 'der', type: 'spki' });
    return crypto.verify(null, message, keyObj, sig);
  } catch (e) {
    return false;
  }
}

// Verify a check-in signature. The app signs check-ins with a Dilithium-3 + Ed25519
// HYBRID signature, wire format [4B big-endian Dilithium len][Dilithium sig][Ed25519
// sig 64B]. Node has no liboqs Dilithium, and server-side check-in verification is
// self-referential anyway (the public key is supplied in the request) — the
// post-quantum check that matters happens peer-side in verifyCandidates against the
// Dilithium key learned at pairing. So we verify the Ed25519 component here and store
// /serve the full hybrid blob unchanged so peers can do the Dilithium verification.
// A legacy 64-byte Ed25519-only signature is still accepted.
function verifyCheckinSig(message, sigB64, pubB64) {
  let sig;
  try { sig = Buffer.from(sigB64, 'base64'); } catch { return false; }
  if (sig.length === 64) return verifyEd25519Raw(message, sig, pubB64);
  if (sig.length > 4 + 64) {
    const dLen = sig.readUInt32BE(0);
    if (sig.length === 4 + dLen + 64) {
      return verifyEd25519Raw(message, sig.subarray(4 + dLen), pubB64);
    }
  }
  return false;
}

// True iff nodeId == SHA3-256(kemPub.toBytes() ‖ signPub.toBytes()). signPub.toBytes()
// is [4B BE dilithium len][dilithium][ed25519 32B]; kemPub arrives already as toBytes().
function nodeIdBindsToKeys(nodeId, kemPubB64, dilithiumPubB64, ed25519PubB64) {
  try {
    const kem = Buffer.from(kemPubB64, 'base64');
    const dil = Buffer.from(dilithiumPubB64, 'base64');
    const ed = Buffer.from(ed25519PubB64, 'base64');
    if (ed.length !== 32 || kem.length === 0 || dil.length === 0) return false;
    const dilLen = Buffer.alloc(4);
    dilLen.writeUInt32BE(dil.length, 0);
    const signPub = Buffer.concat([dilLen, dil, ed]);
    const material = Buffer.concat([kem, signPub]);
    return crypto.createHash('sha3-256').update(material).digest('hex') === nodeId;
  } catch {
    return false;
  }
}

function randomDummyIp() {
  const r = crypto.randomInt(3);
  const o = () => crypto.randomInt(256);
  const h = () => 1 + crypto.randomInt(254);
  if (r === 0) return `10.${o()}.${o()}.${h()}`;
  if (r === 1) return `192.168.${o()}.${h()}`;
  return `172.${16 + crypto.randomInt(16)}.${o()}.${h()}`;
}

function candidate(ip, port, timestamp, sigB64) {
  return { ip, port, timestamp, signature: sigB64 };
}

function shuffle(arr) {
  for (let i = arr.length - 1; i > 0; i--) {
    const j = crypto.randomInt(i + 1);
    [arr[i], arr[j]] = [arr[j], arr[i]];
  }
  return arr;
}

function allowFind(ip) {
  const now = Date.now();
  const hits = (findHits.get(ip) || []).filter((t) => now - t <= 60000);
  if (hits.length >= FIND_RATE_LIMIT_PER_MIN) { findHits.set(ip, hits); return false; }
  hits.push(now);
  findHits.set(ip, hits);
  return true;
}

function send(res, code, obj) {
  const body = JSON.stringify(obj);
  res.writeHead(code, { 'Content-Type': 'application/json' });
  res.end(body);
}

function readBody(req, cb) {
  let data = '';
  let tooBig = false;
  req.on('data', (chunk) => {
    data += chunk;
    if (data.length > MAX_BODY) { tooBig = true; req.destroy(); }
  });
  req.on('end', () => cb(tooBig ? null : data));
  req.on('error', () => cb(null));
}

const server = http.createServer((req, res) => {
  purge();
  const parsed = new URL(req.url, 'http://localhost');
  const url = parsed.pathname.replace(/\/+$/, '');
  const remoteIp = (req.socket.remoteAddress || '?').replace('::ffff:', '');

  if (req.method === 'POST' && url === '/checkin') {
    return readBody(req, (body) => {
      if (!body) return send(res, 400, { error: 'missing body' });
      let j;
      try { j = JSON.parse(body); } catch { return send(res, 400, { error: 'bad json' }); }
      const { nodeId, ip, port, timestamp, ed25519Pub, dilithiumPub, kemPub, signature } = j;
      if (!/^[0-9a-f]{64}$/.test(nodeId || '') || !ip || !(port >= 1 && port <= 65535) ||
          !timestamp || !ed25519Pub || !signature) {
        return send(res, 400, { error: 'invalid checkin fields' });
      }
      const now = Date.now();
      if (Math.abs(now - timestamp) > CHECKIN_FRESHNESS_MS) {
        return send(res, 400, { error: 'timestamp outside freshness window' });
      }
      if (now - (lastCheckin.get(nodeId) || 0) < CHECKIN_RATE_LIMIT_MS) {
        return send(res, 429, { error: 'rate limited' });
      }
      // nodeId↔key binding — stops a rogue squatting someone else's nodeId. Verified
      // when the KEM key is present; required only when STRICT_BINDING is on.
      if (kemPub && dilithiumPub) {
        if (!nodeIdBindsToKeys(nodeId, kemPub, dilithiumPub, ed25519Pub)) {
          return send(res, 401, { error: 'nodeId does not match keys' });
        }
      } else if (STRICT_BINDING) {
        return send(res, 401, { error: 'nodeId binding required' });
      }
      const msg = checkinMessage(nodeId, ip, port, timestamp);
      if (!verifyCheckinSig(msg, signature, ed25519Pub)) {
        return send(res, 401, { error: 'signature verification failed' });
      }
      lastCheckin.set(nodeId, now);
      nodes.set(nodeId, { ip, port, timestamp, sigB64: signature, ed25519Pub, storedAt: now });
      return send(res, 200, { status: 'registered', ttlSeconds: TTL_MS / 1000 });
    });
  }

  if (req.method === 'GET' && url === '/checkin') {
    return send(res, 200, { status: 'ok', hint: 'POST a signed payload to check in' });
  }

  if (req.method === 'GET' && url.startsWith('/find/')) {
    if (!allowFind(remoteIp)) return send(res, 429, { error: 'rate limited' });
    const nodeId = url.slice('/find/'.length);
    const target = nodes.get(nodeId);
    if (!target) return send(res, 404, { error: 'node not registered' });

    const candidates = [candidate(target.ip, target.port, target.timestamp, target.sigB64)];
    // Dummies only — never disclose other registered nodes' real IPs as /find padding.
    while (candidates.length < CANDIDATE_COUNT) {
      candidates.push(candidate(
        randomDummyIp(),
        1024 + crypto.randomInt(64000),
        Date.now() - crypto.randomInt(300000),
        crypto.randomBytes(HYBRID_SIG_BYTES).toString('base64'),
      ));
    }
    return send(res, 200, { candidates: shuffle(candidates) });
  }

  if (req.method === 'POST' && url.startsWith('/signal/')) {
    const nodeId = url.slice('/signal/'.length);
    if (!/^[0-9a-f]{64}$/.test(nodeId)) return send(res, 400, { error: 'bad nodeId' });
    if (!allowSignalPost(remoteIp)) return send(res, 429, { error: 'rate limited' });
    return readBody(req, (body) => {
      if (!body) return send(res, 400, { error: 'missing body' });
      const q = signals.get(nodeId) || [];
      q.push(body);
      while (q.length > MAX_QUEUE_LEN) q.shift();   // bounded queue: drop oldest
      signals.set(nodeId, q);
      signalActivity.set(nodeId, Date.now());
      resolveWaiter(nodeId, true);   // wake a parked /wait (call offer / pairing)
      return send(res, 200, { status: 'queued' });
    });
  }

  // Contentless wake. Anyone who knows a nodeId can nudge it online (rate-limited);
  // worst case is a brief radio wake with nothing behind it — the actual message is
  // delivered directly peer-to-peer once the woken node comes online, and the server
  // stores nothing here.
  if (req.method === 'POST' && url.startsWith('/tap/')) {
    const nodeId = url.slice('/tap/'.length);
    if (!/^[0-9a-f]{64}$/.test(nodeId)) return send(res, 400, { error: 'bad nodeId' });
    if (!allowTap(remoteIp)) return send(res, 429, { error: 'rate limited' });
    resolveWaiter(nodeId, true);
    return send(res, 200, { status: 'tapped' });
  }

  // Authenticated long-poll. Only the key holder behind nodeId can park (same signed
  // headers as the drain). Returns immediately if signals are already queued; otherwise
  // the response is held open until a tap/signal lands or WAIT_HOLD_MS elapses.
  if (req.method === 'GET' && url.startsWith('/wait/')) {
    const nodeId = url.slice('/wait/'.length);
    if (!verifyDrain(nodeId, req.headers, nodes.get(nodeId))) {
      return send(res, 401, { error: 'wait authentication required' });
    }
    if ((signals.get(nodeId) || []).length > 0) return send(res, 200, { wake: true });
    // Replace any stale parked waiter for this node (e.g. a reconnect).
    resolveWaiter(nodeId, false);
    const timer = setTimeout(() => resolveWaiter(nodeId, false), WAIT_HOLD_MS);
    waiters.set(nodeId, { res, timer });
    // If the client disconnects while parked, drop the waiter and free the timer.
    req.on('close', () => {
      const w = waiters.get(nodeId);
      if (w && w.res === res) { waiters.delete(nodeId); clearTimeout(w.timer); }
    });
    return;
  }

  if (req.method === 'GET' && url.startsWith('/signal/')) {
    const nodeId = url.slice('/signal/'.length);
    // Authenticated drain — only the key holder behind this nodeId can read/empty it.
    if (!verifyDrain(nodeId, req.headers, nodes.get(nodeId))) {
      return send(res, 401, { error: 'drain authentication required' });
    }
    const q = signals.get(nodeId) || [];
    signals.set(nodeId, []);
    signalActivity.set(nodeId, Date.now());
    return send(res, 200, { payloads: q });
  }

  // Publish a forward-secret prekey bundle. Authenticated with the same signed
  // X-Drain-* headers as the drain, so only the key holder behind nodeId may publish.
  if (req.method === 'POST' && url.startsWith('/prekeys/')) {
    const nodeId = url.slice('/prekeys/'.length);
    if (!/^[0-9a-f]{64}$/.test(nodeId)) return send(res, 400, { error: 'bad nodeId' });
    if (!verifyDrain(nodeId, req.headers, nodes.get(nodeId))) {
      return send(res, 401, { error: 'prekey publish auth required' });
    }
    return readBody(req, (body) => {
      if (!body) return send(res, 400, { error: 'missing body' });
      let j;
      try { j = JSON.parse(body); } catch { return send(res, 400, { error: 'bad json' }); }
      if (!j.spk || typeof j.spk !== 'object') return send(res, 400, { error: 'missing spk' });
      const opks = Array.isArray(j.opks) ? j.opks.slice(0, PREKEY_OPK_MAX) : [];
      prekeys.set(nodeId, { spk: j.spk, opks, storedAt: Date.now() });
      return send(res, 200, { status: 'stored' });
    });
  }

  // Fetch a peer's bundle for a forward-secret pairing. Pops one one-time prekey per
  // request; returns the signed prekey with opk:null once the pool is exhausted.
  if (req.method === 'GET' && url.startsWith('/prekeys/')) {
    const nodeId = url.slice('/prekeys/'.length);
    const b = prekeys.get(nodeId);
    if (!b) return send(res, 404, { error: 'no prekey bundle' });
    const opk = (b.opks && b.opks.length) ? b.opks.shift() : null;
    return send(res, 200, { spk: b.spk, opk: opk || null });
  }

  // Liveness probe for the public status page. No identifiers, no counts —
  // just up/uptime. CORS-open so the static status page can fetch it.
  if (req.method === 'GET' && url === '/health') {
    const bodyH = JSON.stringify({ status: 'ok', service: 'aurora-rendezvous', uptimeSeconds: Math.floor((Date.now() - START_MS) / 1000), since: new Date(START_MS).toISOString() });
    res.writeHead(200, { 'Content-Type': 'application/json', 'Access-Control-Allow-Origin': '*', 'Cache-Control': 'no-store' });
    return res.end(bodyH);
  }

  // AGPL-3.0 §13: offer the Corresponding Source to remote users.
  if (req.method === 'GET' && url === '/source') {
    return send(res, 200, { source: SOURCE_URL, license: 'AGPL-3.0-or-later' });
  }

  return send(res, 404, { error: 'unknown endpoint' });
});

server.listen(PORT, HOST, () => {
  // The only lines ever logged — no payloads, no IPs, no message content.
  console.log(`Aurora rendezvous server listening on ${HOST}:${PORT}`);
  console.log(`Source (AGPL-3.0): ${SOURCE_URL}`);
});
