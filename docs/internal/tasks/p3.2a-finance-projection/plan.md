# P3.2a - Finance projection operations: implementation plan

**Status:** Approved implementation sequence following approval of the [specification](specification.md). Checkboxes denote verified and human-approved work, not merely written code. Follow the [AI development workflow](../../../development/ai-development-workflow.md).

## Baseline and prerequisites

- [x] Maintainer approves the P3.2a specification.
- [x] Maintainer approves this plan.
- [x] Create `task/p3.2a-finance-projection` from `main` at `747a9cd` (P2.2a merged; P3.1 root store, schema and generated Finance metadata present). Commit the approved specification and plan as `P3.2a: Specify Finance projection operations and implementation plan` before implementation begins.

No migration, jOOQ regeneration, POM change or new framework is needed, so the offline dependency handoff and Flyway version coordination are unaffected. P2.4a/b are not prerequisites; all tests use Finance command fixtures.

The agent works in the connected devcontainer, where Maven and the `postgres` service are available, and runs the Unit, Integration and E2E suites itself. Each phase ends with one focused change set staged for human review before it is committed. The human runs the Compose deployment smoke.

## Phase A - Actor persistence and root activation

Adds the persistence capabilities the service needs, without any application operation.

- [x] Add `finance.internal.actor`: `MembershipState` (`ACTIVE`, `REMOVED`), the immutable `FinancialActor` snapshot (non-null IDs and state, positive revision) and the `FinancialActorStore` port with `find(groupId, userId)`, `insert(actor)` and `supersede(current, next)`. Document on the port that every call requires the caller to hold the group's root lock in the active segment (PRJ-R03, R09). `supersede` requires the same key and a strictly greater revision for `next`.
- [x] Implement `JooqFinancialActorStore` in `finance.internal.persistence` as an operation-scoped `@Repository` using `JooqAccess` and the generated `ACTORS` table, following `JooqFinancialGroupStore`. `insert` sets `deleted` and `upstream_revision` explicitly and leaves timestamps to their defaults. `supersede` updates `deleted` and `upstream_revision` with `WHERE group_id = ? AND user_id = ? AND upstream_revision = :current AND deleted = :current` and throws `IllegalStateException` unless exactly one row changes. No generated types appear in the port.
- [x] Extend `FinancialGroupStore` with `FinancialGroup activate(FinancialGroup locked)`. It rejects a snapshot that is not `PROVISIONING`, executes `UPDATE ... SET state = 'open' WHERE id = ? AND state = 'provisioning'`, requires exactly one changed row and returns the snapshot as `OPEN` with the unchanged revision. Javadoc states the lock precondition.
- [x] Add `JooqFinancialActorStoreIT` covering PRJ-T16 (state mapping in both directions, compare-and-set mismatch, duplicate insert surfacing the persistence exception, missing root rejected by the FK) and extend `JooqFinancialGroupStoreIT` for activation (provisioning to open with revision preserved; non-provisioning snapshot rejected; a stale snapshot whose row is no longer provisioning fails the row-count check). Add Unit tests for `FinancialActor` validation. Isolate each SQL-error case in its own test, because a PostgreSQL error aborts the rollback-only transaction.
- [x] Run the Unit and Integration suites and `git diff --check`; stage for review.

**Suggested commit:** `P3.2a: Add Finance actor store and root activation`

**Gate A:** Human reviews the port contracts, the compare-and-set and activation SQL and the Integration evidence. Accepted by the maintainer: the Unit suite (393 tests, including Modulith verification), the Integration suite (77 tests, including 12 Finance store tests) and the existing Finance E2E tests (13) passed in the devcontainer.

## Phase B - Projection service and acceptance tests

Adds the application operations on top of Phase A and the specified acceptance tests.

