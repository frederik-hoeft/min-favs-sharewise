# P1.5 - Implementation plan: PostgreSQL transaction acceptance and CI restructuring

**Status:** Phases A–C accepted after reviewer-confirmed green PostgreSQL and full-pipeline CI. Phase D documentation and final Phase 1 sign-off are under review. **Specification:** [P1.5](specification.md). Follow the [AI development workflow](../../../development/ai-development-workflow.md): agree on the specification and plan before implementation, then deliver one reviewed incremental patch per phase. Do not check off work until it is validated and human-approved.

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

The named methods below ran in the reviewer-confirmed green PostgreSQL CI following Phases A and B. They use production `Transactions`, `OperationDispatcher` and independently migrated PostgreSQL databases, **not** the P1.3c rollback-only Integration substitute. The reviewer also confirmed a green CI run after the final Phase C2 pipeline restructuring. The pipeline and job IDs/URLs were not supplied, so this record distinguishes **maintainer-reported CI acceptance** from locally reproduced checks; it does not invent per-job execution counts or links.

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

**CI acceptance evidence (reviewer-reported, November 19, 2026):**

| Gate | Execution evidence | Provenance / limit |
| --- | --- | --- |
| **Phase A/B PostgreSQL E2E** | Maintainer confirmed the PostgreSQL CI pipeline passed after the production-transaction additions, including the Phase B failure tests. | Green-run confirmation in review conversation; exact GitLab pipeline/job URLs and per-suite totals were not provided. |
| **Phase C2 full pipeline** | Maintainer reviewed the staged build/test layout and confirmed the final pipeline was green. | Confirmation covers the final configuration; detailed CI Lint output, an intentionally failed-job rehearsal, JUnit-download verification and runner logs were not supplied separately. |
| **Offline Unit** | The latest locally reproduced Unit run and its test count are recorded in the Phase D validation notes below. | Local Java 25/Maven verification checks compilation and Unit contracts, **not** PostgreSQL commit semantics. |

The GitLab `test-backend-e2e` job is the required real-commit acceptance gate. The `test-backend-integration` job validates a *different* rollback-isolated model. If future audits require direct CI traceability, add the real pipeline/job links here without changing the physical-transaction claims. A green result for Unit or rollback Integration alone is not TX-03–TX-11 evidence.

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

**Accepted:** The C2 job graph and full pipeline were reviewer-approved after a green GitLab CI run. Expanded CI Lint output and negative-path rehearsal were not provided independently.

**Focus:** CI-01 through CI-05; P15-T10. No changes to Maven suite classification, production deployment or frontend application behavior.

- [x] Restructure `.gitlab-ci.yml` with a mandatory **build** barrier (backend package + frontend production build), a shared **test** stage (backend Unit, frontend checks/static export, security scanners and offline handoff), then separate **PostgreSQL Integration** and **PostgreSQL E2E** stages. The backend test chain must fail closed on build/Unit/Integration failures without waiting for unrelated test jobs. Preserve distinct JUnit reports and mandatory overall-pipeline failure behavior.
- [x] Split the frontend monolith into distinct lint, app typecheck, test typecheck, Vitest/component checks and production build jobs. The production build runs in `build` alongside backend packaging; the existing isolated `test:static-export` probe runs in `test` after both builds and still performs **another real Next.js build**, serves exported files and validates assets.
- [x] Use `needs` only for backend Unit → PostgreSQL Integration → PostgreSQL E2E, with artifact downloads disabled, while keeping Unit gated by the build stage. Use `dependencies: []` for independently rebuilding jobs without `needs`, not both keywords in the same job. Keep GitLab security analyzers in the `test` stage. Extend `TestSelectionConventionsTests` to verify build-first ordering, the exact backend dependency chain and artifact isolation. Preserve security/offline jobs and their failure reporting.
- [x] Reassess Maven `.m2/repository` and npm's lockfile-keyed package-download cache (not a shared `node_modules` build artifact). Preserve reproducible `npm ci`, all existing frontend commands, backend Surefire/Failsafe JUnit output, and an appropriate Vitest JUnit report for the frontend test job. Review `workflow:rules` (duplicate branch/MR pipelines), `interruptible`, auto-cancel, and safe cache/artifact expiration against the installed runner/GitLab version before enabling them.
- [x] Validate the job graph locally and obtain reviewer confirmation of a green full GitLab pipeline after C2. Both builds gate Unit, and `needs` links only Unit → Integration → E2E. The complete pipeline retains mandatory frontend, security and offline jobs. GitLab CI Lint output, an intentionally failing-job rehearsal and downloaded JUnit artifacts were not separately provided; the acceptance relies on the reviewer's runner report rather than representing those checks as individually observed.

