# P2.1b - Refresh, logout and session lifecycle: implementation plan

**Status:** Specification and plan approved. Phases A–C are accepted; Phase D HTTP integration is reviewed and its connection-pool follow-up needs final integrated CI evidence. The maintainer accepted Phase E's **manual browser smoke stage** on November 22, 2026, with protected-operation and adversarial cases recorded as blocked or not exercised. Final independent security review, complete CI reconciliation and separate merge authorization remain open. See [the acceptance record](acceptance.md). Checkboxes represent completed, verified and approved activities, not inferred coverage.

## Baseline, scope and team boundaries

- Start from the post-P2.1a approved source archive on an unchanged local `main`. Follow the [development workflow](../../../development/ai-development-workflow.md) and isolate each coherent patch on a dedicated branch, review and merge only with authorization. Approved specification and plan must be committed and pushed to the authoritative task branch before implementation begins.
- Preserve the P2.1.0 trusted ingress, P2.1a IAM schema and login behavior, the `identity` module's ownership of session state, operation-scoped `Transactions`, the current frontend API client, and the host-owned Spring Security chains. The new backend work extends IAM-private ports/adapters; generated jOOQ types are re-generated only for an approved migration.
- The standalone **P2.1b.1** backlog item is implemented as the frontend renewal/restoration phases of this plan, not as a second refresh engine. Its review evidence is called out separately when those phases close.
- MVP excludes scheduled cleanup and a generic token introspection endpoint. A read-only bearer validation response cannot establish whether a refresh cookie rotated successfully after a lost POST; the specified ambiguous-outcome response is terminal until a different recovery protocol is approved.
- The supplied Maven-only Linux x86-64 handoff matches the backend POM/wrapper. Backend Unit baseline on the supplied archive: **251 tests passed**, `mvn -B -ntp -o -Dmaven.repo.local=<isolated-cache> -Punit-tests clean verify`. PostgreSQL, Node 24/npm and real browser HTTPS infrastructure are not included in this handoff; their gates must run in CI or a maintainer environment. A missing gate is not a pass.

## Contract-first decomposition and parallel ownership

The technical lead first freezes the HTTP DTO/problem-code/session-binding contract and adapter port semantics. Independent streams can then proceed against that reviewed contract, but no slice is accepted as an end-to-end feature until it is integrated with its real counterpart.

| Stream | Sole change ownership | Parallel prerequisites | Integration responsibility |
| --- | --- | --- | --- |
| A: IAM lifecycle core | IAM operation services, session/refresh policies, verifier parsing, typed outcomes and Unit tests | Approved contract; existing IAM ports | Provides single authoritative rotation/replay/logout semantics |
| B: IAM persistence | IAM-private jOOQ adapters/ports, row locks, migration if truly required, PostgreSQL tests | Frozen A/B port contract | Proves serialization, no partial commits and reuse revocation on real PostgreSQL |
| C: Browser identity (P2.1b.1) | `core/identity`, shared `lib/api` hooks and identity tests; minimal UI/nav additions | Frozen wire outcomes and session ID field | Uses mocked IAM transport until D is available; no second API client |
| D: IAM HTTP integration | Controller, response DTOs, error mapping, cookie/CSRF/Origin, full-context HTTP/E2E tests | A and B committed/reviewed | Joins credential lifecycle with actual Spring security and commit boundary |
| E: Final acceptance | Browser integration, cross-tab tests, steady-state documentation, independent review | A–D accepted | Checks complete flow across JVM, PostgreSQL, proxy and browser |

Avoid concurrent edits to the same ports, DTOs, shared API client, or test fixture. Each stream submits one independently reviewable patch at a time; integration owners reconcile changes only after the relevant patches pass their human gates. The proposed phase order below is a dependency graph, not a requirement to serialize unrelated A/B/C implementation work.

## Phase 0 - Confirm API and correctness contracts