- [x] Add `finance.internal.projection`: the `ActivateFinancialGroup` and `ApplyMembershipChange` commands (PRJ-R10), `ActivationOutcome`, `MembershipOutcome`, `ConflictingMembershipFactException` and the pure `MembershipTransitionPolicy` (a static decision over `Optional<FinancialActor>` and the command, or a stateless value type; no DI bean unless it gains dependencies).
- [x] Add the operation-scoped `FinanceProjectionService` with `activate(ActivateFinancialGroup)` and `applyMembershipChange(ApplyMembershipChange)`. Each method runs one `Transactions.execute(...)` segment: `ensureAndLock`, then activation or actor read, policy and at most one write. Map outcomes to `COMMIT` or `ABSTAIN` and throw the contradiction exception from inside the callback so the segment rolls back (PRJ-R01–R08, R11). Add `shared::transactions` to the Finance module's `allowedDependencies` and nothing else (PRJ-R13).
- [x] Unit tests PRJ-T01–T03: the full policy table, command validation and the service with fake stores and a recording `Transactions` double that checks call order, single-write behavior and transaction decisions. Confirm PRJ-T17 through the existing Modulith verification.
- [x] E2E tests PRJ-T04–T15 in a new `FinanceProjectionServiceE2E` (split by concern if it grows large), extending `E2ETestBase` with `ProductionEntityScanConfiguration`. Invoke the service through the production operation scope and `Transactions`, and verify committed state through an independent application-role connection. Reuse `JooqFinancialGroupStoreE2E`'s lock-wait technique (backend PID plus `pg_blocking_pids`) for PRJ-T11–T13. Extract its helpers into shared Finance test support rather than copying them. Run PRJ-T08's six permutations on separate groups within one database.
- [x] For PRJ-T14, register a test-only, armed delegating `FinancialActorStore` in the E2E context that throws after a successful insert. Do not add a production hook. Verify that neither root nor actor committed and that a subsequent unarmed attempt commits both once.
- [x] Run the Unit, Integration and E2E suites and `git diff --check`; stage for review.

**Suggested commit:** `P3.2a: Apply Group creation and membership facts to the Finance projection`

**Gate B:** Human reviews the service, policy, outcome mapping and the concurrency and reordering evidence. Accepted by the maintainer: the Unit suite (415 tests, including Modulith verification), the Integration suite (77 tests) and the E2E suite (126 tests, including 12 Finance projection tests) passed in the devcontainer. The PostgreSQL lock-wait helpers were extracted into shared `testsupport.postgres` support used by the Finance and Group E2E tests, rather than Finance-only support. Because a dispatched service operation does not expose its backend PID, PRJ-T11–T13 count the sessions blocked directly or transitively by the fixture lock holder instead of matching a contender PID.

## Phase C - Steady-state documentation

