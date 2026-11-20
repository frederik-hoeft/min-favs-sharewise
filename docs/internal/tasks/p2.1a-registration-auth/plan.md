# P2.1a - Registration and initial authentication: implementation plan

**Status:** Proposed sequence only. [The specification](specification.md) has not yet passed its architectural approval gate; this plan is not an authorization to implement. Implementation starts only after both documents are approved, committed to the authoritative task branch, and reconciled with the completed P2.1.0 baseline.

## Baseline and dependencies

The supplied 2026-11-20 source snapshot includes the P2.0a `IdentityContext` contract, operation-scoped DI and transaction infrastructure, Flyway schemas, HTTP `ProblemDetail` conventions, and the Next.js frontend foundation. It has **no** IAM account/session tables, production authentication or registration/login features. P2.1.0 is being implemented in parallel and is not included in this source snapshot; its evolving implementation is not a suitable implementation baseline.

The provided **Maven-only** offline handoff contains Temurin JDK 25.0.5.1, Maven 3.10.0 and a matching isolated backend dependency repository. The unchanged backend's strict-offline `-Punit-tests clean verify` passed (122 tests, zero failures) and produced a jar. PostgreSQL Integration/E2E and Next.js checks have not been established in this environment: no PostgreSQL service and no supplied Node.js 24/npm dependency cache are available. These are **environment gaps**, not evidence of test failure or a waiver of later acceptance gates.

Before development, P2.1.0 must provide the reviewed primary-JWT issuer and verifier profile, production `IdentityContext`, ingress binding, and necessary Spring Security extension points. Use its **approved, updated source baseline**, not speculative duplicate implementations in this branch. A changed `pom.xml`, wrapper or frontend lockfile may invalidate the existing selective offline handoff; refresh the relevant cache separately before claiming fully offline verification. P2.2 work may proceed in parallel but cannot be modified or integrated by P2.1a without a reviewed boundary.

## Review gates before implementation

- [ ] **G0, architecture:** Agree on account registration semantics, duplicate-email disclosure/email-verification deferral, input/canonicalization rules, password encoder and limits, lockout and token/session TTL policies, CSRF and cookie policy, and the first-slice browser behavior (including no premature logout/refresh).
- [ ] **G1, specification:** Approve `specification.md`, including architectural ownership, IAM-A01–A13 and IAM-AT01–AT16, and commit it to the task branch.
- [ ] **G2, plan:** Approve the phase sequence, prerequisites and completion gates below; commit `plan.md` before implementation.
- [ ] **G3, baseline integration:** Receive the finished P2.1.0 baseline; inspect its actual code/spec/test contracts and overlap with P2.1a, update both documents if necessary, revalidate the unchanged new baseline and confirm all required offline assets exist. Any material architecture change returns to G1/G2.

## Phase A - IAM persistence foundation

**Scope:** IAM-owned Flyway migration for `identity.users`, `identity.sessions`, `identity.refresh_tokens`, their defaults, constraints, indexes, and app-role privileges; narrow SQL ports/adapters for email lookup, credential snapshot, conditional/locked login-state update, account insertion and initial session/token insertion. Create `user_profiles` only when this slice actually uses it or an approved integration requires it; otherwise defer its separate ER table to P2.1c. Preserve the reviewed ER names/types while resolving `passwordHash` to conventional SQL naming.

**Requirements:** IAM-A01, A03–A05, A08, A12.

**Verification:** Migration/role/constraint E2E, unique-email concurrency, UUID/session linkage, token verifier length and partial-write rollback. Query and mutation tests use the real PostgreSQL 18 fixture, never H2 or mocks for SQL correctness. Confirm no other bounded-context schema is touched.

**Completion gate:** Reviewed schema and adapter patch; successful focused PostgreSQL tests, no unapproved dependencies or cross-context access. Stop for human diff review before Phase B.

## Phase B - Account, credential and initial-session workflows

**Scope:** Cohesive IAM application entry services, immutable command/result contracts, normalization and password/lockout policy, cryptographically random refresh-secret creation and its stored verifier, configurable clock/deadlines, and committed registration/login workflows. Use Spring Security's password-encoding primitives and P2.1.0's primary-JWT signing service; no handwritten JWT or password cryptography. Keep costly verification outside row locks while rejecting stale credential snapshots at the commit boundary. Persist failed-login attempts intentionally even on denied authentication.

**Requirements:** IAM-A01–A08, A10, A12–A13.

**Verification:** Focused Unit tests for normalization/validation, password limits, lockout arithmetic and typed results; real PostgreSQL E2E for atomic success, duplicate registration, concurrent failures, lock/unlock, credential-change race and rollback. Include a verified-token round trip through P2.1.0's own decoder. Inject deterministic time and entropy in tests without using predictable production secrets.

**Completion gate:** All relevant Unit and database tests pass; review one complete registration and login path including callback/commit decisions and what happens when finalization fails. Stop for human review.

