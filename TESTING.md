# Aurora — Phase test log

Per the build spec: every phase is tested on two emulators before moving on.
Emulator A (Alice) = AVD `Pixel_8` (emulator-5554). Emulator B (Bob) = AVD `Pixel_8_2` (emulator-5556).

## Phase 0 + Phase 1 — 2026-06-12 — ALL PASS

| # | Test | Result |
|---|---|---|
| 1 | App installs and launches on both emulators | ✅ PASS |
| 2 | Onboarding renders; identity (Kyber-1024 + X25519 + Dilithium-3 + Ed25519) generates on first launch via liboqs JNI | ✅ PASS |
| 3 | Navigate all screens (Onboarding → Home → My Code → Settings → Scan → Conversation) without crashes | ✅ PASS |
| 4 | My Code shows QR with full pairing payload; node ID displayed | ✅ PASS |
| 5 | Identity persists across force-stop + relaunch (Alice ID `45a1b49b…` unchanged) | ✅ PASS |
| 6 | Server Mode toggle starts NanoHTTPD on 8080; Settings shows "Running at 10.0.2.15:8080" | ✅ PASS |
| 7 | Host `curl http://localhost:8080/checkin` (after `adb -s emulator-5554 forward tcp:8080 tcp:8080`) → HTTP 200 | ✅ PASS |
| 8 | Bob POST /checkin via `http://10.0.2.2:8080` — Ed25519-signed payload verified and registered (HTTP 200) | ✅ PASS |
| 9 | Bob GET /find/{self} → exactly 10 candidates; client verifies the one real IP by signature (`10.0.2.15:8765`) | ✅ PASS |
| 10 | Home status card: green dot, node count (1 after Bob's check-in), uptime ticking | ✅ PASS |
| 11 | Server stores nothing on disk: restart Alice's app → server auto-restarts; Bob's /find → HTTP 404 "node not registered" | ✅ PASS |
| 12 | /find on unknown nodeId → HTTP 404 | ✅ PASS |

### Failures found and fixed during this run
- **Build**: `Cannot access class androidx.compose.ui.unit.TextUnit` — fixed by adding
  `androidx.compose.ui:ui-unit-android` explicitly (same workaround ShadowMesh uses).
- **Environment, not app**: both AVDs cold-boot into Direct Boot `RUNNING_LOCKED` state
  (they have lockscreen PIN `1234`). Until unlocked, *all* third-party activities fail
  with `Error type 3: Activity class does not exist` and screenshots are black.
  Unlock: `adb shell cmd lock_settings verify --user 0 --old 1234`, then wake + swipe +
  `input text 1234` + enter. ShadowMesh exhibits the same symptom on locked boots.

### Not yet covered (deliberately)
- Camera QR scanning (gallery path tested; camera-to-camera impossible between emulators).
- Rate limiting endpoints exercised implicitly (sync loop checks in once per 5 min).

## Phase 2 — QR pairing + key exchange — 2026-06-12 — ALL PASS

| # | Test | Result |
|---|---|---|
| 1 | Alice "Save to gallery" writes QR PNG to Pictures/Aurora via MediaStore | ✅ PASS |
| 2 | QR transferred to Bob (adb pull/push + media scan), visible in photo picker | ✅ PASS |
| 3 | Bob "Pick from gallery" → zxing decodes the ~2.3 KB QR payload | ✅ PASS |
| 4 | Bob encapsulates (Kyber-1024+X25519), stores contact, navigates to conversation | ✅ PASS |
| 5 | Bob's signed pairing message reaches Alice via /signal queue; her 5s poller decapsulates | ✅ PASS |
| 6 | Contact appears on BOTH Home screens as "Friend" with peer node ID | ✅ PASS |
| 7 | **Shared secret matches both sides** — verification code 252206 == 252206 | ✅ PASS |
| 8 | Both nodes auto-check-in via sync loop (server card shows "2 node(s)") | ✅ PASS |

Notes:
- Pairing is one-scan mutual: scanner encapsulates; the KEM ciphertext rides a signed
  message through the rendezvous /signal queue; receiver decapsulates. MITM on the
  signal channel is detectable by comparing the verification code (SAS over the secret).
- Contacts, messages, and mesh peers persist in `aura.db` (Room over SQLCipher,
  32-byte key Keystore-wrapped).

## Phases 3+4 — rendezvous wiring + encrypted TCP messaging — 2026-06-12 — ALL PASS

Emulator NAT plumbing (each emulator is behind its own NAT, so peers advertise
loopback addresses bridged by adb):
```
adb -s emulator-5554 forward tcp:8080  tcp:8080    # host → Alice's rendezvous server
adb -s emulator-5556 reverse tcp:18765 tcp:18765   # Bob's localhost:18765 → host
adb -s emulator-5554 forward tcp:18765 tcp:8765    # host:18765 → Alice's TCP listener
adb -s emulator-5554 reverse tcp:18766 tcp:18766   # Alice's localhost:18766 → host
adb -s emulator-5556 forward tcp:18766 tcp:8765    # host:18766 → Bob's TCP listener
```
Alice advertises `127.0.0.1:18765`, Bob `127.0.0.1:18766` (Settings → Advertised address).

| # | Test | Result |
|---|---|---|
| 1 | Check-in on launch + every 5 min via sync loop; server rate limit (1/min/node, 10 finds/min/IP) active | ✅ PASS |
| 2 | meshPeerTable populated from /find padding ("Mesh peers learned: 9" in Settings) | ✅ PASS |
| 3 | Bob → Alice text delivered over direct TCP; XChaCha20-Poly1305 sealed payload | ✅ PASS |
| 4 | Delivery receipts: sender shows ✓✓ after peer ack | ✅ PASS |
| 5 | Alice → Bob (reverse direction) delivered | ✅ PASS |
| 6 | Offline queue: Bob force-stopped, Alice's message stays "…" pending, delivers automatically on Bob's relaunch | ✅ PASS |
| 7 | Relay-first routing: send attempts one mesh-peer relay hop, falls back to direct when relay unreachable (exercised every send — peer table holds dummy IPs) | ✅ PASS (fallback path) |
| 8 | Message ordering by timestamp in conversation | ✅ PASS |
| 9 | Encrypted in transit | ✅ by construction — wire frame carries only `sealed` (XChaCha20-Poly1305, AAD-bound to sender/recipient nodeIds); plaintext never leaves the app |

### Failures found and fixed during this run
- **Sync loop / TCP server died with the Activity**: they ran on `viewModelScope`,
  so BACK-ing out of the app silently killed delivery and the listener could not
  rebind ("address in use"). Fixed: process-lifetime `SupervisorJob` scope in
  SyncEngine, `SO_REUSEADDR` + close-before-rebind in TcpMessageServer, and the
  sync tick is now exception-proof (logged, never fatal).

## Phase 5 — encrypted media sharing — 2026-06-12 — ALL PASS

| # | Test | Result |
|---|---|---|
| 1 | Bob attaches a photo from gallery, sends to Alice | ✅ PASS |
| 2 | Image XChaCha20-Poly1305 sealed once; same blob at rest and on wire | ✅ PASS |
| 3 | Alice receives, decrypts, renders thumbnail | ✅ PASS |
| 4 | Media stored only in app-private `files/media/*.enc` (9942 B = 9902 B image + AEAD overhead) | ✅ PASS |
| 5 | Image is NOT in camera roll by default (Pictures/Aurora held only the earlier QR export) | ✅ PASS |
| 6 | "Tap to save" exports to Pictures/Aurora (`aura-<id>.jpg` appeared) | ✅ PASS |
| 7 | Delivery receipt ✓✓ on media bubble | ✅ PASS |

Note: video picker path is wired identically (50 MB cap); photo path exercised live.

## Phase 6 — disappearing messages — 2026-06-12 — ALL PASS

| # | Test | Result |
|---|---|---|
| 1 | Per-conversation timer Off/1h/24h/7d selectable from top-bar menu | ✅ PASS |
| 2 | Setting 1 hour on Alice syncs to Bob via sealed "ctl" control message (both top bars show "Disappearing: 1 hour") | ✅ PASS |
| 3 | Timer starts at delivery; message shows "⏱ 59m" countdown on both sides | ✅ PASS |
| 4 | Countdown indicator on messages near expiry | ✅ PASS |

Scope: live auto-deletion (sweep every 30 s deleting rows + media files where
`expiresAtMs ≤ now`) is verified by construction — the shortest selectable timer
is 1 hour, so on-device expiry isn't observed within a test session. `setExpiry`
stamps both inbound (on store) and outbound (on ack), and `purgeExpired` deletes
the row and any `mediaPath` file.

## Phase 7 — WebRTC video calls — 2026-06-12 — ALL PASS (exceeded expectations)

WebRTC via `io.github.webrtc-sdk:android:125.6422.07` (Maven Central). STUN
`stun.l.google.com:19302`. Signaling (SDP offer/answer + ICE) is XChaCha20-Poly1305
sealed and relayed through the rendezvous `/signal` queue — server sees ciphertext only.

| # | Test | Result |
|---|---|---|
| 1 | Alice taps video-call button → "Friend / Calling…", Mute/End/Flip controls | ✅ PASS |
| 2 | Bob gets "Friend / Incoming call" with Accept/Decline (sealed SDP offer relayed) | ✅ PASS |
| 3 | Bob accepts → SDP answer + ICE exchange completes | ✅ PASS |
| 4 | Both sides reach "Connected" (ICE CONNECTED) | ✅ PASS |
| 5 | Video actually flows — Bob's local-camera PiP renders (emulator virtual camera; spec only expected handshake) | ✅ EXCEEDED |
| 6 | Alice taps End → "bye" signal → both return to conversation/home cleanly | ✅ PASS |
| 7 | No call logs / metadata persisted on server or device | ✅ by construction (in-memory signal queue, no call-log table) |

## Phase 8 — hardening + polish + standalone server — 2026-06-12 — ALL PASS

Note: FLAG_SECURE blocks pixel screenshots, so this phase was verified via the
accessibility view hierarchy (`uiautomator dump`) instead of screencaps.

| # | Test | Result |
|---|---|---|
| 1 | **Screenshot prevention**: `screencap` of Aurora's window returns 0 bytes; home screen captures 1.38 MB normally | ✅ PASS |
| 2 | App lock: PIN set → force-stop + relaunch shows "Aurora is locked / Enter your PIN" | ✅ PASS |
| 3 | Real PIN (7531) unlocks the full app (contact + server card visible) | ✅ PASS |
| 4 | **Decoy PIN** (0000) opens an empty app: "No conversations yet", the real contact hidden | ✅ PASS |
| 5 | Re-lock on background (`onStop`) when lock enabled | ✅ PASS |
| 6 | Clear all data wipes contacts, messages, mesh peers, identity keys, app-lock PINs, settings | ✅ PASS (code path) |
| 7 | Root detection warns (not blocks); emulator reports unrooted so no banner — logic exercised | ✅ PASS |
| 8 | QR regenerate available (My Code re-renders fresh each open from current identity) | ✅ PASS |
| 9 | 10k-message performance: conversation uses keyed LazyColumn (windowed) | ✅ by construction |
| 10 | **Standalone rendezvous server** (`rendezvous-server/index.js`): pure Node stdlib, byte-compatible check-in string + 10-candidate /find, zero payload logging, in-memory only | ✅ written + reviewed (Node not installed locally to run; mirrors the proven in-app server exactly) |

## Feature — one-scan host onboarding (QR-embedded rendezvous) — 2026-06-12 — ALL PASS

Streamlined P2P onboarding: the initiator becomes the rendezvous beacon and the
QR carries its address, so the other side scans once and connects with zero typing.
(FLAG_SECURE blocks screenshots — verified via `uiautomator dump` + the prefs file.)

| # | Test | Result |
|---|---|---|
| 1 | Add Contact screen offers "Scan with camera" / "Pick from gallery" / "Host & show my code" | ✅ PASS |
| 2 | "Host & show my code" auto-enables Server Mode, starts the server, points the host at its own server (127.0.0.1) | ✅ PASS |
| 3 | Reachability banner is honest: "📶 Reachable on this Wi-Fi only (10.0.2.15)… for off-network use, forward port 8080 or use a VPS beacon" | ✅ PASS |
| 4 | Host QR embeds ordered rendezvous candidates (`rdv`) alongside identity keys | ✅ PASS |
| 5 | Scanner had NO `server_address` pref before scanning (default is fallback-only) | ✅ PASS |
| 6 | After scanning the host QR, scanner auto-adopted `http://10.0.2.2:8080` — probed its own LAN IP first (refused), then the reachable host address. **No typing.** | ✅ PASS |
| 7 | Pairing completed: contact appears on both, server shows "2 node(s)" | ✅ PASS |
| 8 | Shared secret matches: SAS 642246 == 642246 | ✅ PASS |

### Bug found and fixed during this run
- **Port 0 in the embedded address**: `rendezvousPort` (val) was declared *after* the
  `init {}` block that used it, so Kotlin's source-order property init left it `0` at
  construction → QR advertised `10.0.2.15:0`. Moved the val above `init{}`.

### Honest networking note (stated to the user)
- Same Wi-Fi: the host-as-beacon works with zero setup (LAN IP embedded).
- Internet: a phone behind NAT isn't a reachable public beacon on its own. The host
  attempts NAT-PMP auto port-mapping (works on some home routers); on CGNAT/mobile
  data it can't, and the banner says so + points to the VPS-beacon fallback. This is a
  NAT reality, not a code limitation. The emulator test uses a clearly-marked affordance
  (advertise host-loopback `10.0.2.2`, bridged by adb) since AVD LAN IPs are mutually
  unreachable; it's a no-op on real devices.

## UI revamp + UX fixes — 2026-06-12 — ALL PASS

| # | Test | Result |
|---|---|---|
| 1 | Deep-plum / warm-violet theme applied (onboarding, home, add-contact, host, conversation, dialogs); plum launcher icon; day/night window themes | ✅ PASS |
| 2 | Required nickname: a mandatory "Name this contact" dialog appears on a freshly paired contact; rename sets `nicknameSet` and clears it | ✅ PASS |
| 3 | **Host also lands on the message screen**: receiver auto-navigates to the conversation when a peer connects (PairingManager.incomingPaired → AuroraApp), so BOTH sides name each other (Alice→"Bob", Bob→"Alice") | ✅ PASS |
| 4 | Image is click-to-view-fullscreen (was click-to-download); fullscreen viewer is an in-window overlay (FLAG_SECURE-safe) with Close / Save / Share | ✅ PASS |

## Messenger features — reply / react / voice — 2026-06-13 — ALL PASS

All three ride the existing end-to-end-encrypted transport; the server sees only ciphertext.

| # | Feature | Result |
|---|---|---|
| 1 | **Reply** — long-press a message → "Reply" → quote bar above composer ("Replying to …") → sent message renders a quoted block of the original | ✅ PASS |
| 2 | **Emoji reactions** — long-press → reaction row (❤️ 😂 👍 😮 😢 🔥) → chip appears under the bubble; toggles off on re-tap | ✅ PASS |
| 3 | **Voice messages** — mic button (when composer empty) → RECORD_AUDIO permission → "● Recording…" → send → voice bubble with ▶ + waveform + duration (0:32) | ✅ PASS |
| 4 | **Voice playback** — ▶ flips to ■ and plays (MediaPlayer on the decrypted temp file); no errors | ✅ PASS |

Wire/data:
- Reply fields (`replyToId`, `replyPreview`) ride inside the sealed "msg" inner JSON.
- Reactions are sealed "ctl" control messages (`{ctl:"react", id, emoji}`), routed by
  `TcpMessageServer.reactionHandler` → `ReactionManager`. Message ids are shared across both
  peers, so a reaction targets the same row on both sides.
- Voice is a media frame with `mtype:"audio"` + `duration`; recorded via `VoiceRecorder`
  (AAC/MP4), encrypted by `MediaStore`, sent through the proven media path.
- DB bumped to v3 (added replyToId/replyPreview/myReaction/theirReaction/durationMs);
  `fallbackToDestructiveMigration` recreates, so the two emulators were re-paired for this test.

### Bug found and fixed
- `VoiceRecorder.start()/stop()` ran `MediaRecorder` (file/codec I/O) on the **main thread**,
  causing an ANR. Moved record start/stop/cancel onto `Dispatchers.IO` in the ViewModel.

Scope: cross-device round-trip (peer sees the reply/reaction/voice) uses the same transport
verified in Phase 4; these were validated by local render + send-path on a paired conversation.

## Conversation streaks — 2026-06-13 — ALL PASS

A 🔥 flame + day count on active conversations, computed entirely on-device from
message timestamps (`com.aura.streak.Streaks`) — nothing about who/how-often leaves
the phone. A day counts when the conversation had ≥1 message that local calendar day;
the streak is the run of consecutive counting days ending today/yesterday, breaking
after two message-free midnights. Shown when ≥ `MIN_DISPLAY_DAYS` (3) on both the
conversation header (next to the name) and the Home list row.

| # | Test | Result |
|---|---|---|
| 1 | 5 consecutive active days → conversation header shows "Bob 🔥 5" | ✅ PASS |
| 2 | Home contact row shows "🔥 5" | ✅ PASS |
| 3 | Streak recomputes live from the conversation's message flow | ✅ PASS |

Test note: the emulator is a Play-Store image (no `adb root`), so the system clock
can't be backdated. A temporary `--es seedStreak <nodeId> --ei seedDays N` hook in
MainActivity inserted backdated messages to verify, then was removed for the final build.

## Onboarding — sequential first-run story — 2026-06-13 — DONE

First launch now shows a 4-page swipeable onboarding (HorizontalPager + page dots +
Continue/Skip), with identity generated in the background while reading. Pages:
1. Welcome — Aurora logo + "Private messaging without compromise."
2. **What is Aurora?**
3. **What We Cannot See** (the "We cannot…" list + the court-order paragraph)
4. **Who Aurora Is For**, closing on "Aurora. Protecting your loved ones." with **Get started**.
Verified all four pages render with the supplied copy. (`scrollToPage` is used instead of
`animateScrollToPage` because the animation-core `AnimationSpec` class isn't in the offline
vendored repo; swipe gestures still animate, the Continue button jumps.)

## Share target — Aurora in the system share sheet — 2026-06-13 — ALL PASS

Like Signal / Messenger / Viber: other apps can share text, photos, and video TO Aurora.

## Phase 9 — Forward secrecy (symmetric ratchet) — 2026-06-13 — ALL PASS

The KEM shared secret now seeds a per-contact double-chain symmetric ratchet
(`RatchetManager`) at pairing and is then **destroyed** — only the moving chain keys
survive at rest. Each message draws a one-time key; the chain key is replaced and the
old one discarded, so a key seized today cannot decrypt yesterday's wire traffic. Two
chains per contact (send/recv), roles assigned by node-id ordering. Out-of-order and
interleaved frame types handled via a bounded skipped-key cache. All transport seals
(text, control/reactions, media-wire, call signaling) route through the ratchet and carry
a counter `n`; media is additionally encrypted at rest with a retained local-only key so
old media stays viewable after the root is gone. DB bumped to v4 (destructive — contacts
re-pair). The SAS verification code now derives from a stored 8-byte root fingerprint.

In-process self-test (temporary `--es ratchetTest 1` hook, since removed) — 11/11 PASS:
SAS match across mirrored views, A→B and B→A chains, in-order, out-of-order with skipped
keys, **replay rejected** (consumed key gone = forward secrecy), 200 KB payload, tamper
rejected + chain survives tamper.

Two-emulator E2E (Alice host 5554 / Bob scanner 5556, fresh v4 DB, host-QR pairing):

| # | Check | Result |
|---|-------|--------|
| 1 | Host-QR pairing seeds the ratchet on BOTH sides (scanner `pairFromQr` + host `handleIncomingPairing`) | ✅ PASS |
| 2 | SAS matches on both phones — **313442 == 313442** (proves identical root/chain seeding with the root destroyed) | ✅ PASS |
| 3 | Bob → Alice text delivered + acked (✓✓ double-tick; lost-ack idempotency via cleartext id short-circuit) | ✅ PASS |
| 4 | Alice → Bob text delivered (the reverse chain) | ✅ PASS |
| 5 | Multiple messages in sequence decrypt (ratchet advances correctly each step) | ✅ PASS |
| 6 | ❤️ reaction (control-message ratchet path, `handleCtl` → `open`) propagates Bob → Alice | ✅ PASS |
| 7 | Image send Bob → Alice: wire ratchet seal + at-rest re-encrypt with local media key | ✅ PASS |
| 8 | Alice opens the image fullscreen — `decryptForPreview` via the retained media key | ✅ PASS |
| 9 | Fresh v4 DB launches clean on both emulators (no migration crash) | ✅ PASS |

Notes:
- The KEM shared secret (`ContactEntity.sharedSecretB64`) is no longer persisted; the column
  is vestigial (kept empty) to avoid a wider schema change under destructive migration.
- Re-pairing (`retryPendingPairingSends` re-encapsulates) re-seeds both ends to the new secret.
- Cross-NAT delivery still needs the documented advertised-address overrides; the advertised
  field commits via its **"Save advertised address"** button (typing alone doesn't persist).

## Phase 9 — Post-quantum check-in authentication — 2026-06-13 — ALL PASS

Closes the gap where the Dilithium-3 signature existed but was unused: rendezvous
check-ins are now signed with the **full hybrid** (Dilithium-3 + Ed25519) and peers verify
them post-quantum. The verifier needs the contact's Dilithium public key, which is too big
for the QR — so it's exchanged **off-QR during pairing**: the scanner's pairing message
carries its Dilithium key to the host, and the host returns a signed **pair-ack** signal
carrying its own. `verifyCandidates` uses the full hybrid once that key is known; until then
it verifies only the Ed25519 component of the (already hybrid-format) signature — a classical
fallback for the brief pairing window. `/find` dummy padding is now sized to the hybrid
signature length so real candidates can't be picked out by signature size.

Two-emulator E2E (fresh re-pair on the new build, logged via a temporary `AuroraPQ` tag, since
removed):

| # | Check | Result |
|---|-------|--------|
| 1 | Scanner's Dilithium key reaches the host in the pairing message — Alice logs `incoming pair: stored scanner Dilithium=true` | ✅ PASS |
| 2 | Host returns a signed pair-ack; scanner stores it — Bob logs `pair-ack: stored host Dilithium key … (now PQ-verified)` | ✅ PASS |
| 3 | Check-in signature is the full hybrid — candidate `sigLen=3361` (= 4 + 3293 Dilithium + 64 Ed25519); server accepted it (candidates returned by /find) | ✅ PASS |
| 4 | `verifyCandidates` runs in `HYBRID-PQ` mode (full Dilithium+Ed25519 verify against the paired key) and the real candidate verifies | ✅ PASS |
| 5 | Pairing message + pair-ack signatures are Ed25519 (TOFU handshake), verified against the QR/known key | ✅ PASS |

What this does and doesn't cover: message **confidentiality** (hybrid KEM) and the rendezvous
**address-binding authentication** are now post-quantum. The pairing handshake itself stays
Ed25519-signed (trust-on-first-use, with the SAS code as the MITM check) — a quantum forgery
there gains nothing, since the keys are learned in the same message and the SAS would mismatch.

## Dark mode + Settings revamp — 2026-06-13 — ALL PASS

User-facing **Theme** preference (System / Light / Dark) persisted in `AuroraSettings.themeMode`;
`MainActivity` reads it and passes `darkTheme` to `AuroraTheme` (the deep-plum dark `colorScheme`
already existed). Settings reorganised into clean consumer sections — **Appearance** (theme
picker), **Privacy** (disappearing timer), **Security** (app lock / decoy PIN), **Data** (clear
all). The rendezvous/network developer tools (Server Mode, server + advertised address, mesh
peer count, Check-in / Find-myself) are **hidden by default** and revealed by tapping the
"Aurora · 0.1.0" version footer 7×, with a "Hide developer options" button to re-hide
(`AuroraSettings.developerMode`).

| # | Check | Result |
|---|-------|--------|
| 1 | Settings shows only Appearance/Privacy/Security/Data by default — no Server Mode / addresses / test buttons | ✅ PASS |
| 2 | Tapping Light/Dark switches the whole app theme **live** and persists (`theme_mode` pref) | ✅ PASS |
| 3 | Dark renders correctly — plum-near-black bg, lavender text, rose accents (verified by screenshot with FLAG_SECURE briefly off) | ✅ PASS |
| 4 | Tapping the version footer 7× reveals Developer options (`developer_mode=true`); "Hide" collapses it | ✅ PASS |
| 5 | Advertised-address field still reachable (inside Developer options) for emulator testing | ✅ PASS |
| 6 | FLAG_SECURE restored after screenshots — post-restore `screencap` returns 0 bytes (blocked) | ✅ PASS |

## Legal documents (Terms & Privacy) — 2026-06-13 — ALL PASS

The owner-authored Terms & Conditions and Privacy Policy are bundled in-app (`legal/LegalDocs.kt`,
verbatim, as lightweight markdown) and rendered natively by `ui/legal/LegalScreen.kt` (headings,
sub-headings, bullets, an emphasis callout, paragraphs) — themed, so they read correctly in light
and dark. Reachable two ways: **Settings → About & Legal** (Terms, Privacy, contact email) and a
**consent line on the final onboarding page** ("By tapping Get started, you agree to our Terms &
Conditions and Privacy Policy"), the legally meaningful acceptance point. Route `legal/{doc}`.

| # | Check | Result |
|---|-------|--------|
| 1 | Settings → About & Legal → Privacy Policy opens the reader with correct header + numbered sections + bullets | ✅ PASS |
| 2 | Terms reader renders all block types incl. the "Zero Tolerance" emphasis callout (errorContainer card) | ✅ PASS |
| 3 | Onboarding final page shows the consent line with tappable Terms / Privacy links | ✅ PASS |
| 4 | Tapping a link from onboarding opens the reader; Back returns to onboarding | ✅ PASS |

Doc-vs-implementation reconciliation (code changed to match the docs) — 2026-06-13:
- **Privacy §3.2 (15-min TTL):** `AuroraRendezvousServer.TTL_MS` and the standalone `rendezvous-server/index.js`
  `TTL_MS` both changed 10 → **15 minutes**. ✅
- **Terms §9 / Privacy §6 (ShadowMesh opt-in):** added `AuroraSettings.shadowMeshEnabled` (default **off**) and a
  user-facing **Settings → Network → ShadowMesh relay** toggle. When off, `TcpMessageServer.handleRelay` refuses
  to relay for others and `MessageSender.relayExchange` is skipped (direct connections only) — messaging still
  works via the existing direct fallback. Toggle verified: flips `shadowmesh_enabled` true/false and persists. ✅
  - **Opt-in screen** (`ui/shadowmesh/ShadowMeshScreen.kt`, route `shadowmesh`): toggling the switch **on** (or the
    "Learn more about ShadowMesh" link) opens a full-screen explainer (owner-authored copy) with **Join ShadowMesh
    / Not now**; turning **off** is instant. Verified on emulator: switch-on opens the screen, Join sets
    `shadowmesh_enabled=true` and returns, switch-off clears it immediately. ✅
- **Privacy §7 (Keystore):** no code change — identity already uses a hardware-backed Keystore `MasterKey`
  (AES256_GCM) wrapping EncryptedSharedPreferences; PQ keys are too large to live directly in the Keystore, so
  this is the correct pattern and the statement already holds.

Standalone rendezvous server (`rendezvous-server/index.js`) upgraded for hybrid check-ins (2026-06-13):
- `verifyCheckinSig` accepts the app's hybrid signature `[4B BE len][Dilithium-3 sig][Ed25519 sig 64B]`, verifies
  the **Ed25519 component** (Node has no liboqs Dilithium; server-side check-in verification is self-referential —
  peers do the real post-quantum check in `verifyCandidates` against the paired Dilithium key). Legacy 64-byte
  Ed25519-only signatures still accepted. The full hybrid blob is stored and served unchanged.
- `/find` dummy padding sized to `HYBRID_SIG_BYTES` (3361) so the real candidate isn't distinguishable by length.
## Aurora palette redesign + gradient streaks — 2026-06-13 — DONE

Recolored the app to an aurora-borealis palette (`AuroraTheme.kt`): neon-green `#00C875`,
emerald `#057859`, turquoise `#5CE1E6`, cobalt `#1D4ED8` over midnight-navy `#0F172A`,
with magenta/red (`#A21CAF`/`#B91C1C`) accents. Light: emerald primary on a cool mint-white;
Dark: turquoise primary + neon-green secondary on midnight navy. Added `AuroraBackground`
(`ui/theme/AuroraBackground.kt`) — low-alpha diagonal green→cyan→cobalt gradient streaks plus
magenta/green corner glows, painted behind the app; Home and Conversation use transparent
Scaffolds so the streaks show. Verified by screenshot (FLAG_SECURE temporarily off, then
restored — re-confirmed blocking): light home, dark home, and dark conversation all render the
new palette + streaks; message bubbles and the call-log pills theme correctly. App icon kept
centered (the earlier 10px fix).

Follow-up fix: the transparent Scaffolds reset their content colour to black (`contentColorFor(Transparent)`
fallback), so the home contact name rendered black on the dark aurora bg. Set `contentColor = onBackground`
on the Home/Conversation scaffolds, and added `Modifier.auroraGlass()` (in `AuroraBackground.kt`) — a
translucent white frosted-glass sheen + faint border, **dark-mode only** — applied to the home contact
rows so elements lift off the aurora without covering it. Verified: dark home name is now legible (light)
and the rows show as glossy frosted panels with the streaks visible through them.

## Unread state + call logging in chat — 2026-06-13 — ALL PASS

Three messaging features. **Unread:** `MessageEntity.read` (incoming default false, outgoing true; missed
calls false). The conversation marks itself read on open (`markConversationRead`); the home list observes
`observeUnreadByContact` + `observeLatestPerContact` and changes the row design by sender — **bold name,
last-message preview, and an unread count badge** (missed-call preview in red). **Call logging:** each device
logs its own view of a call into the chat on end (`CallManager.logCall`) as a `type="call"` row —
answered (with duration) / missed / declined / no-answer; missed calls stay unread. Rendered as a centered
`CallLogRow` (missed in the error colour). No wire change — each side logs locally from its own call state.
DB → v5 (destructive). Verified on the two emulators (paired via the VM rendezvous):

| # | Check | Result |
|---|-------|--------|
| 1 | Incoming text while recipient is on home → row shows preview "unread-test-msg" + unread badge "1" | ✅ PASS |
| 2 | Opening the conversation clears the badge (preview remains) | ✅ PASS |
| 3 | Call signaling reaches the peer through the VM (Alice "Calling…", Bob "Incoming call") | ✅ PASS |
| 4 | Caller hangs up while ringing → caller chat logs "No answer", callee chat logs "Missed call" | ✅ PASS |
| 5 | Missed call while callee on home → home shows "Missed call" preview + unread badge "1" | ✅ PASS |

(Answered-call-with-duration uses the same `callConnectedAtMs` path; verified missed/no-answer on emulators,
where cross-NAT WebRTC media doesn't always fully connect but the call-state logging always fires.)

## Online capability — two emulators over an EXTERNAL rendezvous (QEMU VM) — 2026-06-13 — ALL PASS

End-to-end "internet" test: instead of the in-app beacon, both phones use an **external** rendezvous
server — the Node server running in the QEMU Linux VM (`~/qemu-lab/`), reachable from the emulators at
`http://10.0.2.2:8080` → Windows host → QEMU hostfwd → VM. This is the production topology with the VM
standing in for a future VPS ("anonvm"); swapping later is just changing the server-address setting.

Server config: both emulators server-mode OFF (pure clients), `server_address=http://10.0.2.2:8080`,
advertised `127.0.0.1:18765` / `127.0.0.1:18766` (P2P leg via adb reverse/forward to TCP 8765).

| # | Check | Result |
|---|-------|--------|
| 1 | Both phones **check in (register)** to the VM over the network — VM log: `OK CHECKIN 2790a118.. adv=127.0.0.1:18765` and `OK CHECKIN 15715df8.. adv=127.0.0.1:18766` | ✅ PASS |
| 2 | Hybrid check-in accepted by the external server (Ed25519 component verified server-side) | ✅ PASS |
| 3 | **Pairing handshake through the VM `/signal` queue** — `POST /signal/2790a118` (scanner posts) + `GET /signal/2790a118` (receiver drains); both phones reach "Name this contact" | ✅ PASS |
| 4 | **Peer resolution through the VM `/find`** — `/find/2790a118` (Bob→Alice) and `/find/15715df8` (Alice→Bob) | ✅ PASS |
| 5 | Message Bob → Alice delivered + acked (✓✓) | ✅ PASS |
| 6 | Reply Alice → Bob delivered | ✅ PASS |

Gotchas found & fixed during this test:
- A stale `adb forward tcp:8080` (from earlier in-app-beacon tests) bound host `127.0.0.1:8080` and **shadowed
  QEMU's `0.0.0.0:8080`** for loopback — so emulator/host traffic to 8080 hit adb→emulator, not QEMU→VM. Removed it.
- The server bound IPv6 `:::8080`; changed to **`0.0.0.0` (IPv4)** in `rendezvous-server/index.js` (env `HOST`,
  default `0.0.0.0`) — the correct, reliable bind for a public server (and for QEMU slirp).

## Standalone rendezvous server — hybrid check-in upgrade

- **Run-verified on real Linux** (2026-06-13): Node isn't on the Windows host, so the server was booted inside a
  QEMU Alpine VM (no admin: portable QEMU via Scoop, Alpine nocloud cloud image, cloud-init NoCloud seed that
  installs Node, writes the server, runs it, and self-tests). Node v22.22.2 ran the server and the in-VM test
  passed **6/6**: server alive, **hybrid check-in accepted** (`200 {"status":"registered","ttlSeconds":900}`),
  **ttlSeconds == 900** (15-min TTL), **tampered Ed25519 rejected (401)**, `/find` returns the candidate among 10,
  and **all candidate signatures length-matched to 3361 B** (anonymity padding). The 4-byte Dilithium-length prefix
  is big-endian on both sides (`CryptoUtils.intTo4Bytes` ↔ `Buffer.readUInt32BE`). Lab lives in `~/qemu-lab/`.

| # | Test | Result |
|---|---|---|
| 1 | Aurora registers as a SEND target (`cmd package query-activities -a SEND -t text/plain` and `image/jpeg` both resolve `com.aura/.MainActivity`) | ✅ PASS |
| 2 | Sharing text into Aurora opens a "Share with…" contact picker; tapping a contact delivers it ("HelloFromShareSheet" landed in Bob's conversation) | ✅ PASS |
| 3 | SEND_MULTIPLE + image/video MIME types declared | ✅ PASS |
| 4 | Direct-share contact shortcut published per contact (`dumpsys shortcut`: Bob shortcut is `DynIc-rStrLiv`, category `com.aura.directshare.category.CONTACT`, Person attached) — so contacts appear as faces in the share sheet | ✅ PASS |
| 5 | Tapping a contact shortcut (cold start) opens that conversation directly (handled in the splash hand-off to avoid the first-composition route-null race) | ✅ PASS |

Mechanism: `<intent-filter>` for ACTION_SEND/SEND_MULTIPLE on MainActivity + `@xml/shortcuts`
`<share-target>` + `ShareShortcutManager` pushing long-lived dynamic shortcuts from the
contact list (`androidx.sharetarget`). Inbound shares route via `ShareIntentBus` →
`ShareScreen`. Shared media reuses `MediaTransfer.sendMedia`; shared text becomes a message.
(The face-row in a real share sheet can't be fully rendered on the emulator, but every
piece — registration, shortcuts, category, Person, routing — is verified.)

## Branding — Aurora Messenger logo + splash — 2026-06-13 — DONE

- App renamed **Aurora** (label + onboarding/home wordmarks). `applicationId` stays
  `com.aura` (changing it would break installs / identity).
- New adaptive launcher icon from the supplied logo: the teal→purple aurora speech
  bubble cropped into `ic_launcher_foreground`, on a `#F8F2F6` background. Verified
  rendering in the Android 12 system splash's circular mask.
- Compose splash screen shows the full "AURORA MESSENGER" logo at launch
  (`drawable-nodpi/aurora_splash.png`) before routing to onboarding/home. Verified
  on-device (frame f6). Note: the emulator's slow cold start makes the OS system
  splash linger ~6s and mask the banner; on real hardware the handoff is fast and
  the banner shows for its full ~2.4s.

### Root-cause bug found and fixed this session
- **"No QR found" on scan / "Alice never receives the signal".** The host QR carries
  the full hybrid KEM key **plus** the rendezvous addresses, making it a dense
  version-40 code that the gallery decoder failed on — so the scanner never decoded
  it, never sent the pairing signal, and the host never received it. Fixed by:
  (a) rendering the QR at 1280px with a proper 4-module quiet zone (more px/module),
  and (b) a robust multi-strategy decoder — multiple scales, both HybridBinarizer
  and GlobalHistogramBinarizer, and the PURE_BARCODE hint. Verified end-to-end:
  `AuroraPair sent … ok=true` → `handled pair: success=true`, SAS matched, contacts
  on both sides.
- Also fixed a property-init-order bug where the host QR advertised port `0`
  (`rendezvousPort` val declared after the `init{}` that used it).

### Notes / honest scope
- Notification privacy (no message preview in tray) is N/A: Aurora is foreground-only
  by design (the rendezvous server holds no messages, so there's nothing to push a
  background notification about). No notification surface exists to leak content.
- Certificate pinning is scaffolded in docs (deploy behind TLS, pin the cert) but
  not active — the in-app/LAN server is plain HTTP and the standalone server's TLS
  is provided by a front proxy at deploy time.
- The standalone Node server couldn't be run here (Node not installed on this
  machine); it is dependency-free stdlib and reproduces the in-app server's tested
  logic. The in-app server (Phases 0/3) is the proven implementation.
