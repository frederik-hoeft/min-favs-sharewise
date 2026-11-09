# P1.3b - Implementation plan: application-owned transactions

**Status:** Draft for human review alongside the [P1.3b specification](specification.md). The [development workflow](../../../development/ai-development-workflow.md) requires approval of both artifacts before implementation, and approval of each incremental phase before progressing. P1.3a's [implemented DI scope](../../../architecture/dependency-injection.md) is the baseline; P1.2's PostgreSQL foundation and P1.3c's database test fixtures are independent prerequisites for final database acceptance.

## Baseline, dependencies and ownership

The approved P1.3a baseline contains Spring Boot 4.1.2 / Java 25, the `ServiceScopeFactory` / `ServiceScope` / `ServiceProvider` contracts and `OperationDispatcher`. It also contains Spring Data JPA and starter H2, but not the planned PostgreSQL/Flyway/jOOQ infrastructure. Transaction management must not add a second DI scope, transaction coordinator, connection pool or generic business-result library. The existing Spring transaction API is available through the backend's data dependencies; verify that with the build rather than assuming a new dependency is necessary.

Public application contracts belong in `de.hsh.favs.sharewise.shared.infra.transactions`, parallel to `shared.infra.di`. Keep Spring-specific implementation in `shared.infra.transactions.internal`. Future Modulith verification publishes the API as `shared::transactions`, while keeping implementation packages unpublished. If a narrow DI activation/lease check is required, extend the **existing** DI infrastructure without introducing a transaction-specific ambient context; transactions depend on DI, never the reverse.

The production component is a Spring **operation-scoped** `Transactions` bean. It delegates physical acquisition and finalization to the configured `PlatformTransactionManager`, preferably via `TransactionTemplate`. `ServiceScope` and `OperationDispatcher` never open physical database transactions implicitly. `Transactions.execute(...)` creates a new, synchronous transaction segment, obtains its single returned `TransactionOutcome<T>`, and completes the physical transaction before exposing the application result.

Each phase yields one independently reviewable patch on the approved baseline. Checkboxes are completed **only after validation and human approval**. When a phase is blocked by P1.2, retain the block visibly rather than substituting H2/mock results for PostgreSQL acceptance.

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

- [ ] Introduce the smallest adapter-facing transaction-segment access mechanism necessary to check active segment identity, owner thread and closure; capture an immutable segment identity/lease for each resource handle instead of relying on the mutable manager's current segment at execution time.
- [ ] A persistence adapter may obtain a resource only inside `Transactions.execute(...)`; operations on retained handles after finalization, across segments or outside scope are rejected before SQL execution. Avoid a general resource registry, direct connection management or an application-visible `TransactionSession` argument.
- [ ] Validate guard composition and lifetime failures with an instrumented fake adapter. Ensure accessors do not begin a transaction, cannot resurrect a closed segment and do not cache transaction-bound facades across calls.
- [ ] Integrate Spring Boot's jOOQ starter and operation-scoped `JooqAccess.dsl()` and `JpaAccess.entityManager()` facades. Preserve Spring's transaction-aware connection provider and JPA persistence context. Both accessors must use the same segment-lease interface; guard jOOQ connection acquisition and SQL execution as well as JPA facade and deferred query invocations. Document local-only resource use; do not expose unguarded provider resources. Keep PostgreSQL-specific evidence and jOOQ/JPA interoperability in Phase D.

**Gate C:** No mock-only or H2 assertions are advertised as proof of PostgreSQL behavior. The additional Spring Boot jOOQ dependency requires a refreshed offline Maven cache before the full backend build and jOOQ integration tests can run; record that validation gap explicitly. Reviewer approval and completed verification are required before Phase D.

## Phase D - Real PostgreSQL integration (requires P1.2)

**Focus:** Physical commit, rollback, connection lifetime, visibility and compatibility. **Acceptance:** TX-03 through TX-11 where real persistence is required. P1.3c may supply reusable test fixtures; P2.4 owns publication/consumer integration in TX-12.

- [ ] With P1.2's actual PostgreSQL datasource, migrations and transaction manager present, verify `Transactions` participates in the chosen Spring physical manager; verify uncommitted changes are invisible to independent transactions and completed changes are visible after return.
- [ ] Verify multi-port atomicity, ROLLBACK/ABSTAIN, typed failures with intentional COMMIT, rollback-only persistence behavior, and segment A commit followed by segment B rollback in a *real* database. Use independent connections/transactions for visibility assertions; do not accidentally wrap production-dispatch tests in a Spring TestContext `@Transactional` transaction.
- [ ] Exercise known and uncertain finalization failures without claiming an uncertain commit is rolled back. Verify a failing `afterCommit()` callback reports failure while the committed rows remain durable. Prove wrong-segment and retained-handle guards against actual SQL paths.
- [ ] Verify jOOQ/JPA participation and flush/visibility against P1.2's real PostgreSQL configuration. Both accessors are introduced in Phase C, but their cross-technology transaction behavior is not established by H2. Keep producer-publication atomicity and consumer tests assigned to the messaging milestone where that infrastructure exists.

**Gate D:** Real PostgreSQL-backed tests pass through the same production scope/manager configuration. If P1.2 or the required runtime is unavailable, **Phase D remains blocked** and P1.3b is not represented as having passed its complete physical-database acceptance gate. H2 and manager fakes are interim coverage only.

## Phase E - Steady-state documentation and acceptance

**Focus:** Final reconciliation, independent review and handoff after the applicable integration work is present.

- [ ] Write `docs/architecture/transactions.md` as the authoritative **implemented** transaction architecture: ownership, outcome semantics, segment lifecycle, resource participation, failure boundaries, concurrency and relationship to DI. It must not read as a task status report or repeat this plan.
- [ ] Update affected existing architecture and developer usage guidance, leaving future integrations and TODOs in `docs/stack` and `docs/internal/tasks`. Correct obsolete spec/backlog references when implementation changes actual contracts.
- [ ] Run relevant focused tests, complete Maven offline verification, docs link/anchor validation and `git diff --check`. Record separately which tests use fake managers, H2 or real PostgreSQL, and which physical guarantees have been established.
- [ ] Provide reviewable final patch, explicit acceptance evidence, known limitations and an infrastructure-focused manual acceptance procedure. Obtain human final approval before marking P1.3b complete or merging into the authoritative baseline.

**Gate E:** The committed implementation, tests, specification and authoritative steady-state architecture agree. Human review and operational acceptance have been recorded; no unsupported claim of successful PostgreSQL, jOOQ/JPA or messaging integration remains.

## Out of scope and follow-up ownership

P1.3a owns DI lifetime/dispatch. P1.2 owns PostgreSQL schema, datasource and persistence framework configuration. P1.3c owns special rollback-isolated test transaction behavior and reusable real-commit fixtures. P2.4 owns durable publication and consumer integration. P1.3b does not implement generic retries/idempotency, nested transaction propagation or savepoints, distributed transactions, domain-specific locking/history, cross-module business transactions or arbitrary resource reference tracking.