**Scope:** Review the exact route payloads, `sessionId` response addition, conditional `expectedSessionId` semantics, `refresh_unavailable`/`session_context_changed` outcomes, safe pre-dispatch 401 allowlist, and logout's no-cookie idempotency. Align the session-core port contract and lock/replay ordering with the actual `identity` tables; choose one row-lock acquisition order for refresh/logout. No implementation yet.

- [x] Confirm the token-secret validation and correct-secret-before-family-revocation rule, plus one-transaction consumption/successor insertion/idle extension and commit-before-credential-release semantics (IAM-B01–B05).
- [x] Confirm how pre-dispatch `invalid_credential`/`authentication_required` is evidenced, body replayability and the boundary with application-generated `unauthenticated` (IAM-B08, IAM-BT13).
- [x] Confirm the browser FSM's context-change, restoration and lost-response classification; add adversarial tests before the first code patch if a contract gap is found (IAM-B07–B10).

**Gate 0:** Human approves specification and this phase plan; the approved artifacts are committed and pushed to the task branch. Later contract changes return to this gate.

## Phase A - IAM application lifecycle and Unit tests

**Scope:** Introduce focused, DI-managed IAM refresh/logout operations using the existing time source, transaction outcome pattern, credential generator and signing infrastructure. Separate validated credential parsing, session policy and typed business outcomes from HTTP/cookie transport. No new `shared` authentication service or parallel crypto implementation.

- [x] Add constant-time secret-verifier checks, generic invalid-secret denial, verified-consumed reuse handling, expected-session mismatch before mutation, and eligibility/deadline rules (IAM-B01, B03–B05).
- [x] Extend session/refresh ports only where required; express the atomic single-segment operation, reuse revocation, logout and failure outcomes; keep output credentials private until commit completes (IAM-B02, B06).
- [x] Unit-test correct and incorrect secret, consumed token, expiry boundaries, disabled/revoked account, context mismatch, clock limits, rollback/issuer failures and no credential leakage (IAM-BT01, BT03–BT05).

**Gate A:** Unit and module verification pass offline, application/transaction semantics are reviewed, and the phase patch is approved. PostgreSQL concurrency remains unproven until B.

## Phase B - PostgreSQL serialization, adapters and race tests

**Scope:** Implement IAM-private physical persistence for the A contract. Reuse the current partial unique index and generated jOOQ schema metadata; add migrations only if a proven invariant cannot be enforced with existing tables. Keep the stored verifier/history representation unchanged unless a reviewed migration is essential.

- [x] Select and lock the affected session deterministically, re-evaluate the presented generation after acquiring the lock, and atomically consume, insert, extend or revoke as the operation requires (IAM-B02–B05).
- [x] Verify exactly one winner for concurrent use of the same generation and durable family revocation after genuine reuse; test parallel refresh/logout, independent connections, expired sessions, rollback and incorrect-secret non-revocation (IAM-BT01–BT06).
- [x] Preserve migration/role ownership and generated-schema workflow; avoid runtime DDL and multi-transaction issuance paths.

**Gate B:** Real PostgreSQL 18 Integration/E2E tests establish SQL, transaction and concurrency semantics. Unit mocks and local Maven packaging do not satisfy this gate. Human reviews the persistence patch before D integration.

## Phase C - Browser coordinator, restoration and bounded reactive recovery (P2.1b.1)

**Scope:** Extend `AuthSession`, `AuthCoordinator`, `IdentityApi` and the shared `createApiClient` hooks against the approved mock HTTP contract. Keep JWTs tab-local and treat `expiresAt` as advisory. Add stable session context, in-tab single-flight, shared-cookie Web Lock, minimal `BroadcastChannel` notification, typed terminal states and protected-route navigation behavior. No generic CoR registry or frontend backend/proxy endpoint.

