# P2.1a Phase G: live acceptance and final review

**Status:** F1 backend HTTP/CSRF/PostgreSQL E2E gate accepted; **Phase G open**. On November 21, 2026, the maintainer confirmed that CI was green after the real-servlet CSRF cookie-header and expiry-assertion fixes. This record distinguishes that server-side result from pending frontend verification, manual browser acceptance and final merge authorization. The task's [requirements and acceptance scenarios](specification.md#requirements-and-invariants) remain authoritative.

## Automated evidence and outstanding checks

| Gate | Evidence or command | State |
| --- | --- | --- |
| Backend Unit/Modulith/package | `cd src/backend/sharewise && ./mvnw -Punit-tests clean verify` | Previously green during F1 review; rerun against final branch |
| PostgreSQL-backed Integration | `./mvnw -Pintegration-tests clean verify` with PostgreSQL 18 | Prior B/C approval; rerun against final branch |
| PostgreSQL-backed E2E | `./mvnw -Pe2e-tests clean verify` with PostgreSQL 18 | **Green in maintainer-confirmed F1 CI**; includes `IamHttpAuthenticationE2E` |
| Frontend lint / type checks | `cd src/frontend && npm ci && npm run lint && npm run typecheck && npm run test:typecheck` | Pending Phase G final-branch evidence |
| Frontend tests / builds | `npm run test && npm run build && npm run test:static-export` | Pending Phase G final-branch evidence |
| Source hygiene | `python3 scripts/ci/check-source-encoding.py`, `git diff --check`, repo Markdown-link validation | Required on final patch |
| Real browser, real HTTPS backend | Procedure below, using production signing and a disposable PostgreSQL database | **Pending human acceptance** |
| Independent code review and merge authorization | Review final diff and residual risks | **Pending** |

A green E2E CI job demonstrates backend HTTP/transaction/cookie behavior through a servlet and PostgreSQL, **not** browser cookie persistence, real Web Locks, frontend accessibility, or the reverse proxy in a production-style topology. Record the final CI job/pipeline URL and commit ID with the acceptance decision; do not infer them from a local test run.

## Human browser procedure (IAM-AT14/15)

