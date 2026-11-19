# P2.0a - Minimal published identity and module contracts: implementation plan

**Status:** Proposed plan, pending human approval of the [specification](specification.md) and the implementation sequence. **No implementation phase has begun.** Follow the [AI development workflow](../../../development/ai-development-workflow.md): approve specification and plan before implementing; review each coherent patch before advancing.

## Baseline and prerequisites

- Start from the approved backlog baseline containing the P2.0a/P2.1 restructuring, not from the unmerged P1.2 diff. Keep this task on its own feature branch and return patches for review.
- Confirm the existing P1.1 closed-module verification and P1.3a `OperationDispatcher`/Spring `operation` scope behavior. Reuse these; do not introduce another resolver, an HTTP request scope, or a new dispatcher overload.
- Implement only the [P2.0a requirements](specification.md#requirements-and-invariants). Group business persistence, event integration and JWT/claims logic are owned by later tasks.
- Before implementation, verify the supplied offline JDK/Maven/cache against the source revision. If the handoff is incompatible, report the exact dependency limitation and run all feasible static/document checks; do not adjust dependency versions to hide the issue.

## Phase A - Published identity contract and module boundary

- [ ] Create the `identity` module top-level API with `CurrentIdentity` and the domain-neutral unauthenticated failure contract (ID-01, ID-02). Choose minimal public names/Javadoc; Java implementation and tests use explicit local variable types, not `var`.
- [ ] Declare `identity` as a closed module with an explicit `allowedDependencies` whitelist. Keep it free of `shared` implementation or Spring Security dependencies; avoid invented Group or IAM business APIs (ID-06, ID-09).
- [ ] Add positive architectural verification for the actual module and a test fixture for a consumer using the published contract. Reuse P1.1 verification infrastructure rather than hand-rolling a parallel checker (ID-T06).

**Gate A:** Human reviews the exact public API and package/whitelist change. The module-boundary check and contract compilation succeed, or any offline dependency/tool limitation is documented precisely. No fake production authentication service exists.

## Phase B - Operation-scoped test identity

- [ ] Add an explicitly imported **test-only** Spring configuration defining an operation-scoped `CurrentIdentity` implementation. The deterministic identity input can use a test-only lexical binding around dispatch or another verified isolated fixture seam; no global mutable user and no production scope/dispatcher change (ID-03–ID-05).
- [ ] Exercise a test-only Group-like consumer service with constructor injection through the existing `OperationDispatcher` and local fake membership policy. It consumes `requireUserId()` but no token, HTTP or claims dependencies (ID-07, ID-T01, ID-T02, ID-T09).
- [ ] Test unauthenticated failure, separate sequential/concurrent principals and cleanup after failures, including a second dispatch after failed resolution (ID-T03–ID-T05).

**Gate B:** Focused Spring integration tests pass using the actual operation-scope machinery; human reviews fixture isolation and confirms that no test identity leaks into main source or production wiring. No IAM persistence or Group database is required.

## Phase C - Architecture/security verification and handoff

- [ ] Add or extend a negative module fixture for forbidden implementation-package access or missing dependency whitelist, reusing existing architecture-test helpers where possible (ID-T07).
- [ ] Check the production component scan/main-source dependencies for test authentication fallbacks; verify that missing trusted authentication cannot silently produce an identity (ID-T08).
- [ ] Run backend Maven checks in strict offline mode with the verified supplied toolchain/cache when available, at least the focused tests and module verification. Run `git diff --check` and check documentation links; report missing artifacts separately from source failures.
- [ ] Review the full change for unintended module coupling and unnecessary new abstractions. Keep P2.1d's authoritative lookup **specification-only**; no implementation of current-state eligibility or ingress security in this patch.

**Gate C:** Tests demonstrate acceptance ID-T01–T09 or explicitly identify blocked cases. The patch is limited to the identity contract, test fixtures/architecture tests and necessary documentation. Human approval is required before the next phase.

## Phase D - Documentation reconciliation and review handoff

- [ ] Reconcile the steady-state module/API documentation in [architecture.md](../../../architecture.md) and relevant [DI/development documentation](../../../architecture/dependency-injection.md), describing only behavior actually implemented. Preserve the existing security spec as forward-looking for P2.1.0.
- [ ] Keep this [specification](specification.md) and plan aligned with the approved implementation. Record test commands/results, known toolchain limitations and any deferred integration risks.
- [ ] Produce a clean review patch against the approved baseline, leave the task branch unmerged, and obtain independent review and applicable human acceptance before updating the backlog checkbox/merging.

**Gate D:** The implementation diff, tested contract and documentation agree; the contributor approves the completed task before merge. Group Management can then begin P2.2a–c against fixture IDs, while IAM develops independently.

## Out of scope and future review gates

- Production JWT/JOSE, claims providers, attestation validation and ingress-to-operation seeding: **P2.1.0/P2.5**.
- Stateful current IAM account/session eligibility service and local-miss lookup: **P2.1d/P2.2e**. Its contract is recorded in the P2.0a specification but has no production type/bean in this task.
- PostgreSQL schema, fixture seeding and Group membership business logic: **P1.2/P2.0c/P2.2**.
- IAM user events, deletion ordering, consumer inbox or tombstones: **P2.4a/P2.2d/P2.1e**.
- HTTP routes, authenticated middleware, browser flows or status-code mapping: **P1.4/P2.0d/P2.1**.
