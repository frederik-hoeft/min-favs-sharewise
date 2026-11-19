# P1.5 - Implementation plan: PostgreSQL transaction acceptance and CI restructuring

**Status:** Specification and plan accepted; Phases A and B reviewer-approved with green PostgreSQL CI reported. Phase C implementation is ready for review; CI Lint and runner acceptance remain pending. **Specification:** [P1.5](specification.md). Follow the [AI development workflow](../../../development/ai-development-workflow.md): agree on the specification and plan before implementation, then deliver one reviewed incremental patch per phase. Do not check off work until it is validated and human-approved.

## Starting point and evidence inventory

Use the **November 19, 2026 main-branch snapshot** as the development baseline, including the accepted P1.3c rollback-owner correction and the current frontend. Record the precise upstream Git commit when the implementation branch is created; the supplied source archive does not carry Git history. Do not reuse the former P1.3 feature-branch patch stack. P1.5's test work uses `E2ETestBase`, `OperationDispatcher`, the **production** `Transactions` bean and migrated PostgreSQL 18 fixture databases. The rollback-only `IntegrationTestRunner` cannot prove real commits.

| Existing PostgreSQL tests | Accepted coverage to preserve | P1.5-specific follow-up |
| --- | --- | --- |
| `RelationalTransactionE2E` | Mixed JPA/jOOQ transactions; `COMMIT`/`ROLLBACK`/`ABSTAIN`; independent reads; rollback-only; callback, after-commit and commit-time JPA errors | Add Spring JDBC participant, deferred PostgreSQL commit constraint, independent concurrency and controlled uncertainty. |
| `JooqAccessE2E`, `JpaAccessE2E` | Segment-bound facades, derived-query expiry, wrong lifetime and sequential segment access | Ensure P1.5 traceability and supplement only missing PostgreSQL-specific cases. |
| `TransactionsTests` (Unit) | Fake-manager failure/cleanup paths, nested/ambient/scope errors | Keep as isolated contract checks; they do not prove physical PostgreSQL outcome. |
| P1.3c `*IT` | Rollback-owned transaction and pending-state inspection | Retain as test-infrastructure acceptance; never use as P1.5 real-commit evidence. |