- [x] Accept successful server-issued JWTs regardless of an ahead client clock; attach tokens to requests, classify only confirmed pre-dispatch 401s and permit one replay of a repeatable request (IAM-B07–B08, IAM-BT08, BT13).
- [x] Restore lazily when a tab first needs identity (including reloaded workspace); bind later refresh/logout to the known `sessionId`, coalesce simultaneous requests, exclude cookie-backed endpoints from bearer attachment/recursive recovery, recheck token/session after acquiring the lock, and bootstrap CSRF inside it (IAM-B05, B08, B10).
- [x] Broadcast non-secret session changes only, suspend a stale tab with a reload prompt, distinguish revoked/expired from context changed, handle uncertain refresh responses without re-submission, and validate `next` redirects (IAM-B09–B10).
- [x] Test skewed clocks, parallel in-tab requests, asynchronous/missed broadcasts, rotated cookies in other tabs, unknown responses, non-replayable bodies, lock failure, redirects and no credential persistence (IAM-BT08–BT13).

**Gate C (also P2.1b.1 code review):** Frontend lint, typecheck, Vitest and static build/export pass with the actual Node 24/npm toolchain; human reviews focused mocked-transport tests and patch. It cannot claim end-to-end refresh acceptance without D/E.

## Phase D - IAM HTTP wiring, security and commit boundary

**Scope:** Add `/refresh` and `/logout` through IAM's existing Spring-managed security contribution and operation ingress. Extend login/register JSON with `sessionId`; preserve the existing refresh-cookie attributes, strict `Origin` check, masked CSRF bootstrap and response non-caching. Response cookie issuance and deletion occur only after correct completed operations.

- [x] Implement request/response validation, stable problems, `session_context_changed` without cookie mutation, generic credential rejection, and idempotent missing-cookie logout (IAM-B05–B06).
- [x] Exercise the actual filter chain and security entry points: CSRF/Origin, missing versus invalid bearers, pre-dispatch versus operation 401, JWT issuer/type/audience and refresh cookie not authenticating other APIs (IAM-BT07, BT13).
- [ ] Confirm the final CI execution of real-servlet PostgreSQL E2E for rotation/logout success after COMMIT, rollback, replay and concurrent requests; confirm the `Set-Cookie` operations preserve CSRF-cookie clearing (IAM-BT01–BT07).

**Gate D:** Unit, real PostgreSQL Integration/E2E and HTTP security regression tests pass; no known incorrect state transition remains; human approves the complete backend patch.

## Phase E - Full flow, independent review and documentation

**Scope:** Verify the integrated browser/server lifecycle, record the maintainer's observed smoke-test results and limits, reconcile authoritative documentation, and complete independent review before final merge authorization. Further frontend/backend implementation is limited to defects found during acceptance or review.

- [x] Record the maintainer-accepted HTTPS browser smoke test for login, reload/new-tab restoration, browser-profile separation, logout and reauthentication, cross-tab logout propagation and explicit session-context reload (IAM-BT09–BT14). The [acceptance record](acceptance.md) distinguishes successful observations from blocked or partial scenarios.
- [ ] Verify protected-operation renewal/retry, repeated rotation and return-path navigation in a live application when a production protected API is available; the current workspace exposes demo routes, not protected business APIs.
- [ ] Complete or explicitly disposition remaining adversarial manual scenarios; rely on existing automated tests for database races, replay, clock skew and uncertain HTTP responses, but do not claim they were all manually exercised.
- [ ] Reconcile final backend Unit/Integration/E2E CI (including the 16-worker disposable-pool follow-up) and frontend lint/typecheck/Vitest/build/static-export evidence at the same integrated revision.
- [ ] Approve this reconciliation of [security](../../../stack/security.md), [frontend architecture](../../../architecture/frontend.md), [development guide](../../../development.md), frontend test guidance and backlog with implemented contracts; retain the approved specification and this plan.
- [ ] Record independent security review findings and their resolution, then obtain final human approval for the complete merge-request diff.

**Gate E:** The maintainer has accepted the **smoke-test stage**, not a claim that every originally proposed manual scenario passed. Independent review, final CI reconciliation and explicit merge authorization remain required before P2.1b/P2.1b.1 can be marked complete in the backlog.