## Phase C - Security and HTTP contract

**Scope:** IAM-owned controllers, `/csrf` bootstrap, `/auth/register` and `/auth/login` endpoints, request/response mapping, `ProblemDetail` codes and cookie issuance. Extend the P2.1.0 Spring Security configuration through its accepted extension boundaries for anonymous routes, bearer protections, CSRF and Origin controls; do not fork its claim context, token verification or filter chain. Credential/cookie material leaves the application service only after confirmed commit and is never placed in errors or logs.

**Requirements:** IAM-A06–A10, A13.

**Verification:** MockMvc/component and PostgreSQL-backed HTTP E2E for exact status/body/cookie/CSRF/Origin semantics, security profile validation, `IdentityContext` resolution on protected endpoints and protection against refresh-cookie-only authentication. Check actual Spring Security CSRF token-handler behavior, including post-auth token renewal. Run module-boundary verification and inspect log redaction.

**Completion gate:** HTTP contract and security behavior independently reviewable; credential bypasses, anonymous fallbacks, wrong-profile token acceptance and CSRF failures are negative tests, not deferred manual checks. Stop for human review.

## Phase D - Browser registration and login

**Scope:** Add public login/registration routes using the design foundation, domain-facing `core/identity/` adapter, in-memory primary bearer state and integration with existing `lib/api` request hooks. Adapt the public CTA and account-shell entry points without replacing shared components or inventing a parallel HTTP client. Serialize cookie-changing operations with an exclusive Web Lock and per-tab single-flight; bootstrap CSRF inside the lock. Show reauthentication on reload/token expiry until P2.1b provides renewal, without a fake logout.

**Requirements:** IAM-A09, A11 and frontend portions of A13.

**Verification:** Vitest component/API tests, accessibility assertions, lint, application/test typechecks, normal Next.js build and static-export probe, followed by a real-browser check across keyboard/mobile/light/dark flows. Verify no bearer values appear in localStorage, sessionStorage, URLs or application logs. Test competing tab actions, unavailable Web Locks and error recovery.

**Completion gate:** Actual UI and API integration, responsive/accessibility evidence and passing frontend checks. Do not call the browser flow tested if only jsdom tests ran. Stop for human review.

## Phase E - Integrated regression, deployment assumptions and documentation

**Scope:** Run the full relevant backend and frontend gates after integrating Phase A–D. With real PostgreSQL and the approved P2.1.0 implementation, exercise registration/login end-to-end and the negative concurrency/authorization scenarios. Confirm current Group consumers of `IdentityContext` still compile and that no `UserRegistered` projection is assumed. Record any remaining rate-limit/production edge prerequisite as an explicit P2.6 dependency.

**Requirements:** IAM-A01–A13 and IAM-AT01–AT16; evaluate them against their specified verification boundaries rather than a single green test suite.

**Verification:** Independent backend Unit/Integration/E2E profiles against PostgreSQL 18, frontend lint/typecheck/Vitest/build/static-export, module verification, committed data/cookie/browser checks and `git diff --check`. Record exact commands and results, and retain the human's manual acceptance outcomes. If offline assets remain selective or lack newer dependencies, report which gates were impossible instead of substituting system caches.

**Documentation reconciliation (final implementation step):** Bring [security](../../../stack/security.md), [Phase 2 handoff](../../integration/phase-2-foundation.md), [application architecture](../../../architecture.md), [frontend architecture](../../../architecture/frontend.md), [frontend development guide](../../../development/frontend.md), [developer/test guides](../../../development.md), and relevant task references into agreement with **implemented** behavior. Avoid copying the phase plan or presenting deferred refresh/revocation/event delivery as complete. Update them again if review fixes change their contracts.

**Completion gate:** Reviewed full branch diff, traceable test results, reconciled authoritative docs and recorded missing/external gates. Stop for human review and the separate independent-MR review and manual acceptance steps before any merge authorization.

## Integration and merge-conflict discipline

- Work on a new task branch based on the **user-provided revised baseline** after P2.1.0 is ready. Do not develop against the transient P2.1.0 branch or merge provisional code into the current approved baseline.
- Likely **high-conflict areas**: backend `pom.xml`, `identity/package-info.java` and published IAM/security contracts, Spring Security configuration, operation-scope binding and HTTP security tests. Do not independently implement any of these shared security primitives in P2.1a.
- Likely **P2.1a-owned areas**: `identity` account/session domain and persistence internals; IAM feature migrations; `identity/api/v1`; frontend login/registration routes and `core/identity`. Security adapter extension points are reviewed together with P2.1.0 before edits.
- Maintain separate commits/review patches per phase. Each checked phase requires both verified evidence and **human approval**; do not check it merely because code was written. A material spec change returns to G1 and its implementation phases are replanned before continuing.
- Independent review examines the final change against the same commit/specification; the human performs the real UI acceptance and makes the final merge decision. P2.1a does not mark backlog completion or merge itself.