The [P1.3b acceptance table](../p1.3-transactions/specification.md#acceptance-criteria) lists TX-03 through TX-11. Before writing tests, map each criterion to existing methods and distinguish directly executed PostgreSQL evidence from offline/fake-manager assertions. Preserve passing coverage; add focused methods only for material gaps.

## Phase A - Production PostgreSQL acceptance gaps

**Focus:** PG-01 through PG-03, PG-05 and PG-06; P15-T01 through P15-T06.

- [x] Audit the current `RelationalTransactionE2E`, jOOQ/JPA E2E and transaction Unit methods; create a TX-03 through TX-11 evidence matrix in the task plan or an attached acceptance record, with concrete test names and gaps.
- [x] Extend real-commit E2E coverage to include Spring-bound JDBC alongside jOOQ and JPA within one physical segment. Verify read visibility before/after commit on an **independent** application-role connection, and atomic `ROLLBACK`/`ABSTAIN` across all three adapters. Retain explicit JPA `flush()` / `refresh()` checks.
- [x] Cover any missing sequential-segment, rollback-only, aborted-transaction and stale-resource cases against PostgreSQL using production `OperationDispatcher`. Add deterministic independent-operation concurrency only where the existing suite lacks corresponding evidence; avoid shared mutable fixtures, sleeps and lock-order assumptions.
- [x] Keep schema probes fixture-local and migrations/roles production-compatible. Do not add a second transaction manager, duplicate fixture framework, or an enclosing rollback test transaction.

**Gate A:** Focused PostgreSQL E2E tests and existing Unit/Integration suites pass in GitLab; the evidence matrix identifies covered TX requirements. Review and approve before Phase B.

## TX-03 through TX-11 evidence matrix

This matrix identifies **test implementations** and reviewer-confirmed green PostgreSQL CI for Phases A and B; the precise upstream pipeline/job URLs still need to be recorded in Phase D. Phase A adds JDBC, PostgreSQL aborted-transaction and concurrent-operation coverage. Phase B adds deferred-constraint and commit-boundary injection tests. All referenced `*E2E` methods use production `Transactions`, `OperationDispatcher` and independently migrated PostgreSQL databases rather than the P1.3c rollback substitute.

| Contract | Named real-PostgreSQL test methods | Status / remaining evidence |
| --- | --- | --- |
| **TX-03**: atomic COMMIT and visibility | `RelationalTransactionE2E.jdbcJooqAndJpaWritesCommitOrRollBackAsOnePhysicalUnit`, `independentConnectionDoesNotSeeUncommittedJdbcJooqOrJpaWrites` | P15-T01 implemented; reviewer-confirmed green CI, job URL pending. |
| **TX-04**: ROLLBACK and ABSTAIN | `RelationalTransactionE2E.jdbcJooqAndJpaWritesCommitOrRollBackAsOnePhysicalUnit` | P15-T02 covers all three adapters; reviewer-confirmed green CI, job URL pending. |
| **TX-05**: typed result and deliberate COMMIT | `RelationalTransactionE2E.expectedBusinessFailureMayCommitAnIntentionalSideEffect` | P15-T04 uses typed `Result.Err` with a durable side effect; reviewer-confirmed green CI, job URL pending. |
| **TX-06**: veto and aborted transaction | `RelationalTransactionE2E.rollbackOnlyVetoPreventsMixedAdapterCommitAndNextSegmentIsHealthy`, `translatedDatabaseErrorMustExplicitlyPoisonItsSegment` | P15-T04 includes SQLSTATE `25P02` validation; reviewer-confirmed green CI, job URL pending. |
| **TX-07**: callback and physical finalization failures | `RelationalTransactionE2E.callbackFailureRollsBackBothAdaptersAndManagerRemainsUsable`, `commitTimeJpaConstraintFailureMustNotReportSuccessOrPermitReuse`, `afterCommitFailurePropagatesEvenThoughRowsAreDurable`; `RelationalCommitFailureE2E.deferredConstraintFailsDuringPhysicalCommitAndPoisonsTheOperationManager`, `failedCommitBeforePhysicalIssuanceRollsBackAndPoisonsTheManager`, `failedCommitAcknowledgementCanFollowAnActualDurableCommit` | Phase B P15-T07 and P15-T09 implemented; reviewer-confirmed green CI, job URL pending. P15-T08 is already covered. `TransactionsTests` fake-manager cases remain supplemental only. |
| **TX-08**: nested, ambient, scoped and concurrent operations | `RelationalTransactionE2E.nestedLogicalSegmentIsRejectedWithoutStartingItsCallback`, `anUnrelatedActiveSpringTransactionCannotOwnTheOperationCommit`, `requiresNewCannotUseResourcesBelongingToSuspendedSegment`, `notSupportedCannotUseResourcesBelongingToSuspendedSegment`, `concurrentProductionDispatchesOwnIndependentPhysicalTransactions`; `JooqAccessE2E.jooqAccessCannotBeUsedThroughAnotherScopeOrThread`; `JpaAccessE2E.wrongScopeWrongThreadAndPostCloseAccessFail` | P15-T06 adds simultaneous PostgreSQL sessions with a barrier; reviewer-confirmed green CI, job URL pending. |
| **TX-09**: sequential segment and stale resources | `RelationalTransactionE2E.rollbackOnlyVetoPreventsMixedAdapterCommitAndNextSegmentIsHealthy`; `JpaAccessE2E.usesCurrentJpaTransactionAndAllowsSequentialSegments`; `JooqAccessE2E.derivedQueriesFromAnEarlierSegmentCannotExecuteInLaterSegments` | Existing E2E coverage; retain under P1.5. |
| **TX-10**: segment-bound persistence guards | `JooqAccessE2E.acceptsSqlOnlyWithinItsOwningSegmentAndHonorsCommitAndRollback`, `derivedQueriesFromAnEarlierSegmentCannotExecuteInLaterSegments`, `jooqTransactionEntryPointsCannotBypassOwningSegment`; `JpaAccessE2E.staleJpaFacadesAndFluentQueriesNeverRebindToLaterSegment`, `rejectsAnUnrelatedJdbcTransactionWithoutJpaPersistenceContext` | Existing E2E coverage; retain under P1.5. |
| **TX-11**: transaction-compatible adapters / flush / refresh | `RelationalTransactionE2E.jpaFlushBecomesVisibleToJooqAndJooqMutationNeedsJpaRefresh`, `independentConnectionDoesNotSeeUncommittedJdbcJooqOrJpaWrites`, `jdbcJooqAndJpaWritesCommitOrRollBackAsOnePhysicalUnit` | P15-T01–T03 include JDBC session identity and visibility; reviewer-confirmed green CI, job URL pending. |

The final Phase D record will add actual CI pipeline/job references and executed results. The table intentionally does not count passing offline Unit tests as PostgreSQL acceptance.

## Phase B - Physical commit failure and uncertainty

**Focus:** PG-04 and PG-06; P15-T07 through P15-T09.

- [x] Add a fixture-local PostgreSQL table with a **deferred** constraint, then prove a callback-returned `COMMIT` fails only when the real physical transaction attempts to commit. Inspect the database independently after finalization and verify manager failure/reuse semantics.
- [x] Preserve and, where needed, strengthen the real `afterCommit` failure case: verify rows remain durable while the application call fails and the operation cannot safely proceed on a manager whose cleanup did not confirm completion.
- [x] Identify the actual `JpaTransactionManager` / Hibernate / JDBC physical commit path used by this baseline. Only then add the narrowest test-only interception that can distinguish a failure **before** commit issuance from a failure **after** PostgreSQL committed but before the client receives success. Both paths must delegate to real infrastructure and leave production wiring unaffected.
- [x] Exercise the injected paths through production `Transactions.execute(...)`; assert propagated failures, safe manager state, independent database visibility and preserved exception/cause information. Do not infer that a failed client response means rollback or introduce automatic retries.
- [x] Add focused Unit checks only for genuinely new glue or exception-handling behavior. If the test exposes a production defect, submit the smallest production fix with targeted regression coverage and explicitly review any architectural-contract change.

**Gate B:** E2E proves deferred-constraint rejection, durable-commit/after-commit failure, and the two controlled commit-response failure scenarios against PostgreSQL; the test-only mechanism is demonstrably outside production. Record the precise limits of fault injection. Human approval precedes Phase C.

**Phase B accepted implementation note:** `RelationalCommitFailureE2E` attaches a test-only `TransactionExecutionListener` to the real `JpaTransactionManager` in each disposable fixture. Spring invokes `beforeCommit` before the physical `doCommit` path and the successful `afterCommit` listener after the real commit, but before acknowledging success to `Transactions.execute(...)`. The tests distinguish rollback before commit issuance from an already durable commit with a failed application response, and assert the operation manager cannot be reused. This models a **lost success result at Spring's commit boundary**, not a JDBC driver/socket failure. A real deferred foreign-key constraint covers database-side commit rejection; the existing `RelationalTransactionE2E.afterCommitFailurePropagatesEvenThoughRowsAreDurable` covers P15-T08. The offline Unit gate passed 109 tests with the new E2E source compiled. The reviewer confirmed the PostgreSQL CI passed; exact job URLs remain to be recorded.

## Phase C - GitLab CI test stages and frontend job split

**Implementation prepared for review; CI Lint and live pipeline acceptance pending.**

**Focus:** CI-01 through CI-05; P15-T10. No changes to Maven suite classification, production deployment or frontend application behavior.

- [ ] Restructure `.gitlab-ci.yml` so backend **Unit**, **Integration** and **E2E** suites have individually named stages, JUnit reports and failure gates. Prefer an explicit backend chain (`Unit → Integration → E2E`) through `needs` to spare PostgreSQL runner time after an early failure. Build/package independently and avoid downloading its JAR into test jobs that recompile their own sources (`needs:artifacts: false`).
- [ ] Split the current frontend `build-frontend` monolith into distinct lint, app typecheck, test typecheck, Vitest/component checks and production build jobs (combine only adjacent fast checks with clear attribution). Keep the existing isolated `test:static-export` script as a separate job: it performs **another real Next.js build**, copies dependencies and serves exported files. Gate it on the relevant frontend checks/build while avoiding unnecessary artifact downloads.
- [ ] Use `needs: []` for independent early jobs where useful, and explicit `needs` for actual dependencies. Make all job names, stage names and artifact transfer consistent; extend `TestSelectionConventionsTests` so it checks the revised CI job/stage contract rather than retaining a stale assumption. Keep GitLab template stages valid for SAST, `secret_detection` and offline-handoff checks; do not accidentally skip scanners or make backend checks wait for frontend work.
- [ ] Reassess Maven `.m2/repository` and npm's lockfile-keyed package-download cache (not a shared `node_modules` build artifact). Preserve reproducible `npm ci`, all existing frontend commands, backend Surefire/Failsafe JUnit output, and an appropriate Vitest JUnit report for the frontend test job. Review `workflow:rules` (duplicate branch/MR pipelines), `interruptible`, auto-cancel, and safe cache/artifact expiration against the installed runner/GitLab version before enabling them.
- [ ] Validate expanded CI configuration with the project's **GitLab CI Lint** including external templates, then run a real branch/MR pipeline. Confirm required jobs exist, dependency order and independent frontend/backend progress, retained security checks, report visibility, and a representative failure/rerun. Do not rely on local YAML parsing as GitLab semantic validation.

**Implementation note (review pending):** The candidate graph keeps backend packaging independent and gates Unit → Integration → E2E using `needs` without artifact transfers. Frontend lint, application/test typechecks and Vitest run independently; the production build depends on those checks, and the separate static-export probe depends on the build. npm package downloads and Maven artifacts are cached by lockfile/manifest, with one cache writer per toolchain; `node_modules` is never transferred. Vitest produces a JUnit report. Merge-request pipelines supersede duplicate branch pushes and explicitly retain MR security scanning; protected refs are excluded from auto-cancel. Local YAML/DAG checks and the offline Unit suite passed, but only the project GitLab CI Lint and actual pipeline can verify included templates, analyzer scheduling and runner behavior.

**Gate C:** The complete GitLab pipeline passes with all expected frontend/backend/security/offline jobs, clear per-job diagnostics and usable JUnit reports. CI Lint, DAG behavior and actual runner support are recorded. Human review and approval precede Phase D.

## Phase D - Acceptance, documentation and Phase 1 closure

**Focus:** PG-06 and final CI acceptance; entire P1.5 specification.

- [ ] Finish the TX-03 through TX-11 evidence matrix with test names, GitLab job/pipeline references, observed status and explicitly deferred requirements. Confirm no claims rely exclusively on P1.3c's rollback substitute or H2/fake manager.
- [ ] Reconcile `docs/development/tests.md` and affected developer/CI guidance with the final job graph, commands, fixture setup and failure diagnostics. Update [transaction architecture](../../../architecture/transactions.md) only if the **implemented** production contract changed; do not record proposed behavior as steady state.
- [ ] Validate documentation links, Markdown structure, `git diff --check`, backend tests (including PostgreSQL) and frontend lint, typechecks, Vitest, normal build and static-export probe. Capture CI execution and operational failure-injection evidence for independent review.
- [ ] Obtain independent code review and human acceptance. Only afterward mark [P1.5 and Phase 1](../backlog.md#phase-1---modular-application-and-persistence-foundation) complete and authorize merge separately.

**Gate D:** PostgreSQL TX-03 through TX-11, CI acceptance and authoritative documentation agree. No unimplemented messaging, domain invariants, deployed smoke tests or P2.6 image/deployment work is claimed as completed.

## Implementation constraints and review notes

The work proceeds in the stated order only after each gate passes; adjust the plan with reviewed changes when new technical evidence warrants it. Keep Java 25 explicit local types (`var` is forbidden), stable `*Test`/`*Tests`, `*IT` and `*E2E` conventions, and `./mvnw clean verify` as the existing combined developer invocation. No new `-Pall-tests` switch is needed. P2.4 owns durable messaging acceptance; P2.6 owns registry publishing/deployment CI. If dependencies change, refresh the separate offline handoff and verify it rather than masking missing artifacts with network downloads.
