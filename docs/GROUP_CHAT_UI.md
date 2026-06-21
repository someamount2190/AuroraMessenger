# Group Chat — UI Implementation Plan

**Status:** Plan (no code). The group-chat **engine** is built + green on `feature/group-chat`
(see `GROUP_DEGRADED_RELAY.md`): create → admin-signed membership → pairwise fan-out → offline
carry relay, all NAT-traversing, no server in the data path. This plans the **UI** that sits on it.

**Read with:** `KOTLIN_SOP.md` (Compose §7), `GROUP_DEGRADED_RELAY.md`.

## A. Goal & scope

Make group chat usable: see groups in the conversation list, open one, read messages (with sender
names), send text, create a group, and manage membership. Reuse the engine (`GroupManager`,
`GroupMessageSender`, `groupDao`) and the existing message-bubble rendering.

**v1 in scope:** group conversation list entry; group conversation screen (text); create group;
group info + admin management (rename, add/remove member, leave).

**v1 out of scope (engine not built for groups yet — disable cleanly, don't fake):** group media
& voice, group reactions, group disappearing timers, group calls. Surface them as absent, not broken.

## B. Key decisions (grounded in the current code)

1. **Do NOT branch `ConversationViewModel`.** It's dense with contact-only concerns — pairing/
   verify, single-peer `rtcTransport.state(contactId)`, media/voice, reactions, streak,
   delete-contact. Branching it on `isGroup` would thread `if`s through all of that and risk the
   proven 1:1 path. **Add a separate `GroupConversationViewModel` + `GroupConversationScreen`**,
   and **extract the shared message-list/bubble composables** so both screens render identically
   (the group screen adds a sender-name label above incoming bubbles).
2. **Distinct nav route.** Add `Routes.GROUP_CONVERSATION = "group/{groupId}"` (+ `Routes.group(id)`
   and a `CREATE_GROUP = "creategroup"`). Cleaner than overloading `conversation/{contactId}` and
   detecting the type at runtime. Home dispatches: contact → `conversation(id)`, group → `group(id)`.
3. **Unified conversation list.** Introduce a sealed `ConversationRow { Contact(ContactRow) |
   Group(GroupRow) }` in `HomeViewModel`; merge `contactDao.observeAll()` with
   `groupDao.observeGroups()` + each group's latest message, sorted by last-activity time. The
   existing `ContactRow` stays; `GroupRow(group, memberCount, lastMessage)` is new.
4. **Sender identity.** Group incoming rows carry `senderNodeId`; resolve to a display name via the
   member's contact (`contactDao`), falling back to a short nodeId. The group VM exposes a
   `Map<nodeId, displayName>` (members ∩ contacts) for the screen.
5. **Creation entry point = the 1:1 conversation's "Add people" menu** (Signal/WhatsApp pattern),
   not a Home "New group" button. From a two-person chat, the overflow dropdown gains **Add people**
   → the contact picker opens **with the current peer pre-selected** → `createGroup(me + peer +
   invitees)` → navigate to the new group. **This creates a NEW group; the 1:1 thread is untouched.**
   Do NOT convert the thread in place: the peer's device also holds the 1:1 (can't be retroactively
   re-keyed there), 1:1 history is sealed pairwise, and membership is admin-authoritative. The same
   picker is reused inside group info for `addMember`. (A Home "New group" entry is optional/later.)

## C. Components

### New files
- `ui/group/GroupConversationViewModel.kt` (`@HiltViewModel`): `groupId` from `SavedStateHandle`;
  `group: StateFlow<GroupEntity?>` (`groupDao` observe — add `observeGroup(id)`), `members`,
  `nameOf(nodeId)`, `messages = messageDao.observeConversation(groupId)`, `send(body)` →
  `groupMessageSender.sendGroupMessage`, `markRead()`. No media/voice/reactions/verify wiring.
- `ui/group/GroupConversationScreen.kt`: top bar (name + "N members", tap → group info), shared
  message list (sender-name label on incoming), text compose bar only.
- `ui/group/CreateGroupScreen.kt`: name field + multi-select list of contacts
  (`contactDao.observeAll`); accepts an optional `preselect` nodeId (the peer, when launched from a
  1:1 chat — pre-checked and shown as already included). "Create" → `groupManager.createGroup(name,
  picked)` → navigate to `group(id)`. Needs a small `CreateGroupViewModel`. The same contact-picker
  composable is reused by group info's "Add member".
- `ui/group/GroupInfoScreen.kt` (or a bottom sheet): member list with roles; admin-only actions
  (rename, add member [from contacts not already in], remove member, leave). Backed by the group VM
  or a `GroupInfoViewModel` calling `GroupManager`.
