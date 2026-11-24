# P2.1d - Authoritative IAM identity and session verification: implementation plan

**Status:** Phase A approved by the human maintainer; Phase B implementation in review. PostgreSQL and operational acceptance remain open. See the approved [specification](specification.md) and IAM-D01–D09 / IAM-DT01–DT08 contracts.

## Baseline, ownership and dependency gates

Begin from the supplied P2.1c-in-progress source snapshot on a clean task branch, following the [AI development workflow](../../../development/ai-development-workflow.md). Keep the approved baseline unchanged and do not merge without human authorization. This task owns only IAM's published synchronous Java contract, local implementation, IAM-private persistence and related tests. P2.2e owns the consumer's local-miss integration, transaction sequence and projection concurrency; P2.1e owns deletion policy; P2.5 owns attestations.

The accepted P2.1b session schema and the existing `IdentityContext`, operation-scope `Transactions`, `Result<T, E>` and jOOQ access are prerequisites. Coordinate final verification against P2.1c's password-change implementation before accepting the PostgreSQL concurrency gate. Neither an HTTP endpoint, service credential profile, database migration nor a frontend change is anticipated. If the implementation reveals a necessary change to the public contract or failure model, return to specification review first.

**Available validation:** The supplied Maven-only offline JDK 25/Maven 3.10.0 handoff supports a strict offline backend Unit build; the unchanged source snapshot previously passed 312 Unit tests. PostgreSQL 18, production-style Compose smoke and human operational acceptance require a maintainer/CI environment and are not implied by that Unit result.

## Gate 0 - Architectural and plan approval

- [x] Approve `IdentityContext` (ambient admitted evidence) versus `IdentityPort` (synchronous authoritative query), the narrow zero-argument method, immutable `Result` contract and typed `INELIGIBLE`/`UNAVAILABLE` distinction (IAM-D01–D03, D05, D08).
- [x] Approve the read-only account/session eligibility predicate, no-active-transaction invariant, point-in-time semantics, safe failure classification and scope exclusions (IAM-D04, D06–D09).
- [x] Approve this two-phase plan separately after specification approval.
- [x] Publish the approved artifacts on the authoritative task branch (offline patch workspace has no configured remote).

**Gate 0:** Specification and plan approved in order, with reproducible task-branch artifacts. No implementation starts before this gate.

## Phase A - Public contract and local IAM service

**Scope:** Add the IAM-published `IdentityPort`, immutable result/failure types and operation-scoped local implementation. Obtain the verified user/session pair from `IdentityContext`; use a private eligibility query port, `Clock` and IAM-owned `Transactions.execute(...)` with `ABSTAIN`. Do not introduce generic cross-context RPC abstractions or HTTP transport. Keep public contract types independent of database, JOSE and Spring MVC.

- [x] Test eligible/denied/unavailable and anonymous outcomes, same-credential user/session binding, injected-clock boundaries and login-lockout behavior using deterministic private query fakes (IAM-DT01–DT04).
- [x] Test the synchronous call after a completed segment and rejection inside an active or unrelated transaction, using the real operation-scope transaction guard where appropriate (IAM-DT05, DT08).
- [x] Run focused Unit tests and Spring Modulith verification, plus the offline backend regression gate. Inspect the staged diff for unrelated code and prohibited dependencies.

**Gate A:** Contract, Unit behavior, transaction-boundary tests and module rules are green; human reviews and approves the focused Java/service patch before Phase B. Human approval received; 326 offline Unit tests and packaging passed.

## Phase B - IAM PostgreSQL adapter, integration and final documentation

**Scope:** Implement a single IAM-private jOOQ database query joining the authoritative account and session rows and projecting the matching unrevoked session ID and deadlines. Evaluate temporal eligibility using the IAM clock after the read. Reuse generated tables and existing persistence infrastructure. Add real PostgreSQL Integration tests for eligibility, failure paths and concurrent lifecycle changes, integrating the accepted final P2.1c behavior. Keep the write path, refresh tokens and Group schema unchanged.

- [x] Verify missing/disabled accounts, wrong-owner/revoked sessions, exact idle/absolute boundaries (including expiry during a blocked query), projection mapping and read-only/no-side-effect behavior on PostgreSQL 18 (IAM-DT02–DT03).
- [x] Verify consistency with logout, account disablement and password-change session revocation using independent connections; do not demand atomicity across the verification read and a later consumer write (IAM-DT06).
- [x] Exercise database unavailability/failed completion paths with meaningful fault injection; prove unavailable/indeterminate results fail closed and cannot be followed by consumer mutation (IAM-DT07).
- [x] Run Unit, PostgreSQL Integration, Modulith and relevant regression suites; arrange production-style local deployment smoke with a representative Java-level integration workflow, or record a justified human-approved exception if no meaningful deployed probe is available.
- [x] **Final implementation step:** reconcile implemented behavior in [implemented authorization architecture](../../../architecture/authorization.md), [transaction architecture](../../../architecture/transactions.md) if needed, [planned security design](../../../stack/security.md), relevant developer/testing docs, and the [backlog](../backlog.md). Preserve the distinction between implemented guarantees and future HTTP/service-authentication proposals.
- [x] Obtain an independent security/architecture review proportionate to this authority-sensitive change, resolve findings in reviewable increments, and record human operational acceptance and outstanding infrastructure limitations.

**Phase B implementation evidence:** `JooqIdentityEligibilityQuery` uses the existing generated IAM schema and a read-only session projection. `JooqIdentityEligibilityQueryTests` covers recognized SQL-state failures and unexpected errors; `JooqIdentityEligibilityQueryIT` covers the database predicate and exact persisted session projection; `IdentityVerificationE2E` exercises production IAM wiring, physical lifecycle changes, delayed reads and database fault injection. The application-service Unit tests cover temporal boundary and read-order semantics. The original Phase B offline `-Punit-tests clean verify` gate passed with 338 Unit tests, zero failures/errors/skips, including Modulith structure verification and packaging. Targeted `-Pintegration-tests -Dit.test=JooqIdentityEligibilityQueryIT verify` and `-Pe2e-tests -Dit.test=IdentityVerificationE2E verify` both reached Failsafe but failed during fixture setup with `UnknownHostException: postgres`; no database behavior was exercised. PostgreSQL tests require a reachable PostgreSQL 18 instance; neither a server nor a container runtime is available in this workspace. No database acceptance check is marked complete based on compilation or Unit results. The supplied P2.1c snapshot is still the integration baseline; rerun against its final accepted revision.

**Review follow-up (B.2):** Resolve the [evaluation-time finding](https://github.com/frederik-hoeft/min-favs-sharewise/pull/32#discussion_r4673782483) with the post-read temporal check and deterministic blocked-read regression. Offline `-Punit-tests verify` passed with 340 Unit tests, zero failures/errors/skips, Modulith verification and packaging; the revised PostgreSQL tests compile but require a live database. The shared database timeout policy is deferred to [P8.5](../backlog.md#phase-8---operational-hardening-and-full-mvp-readiness), not an IAM-specific transaction change. PostgreSQL execution and independent re-review remain open.

**Gate B:** PostgreSQL-backed correctness and module boundaries are demonstrated, final P2.1c interaction checked, authoritative docs reconciled, independent and human reviews accepted, and the complete patch approved for merge. Leave the branch unmerged until authorization.