**Prerequisites:** Use a disposable PostgreSQL 18 database, a backend with `sharewise.iam.auth.enabled=true` and correctly provisioned ES256 issuer/audience/signing/verification profile, and the current frontend served at a **single trusted HTTPS origin** with same-origin `/api/*` forwarding to the backend. Use an isolated browser profile without old ShareWise cookies. Do not use test JWT issuers, bypass browser TLS errors, disable the CSRF filter, or move the frontend to a separate origin. The [developer setup](../../../development.md#initial-iam-authentication) and [deployment guide](../../../../deploy/README.md) describe the relevant wiring. Use unique test identities and nonproduction passwords.

Record the browser/version, viewport sizes, theme, backend/frontend commit IDs, origin (excluding credentials), and results. Do **not** paste access JWTs, refresh-cookie values, signing keys or raw passwords into the report.

1. **Registration and server contract:** Navigate to `/auth/register` with keyboard only, submit a valid unique email/password, and verify accessible completion/navigation. In DevTools Network, confirm a non-cacheable `GET /api/iam/v1/auth/csrf` before `POST /api/iam/v1/auth/register`, matching `X-XSRF-TOKEN`, `Origin` equal to the browser origin, and a `201` response. The CSRF token bootstrap must occur inside the Web Lock. Check that an existing email gives the documented `409` and field errors are announced for invalid input.
2. **Cookies:** Inspect browser cookie attributes **without copying their values**. The CSRF and refresh cookies use `__Host-` names, `Secure`, `HttpOnly`, `SameSite=Strict`, `Path=/`, no `Domain`. On successful authentication, the CSRF cookie is invalidated and a refresh cookie is set with expiry bounded by the committed session deadline. Inspect both `Set-Cookie` headers: a servlet may express deletion using `Expires` in the past instead of `Max-Age=0`. Cookies must not appear in response JSON.
3. **Login, CSRF renewal and errors:** Open `/auth/login` and sign in with the new account; verify a **fresh** CSRF bootstrap following the preceding successful mutation, then `200` login. Wrong password, missing/invalid CSRF, and untrusted Origin must not authenticate or set a new refresh cookie. Check that the UI describes denial generically and keeps password values out of displayed/server logs.
4. **Session lifetime:** Verify that a bearer is sent for subsequent authenticated API requests, but **not** for anonymous IAM auth endpoints. Inspect memory/storage behavior without displaying the credential: no JWT in `localStorage`, `sessionStorage`, persistent cookies or HTML hydration. Reload the page and open a new tab; both should require reauthentication even if the HttpOnly refresh cookie persists. Verify expired/unauthorized token handling also leads to reauthentication, without simulated refresh or a functional logout claim.
5. **Cross-tab locking:** In two tabs on the same HTTPS origin, initiate overlapping login/register submissions and inspect sequencing of CSRF bootstrap and cookie-changing POST requests. The second mutation must not start its CSRF bootstrap before the first lock has released. In a browser/devtools fixture where `navigator.locks` is unavailable or lock acquisition fails, no credential POST must be issued; an actionable error should be shown. Restore the API afterward.
6. **Accessibility and appearance:** Repeat form navigation, validation errors, submission, focus changes, and success/denial with keyboard only. Check narrow/mobile and desktop widths, light/dark/system themes, zoom and focus visibility, and screen-reader announcements where available. Record any contrast, layout or keyboard blocker as a defect rather than declaring acceptance from jsdom tests.
7. **Cross-context isolation:** Verify that a refresh cookie alone does not authenticate a bearer-only Group-shaped API operation. Confirm no visible Group projection, automatic membership creation, refresh rotation, or account lifecycle event behavior is presented as implemented.

**Acceptance evidence:** Attach sanitized request/response status/attribute observations, screenshots of layout/focus if useful, and a pass/fail/blocked entry per step. A blocked step remains an open gate, even if Unit and E2E CI pass.

## Requirement-to-evidence trace

| Requirements / scenarios | Strongest present evidence | Remaining Phase G action |
| --- | --- | --- |
| IAM-A01–A05; IAM-AT01–AT08 | IAM domain/credential Unit tests; `IamAuthenticationIT`, `IamAuthenticationE2E`, `IamPersistenceE2E` and database constraints | Capture final-branch Unit/IT/E2E CI links |
| IAM-A06–A09; IAM-A13; IAM-AT09–AT12 | `IamAuthenticationHttpTests`, `IamHttpAuthenticationE2E`, production JWT/credential tests | Verify actual HTTPS cookie storage, CSRF/Origin and header observations in browser |
| IAM-A07, A10, A14; IAM-AT10, AT13, AT17 | `FullApplicationIamIngressTests`, JWT/DI/Modulith tests, production-wiring tests | Capture final-branch CI and independent trust-boundary review |
| IAM-A11; IAM-AT14–AT15 | Frontend `identity-runtime`, `auth-session`, `authentication-form` and API tests with mocked HTTP | Complete **real browser** flows, Web Locks, accessibility and mobile/theme review |
| IAM-A12; IAM-AT16 | Context/module verification and IAM migration/repository inspection | Review absence of `UserRegistered` publication and Group writes; sign off on reconciliation handoff below |

## Bootstrap and follow-on handoff

**Group identity projection:** P2.1a commits users only to `identity.users`; it does not publish `UserRegistered` events, create `groups.users`, or populate Group memberships. Consequently, a future P2.4a/P2.2d listener cannot rely on an event registry to replay accounts created **before** publication begins. That integration must provide an explicit, idempotent initial projection from an authoritative IAM account snapshot, account for registrations racing with the snapshot/cutover, and reconcile missing/stale Group identities. A suitable cutover needs a proven no-gap boundary (for example, a transactional publication/outbox boundary plus snapshot reconciliation); it is **not** safe to enable a listener and assume all historical registrations appear automatically. Do not backfill across bounded contexts from this slice or infer eligibility from a signed JWT. Account deletion ordering and reconciliation require their separately reviewed policy.

**Refresh/logout:** P2.1a creates one durable, unconsumed refresh-token verifier attached to each initial session and issues the opaque cookie. P2.1b must consume/rotate it transactionally with replay detection, obey idle/absolute deadlines, and reuse `AuthCookieLock`/`AuthCoordinator`'s browser coordination policy, not create a second conflicting cookie-mutation lock. No refresh or logout endpoint is present here. P2.1d remains responsible for authoritative live account/session eligibility; currently verified access JWTs can remain accepted until expiry/skew without live introspection.

**Review gate:** Do not mark P2.1a's backlog item complete or authorize merge until the final CI references, real-browser results, independent review and any remaining exposure/operational limitations have been recorded and approved.
