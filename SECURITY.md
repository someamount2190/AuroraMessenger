# Security Policy

Aurora Messenger is a post-quantum, end-to-end encrypted messenger. We welcome scrutiny.
This is **pre-alpha software that has not yet had an independent third-party audit**, so
good-faith reports make it materially safer.

## Reporting a vulnerability

Email **christiancorrea26@gmail.com** with the subject `SECURITY: <short summary>`.
Please do **not** open a public GitHub issue for a security vulnerability.

Include where possible:
- A description of the issue and its **security impact**.
- Steps to reproduce or a proof of concept.
- Affected component, app version (`versionName`), and commit/tag if known.
- Any suggested remediation.

Please give us reasonable time to remediate before public disclosure (coordinated, or 90
days, whichever is sooner).

## Response targets (best-effort; solo, volunteer-maintained)

| Stage | Target |
|---|---|
| Acknowledgement | within 5 days |
| Initial severity assessment | within 14 days |
| Fix / mitigation plan | by severity; critical issues prioritized |
| Coordinated disclosure & credit | after a fix ships |

## Scope

**In scope** — please report:
- The cryptographic core (`com.aura.crypto`): KEM, signatures, AEAD, KDF, ratchet, prekeys.
- Pairing & verification (PQXDH handshake, SAS, identity binding).
- The rendezvous protocol and servers (`rendezvous-server/`, `com.aura.server`).
- At-rest protection (SQLCipher DB, key storage, encrypted backups, cryptographic erase).
- App hardening (app lock, decoy PIN, duress wipe, `FLAG_SECURE`) within the stated model.
- Anything that breaks a guarantee in the README **Security claims** table.

**Out of scope** — generally not eligible:
- Already-public vulnerabilities in third-party dependencies (report upstream; do tell us
  if Aurora's usage amplifies them).
- Attacks requiring a compromised/rooted/malware-infected device — endpoint security is an
  explicit non-goal (see [`docs/THREAT_MODEL.md`](docs/THREAT_MODEL.md)).
- Physical attacks beyond the device-theft/coercion model in the threat model.
- Denial of service against the free, single-instance rendezvous server.
- Social engineering, spam, self-XSS.
- Metadata the design openly discloses (e.g., that the rendezvous can tell a device is
  reachable — see the Privacy Policy and threat model).

Full model: [`docs/AUDIT_SCOPE.md`](docs/AUDIT_SCOPE.md) and [`docs/THREAT_MODEL.md`](docs/THREAT_MODEL.md).

## Supported versions

Aurora is on a single pre-alpha line; only the **latest released tag** is supported.

| Version | Supported |
|---|---|
| latest `0.2.x-pre` tag | ✅ |
| older pre-alpha tags | ❌ |

## Safe harbor

We will not pursue action against researchers who, in good faith:
- follow this policy,
- avoid privacy violations, data destruction, and service degradation,
- only interact with accounts/devices they own or are authorized to test, and
- allow reasonable time to remediate before public disclosure.

## Encrypted reports

There is currently no published PGP key. If your report is sensitive, email first asking to
establish a secure channel and we'll arrange one.
