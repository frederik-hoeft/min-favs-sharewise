# P1.3b - Implementation plan: application-owned transactions

**Status:** Phases A-E accepted; P1.3b implementation and architecture documentation complete. PostgreSQL acceptance remains assigned to P1.5. [P1.3b specification](specification.md). The [development workflow](../../../development/ai-development-workflow.md) governs review gates. P1.3a provides the [DI foundation](../../../architecture/dependency-injection.md). P1.3b verifies Spring/H2 integration independently; [P1.5](../backlog.md#phase-1---modular-application-and-persistence-foundation) owns the mandatory PostgreSQL integration gate after P1.2.

## Baseline, dependencies and ownership

The approved P1.3a baseline contains Spring Boot 4.1.2 / Java 25, the `ServiceScopeFactory` / `ServiceScope` / `ServiceProvider` contracts and `OperationDispatcher`. It also contains Spring Data JPA and starter H2, but not the planned PostgreSQL/Flyway/jOOQ infrastructure. Transaction management must not add a second DI scope, transaction coordinator, connection pool or generic business-result library. The existing Spring transaction API is available through the backend's data dependencies; verify that with the build rather than assuming a new dependency is necessary.

Public application contracts belong in `de.hsh.favs.sharewise.shared.infra.transactions`, parallel to `shared.infra.di`. Keep Spring-specific implementation in `shared.infra.transactions.internal`. Future Modulith verification publishes the API as `shared::transactions`, while keeping implementation packages unpublished. If a narrow DI activation/lease check is required, extend the **existing** DI infrastructure without introducing a transaction-specific ambient context; transactions depend on DI, never the reverse.

The production component is a Spring **operation-scoped** `Transactions` bean. It delegates physical acquisition and finalization to the configured `PlatformTransactionManager`, preferably via `TransactionTemplate`. `ServiceScope` and `OperationDispatcher` never open physical database transactions implicitly. `Transactions.execute(...)` creates a new, synchronous transaction segment, obtains its single returned `TransactionOutcome<T>`, and completes the physical transaction before exposing the application result.

Each phase yields one independently reviewable patch on the approved baseline. Checkboxes are completed **only after validation and human approval**. H2 and fake-manager tests validate Spring/application behavior but cannot substitute for the real-PostgreSQL evidence explicitly scheduled under P1.5.

## Phase A - Public contract and Spring-backed segment execution

**Focus:** `Transactions`, `TransactionDecision`, `TransactionOutcome<T>`, basic operation-scope registration and outcome-driven physical completion. **Acceptance:** TX-01, TX-02, TX-04, TX-05, TX-09 and TX-13 at the Spring/component-test level.

- [x] Add the small public contract with `execute(Supplier<TransactionOutcome<T>>)` and sticky rollback-only accessors. Implement `TransactionOutcome.commit(result)`, `.rollback(result)` and `.abstain(result)`; reject a null outcome/decision while permitting a null application result.
- [x] Register one operation-scoped Spring-backed manager. Resolve the single compatible `PlatformTransactionManager` using normal constructor injection; construct/use a `TransactionTemplate`, not a second connection or transaction manager. Do not instantiate physical resources until `execute(...)`.
- [x] Implement the explicit result-driven commit/rollback path and reset segment-local state for sequential calls. A nested service may be injected with the same `Transactions` instance; constructing an outcome alone has no side effects.
- [x] Add Spring-context tests using a deterministic instrumented `PlatformTransactionManager` and operation dispatch: shared scoped identity, zero begin on injection alone, one begin/finalization per `execute(...)`, commit/rollback/abstain, sequential segments and returned result identity. Cover outcome delegation and unused outcomes.

**Gate A:** Focused tests and backend offline `mvn clean verify` pass. No `@Transactional` interception or direct physical transaction handling has been introduced in the dispatcher. The public API and component ownership are reviewed before Phase B.

## Phase B - Lifetime, rollback-only and failure semantics

**Focus:** Explicit failure state, scope ownership and physical finalization invariants. **Acceptance:** TX-06 through TX-09 and TX-13 for mockable Spring behavior; PostgreSQL-dependent portions remain for Phase D.

- [x] Enforce active/current scope identity, owner-thread confinement and post-close invalidation for **every** public manager operation. Add only the minimal DI-internal activation check needed; an escaped manager cannot run outside `ServiceScope.run(...)` or under another scope.
- [x] Reject reentrant/nested `Transactions.execute(...)`; reject an ambient Spring transaction or synchronization context that would defer the promised commit; assert the transaction acquired by Spring is new. Do not rely on `PROPAGATION_REQUIRED` automatically joining ambient work, or use `REQUIRES_NEW` to hide an existing transaction.
- [x] Implement sticky `markRollbackOnly()` for the active segment and enforce that a requested COMMIT cannot succeed when poisoned. Permit ROLLBACK/ABSTAIN to return typed results if rollback completes. Reject rollback-only access outside a segment; reset the veto on a new segment.
- [x] Guarantee rollback for thrown callbacks, null/invalid outcomes, and failed validity checks inside a segment. Distinguish a definitively rolled-back callback exception from an uncertain or failed finalization: only the latter poisons the manager for subsequent segments. Preserve primary exceptions and cleanup/finalization diagnostics as far as the Spring API permits.
- [x] Cover physical commit/rollback failure, transaction synchronization failure (including `afterCommit()` after durable commit), nested calls, two consecutive segments, cross-thread invocation and independent concurrent scopes. Verify failure states do not return apparent commit success or automatically retry ambiguous writes.

**Gate B:** The deterministic transaction-manager tests demonstrate one manager-owned segment and definitive or uncertain finalization, state reset/poisoning, and fail-closed ambient/nesting behavior. Spring may attempt rollback after a failed commit; that fallback is not proof that the commit never occurred. Tests do **not** claim PostgreSQL durability or connection isolation. The patch is reviewed before Phase C.

## Phase C - Segment-bound persistence participation

**Focus:** Internal adapter support for acquiring resources safely inside the *current* physical segment, without leaking transaction internals into domain ports. **Acceptance:** TX-09 and TX-10 at the guard/component level.

- [x] Introduce the smallest adapter-facing transaction-segment access mechanism necessary to check active segment identity, owner thread and closure; capture an immutable segment identity/lease for each resource handle instead of relying on the mutable manager's current segment at execution time.
- [x] A persistence adapter may obtain a resource only inside `Transactions.execute(...)`; operations on retained handles after finalization, across segments or outside scope are rejected before SQL execution. Avoid a general resource registry, direct connection management or an application-visible `TransactionSession` argument.
- [x] Validate guard composition and lifetime failures with an instrumented fake adapter. Ensure accessors do not begin a transaction, cannot resurrect a closed segment and do not cache transaction-bound facades across calls.
- [x] Integrate Spring Boot's jOOQ starter and operation-scoped `JooqAccess.dsl()` and `JpaAccess.entityManager()` facades. Preserve Spring's transaction-aware connection provider and JPA persistence context. Both accessors must use the same segment-lease interface; guard jOOQ connection acquisition and SQL execution as well as JPA facade and deferred query invocations. Document local-only resource use; do not expose unguarded provider resources. Keep PostgreSQL-specific evidence and jOOQ/JPA interoperability in Phase D.

**Gate C (accepted):** Operation-scoped segment leases and both jOOQ/JPA accessors pass the full offline backend build with the refreshed Maven handoff (54 tests before Phase D). The human approved the C.1 revision. These tests establish H2/framework behavior only, not PostgreSQL guarantees.

## Phase D - Spring/H2 persistence integration and failure paths

**Focus:** Exercise the production operation scope, transaction manager and both persistence accessors against real Spring-managed H2 transactions **without depending on P1.2**. **Acceptance:** Framework-level portions of TX-03 through TX-11; PostgreSQL-specific evidence moves to mandatory P1.5. P2.4 owns messaging publication/consumer integration in TX-12.

- [x] Use one Spring `JpaTransactionManager` and transaction-aware jOOQ configuration with the same H2 `DataSource`. Exercise jOOQ and JPA writes in one physical transaction; verify JPA flush-to-SQL visibility and explicit refresh after jOOQ mutations.
- [x] Verify mixed-adapter COMMIT, ROLLBACK, ABSTAIN and sequential segments, including rollback-only vetoes, typed failure with intentional COMMIT, and callback exception recovery. Verify pending writes are invisible through a genuinely independent connection before commit; avoid accidental participation through Spring's thread-bound connection.
- [x] Exercise an `afterCommit()` callback failure after a completed commit, commit-time ORM/constraint failure, known SQL error translated into a rollback-only result, and incompatible ambient transactions. Retain deterministic fake-manager tests for uncertain commit/rollback outcomes that H2 cannot simulate reliably.
- [x] Retain the Phase C regression coverage for stale/derived jOOQ and JPA handles, wrong-scope/thread access and missing physical context. Do not treat H2 isolation, transaction-manager compatibility, or constraints as PostgreSQL acceptance evidence.

**Gate D:** The full backend offline build and focused H2 tests pass. Record H2/fake-manager versus PostgreSQL evidence separately. Human approval accepts P1.3b's Spring/adapter component integration only; **P1.5 remains a mandatory, separate Phase 1 gate against P1.2's actual PostgreSQL wiring**.

## Phase E - Steady-state documentation and acceptance

**Focus:** Final reconciliation, independent review and handoff after the applicable integration work is present.

- [x] Write `docs/architecture/transactions.md` as the authoritative **implemented** transaction architecture: ownership, outcome semantics, segment lifecycle, resource participation, failure boundaries, concurrency and relationship to DI. It must not read as a task status report or repeat this plan.
- [x] Update affected existing architecture and developer usage guidance, leaving future integrations and TODOs in `docs/stack` and `docs/internal/tasks`. Correct obsolete spec/backlog references when implementation changes actual contracts.
- [x] Run relevant focused tests, complete Maven offline verification, docs link/anchor validation and `git diff --check`. Record separately which tests use fake managers, H2 or real PostgreSQL, and which physical guarantees have been established.
- [x] Provide reviewable final patch, explicit acceptance evidence, known limitations and an infrastructure-focused manual acceptance procedure. Obtain human final approval before marking P1.3b complete or merging into the authoritative baseline.

**Gate E:** The implementation, tests, specification and authoritative steady-state architecture agree. Human review and operational acceptance have been recorded. P1.3b can be accepted with its H2/manager-level evidence, but PostgreSQL-backed transaction and cross-adapter guarantees **remain open until P1.5 passes**; messaging integration remains assigned to P2.4.

## Out of scope and follow-up ownership

P1.3a owns DI lifetime/dispatch. P1.2 owns PostgreSQL schema, datasource and persistence framework configuration. P1.5 owns PostgreSQL transaction interoperability and acceptance against that configuration. P1.3c owns special rollback-isolated test transaction behavior and reusable real-commit fixtures. P2.4 owns durable publication and consumer integration. P1.3b does not implement generic retries/idempotency, nested transaction propagation or savepoints, distributed transactions, domain-specific locking/history, cross-module business transactions or arbitrary resource reference tracking.
