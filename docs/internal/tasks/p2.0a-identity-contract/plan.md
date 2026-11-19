# P2.0a - Minimal published identity and module contracts: implementation plan

**Status:** Phases A–C implemented and approved in successive reviews; Phase D documentation changes are prepared for a separate human review. Follow the [AI development workflow](../../../development/ai-development-workflow.md) for final task acceptance and merge.

## Baseline and prerequisites

- Start from the supplied approved baseline containing the P2.0a/P2.1 restructuring and merged P1.2 foundation. Do not apply the separate P1.2 WIP diff; keep this task on its own feature branch and return patches for review.
- Confirm the existing P1.1 closed-module verification and P1.3a `OperationDispatcher`/Spring `operation` scope behavior. Reuse these; do not introduce another resolver, an HTTP request scope, or a new dispatcher overload.
- Implement only the [P2.0a requirements](specification.md#requirements-and-invariants). Group business persistence, event integration and JWT/claims logic are owned by later tasks.
- Before implementation, verify the supplied offline JDK/Maven/cache against the source revision. If the handoff is incompatible, report the exact dependency limitation and run all feasible static/document checks; do not adjust dependency versions to hide the issue.

## Phase A - Published identity contract and module boundary

- [x] Create the `identity` module top-level API with `IdentityContext` (`Optional<UUID> userId()` plus default `requireUserId()` and `isAuthenticated()`) and the domain-neutral unauthenticated failure contract (ID-01, ID-02). Choose minimal public names/Javadoc; Java implementation and tests use explicit local variable types, not `var`.
- [x] Declare `identity` as a closed module with an explicit `allowedDependencies` whitelist. Keep it free of `shared` implementation or Spring Security dependencies; avoid invented Group or IAM business APIs (ID-06, ID-09).
- [x] Add positive architectural verification for the actual module and a test fixture for a consumer using the published contract. Reuse P1.1 verification infrastructure rather than hand-rolling a parallel checker (ID-T06).

**Gate A:** Human reviews the exact public API and package/whitelist change. The module-boundary check and contract compilation succeed, or any offline dependency/tool limitation is documented precisely. No fake production authentication service exists.

## Phase B - Operation-scoped test identity

- [x] Add an explicitly imported **test-only** Spring configuration defining an operation-scoped `IdentityContext` implementation. The deterministic identity input can use a test-only lexical binding around dispatch or another verified isolated fixture seam; no global mutable user and no production scope/dispatcher change (ID-03–ID-05).
- [x] Exercise a test-only Group-like consumer service with constructor injection through the existing `OperationDispatcher` and local fake membership policy. It consumes `requireUserId()` but no token, HTTP or claims dependencies (ID-07, ID-T01, ID-T02, ID-T09). Verify that all three context accessors agree for an authenticated fixture.
- [x] Test the unauthenticated triad (`userId()` empty, `isAuthenticated()` false, `requireUserId()` throwing), separate sequential/concurrent principals and cleanup after failures, including a second dispatch after failed resolution (ID-T03–ID-T05). Confirm provider faults do not silently become `Optional.empty()`.

**Gate B:** Focused Spring integration tests pass using the actual operation-scope machinery; human reviews fixture isolation and confirms that no test identity leaks into main source or production wiring. No IAM persistence or Group database is required.

## Phase C - Architecture/security verification and handoff

- [x] Add or extend a negative module fixture for forbidden implementation-package access or missing dependency whitelist, reusing existing architecture-test helpers where possible (ID-T07).
- [x] Check the production component scan/main-source dependencies for test authentication fallbacks; verify that missing trusted authentication cannot silently produce an identity (ID-T08).
- [x] Run backend Maven checks in strict offline mode with the verified supplied toolchain/cache when available, at least the focused tests and module verification. Run `git diff --check` and check documentation links; report missing artifacts separately from source failures.
- [x] Review the full change for unintended module coupling and unnecessary new abstractions. Keep P2.1d's authoritative lookup **specification-only**; no implementation of current-state eligibility or ingress security in this patch.

**Gate C:** Tests demonstrate acceptance ID-T01–T09 or explicitly identify blocked cases. The patch is limited to the identity contract, test fixtures/architecture tests and necessary documentation. Human approval is required before the next phase.

## Phase D - Documentation handoff and review

- [ ] Publish the [Phase 2 IAM/Group handoff](../../integration/phase-2-foundation.md): bounded-context responsibilities, the implemented `IdentityContext`/test fixture seam, staged IAM-to-Group integration, and the reviewed P2.0c ER diagram with table/column provenance and operational constraints. Keep the document design-status explicit; do not duplicate the task backlog or claim business migrations exist.
- [ ] Publish the [proposed claims/security context architecture](../../../stack/claims-context-proposal.md) as input to P2.1.0 and P2.5: the lean claims/provenance model, operation-scoped DI providers, semantic `<Concept>Context` facade, trust/correlation policy layers, and explicit design gates. Do not prematurely reconcile the full steady-state JWT implementation documentation.
- [ ] Reconcile this specification/plan with the implemented A–C contract and the two design handoffs. Verify document links, diagram equivalence, provenance/terminology consistency and patch application; record test evidence and deferred decisions. Produce an incremental review patch on the approved Phase C baseline and leave it unmerged pending human acceptance.

### Validation evidence for Phase D review

- **Backend baseline:** `JAVA_HOME=<supplied JDK 25> <supplied Maven 3.10.0> -B -ntp -o -Punit-tests -Dmaven.repo.local=<supplied cache> -f src/backend/sharewise/pom.xml clean verify` completed successfully: **124 Unit tests, zero failures/errors/skips**. This does not establish PostgreSQL Integration or E2E correctness, which is outside this documentation-only phase.
- **Documentation:** the supplied ER PDF and `docs/assets/er-data-model.pdf` have identical SHA-256 hashes; checked relative links, the new references' source/implementation status and `git diff --check`.
- **Deferred risks:** production provider/operation-scope input plumbing, attestation binding/exposure, session UUIDv7 wording, account deletion and out-of-order identity events, and final framework publication-registry DDL remain review gates with their owning tasks.

**Gate D:** Phase 2 teams have cohesive, reviewable architecture/data references without mistaking proposed claims/event integrations or ER tables for implemented behavior. The implementation diff, tested `IdentityContext` and documents agree; the contributor approves the complete task before merge.

## Out of scope and future review gates

- Production JWT/JOSE, claims providers, attestation validation and ingress-to-operation seeding: **P2.1.0/P2.5**.
- Stateful current IAM account/session eligibility service and local-miss lookup: **P2.1d/P2.2e**. Its contract is recorded in the P2.0a specification but has no production type/bean in this task.
- PostgreSQL schema, fixture seeding and Group membership business logic: **P1.2/P2.0c/P2.2**.
- IAM user events, deletion ordering, consumer inbox or tombstones: **P2.4a/P2.2d/P2.1e**.
- HTTP routes, authenticated middleware, browser flows or status-code mapping: **P1.4/P2.0d/P2.1**.