- [x] Update the Finance sections of [persistence architecture](../../../architecture/persistence.md#finance-persistence): the projection operations, activation, root-before-actor lock ordering, the compare-and-set guard, no-change outcomes and contradiction failure. Extend the root-lock sequence diagram only if the existing one no longer reflects the callers. Update the verification-boundary paragraph with the new test coverage.
- [x] Update the Finance module summary in [architecture overview](../../../architecture.md) and the status line of the [domain model](../../../data-model-design.md). Check [messaging](../../../stack/messaging.md) and the [Phase 3 foundation](../../integration/phase-3-foundation.md) for statements the implementation contradicts, especially the single-upsert sketch; the foundation remains authoritative for the shared contract and changes only if a shared contract changed.
- [x] Search the documentation for stale wording about Finance having no application service or operations. Verify relative links and `git diff --check`; stage for review.

**Suggested commit:** `P3.2a: Document Finance projection operations`

**Gate C:** Human approves the documentation change. Approved by the maintainer. The [test guide](../../../development/tests.md) additionally documents the shared PostgreSQL lock-wait helpers; the Phase 3 foundation and messaging design required no change.

## Independent review findings and resolutions

An independent review of the integrated revision `a26fa0b` (GitHub mirror PR #38) found no production correctness defect in the projection service's call path. It reported one contract defect and two coverage gaps, each confirmed against the branch before resolution. The resolutions change no production behavior, schema, module dependency or published interface.

| Finding | Severity | Validation | Resolution |
| --- | --- | --- | --- |
| R1 - Root-lock enforcement overstated | P3, documentation/contract | Confirmed. `JooqAccess` checks only for an active segment; actor insert relies on the keys and supersede on a compare-and-set of the read state and revision, so a write without the root lock succeeds when no conflict exists, including while another transaction holds the lock. `FinanceProjectionService` always calls `ensureAndLock` first. | Documented root-lock ownership as a caller-enforced precondition and the guards as conflict detection in `FinancialActorStore`, `JooqFinancialActorStore`, `FinancialGroupStore.activate`, the [persistence architecture](../../../architecture/persistence.md#membership-projection-operations) and the [specification](specification.md#operation-protocol). Runtime enforcement was rejected: no requirement mandates it, PostgreSQL row locks are not observable through `pg_locks`, and a segment-bound lock registry or a re-lock inside each write would add infrastructure and lock-ordering risk for no current caller. |
| R2 - Concurrent `updated_at` anomaly untested | Coverage gap | Confirmed. PRJ-T12 checks revision convergence only, and PRJ-T06 uses a seeded past timestamp. | Added PRJ-T19 to `FinanceProjectionConcurrencyE2E`. Both facts run as production dispatches through the unchanged service. Because the service's first statement acquires the root lock immediately after fixing the transaction start, the test imports a test-only, armed one-shot decorator of `FinancialGroupStore` that starts the higher revision's transaction with `SELECT now()` and pauses on a bounded latch before delegating to `ensureAndLock`. No production hook or schema change is involved, and the root lock still precedes all actor access. |
| R3 - Membership projection in closed and archived roots untested | Optional coverage | Confirmed. Membership E2Es seeded only provisioning and open roots. | Added PRJ-T18, a parameterized `FinanceProjectionServiceE2E` over all four lifecycle states covering insert, supersede, duplicate and stale facts against a nonzero revision, with the complete root row unchanged after every fact. No lifecycle guard was added; the archived-root policy remains a P5.3 question. |

The specification gained the PRJ-T18 and PRJ-T19 rows for traceability. The maintainer approved the resolutions, including both additive test obligations, and they are committed as `5c0d6cb`.

**Resolution evidence.** Agent-run in the devcontainer against its PostgreSQL 18 service, on the source tree committed as `5c0d6cb`, from `src/backend/sharewise`:

| Command | Result |
| --- | --- |
| `./mvnw -B -ntp -Punit-tests clean verify` | 430 tests passed, including `ApplicationModuleStructureTests` and `ModuleVerificationFixtureTests`. A first attempt reported 64 `NoClassDefFound` errors because the IDE's Java language server rebuilt `target/` concurrently; the immediate rerun, unchanged, passed. |
| `./mvnw -B -ntp -Pintegration-tests clean verify` | 79 tests passed, including 12 Finance store tests. |
| `./mvnw -B -ntp -Pe2e-tests clean verify` | 149 tests passed, including 13 `FinanceProjectionServiceE2E` (4 for PRJ-T18), 4 `FinanceProjectionConcurrencyE2E` (1 for PRJ-T19) and the Group E2Es sharing `testsupport.postgres` lock-wait helpers. |
| `./mvnw -B -ntp -Pe2e-tests -DtestParallelism=16 -Dit.test='Finance*E2E,JooqFinancial*E2E,Group*E2E' verify` | 62 tests passed at the CI worker count. |
| PRJ-T19 alone, five consecutive runs | 5 of 5 passed. |
| `git diff --check` | Clean. |

No `sharewise_it_*` database remained after the runs. No test was disabled, skipped or weakened.

## Verification commands and final acceptance

From `src/backend/sharewise` in the devcontainer:

```bash
./mvnw -B -ntp -Punit-tests clean verify
./mvnw -B -ntp -Pintegration-tests clean verify
./mvnw -B -ntp -Pe2e-tests clean verify
git diff --check
```

Focused `-Dtest` and `-Dit.test` runs are fine during development; the full suites run before each gate. The human runs the deployment smoke from the repository root:

```bash
cd deploy && ./scripts/test-compose.sh
```

P3.2a registers no listener and adds no route, so the smoke only confirms that startup and migrations are unaffected. No manual UI scenario applies (see the specification's acceptance gate).

| Evidence | Result |
| --- | --- |
| Specification and plan approval | Approved by maintainer |
| Unit suite (including Modulith verification) | Passed at `5c0d6cb`: 430 tests |
| PostgreSQL Integration suite | Passed at `5c0d6cb`: 79 tests |
| PostgreSQL E2E suite, including concurrency and reordering | Passed at `5c0d6cb`: 149 tests, plus 62 Finance and Group E2Es at 16 workers |
| Disposable deployment smoke | Pending |
| Independent MR review and resolutions | Review of `a26fa0b` complete; [resolutions](#independent-review-findings-and-resolutions) approved by the maintainer and committed as `5c0d6cb` |
| Human final diff review and merge authorization | Pending |

- [x] Perform an independent MR review of the integrated revision against the specification; resolve or explicitly justify findings, re-run affected suites and reconcile documentation when corrections change behavior. Suggested follow-up commit: `P3.2a: <specific review fix>`.
- [ ] Record the tested revision, commands and results above, plus the human's deployment smoke result or an approved exception.
- [ ] After all gates pass, mark P3.2a complete in the backlog and link its specification and plan there. Suggested final commit: `P3.2a: Record acceptance and complete Finance projection operations`. The human authorizes and performs the merge.
