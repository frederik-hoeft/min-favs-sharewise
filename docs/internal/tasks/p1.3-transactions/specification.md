# P1.3 - Operation-scoped transactions and integration-test isolation

**Status:** Draft specification for review; not an implemented or approved contract. **Backlog item:** [P1.3](../backlog.md#phase-1---modular-application-and-persistence-foundation). The [application architecture](../../../architecture.md#transaction-aware-ports), [persistence architecture](../../../stack/persistence.md#transactions-and-failure-behavior), and [messaging architecture](../../../stack/messaging.md) establish the surrounding system contracts.

## Goal and boundaries

Provide an application-owned, technology-independent transaction boundary backed by Spring's local transaction management, together with test-only integration support for isolated, automatically rolled-back database tests. Deliver both as one cohesive P1.3 implementation task with two tightly coupled parts:

- **P1.3-A, production transaction boundary:** application contracts, a Spring implementation, DI composition, session/finalization guards and verification.
- **P1.3-B, integration-test support:** a test-specific transaction participation mode, deterministic database-fixture conventions, automatic test rollback and an explicit real-commit testing path.

The abstraction is reusable by operations in any bounded context and by REST, listener and scheduled-job entry points. It owns transaction orchestration, **not** domain-specific authorization, locking, revision allocation, persistence schemas, connection configuration or cross-context business coordination. A consumer obtains `Transactions` through DI; the infrastructure adapter uses the Spring `PlatformTransactionManager` selected by persistence configuration.

The task can be developed independently of **P1.2 PostgreSQL bootstrap**. It does not modify P1.2's database, Flyway migrations, DataSource configuration, jOOQ/JPA choices or dependencies. Real PostgreSQL integration evidence is required for the full Phase 1 acceptance gate once that environment is available; a successful H2 or mocked test is not a substitute.

## Production transaction contract (P1.3-A)

### Application API and ownership

The application-facing contract consists of an operation-scoped `Transactions` port, an opaque `TransactionSession`, and an outcome that contains an application result independently of a **COMMIT**, **ROLLBACK** or **ABSTAIN** decision. The exact Java signatures, package ownership and exception names are finalized during implementation in accordance with P1.1's module layout; the following shape communicates the intended semantics:

```java
interface Transactions {
    <T> T execute(Function<TransactionSession, TransactionOutcome<T>> operation);
}

interface TransactionSession {
    void markRollbackOnly();
    void requireActive();
}

// TransactionOutcome<T> pairs result T with COMMIT, ROLLBACK or ABSTAIN.
// Factories such as commit(result), rollback(result), abstain(result) are preferred.
```

The application operation invokes `Transactions.execute(...)`, passes the **same session explicitly** to its participating outbound ports, and makes the final decision. Data adapters participate in that session but cannot commit, roll back or prolong it independently. A session is not a `Connection`, `EntityManager`, DI scope, or Spring `TransactionStatus`; those remain infrastructure details. Ordinary typed application failures (for example, a conflict) are not transaction decisions, and a typed failure result can legitimately accompany **COMMIT**, such as when recording a failed authentication attempt.

A non-null operation callback and a valid, non-null outcome are required. The implementation rejects malformed usage rather than inferring a commit. It reports a return value only after the requested physical finalization completes successfully.

### Finalization rules

| Decision or condition | Production contract |
| --- | --- |
| **COMMIT** | Finish the underlying physical transaction before returning the outcome's result. No earlier nested or ambient owner may postpone the commit. |
| **ROLLBACK** | Roll back all work performed by the operation, then return the supplied result after successful finalization. |
| **ABSTAIN** | End without committing any changes, including accidental writes; roll back the transaction and return the result. This communicates a deliberate no-commit/read-only outcome, distinct from an explicit rollback decision. |
| **Rollback-only participant** | Any participant may irrevocably mark the active session rollback-only. A subsequent **COMMIT** cannot succeed or return an apparent success result; the transaction is rolled back and the conflict is reported. |
| **Exception or abandoned callback** | Roll back and propagate the original exception unless rollback/finalization failure prevents a trustworthy outcome. No successful result is fabricated. |
| **Physical commit/finalization failure** | Propagate a failure, never a success result. A lost connection during commit may leave the outcome **unknown**, so do not assert the database definitely rolled back. Callers needing retry safety must use their own idempotency or reconciliation protocol. |

Known expected persistence failures may become typed application results **only** when the adapter can establish that the transaction remains usable. A statement error that poisons the PostgreSQL transaction, a failed flush, and other unsafe infrastructure states require rollback-only or exception propagation. Classification relies on supported error codes/types and transaction state, never parsing vendor exception messages. No infrastructure error can be swallowed and followed by a successful commit of potentially partial work.

### Transaction ownership, lifetime and concurrency

The first implementation uses **strict top-level operation ownership**. `Transactions.execute(...)` rejects an already active Spring transaction, including one opened by unrelated `@Transactional` code; nested `Transactions.execute(...)` on the same execution thread is likewise rejected. It does not silently join `PROPAGATION_REQUIRED`, start an independent `REQUIRES_NEW` transaction, or introduce savepoints. Participants share the original explicit session rather than starting a second application transaction. A later participation mode, if needed by a production use case, requires a separately reviewed contract.

The infrastructure creates the transaction when the operation executes, not during bean construction or request creation. A session is valid only within its callback and on its owning thread. Use after completion, escape to a different thread, or concurrent use of the same session is rejected deterministically, including if no database operation is subsequently attempted. Different top-level operations may run concurrently with independent sessions and physical transactions; no application-wide serialization lock is added. A callback is synchronous and must not detach work whose completion or use of transaction state outlives the operation.

No ordinary application service, REST controller, scheduled-job adapter or event ingress listener owns a conflicting `@Transactional` boundary. The Spring implementation owns finalization through `TransactionTemplate` or an equivalently verified Spring transaction mechanism and obtains the configured transaction manager through DI. Transaction-aware jOOQ/JPA/publishing adapters must use transaction-associated resources from that same manager and database; the transaction port must not create a second `DataSource`, connection pool or commit authority. Compatibility with actual mixed persistence adapters and the Spring Modulith publication registry is validated as those consumers are introduced.

## Integration-test transaction contract (P1.3-B)

Test support is a **test-only DI integration** of the same application-facing `Transactions` port, not a second production transaction API or a custom database transaction simulator. Its two modes must be selected explicitly and documented, so a test never mistakes a logical success for a physically committed result.

### Rollback-isolated mode

Spring TestContext owns one enclosing test-managed transaction for each test method and rolls it back after the method completes, including when an assertion or operation fails. The test DI configuration supplies a participating implementation of `Transactions` that joins **only that existing test transaction**. Unlike the production implementation, it deliberately permits the test's ambient transaction; this exception to top-level ownership must never be registered in production.

The supported happy path is one or more application operations returning **COMMIT**. The result represents a **logical commit inside the enclosing test transaction**, not a physical commit. Subsequent operations and assertions in the same test thread see the writes through the shared transaction. On test completion, none of the test's changes persist. The helper must fail if the required test transaction is absent rather than silently falling back to a real commit.

**ROLLBACK**, **ABSTAIN**, or rollback-only within a shared test transaction cannot be modeled as an independent operation rollback without also affecting earlier test changes. The initial mode therefore **fails fast for such outcomes**, marks the test transaction rollback-only where appropriate, and does not return a misleading independent rollback or commit result. Tests whose central assertion depends on those outcomes must use the **production adapter in real-commit mode**. Test support does not introduce per-operation savepoints, nested transactions or Hibernate persistence-context restoration to disguise this limitation. Read-only application operations that return **ABSTAIN** are likewise outside the initial shared-test-transaction mode; state assertions can query the database through test-side adapters in that transaction.

This mode is for synchronous, in-process application/persistence integration tests. The test-managed transaction is thread-bound; asynchronous workers, detached tasks, `REQUIRES_NEW` transactions, separate connections that do not enlist, and requests handled on other threads/processes are **not covered** by its rollback guarantee. Such flows use real-commit mode. Avoid preemptive test timeouts that execute the test body outside the test transaction's thread.

### Real-commit mode

Real-commit tests use the **unmodified production `Transactions` implementation** and do not wrap the test body in a test-managed transaction. Application **COMMIT** means physical completion; **ROLLBACK**, **ABSTAIN**, rollback-only, ambient-transaction rejection, commit-time failures, cross-transaction visibility and post-commit integrations are tested here.

State isolation is explicit: use a disposable database or schema/fixture where feasible, or provision a controlled reset strategy for tests that share state. Tests with conflicting shared fixtures may run serially; independently provisioned real-commit tests may still run in parallel. Opting out is a test-configuration choice, not an implicit global switch or a `@Commit` override on a rollback-isolated test. Test code must not rely on an earlier test's commits.

### Fixtures and parallel test execution

P1.2 owns the PostgreSQL instance, Flyway-managed schema and base integration fixtures. P1.3-B consumes that infrastructure through DI and provides only the transaction-mode wiring and minimal reusable test conventions needed by this task. A deterministic committed baseline is established before each applicable test suite or isolated database; test-specific data is added inside each test's rollback transaction. The fixture scope and reset mechanism must prevent persistent cross-test state, including after exceptions.

Rollback-isolated tests are eligible for parallel execution with **independent test transactions/connections** and disjoint mutable keys or records. They must not concurrently update shared baseline rows or assume isolation prevents row-lock contention, uniqueness conflicts, advisory locks or other inter-test interference. PostgreSQL sequence values are not rolled back; tests must not depend on contiguous or reset sequence numbers. Real-commit tests using the same mutable database state are serialized or use isolated fixtures. No global static transaction state or shared mutable singleton test session is introduced.

Tests should inspect actual persisted rows and invariants inside their transactions rather than recording hypothetical SQL writes. A general change recorder would need to account for JPA flushes, database triggers, generated values and SQL mutations; it is deliberately **out of scope**.

## Acceptance criteria and verification

Each test is implemented at the lowest level that proves the stated behavior. Unit/contract tests do not need a database; Spring integration tests prove wiring and scope management; **PostgreSQL integration tests** prove actual transactional and concurrency guarantees. Missing PostgreSQL infrastructure is reported explicitly rather than silently replaced with H2 or a fake and counted as passing.

### Production contract

| ID | Scenario and expected evidence |
| --- | --- |
| **TX-01** | Two writes in one operation with **COMMIT** persist atomically and are visible in a separate, later transaction only after execution returns. |
| **TX-02** | An operation with writes and **ROLLBACK**, or **ABSTAIN** after incidental writes, persists none of them and returns its typed result after rollback. |
| **TX-03** | A typed expected failure accompanied by **COMMIT** persists an intentional failure-side effect when the transaction remains healthy. |
| **TX-04** | A participating port marks rollback-only; a later **COMMIT** request fails closed and cannot persist writes or return normal success. |
| **TX-05** | An exception during the callback rolls back, while a simulated/real commit-time failure propagates without an apparent successful commit. A connection-loss test does not assume the physical outcome is known. |
| **TX-06** | Null/invalid operation outcomes, expired sessions, cross-thread session access and attempts to reuse a session after completion are rejected. |
| **TX-07** | A nested invocation or ambient Spring transaction is rejected before starting a competing business transaction. |
| **TX-08** | Concurrent independent operations have distinct sessions and correct commit/rollback isolation; a shared session cannot be used concurrently. |
| **TX-09** | Spring DI selects the configured transaction manager; participating adapters use its physical transaction and no application-core API exposes Spring/JDBC/JPA types. |

### Test integration

| ID | Scenario and expected evidence |
| --- | --- |
| **TX-T01** | A rollback-isolated test calls multiple successful application operations; later operations and direct database assertions see earlier changes, but a subsequent independent transaction sees none after test teardown. |
| **TX-T02** | Rollback-isolated cleanup still occurs after a failing assertion/exception; absent test transaction or accidental production-mode fallback is detected. |
| **TX-T03** | Two parallel tests using independent transactions and disjoint fixtures cannot observe each other's uncommitted writes and leave the same committed baseline afterward. |
| **TX-T04** | **ROLLBACK**, **ABSTAIN** and rollback-only in the shared test transaction are rejected/poisoned as documented rather than falsely presented as isolated operation rollbacks. |
| **TX-T05** | Real-commit mode performs physical commits visible on a different connection; rollback, poisoned and commit-time failure cases use the production adapter. |
| **TX-T06** | An integration scenario dependent on transaction completion or an after-commit listener is verified in real-commit mode; rollback-isolated mode makes no such guarantee. |
| **TX-T07** | Fixture setup and teardown are deterministic across repeated runs, and nontransactional/thread-escaping operations are not incorrectly described as rollback-isolated. |

The PostgreSQL-backed subset is an explicit **integration acceptance gate**, not a prerequisite to building the technology-independent API and test wiring. Until P1.2 makes PostgreSQL available, initial tests may validate the contract and Spring wiring independently, with PostgreSQL-specific tests pending and accurately reported. Completion of the full Phase 1 gate requires the combined verification on the actual configured database.

## Scope limits and handoff

P1.3 does not implement Finance's group-local serialization/revision protocol, IAM refresh rotation, Modulith publication registry/inboxes, inter-service transactions, retries, savepoints, transaction change capture, a generic fixture/data-reset framework, or asynchronous transaction propagation. Those belong to later business or infrastructure tasks. This specification precedes a separate, reviewed `plan.md`; implementation phases and commit boundaries belong in that plan, not here.

**Implementation-dependent details to settle in the plan:** final package placement after P1.1, precise exception types and session API ergonomics, the test-specific Spring bean wiring and the minimal PostgreSQL fixture integration supplied by P1.2. These choices must preserve the guarantees above without introducing parallel transaction managers or changing the concurrent team's database bootstrap.
