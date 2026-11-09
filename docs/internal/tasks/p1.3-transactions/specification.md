# P1.3b - Application-owned transaction management

**Status:** Accepted production contract; P1.3b implementation and architecture documentation are complete with Spring/H2 and deterministic transaction-manager evidence. PostgreSQL-specific acceptance remains tracked under P1.5. **Backlog item:** [P1.3b](../backlog.md#phase-1---modular-application-and-persistence-foundation). The [application architecture](../../../architecture.md#transaction-aware-ports), [DI operation-scope architecture](../../../architecture/dependency-injection.md), [implemented transaction architecture](../../../architecture/transactions.md), [persistence architecture](../../../stack/persistence.md#transactions-and-failure-behavior), and [messaging architecture](../../../stack/messaging.md) establish the surrounding system contracts.

## Goal, dependencies and scope

The transaction-management work fits among three related responsibilities:

1. **Operation-scope DI foundation (implemented):** Reuse the implemented Spring [operation-scope contract](../../../architecture/dependency-injection.md), including inbound-agnostic dispatch, scoped constructor injection, lifetime isolation and deterministic cleanup. The [alternatives](../../../stack/dependency-injection-alternatives.md) explain the selected model. P1.3b builds on that reusable DI foundation without adding a second scope or changing its lifetime semantics.
2. **Production transaction management:** Implement an operation-scoped, technology-independent `Transactions` port backed by Spring's programmatic physical transaction management. The application can execute zero or more sequential transaction segments within an operation scope, with result-driven finalization and guarded resource participation. This document specifies the production contract.
3. **Integration-test execution and isolation (separate task):** The companion [P1.3c specification](../p1.3c-integration-tests/specification.md) defines test-facing operation-scope access, rollback-isolated single-segment tests, and real-commit workflow fixtures. Physical commit-dependent messaging is verified only by the real-commit mode.

The transaction capability serves application entry services invoked from REST, event consumers, scheduled jobs and other inbound adapters. It does not own their dispatch or DI lifetime. It does not implement Finance revision/locking protocols, IAM token rotation, a Modulith registry/inbox, an external broker, database schema migrations, generic retry policy or distributed transactions.

P1.2 independently supplies the PostgreSQL `DataSource`, compatible Spring `PlatformTransactionManager`, schema/migrations and production persistence configuration. P1.3b must not construct a competing connection pool, choose a database vendor in the application core or modify P1.2's bootstrap solely to make its abstraction work. P1.3b verifies Spring and cross-adapter operation behavior using H2 and deterministic transaction-manager tests; [P1.5](../backlog.md#phase-1---modular-application-and-persistence-foundation) owns mandatory PostgreSQL integration against P1.2's actual configuration. H2 does not establish PostgreSQL isolation, locking, constraint or durability guarantees.

## Consumer model and lifetime

### Entry point and participation

An inbound adapter dispatches an **application entry service** into a fresh operation DI scope. Spring constructs that service and injects its operation-scoped dependencies, including one `Transactions` instance and any participating application services or data adapters. Scope activation alone does not open a database transaction. The entry service explicitly invokes `Transactions.execute(...)` for each transactional segment it owns. Participating services access the same scoped manager and persistence capabilities by constructor injection; ordinary business methods need no `TransactionSession` parameter.

The application-facing contract belongs in `de.hsh.favs.sharewise.shared.infra.transactions`. Spring-specific code belongs in `shared.infra.transactions.internal`; this package is a future named interface boundary for Spring Modulith alongside `shared::di`:

```java
interface Transactions {
    <T> T execute(Supplier<TransactionOutcome<T>> operation);
    void markRollbackOnly();
    boolean isRollbackOnly();
}

enum TransactionDecision { COMMIT, ROLLBACK, ABSTAIN }

record TransactionOutcome<T>(T result, TransactionDecision decision) {
    // Factories: commit(result), rollback(result), abstain(result).
}
```

`Transactions` is a mutable **operation-scoped service**, whereas `TransactionOutcome<T>` is an immutable return value carrying the orchestrator's requested final disposition for one segment. `execute(...)` begins a physical transaction when invoked, calls the supplied operation, finalizes that transaction according to the returned outcome, and returns its application result only after finalization succeeds. There is no separate `begin()`/`close()` API and no transaction automatically created on database-client injection. The manager is reusable for **sequential** segments after successful finalization while the enclosing DI scope remains active. An unconfirmed finalization outcome or failed cleanup makes the manager unusable for subsequent segments in that scope. A callback exception followed by a definitively successful rollback does not, by itself, poison the manager for later sequential segments.

Example consumer use (illustrative types and `Result` representation):

```java
@Service
@Scope("operation")
final class ThingService {
    private final Transactions transactions;
    private final ThingStore things;

    ThingService(Transactions transactions, ThingStore things) {
        this.transactions = transactions;
        this.things = things;
    }

    Result<Thing, ThingError> rename(UUID id, long version, String name) {
        return transactions.execute(() -> {
            Result<Thing, ThingError> result = things.renameIfVersion(id, version, name);
            if (result instanceof Result.Err<?, ?>) {
                return TransactionOutcome.rollback(result);
            }
            return TransactionOutcome.commit(result);
        });
    }
}

// REST, event, job or test ingress (same application API):
Result<Thing, ThingError> result = dispatcher.dispatch(
    ThingService.class,
    service -> service.rename(id, expectedVersion, newName));
```

The generic `Result<T, E>` in this example represents an expected success or typed error; it is **not** a new mandated P1.3 result library. Java `Optional<T>` may be sufficient for absence-only queries. The selected standardized result/error abstraction belongs to shared API conventions. Application result classification remains independent of transaction disposition. An outcome must be non-null and have a non-null decision; its application result may be null. `TransactionOutcome<T>` stores an immutable decision/result pair, without promising deep immutability of an arbitrary result object.

### Composing services and transaction segments

**Default composition:** The entry service owns each segment's final decision. Participating services and persistence ports return ordinary typed business outcomes, such as `Result<T, E>` or `Optional<T>`. They may irrevocably mark a segment **rollback-only** if its transaction state is unsafe, but their results do not automatically vote on or change the final disposition. The orchestrator interprets participant results and returns one `TransactionOutcome<T>` from the callback supplied to `execute(...)`; its participants neither finalize the transaction nor start nested transactions. Multiple participants share the same physical transaction within the callback, subject to context ownership rules.

**Optional delegation:** An orchestrator may explicitly delegate the *choice of outcome value* to another component, for example by returning a helper's `TransactionOutcome<T>` directly from its `execute(...)` callback. This shares decision-making without creating accumulated votes: an outcome constructed by a participant has no effect unless the orchestrator returns it. The ordinary participating-service contract remains typed business results; delegation never bypasses rollback-only state or physical finalization.

The scoped manager may execute **multiple sequential physical transactions** in one application operation. Each invocation is atomic only within its own segment; once segment A commits, segment B's failure or rollback cannot undo A. A workflow that requires all-or-nothing persistence must keep its related database changes inside **one** segment. Following a commit with a separate broker send cannot replace the atomic persistent publication intent required by [messaging](../../../stack/messaging.md#event-publication-and-delivery).

Nested `Transactions.execute(...)` invocations in an active segment are rejected in the initial contract, as are preexisting ambient Spring transactions/synchronization scopes that could interfere with or defer the claimed physical commit. The production manager must verify that the physical transaction is new rather than silently joining an existing one. Independent operations have distinct scoped managers and transaction states; a manager cannot be reused after its operation scope ends or concurrently across threads. Nested *service calls* inside one segment are supported; they are not nested transactions.

## Resource acquisition and adapter integration

An operation-scoped persistence adapter receives a transaction-aware accessor by constructor injection, rather than a `DSLContext` or `EntityManager` bound permanently to the operation scope. The accessor consults the scoped transaction manager to acquire resources for the **currently active segment**. Business-facing port signatures remain domain-oriented, for example `ThingStore.getThingById(UUID id)`.

The primary jOOQ adapter API is `JooqAccess.dsl()`, returning a fresh segment-bound facade. Its Javadoc explicitly prohibits retaining the facade beyond the current method call; callers execute synchronous queries and return materialized values. The corresponding JPA API is `JpaAccess.entityManager()`, which follows the same lifetime rule. Both accessors derive leases from the same operation-scoped transaction-segment abstraction; the stable accessors may follow sequential segments, but returned resources must not. A retained handle or executable query derived from segment A must never become valid in segment B by virtue of referencing the same mutable manager. Where execution-time guards cannot reliably cover derived objects, the adapter API must prohibit their escape by convention, with that constraint documented and tested. JPA access uses Spring's shared transactional `EntityManager` routing, with segment guards on the facade and derived query objects. Provider-specific unwrapping and direct transaction control are not exposed. Managed entities and lazy collections must not be retained across segments; flush/refresh semantics remain the responsibility of the JPA persistence adapter. No accessor may initiate a new transaction just because the application invoked a query.

Spring's `PlatformTransactionManager` remains the only physical transaction authority. `TransactionTemplate` is the preferred imperative integration: it starts the transaction, invokes the callback, and physically finalizes it before returning. The adapter rejects ambient Spring transaction synchronization/actual-transaction state before entering the template, confirms that the returned `TransactionStatus` owns a new transaction, and never uses `REQUIRES_NEW` to silently suspend another application transaction. A context with multiple transaction managers must explicitly select the manager compatible with its persistence adapters. Spring associates compatible jOOQ/JPA/JDBC resources with the active physical transaction; P1.3's adapter guards add operation/segment lifetime enforcement, not another custom transaction coordinator. The persistence configuration must prove that participating adapters and any later Modulith publication records share the intended transaction manager and resources.

Operation-scoped service instances may outlive several physical segments, but physical connections, jOOQ execution contexts, managed JPA entity state and other segment-specific resources may not. Persistence adapters cannot cache a transaction-bound `DSLContext` or entity manager for subsequent segments. A missing segment, closed segment, wrong-thread use or leaked guarded facade fails deterministically before executing SQL. Derived SQL objects that cannot be guarded must remain confined to the creating segment by documented adapter conventions.

## Finalization and failure contract

### Outcome selection and finalization

The **outcome returned by the enclosing `execute(...)` callback** is the sole finalization instruction for that physical segment. Constructing `TransactionOutcome.commit(result)` produces an ordinary immutable value; it does not commit, mutate manager state, or register a participant vote. In the default case, the orchestrator maps nested services' typed results to this one outcome. Explicit delegation can supply the outcome value, but only the value actually returned by the enclosing callback is evaluated. Physical finalization is deferred until that callback completes; ShareWise has no separate application-visible deferred-vote accumulator or precedence scheme. A segment has at most one active owner and one finalization attempt.

| Final intent or condition | Segment's final behavior |
| --- | --- |
| **COMMIT**, healthy | Commit physically; return the supplied result only after successful commit and cleanup. |
| **ROLLBACK** | Roll back all changes of this segment; return the supplied result only if rollback completes successfully. |
| **ABSTAIN** | Roll back any incidental changes and return the supplied result after successful rollback. It expresses no request to persist changes, distinct from an explicit rejection. |
| **COMMIT**, rollback-only | Roll back and fail the invocation; a poisoned transaction cannot produce normal commit success. |
| **ROLLBACK/ABSTAIN**, rollback-only | Roll back; returning the requested typed result is permitted if finalization succeeds. |
| Callback throws, produces no outcome or violates the contract | Roll back, invalidate the segment and propagate a failure; never infer commit. |
| Physical commit or rollback fails | Report an infrastructure/finalization failure; retain the initiating failure as primary where possible, with cleanup failure diagnostics preserved. Never return apparent successful finalization. |
| An after-commit callback fails after a successful physical commit | Propagate the callback failure without implying rollback; the business changes may already be durable. |

`ABSTAIN` is an optional **final outcome** for a read-only or no-write operation that does not request persistence. Like `ROLLBACK`, it physically rolls back the segment; it is not a subordinate participant's abstention that lets another vote authorize commit. The common write path selects `COMMIT` or `ROLLBACK` based on the orchestrator's evaluation of typed business results.

`markRollbackOnly()` and `isRollbackOnly()` require an active segment and fail outside it. The manager records the veto and marks Spring's active `TransactionStatus` rollback-only. Returning `COMMIT` after a veto must produce an exception after rollback, not a normal result. Returning `ROLLBACK` or `ABSTAIN` after a veto may return the typed result when rollback succeeds.

Rollback-only is a **sticky participant veto** for the current physical segment, not an aggregated `COMMIT`/`ROLLBACK` vote across the operation scope. New segments begin healthy after a previous segment has been finalized. A normal unsuccessful business result (not-found, authorization refusal, optimistic-version conflict) does not automatically poison the transaction. An operation may commit a typed unsuccessful result when it intentionally persists a failure-side effect, such as a failed-login counter.

Spring transaction synchronization callbacks can fail after the physical commit has succeeded (for example, `afterCommit()`). Such an exception is not evidence that the database changes rolled back, and callers must not blindly retry a non-idempotent operation. An ambiguous connection failure during commit remains a separate, potentially uncertain case.

An adapter may translate an explicitly recognized database constraint or concurrency condition into a typed error. When a database or ORM error leaves the transaction unusable, it also marks the segment rollback-only or throws; converting the error into a `Result.Err` does not make the transaction safe to commit. Error classification uses structured database/framework information, not exception-message parsing. Unknown infrastructure exceptions propagate. A connection failure during commit may leave its physical outcome **uncertain**; the caller cannot assume rollback or retry blindly. P1.3 does not implement business-level idempotency or retries.

### Lifecycle, concurrency and cleanup

Each manager is valid only while its owning operation DI scope is **currently activated**, not merely until its eventual destruction callback. A captured manager cannot execute in the gap between `ServiceScope.run(...)` returning and `ServiceScope.close()`. This requires a narrow scope-validity check from the DI infrastructure, not a second ambient transaction registry. P1.3b may add an internal DI lifecycle/activation check without changing the public `ServiceScope` or `ServiceProvider` API. The dependency direction is transactions → DI, never DI → transactions.

Each manager is valid only while its operation DI scope is active. Its normal state transitions are **idle → active segment → finalization → idle**, with **failed** after uncertain/failed physical finalization and **closed** after operation-scope teardown. A failed manager cannot begin another segment; the caller must finish the operation and establish a fresh scope. Calling `execute(...)` after closure, on a wrong thread, while another segment is active, or within an incompatible ambient Spring transaction is rejected. A physical transaction is finalized before another segment begins, and completion callbacks requiring the active operation scope must run before that scope is destroyed. Destroying an operation scope without an active transaction remains a no-op for physical transaction management; a lingering active segment at destruction is a contract violation and must be reported rather than treated as a successful commit. The dispatcher remains independent of transaction semantics; the scoped transaction manager is responsible for rejecting/rolling back unfinished work during teardown rather than leaking a physical transaction.

Spring's underlying imperative transaction resources are thread-associated. The operation-scope identity uses Java 25 `ScopedValue` only inside the [DI foundation](../../../architecture/dependency-injection.md); P1.3 does not introduce a separate `ThreadLocal` or ambient `CurrentTransaction` registry. Neither operation-scoped managers nor live transaction resources may be used concurrently or sent to unrelated asynchronous threads. Separate event-consumer invocations dispatch independent operation scopes and physical transactions.

## Acceptance criteria

Tests use fake ports for business orchestration and Spring's transaction API for infrastructure behavior. **Physical database, connection, concurrency and Spring Modulith publication guarantees require real PostgreSQL integration tests**; H2 and mocked transaction managers cannot establish those invariants. Independent PostgreSQL bootstrap is not a reason to silently mark those tests as passed.

| ID | Scenario and expected evidence |
| --- | --- |
| **TX-01** | The dispatcher invokes the same entry service from different inbound adapters, and constructor-injected participants share the operation-scoped transaction manager without passing transaction arguments on their business methods. |
| **TX-02** | Starting and closing a DI operation without `Transactions.execute(...)` creates no physical database transaction. |
| **TX-03** | Two persistence operations in one `execute(...)` segment commit atomically with **COMMIT**, and their writes are not visible to independent transactions before the physical commit, but are visible after successful return. |
| **TX-04** | **ROLLBACK** and **ABSTAIN** both leave no writes; a typed result can be returned after successful rollback. |
| **TX-05** | A typed expected error with deliberate **COMMIT** persists an intended side effect; a harmless not-found result does not automatically poison the segment. |
| **TX-06** | A participating service marks the segment rollback-only; the outer **COMMIT** fails and persists no changes. An unusable database transaction cannot be rescued through a typed result. |
| **TX-07** | Callback failure and physical finalization failure propagate; commit-time connection loss is treated as potentially uncertain, not as proof of rollback. A failing Spring `afterCommit()` callback is tested separately: the operation reports a failure even though the PostgreSQL commit succeeded and its writes remain durable. |
| **TX-08** | A manager rejects nested `execute(...)`, incompatible ambient transactions, cross-thread access and use after operation-scope closure. Independent concurrent operation scopes remain isolated. |
| **TX-09** | Within one operation scope, physical segment A commits and segment B rolls back. A's persisted effects remain; B's do not. No segment-specific resource from A can execute in B. |
| **TX-10** | Persistence adapters reject SQL outside an active segment and after finalization, including retained jOOQ/JPA facades and executable queries derived from them where guarded; unguardable derived objects are prohibited from escaping by documented convention and regression tests. The configured Spring manager and datasource determine actual database participation. |
| **TX-11** | Where both technologies are supported, participating jOOQ and JPA adapters use compatible physical transaction resources and proven flush/visibility semantics under PostgreSQL. |
| **TX-12** | A producer's business state and durable publication intent can enlist atomically in one physical segment, and the consumer can execute in an independent scope and transaction once messaging infrastructure exists. This is an integration prerequisite for P2.4, not an assertion that Modulith is already installed. |
| **TX-13** | A participant's typed error does not select a transaction disposition; the orchestrator selects the returned outcome. Creating an unused or delegated-but-not-returned `TransactionOutcome` has no effect on finalization, while an explicitly returned delegated outcome is honored subject to rollback-only enforcement. |

The [operation-scope design](../../../architecture/dependency-injection.md#architectural-boundaries) owns its own lifecycle and DI acceptance checks. P1.3b's fake-manager and H2 tests establish component-level behavior; P1.5 must rerun the applicable TX-03 through TX-11 guarantees against P1.2's actual PostgreSQL transaction wiring before the Phase 1 transactional gate can pass. The two levels of evidence are separately reported, not interchangeable. The companion [P1.3c integration-test specification](../p1.3c-integration-tests/specification.md) defines the fixture and test-execution contracts. Its rollback-test adapter records logical application outcomes within a test-owned physical transaction; P1.3b production finalization semantics remain unchanged. Real-commit tests establish the physical guarantees.

## Open implementation decisions and handoff

The public transactions API is grouped in `shared.infra.transactions`, separate from the public DI contracts in `shared.infra.di`. P1.1 is responsible for exposing a Modulith named interface for this public package; Spring-specific internals are not published. The specific `Result<T, E>` implementation, transaction exception taxonomy, operation-scope bean annotations, jOOQ/JPA guard implementation and Spring configuration are settled during implementation without changing the contracts above. Any design requiring automatic transaction creation on DI resolution, nested physical transactions, generic savepoint handling, a second persistence manager or a new ambient application transaction registry needs renewed architectural review.

The approved [implementation plan](plan.md) separates framework-level behavior from database-specific acceptance. H2 exercises jOOQ/JPA composition and failure paths without P1.2; P1.5 proves those guarantees against PostgreSQL using the selected production configuration. Durable event publication remains owned by P2.4 once the messaging infrastructure exists.
