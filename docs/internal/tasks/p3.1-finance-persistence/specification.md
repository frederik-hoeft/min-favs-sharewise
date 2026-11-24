# P3.1 - FinancialGroup and FinancialActor persistence/provisioning: specification

**Status:** Approved for P3.1 implementation; the maintainer selected the Finance-local PostgreSQL domains in the authoritative ERD. This task implements the persistence foundation of [P3.1a/b](../backlog.md#phase-3---finance-actor-synchronization-and-accounting-foundations), without implementing the application operations consuming that foundation. The maintainer has also approved the [shared Phase 3 contract](../../integration/phase-3-foundation.md) (P3.0).

## Architectural solution and scope

Finance owns the financial group lifecycle, group-local financial revision and retained actor identities. The existing platform migration creates the `finance` schema and configures the runtime role's default privileges; P3.1 adds the first Finance business tables and a transaction-participating FinancialGroup persistence port. The [Phase 3 foundation](../../integration/phase-3-foundation.md#p3-schema-reference-financegroups) is authoritative for the shared physical model and cross-team semantics. Neither the Group module nor its event types, publication registry, IAM data or membership attestations are required to implement this task.

P3.1a owns one Finance migration and the corresponding database-first jOOQ metadata, including the closed Finance Spring Modulith module declaration required for the generated Java package. P3.1b adds an internal root persistence port with a jOOQ adapter. Future projection operations (P3.2a) and financial revision coordination (P3.4) use the same root lock; they are not implemented here. There is no public Finance API, application service, event listener, actor persistence port, financial mutation, activation operation, revision allocator, backfill, currency field, journal, deletion workflow or frontend work in scope.

The internal port exposes immutable Finance-owned data, not generated records, SQL types or database handles. The adapter uses the existing operation-scoped `JooqAccess` and the caller's active `Transactions.execute(...)` segment, following the [persistence](../../../architecture/persistence.md), [DI](../../../architecture/dependency-injection.md) and [transaction](../../../architecture/transactions.md) architectures. Declare only the shared named-interface dependencies the Finance production code needs; keep the module's internal packages closed to other contexts. No additional transaction infrastructure or shared repository abstraction is needed.

## P3.1a - Schema requirements

The following requirements specialize the shared model for implementation; column provenance and future event semantics remain in the Phase 3 foundation.

| ID | Requirement |
| --- | --- |
| FIN-S01 | `finance.groups`: `id uuid PRIMARY KEY` without generated default; `state finance.group_state NOT NULL DEFAULT 'provisioning'`; `current_revision finance.bignatural0 NOT NULL DEFAULT 0`; `created_at timestamptz NOT NULL DEFAULT now()`. The Finance-owned enum has exactly `provisioning`, `open`, `closed`, `archived`. |
| FIN-S02 | `finance.actors`: `group_id uuid NOT NULL`, `user_id uuid NOT NULL`, composite PK `(group_id, user_id)`, non-cascading FK from `group_id` to `finance.groups(id)`; `deleted boolean NOT NULL` without default; `upstream_revision finance.bignatural1 NOT NULL` without default; `created_at` and `updated_at` both `timestamptz NOT NULL DEFAULT now()`. |
| FIN-S03 | A Finance-owned `BEFORE UPDATE` row trigger assigns `NEW.updated_at = now()`. The timestamp reflects transaction start, not commit order or upstream revision. Future consumers must avoid issuing an UPDATE for stale or duplicate membership facts; that consumer policy is not part of this task. |
| FIN-S04 | Use Finance-local PostgreSQL domains `bignatural0` (`bigint`, `CHECK (VALUE >= 0)`) and `bignatural1` (`bigint`, `CHECK (VALUE > 0)`), the specified Finance enum and the PK indexes. Grant runtime usage of the three Finance types explicitly rather than relying on PUBLIC privileges. Do not add cross-context FKs, a surrogate actor ID, redundant indexes, shared SQL domains, additional columns or tables. Upstream IDs are opaque to Finance; runtime callers need no Group/IAM SQL access. |
| FIN-S05 | Flyway applies an additive, Finance-owned migration under `db/migration/finance/` using the existing migration role and schema grants. Runtime DML uses `sharewise_app`; the app cannot create schema objects. Existing context data and historical migrations are unchanged. |
| FIN-S06 | Generate Finance jOOQ metadata from the migrated PostgreSQL schema, using an opt-in `jooq-codegen-finance` profile aligned with the existing identity profile's driver, generator, connection properties and output conventions. Commit generated sources in `finance/internal/persistence/generated` under the backend Java source root. Declare the closed Finance module with no outgoing application-module dependencies at this stage, so the generated package passes module verification. Ordinary builds must not run generation or require a database. No handwritten substitute for generated metadata. |

A new root defaults to provisioning and has no actors. The schema alone does not prohibit actors on a provisioning root or require actors on an open root. It stores the lifecycle but does not enforce future lifecycle transitions. Actor retention and source-version comparison are application invariants of P3.2, not SQL deletion restrictions or new triggers in P3.1.

## P3.1b - Root provisioning and locking contract

The port belongs to Finance's internal application-facing persistence boundary. The proposed contract uses these names and shapes (ordinary Java types; implementation may choose equivalent internal package organization):

```java
interface FinancialGroupStore {
    FinancialGroup ensureAndLock(UUID groupId);
    Optional<FinancialGroup> lockExisting(UUID groupId);
}

record FinancialGroup(UUID id, FinancialGroupState state, long currentRevision) {}

enum FinancialGroupState {
    PROVISIONING, OPEN, CLOSED, ARCHIVED
}
```

The interface is accessible to Finance-internal consumers only. The projection contains the root identity, lifecycle and current financial revision, not its diagnostic creation timestamp. All four persisted enum values map explicitly to their Finance-owned equivalents; unknown or invalid values fail rather than defaulting to a permitted state.

| ID | Requirement |
| --- | --- |
| FIN-R01 | `ensureAndLock` is reserved for operations processing trusted Group-originated facts. Its non-null group UUID identifies the root; it creates a default provisioning row if absent and returns the locked root. No actor, activation, revision increment or other side effect is implied. Trust establishment belongs to the future inbound operation, not the SQL adapter. |
| FIN-R02 | Repeated calls preserve all existing root columns, including state, revision and creation timestamp, regardless of lifecycle. Provisioning cannot reopen a closed or archived root. |
| FIN-R03 | `lockExisting` returns the locked root or `Optional.empty()` if absent; it never creates a row. A missing root has no row lock. Callers must not treat absence as authoritative Group nonexistence or permission to provision. Both methods reject null IDs. |
| FIN-R04 | Both methods require an active caller-owned physical transaction segment within an operation scope. The lock persists until physical transaction completion, not the adapter method's return. The adapter neither starts nor commits a segment and does not retain an executable query or facade across segments. Calls without a compatible segment fail before DML. |
| FIN-R05 | Competing operations on an existing or concurrently first-created root serialize on the same PostgreSQL row. Different roots do not share an application or table-wide serialization lock. Rollback undoes first insertion and releases the lock; the next trusted operation may provision anew. |
| FIN-R06 | `PROVISIONING` is a typed not-ready lifecycle state distinct from absence, `OPEN`, `CLOSED` and `ARCHIVED`. Future financial operations must reject provisioning with their typed not-ready application outcome before mutation, using the state read under lock. No universal `isWritable` policy, application operation/result hierarchy or artificial consumer is introduced in P3.1: closed/archived policy depends on the later operation. P3.1b verifies the typed state and absence contract; operation-level rejection tests belong to the consuming tasks. |
| FIN-R07 | Persistence failure, unexpected loss of the ensured row, or transaction/locking failure propagates to the transaction owner. Do not turn infrastructure errors into missing/not-ready results, swallow errors, commit partial work or retry independently of the caller's transaction. |

### Physical serialization protocol

Use the following two statements in order within the same segment for `ensureAndLock` (parameters are bound by jOOQ):

```sql
INSERT INTO finance.groups (id)
VALUES (:group_id)
ON CONFLICT (id) DO NOTHING;

SELECT id, state, current_revision
FROM finance.groups
WHERE id = :group_id
FOR UPDATE;
```

`lockExisting` uses only the second statement. Under PostgreSQL's default `READ COMMITTED` isolation, the unique index arbitrates concurrent inserts and the next statement obtains a fresh snapshot, allowing it to observe the committed winner. This avoids a same-statement conflict/visibility race and the row-version churn of a no-op `ON CONFLICT DO UPDATE`. An ensured row missing after the lock query violates the contract; there is no supported root hard-delete path. The protocol assumes `READ COMMITTED`; stronger isolation may instead fail with a serialization error requiring whole-transaction retry by a future caller. Do not change global isolation or introduce automatic retries here.

All subsequent group-local writes that need serialization acquire this lock before inspecting mutable root/actor state or allocating financial revisions. A returned projection is a snapshot at lock acquisition, not an independently synchronized object or permission grant. Consumers must maintain the lock across their entire relevant mutation in the same segment. No locks or transaction state escape via the port's return type.

## Verification and acceptance

Use the repository's [test architecture](../../../architecture/tests.md) and [test guide](../../../development/tests.md). PostgreSQL 18 is the authority for constraints, timestamp and locking semantics; no H2 or mocked SQL substitutes. Use `IntegrationTestBase` for rollback-isolated adapter behavior, `E2ETestBase` for real commits, independent transaction/connection visibility, role and migration tests, and Unit tests for meaningful pure behavior and Modulith verification. SQL errors abort a PostgreSQL transaction: isolate negative cases in separate transactions/tests rather than continuing in an aborted segment.

| Test | Requirements | Setup and action | Expected observation | Level |
| --- | --- | --- | --- | --- |
| FIN-T01 | S01–S05 | Migrate fresh database and upgrade a database with the prior platform/IAM migrations and representative existing data. Inspect catalog and Flyway history. | Exact Finance columns, enum labels, domains, constraints, defaults, indexes, trigger, ownership and absence of external FKs; existing data unaffected. | E2E |
| FIN-T02 | S01, S04 | Insert root using an arbitrary upstream UUID; query persisted row. Try missing/null ID, duplicate ID, negative/null revision, null/invalid state and null timestamp independently. Test revision zero and `Long.MAX_VALUE`. | Correct default state/revision/time; invalid rows rejected; both legal revision bounds accepted. No Group/IAM row required. | E2E |
| FIN-T03 | S02, S04 | Insert actor under a provisioning root using opaque user UUID; insert another group with same user. Try duplicate pair, orphan group, null/missing required columns, zero/negative revision; attempt to delete root with actors. | Per-group identity, explicit deleted state and positive revision (including `Long.MAX_VALUE`); invalid inserts and parent deletion rejected; no cascade or external identity requirement. | E2E |
| FIN-T04 | S03 | Insert actor, commit; in a later transaction update it with a deliberately supplied timestamp; inspect from another connection. | Defaults and trigger use their transaction's `now()`; `created_at` remains unchanged and trigger overrides supplied `updated_at`. No strict chronological ordering assertion. | E2E |
| FIN-T05 | S05 | Use the runtime role for Finance DML and attempt DDL separately; inspect migration ownership. | DML succeeds, runtime DDL is denied; migration objects owned by migrator. | E2E |
| FIN-T06 | S06, R04 | Generate from migrated DB; run ordinary Maven Unit build with generation disabled. | Checked-in generated metadata corresponds to migration; ordinary build and architecture verification require no database/codegen. | Generation review + Unit |
| FIN-T07 | R01–R03, R06 | Ensure unknown group; repeat ensure against seeded roots in each lifecycle with nonzero revision and known timestamp. Lock existing and absent IDs; test null IDs. | Default root, immutable typed snapshot, preserved persisted columns and no actors; absence distinct from provisioning, no insertion from lock-only lookup. | Integration |
| FIN-T08 | R04, R07 | Resolve adapter in operation scope with no active segment, invoke both methods; exercise transaction-owner rollback. | Segment guard rejects unsupported access before write; no implicit transaction. | Integration/E2E as needed |
| FIN-T09 | R01, R02, R05 | Start two independent operations/physical transactions against the same absent UUID; hold the first transaction after ensuring, start the second, then commit the first. | Second waits; both observe one root with the same identity and state, no duplicate or overwrite. | E2E |
| FIN-T10 | R04, R05 | Seed root; hold lock in first transaction after adapter returns; second tries locking same root. First changes a fixture revision under lock and commits. | Second cannot complete before first transaction ends; observes committed revision. | E2E |
| FIN-T11 | R05 | Hold root A lock while another operation ensures/locks root B and commits. | B completes without releasing A; no global serialization. | E2E |
| FIN-T12 | R05, R07 | First ensure on absent root is held while second attempts ensure; first changes a fixture revision under lock and rolls back. Also roll back an existing-root mutation and reacquire its lock. | No committed state from rolled-back transaction; waiting operation can create/lock a default revision-zero root and commit; existing root retains prior state and lock is released. | E2E |
| FIN-T13 | R04, R06 | Check actual Finance module via existing Modulith verification; exercise typed lifecycle mapping. | Module is closed, dependency whitelist is narrow and generated types do not leak into port; all lifecycle states map without implicit authorization. | Unit + Integration |

Concurrency cases use independent operations and database connections, explicit coordination and bounded waits, with cleanup in failure paths. Prefer observable PostgreSQL lock/wait evidence to sleeps or timing-only assertions. Include unexpected failure and boundary tests where the implementation warrants them. No test-only application operation may become a production API.

### Acceptance gate

P3.1 is accepted when the reviewed migration and generated sources match, the internal port and adapter meet the above contract, applicable Unit/Integration/E2E checks pass, and the authoritative architecture/developer docs describe only the resulting implemented behavior. The human runs the disposable deployment smoke and manually verifies migration/role access on a migrated database, including the provisioning default and orphan rejection using disposable test data. There is no UI workflow to exercise. Record the tested revision, commands, outcomes and limitations; independent review and human merge approval are mandatory under the [AI development workflow](../../../development/ai-development-workflow.md).

The offline workspace has no PostgreSQL server/container runtime or codegen plugin cache, so the human development environment supplies generated sources and PostgreSQL/deployment evidence. Passing offline Unit tests is not acceptance of persistence correctness. No task or plan completion is inferred from an unexecuted check.
