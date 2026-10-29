# Persistence architecture

**Status:** Preliminary architecture decision, subject to review and refinement during integration. This describes the intended persistence system, not the currently implemented starter. The [application architecture](../architecture.md) defines module and transaction boundaries; the [domain model](../data-model-design.md) defines business invariants and financial history.

**Contents:**

<!-- @import "[TOC]" {cmd="toc" depthFrom=2 depthTo=6 orderedList=false} -->

<!-- code_chunk_output -->

- [Storage and ownership](#storage-and-ownership)
- [Schema lifecycle](#schema-lifecycle)
- [Data access adapters](#data-access-adapters)
- [Transactions and failure behavior](#transactions-and-failure-behavior)
- [Testing and development independence](#testing-and-development-independence)
- [Design refinement](#design-refinement)

<!-- /code_chunk_output -->



## Storage and ownership

**PostgreSQL is ShareWise's authoritative transactional database.** The modular monolith initially uses one database deployment, but each bounded context owns its data, migrations, persistence adapters, and outbound contracts. Physical colocation does not permit cross-context table access, joins, foreign keys, cascades, or business transactions. Other contexts consume published operations or integration events, allowing database ownership to move with a context when it is extracted into a service.

The persistence stack assigns distinct responsibilities:

| Responsibility | Technology | Role |
| --- | --- | --- |
| Durable transactional data | PostgreSQL | SQL execution, constraints, locking, isolation and persistence |
| Schema evolution | Flyway | Ordered, versioned, reviewed SQL migrations and migration history |
| SQL-centric access | jOOQ | Schema-typed, composable queries and explicit mutations |
| Entity-centric access | Spring Data JPA / Hibernate | Entity mapping, relationships and managed lifecycle where useful |
| Connection and transaction lifecycle | Spring JDBC `DataSource` and transaction management | One coordinated local database transaction per operation |
| Tests | JUnit 5, Mockito/fakes, PostgreSQL/Testcontainers | Independent unit tests and database-backed correctness checks |

jOOQ and JPA/Hibernate are alternative implementation choices **inside a persistence adapter**, not competing definitions of the database model. An adapter normally selects one primary technology according to its use cases. A bounded context may contain adapters using either technology without exposing either library through its published API.

## Schema lifecycle

ShareWise follows a **migration-driven, database-first** workflow. Flyway SQL migrations are the authoritative description of physical tables, names, types, nullability, constraints, defaults, indexes and other database objects. Flyway applies outstanding migrations in version order and records their checksums and outcomes in schema history. Applied versioned migrations are immutable; further changes are new migrations, including data transformations when necessary.

Schema evolution follows this sequence:

1. Write and review the SQL migration owned by the affected bounded context, including safe changes to existing data.
2. Apply all relevant migrations to a PostgreSQL database and verify the resulting schema.
3. Generate jOOQ schema types from the **migrated PostgreSQL database**. Keep generated records and tables private to persistence infrastructure and the context that owns the data.
4. Align affected JPA entity mappings explicitly with the physical schema. Hibernate validates mapped tables/columns against the migrated schema; it does not create or update the authoritative schema.
5. Validate generation freshness, entity compatibility and database behavior in CI, including upgrades from representative earlier database states when applicable.

jOOQ code generation uses compatible, pinned generator/runtime versions and deterministic configuration. Normal compilation and unit testing must not depend on a live database: use checked-in generated sources or another verified offline strategy. CI independently regenerates against migrated PostgreSQL and fails on stale generated code. A SQL-script-based jOOQ generator may simplify offline development, but it must be checked against actual PostgreSQL results before becoming authoritative.

JPA mappings use explicit schema/table/column and relevant type configuration rather than relying on implicit naming to establish correctness. Hibernate schema validation checks mapped structures, **not every index, constraint, trigger, default or domain invariant**. Flyway's validation checks migration history, not arbitrary out-of-band schema modifications. Real database tests must exercise the constraints and semantics on which the application depends.

The modulith can start with one ordered Flyway migration history while making context ownership discoverable through migration organization and database naming. A separate physical PostgreSQL schema per context is an option, not a requirement imposed by tooling. Extracted services receive their own migration lifecycle. Database changes that span deployments follow an expand-and-contract strategy; production migrations should run under appropriately restricted privileges rather than relying on broad schema permissions for normal application requests.

## Data access adapters

A persistence adapter is a cohesive, **use-case-oriented** implementation of application-owned outbound ports. It may provide related reads and mutations for a capability, rather than mirroring one database table or exposing an unbounded generic repository. Application services orchestrate business rules and authorization; adapters implement the specific database operations required to enforce and persist those decisions efficiently. Avoid interfaces or translation layers that have no meaningful boundary or purpose.

**jOOQ** suits explicit predicates, targeted DTO projections, ordering, aggregation, history reconstruction, database-side validation, and conditional updates. It composes SQL before execution; filtering and projection represented in its DSL are executed by PostgreSQL, not by later in-memory/offline Java stream operations. jOOQ-generated types are infrastructure details, never domain objects or published module contracts.

**Spring Data JPA/Hibernate** suits operations that benefit from managed entity identity, relationships, cascades, or change tracking. JPA entities stay in adapters. Use explicit operation-specific projections (Command, Query, and Result objects) for data retrieval when loading entire managed graphs would be wasteful; don't assume arbitrary Java collection transformations are SQL-translated. Hibernate entity mappings must be consistent with the Flyway-managed schema.

Both approaches return domain-oriented results or immutable read projections through ports, not live JPA entities, jOOQ records, or vendor internals. Keep queries efficient, filter DB-side, materialize only what's needed, and use database constraints as the authority for uniqueness and concurrency races. Application prechecks may improve error messages but cannot replace atomic database checks. Do not inadvertently put the same table under competing JPA/jOOQ writers within one operation to avoid stale reads from JPA/Hibernate caches.

## Transactions and failure behavior

The [application transaction abstraction](../architecture.md#transaction-aware-ports) owns the operation-scoped commit decision. Its opaque `TransactionSession` is passed to participating data ports, and its infrastructure implementation delegates to Spring transactions, for example via `TransactionTemplate`. Use Spring-managed connections and one compatible local transaction manager; do not open uncoordinated JDBC connections or let individual adapters independently commit.

Spring-configured jOOQ and JPA may participate in the same **physical PostgreSQL transaction** when they share the configured `DataSource` and transaction infrastructure. jOOQ executes SQL immediately but does not independently commit an active Spring transaction. Hibernate may defer SQL writes until flush and retains a first-level managed-entity cache. A direct jOOQ write does not update managed JPA objects, and jOOQ reads may not see unflushed JPA changes. Avoid mixed writers for a given table within one transaction; where mixing is genuinely necessary, explicitly manage flush/refresh and prove the behavior with an integration test.

The operation returns typed expected failures, such as not-found or optimistic-concurrency conflicts. Known database constraints may be translated into typed failures, but exceptions or transaction states that require rollback must never be converted into apparent commit success. A successful transaction outcome means the database commit has completed; commit-time failures remain observable. Never reuse a transaction session, JDBC connection or persistence context concurrently or beyond its operation lifetime.

Financial Management additionally serializes mutations per FinancialGroup, validates lifecycle and expected revisions, persists immutable record revisions with their ChangeRecord, and commits durable event-publication intent atomically. Historical queries reconstruct one consistent group-revision snapshot. These are [financial domain invariants](../data-model-design.md#finance-consistency-boundary), not properties implied by jOOQ, Hibernate or Flyway alone; PostgreSQL integration and concurrency tests must establish their correctness. Local transactions do not span distinct bounded contexts simply because their tables share a database.

## Testing and development independence

**Unit tests must run without a database, container runtime, network, or other external service.** Domain and application services are instantiated directly with Mockito mocks or in-memory fakes of their outbound ports. They test business decisions, validation, expected outcomes, and orchestration without creating a full Spring context or mocking `DSLContext`, `EntityManager` or JDBC internals. This keeps the main unit-test loop reproducible with the supplied JDK/Maven tooling and cached dependencies.

For workflow tests, Spring can substitute in-memory adapters and fake external services to exercise application wiring and paths offline. These are useful simulated integrations, **not proofs of database behavior**. Database integration tests run against actual PostgreSQL, ordinarily via Testcontainers, and cover migrated schemas, query projections, SQL constraints, transactional commit/rollback, JPA/jOOQ interoperability, financial write serialization and historical snapshot correctness. Such tests are explicitly identified; absence of PostgreSQL must not silently cause them to pass using mocks or H2.

A normal build should not require a running PostgreSQL server merely to compile or run unit tests. Schema regeneration and database integration validation are separate, reproducible checks; they can run without network access when the required tools and PostgreSQL/container assets have been provisioned locally. H2 may remain useful for narrow mapping checks but does not replace PostgreSQL where its SQL or concurrency semantics matter.

## Design refinement

The technology choices and code-generation workflow are preliminary and may change after integration review. Preserve the durable contracts: context-owned schemas and ports, migration-authoritative SQL, correct transaction finalization, explicit adapter responsibility, and infrastructure-independent unit tests. Current backend dependencies include JPA/H2, but PostgreSQL, Flyway, jOOQ, and the described validation pipeline are **not yet installed or implemented**.

The [technology alternatives](persistence-alternatives.md) are recorded separately from the intended architecture. Detailed Maven configuration, migration file conventions, local commands and CI implementation belong in the applicable developer/build documentation once introduced.
