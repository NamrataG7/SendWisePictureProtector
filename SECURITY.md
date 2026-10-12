# Security Policy

## Reporting a Vulnerability

If you discover a security issue in SendWise, please report it **privately** by e-mail:

**namrata.m.gaikwad@cumminscollege.in**

Please include:
- A description of the issue and its impact
- Steps to reproduce (or a proof-of-concept)
- Affected component (Android IME, parental dashboard, ingest API, model artefact)
- Your name / handle for credit (optional)

Do **not** open a public GitHub issue for security reports.

## Response SLA

| Milestone | Target |
| --- | --- |
| Initial acknowledgement | within **7 days** of report |
| Triage + severity assessment | within **14 days** |
| Resolution attempt (fix, mitigation, or documented workaround) | within **30 days** |

Complex issues may take longer; we will keep you informed if they do.

## Scope

In scope:
- **SendWise Android IME** (`SafeKeyboardApp/`) — on-device classifier, keyboard service, ingest client, cert pinning.
- **Parental dashboard** (`parental-dashboard/`) — Next.js app, NextAuth session, ingest API route, Redis / KV data model.

Out of scope:
- Third-party dependencies (Next.js, NextAuth, scikit-learn, Android system libs, etc.) — please report those upstream. We will update our lock files when fixes ship.
- Vulnerabilities requiring a physical, rooted, or already-compromised device.
- Denial-of-service against the free-tier hosting itself (Vercel / Upstash rate limits).
- Social-engineering of the parent account.

## Known Security Limitations (Prototype Scope)

SendWise as published alongside the paper is a **research prototype**, not a production-hardened product. The following limitations are documented in [`PAPER_ALIGNMENT_REVIEW.md`](./PAPER_ALIGNMENT_REVIEW.md) and are known:

- **No salt rotation.** The per-install `AppSalt` used for the salted user hash is generated once and never rotated.
- **No authentication on the ingest endpoint.** The `/api/ingest` route accepts any well-formed metadata payload; rate limiting is the only defence.
- **Single parent account.** The dashboard supports exactly one parent identity (env-configured); no multi-parent, multi-child, or role separation.
- **Certificate-pinning placeholder.** The IME ships a pinning hook but the pin set is a placeholder to be replaced with the production leaf/intermediate hashes before real deployment.

These are deliberate prototype scoping decisions and are called out in the paper's Limitations section. They are **not** in-scope security bugs unless a concrete exploit chain is demonstrated.