**Accepted execution model:** The build stage runs backend packaging and frontend production build concurrently. After both succeed, Unit, frontend lint/typechecks/Vitest, isolated static-export, security scanners and offline handoff run in the shared `test` stage. `needs` connects only backend Unit → PostgreSQL Integration → PostgreSQL E2E, so the PostgreSQL chain advances independently of unrelated checks while the full pipeline still requires their success. The PostgreSQL jobs use `needs:artifacts: false`, not inherited `dependencies`; other independently rebuilding test jobs use `dependencies: []`. The two builds populate Maven and npm download caches; `node_modules` is never transferred. Vitest produces a JUnit report. Merge-request pipelines supersede duplicate pushes for unprotected branches; protected refs retain their push pipelines even with an open MR and are excluded from auto-cancel. SAST analyzers inherit artifact isolation from the template's `sast` parent. Local validation is not a substitute for the project's expanded GitLab CI Lint and actual runner execution.

**Gate C (accepted):** The reviewer confirmed successful execution of the complete C2 GitLab pipeline. The source YAML and Unit convention tests verify the expected build barrier, backend `needs` chain and reporting configuration; individual scanner policies remain controlled by GitLab's templates. Runner screenshots/results support the execution graph; exact job URLs and separately reproduced negative-path evidence are unavailable in this handoff.

## Phase D - Acceptance, documentation and Phase 1 closure

**Focus:** PG-06 and final CI acceptance; entire P1.5 specification.

- [x] Finish the TX-03 through TX-11 evidence matrix with named PostgreSQL E2E tests, exact GitLab job names, reviewer-reported execution status and deferred requirements. Note that pipeline/job URLs were not supplied; no claim relies exclusively on P1.3c's rollback substitute or H2/fake manager.
- [x] Reconcile `docs/development/tests.md` and affected developer/CI guidance with the final job graph, commands, fixture setup and failure diagnostics. Update [transaction architecture](../../../architecture/transactions.md) only if the **implemented** production contract changed; do not record proposed behavior as steady state.
- [x] Check documentation links, Markdown structure and `git diff --check`, and rerun the locally available backend Unit gate. Record the latest result below; PostgreSQL and frontend execution is based on the maintainer-confirmed green GitLab pipeline, not an independent local rerun. The Phase B fault-injection mechanisms were exercised in that accepted PostgreSQL E2E gate, with their simulated commit-response limitation retained.
- [ ] Obtain independent review and human acceptance of this final evidence/documentation reconciliation. Confirm the older P1.1 independent-review note in the backlog is resolved before marking [P1.5 and Phase 1](../backlog.md#phase-1---modular-application-and-persistence-foundation) complete; merge authorization remains separate.

**Phase D validation:** Against the accepted C2 baseline, strict-offline Java 25 / Maven 3.10 `-Punit-tests clean verify` passed **110 tests, 0 failures, 0 errors, 0 skipped**, and compiled the production and test sources. Markdown/link and patch consistency checks are recorded with the Phase D patch. PostgreSQL Integration/E2E and frontend checks were not rerun in the offline container; the accepted GitLab CI result was reported by the maintainer. No CI job URLs or independent CI Lint/negative-path artifacts were available in this handoff.

**Gate D (review pending):** PostgreSQL TX-03 through TX-11 and the accepted C2 pipeline are reconciled with the documentation and recorded evidence. Final human approval is required before checking off P1.5 in the backlog and declaring Phase 1 closed. No unimplemented messaging, domain invariants, deployed smoke tests or P2.6 image/deployment work is claimed as completed.

## Implementation constraints and review notes

The work proceeds in the stated order only after each gate passes; adjust the plan with reviewed changes when new technical evidence warrants it. Keep Java 25 explicit local types (`var` is forbidden), stable `*Test`/`*Tests`, `*IT` and `*E2E` conventions, and `./mvnw clean verify` as the existing combined developer invocation. No new `-Pall-tests` switch is needed. P2.4 owns durable messaging acceptance; P2.6 owns registry publishing/deployment CI. If dependencies change, refresh the separate offline handoff and verify it rather than masking missing artifacts with network downloads.