- `ui/conversation/MessageList.kt` (extracted): the shared bubble/list composables both screens use,
  with an optional `senderName: String?` slot rendered above incoming group bubbles.

### Edits
- `ui/AuroraApp.kt`: add `GROUP_CONVERSATION`, `CREATE_GROUP` routes + `composable` blocks; pass
  `onOpenGroup`/`onCreateGroup` into Home; arg extraction for `groupId`.
- `ui/home/HomeViewModel.kt`: add `GroupRow`, the sealed `ConversationRow`, and a merged
  `conversations: StateFlow<List<ConversationRow>>`; extend search to include group names.
- `ui/home/HomeScreen.kt`: render both row types; `onOpenConversation` branches by row type
  (contact → `conversation(id)`, group → `group(id)`). No "New group" button needed — creation is
  driven from the 1:1 conversation menu (decision B5).
- `ui/conversation/ConversationScreen.kt`: add **Add people** to the existing top-bar overflow
  dropdown (alongside rename / timer / delete) → navigate to `CREATE_GROUP` with the peer
  pre-selected. (`CREATE_GROUP` route carries an optional `peer` arg.)
- `db/AuroraDatabase.kt` `GroupDao`: add `observeGroup(groupId): Flow<GroupEntity?>` and
  `observeMembers(groupId): Flow<List<GroupMemberEntity>>` for reactive UI (keep existing suspend
  queries for the engine).
- `MessageList`/bubble: when `senderNodeId != null`, show the resolved name (color-keyed) above
  incoming bubbles; own messages unchanged.

### Reused unchanged
`messageDao.observeConversation(id)` (works for groupId), the engine send/flush/carry paths, the
message entity (already has `groupId`/`senderNodeId`), theme tokens, nav scaffolding.

## D. Phases (each: build → emulator-verify → commit on feature/group-chat)

- **UI-P1 — Read path.** GroupDao reactive queries; `HomeViewModel` merged list; Home renders group
  rows; `GroupConversationViewModel` + `GroupConversationScreen` (read-only) behind the new route;
  extract `MessageList`. *Accept:* a group created via a test hook appears in the list and opens,
  showing messages with sender names. *Verify:* seed a group (debug action or adb), observe list +
  open.
- **UI-P2 — Send path.** Wire the compose bar to `sendGroupMessage` with optimistic insert; mark
  read on screen. *Accept:* typing sends; the row appears immediately and flips to delivered as
  members ack.
- **UI-P3 — Create group from a 1:1.** Add **Add people** to the conversation overflow dropdown →
  `CreateGroupScreen` (peer pre-selected) + `CREATE_GROUP` route. *Accept:* from a two-person chat,
  Add people → pick others + name → a new group is created and opens; the original 1:1 chat is
  unchanged; members receive `grp_sync` and the group appears for them.
- **UI-P4 — Management.** `GroupInfoScreen`: member list + admin actions (rename/add/remove/leave),
  role-gated. *Accept:* admin renames/adds/removes and members converge; a member can leave and the
  group deactivates locally.
- **UI-P5 — Polish.** Sender color/avatar, "N members" subtitle, empty/loading states, optional
  "sent N/N" group delivery indicator, accessibility (content descriptions, focus order), and
  clearly-disabled affordances for the v1-out features.

## E. Verification approach (important)

`MainActivity` ships `FLAG_SECURE`, so screenshots are blocked — drive the UI via
`uiautomator dump` (the accessibility tree is readable under FLAG_SECURE) and verify via logcat +
DB state. Group chat has been verified end-to-end on **two emulators**: modern emulators share a
netsim virtual-WiFi LAN, so they're mutually reachable, ICE completes, and 1:1 and group messages
deliver between them — create → membership sync → fan-out → bidirectional all confirmed. The host
runs two emulators comfortably; a third can exhaust RAM. Cold-booted AVDs are PIN-locked (unlock
over adb before launching the app).

## F. Open questions (UX — need your call)

1. ~~"New group" entry point~~ **Resolved:** created from the 1:1 conversation's "Add people"
   dropdown, seeded with the peer; creates a new group (1:1 untouched). See decision B5.
2. **Group conversation route:** distinct `group/{groupId}` (recommended) vs reusing
   `conversation/{contactId}`?
3. **Delivery indicator:** show per-group "sent N/N members", or just sent/delivered like 1:1?
4. **Group avatar/identity:** initials/auto-color for v1, or a name only?
5. **Admin transfer / admin-leaves:** out of v1 (admin can't leave yet) — confirm that's acceptable.
