# Persistence technology alternatives

**Status:** Rationale for the [preliminary persistence architecture](persistence.md). This document records why the alternatives below were not selected; it does not offer an interchangeable set of persistence technologies. Reconsidering a decision requires a concrete integration or correctness reason and an explicit design review.

The selected approach is Flyway-managed PostgreSQL with **jOOQ for SQL-oriented adapters** and **Spring Data JPA/Hibernate for adapters that benefit from managed entity lifecycles**. The choice is deliberate at the use-case-oriented adapter boundary, not arbitrary per query. In particular, jOOQ lets adapters express filtering, projections, joins and mutations through a typed SQL DSL while delegating dialect-specific SQL rendering to the library. We prefer this separation over embedding handwritten, vendor-specific query strings in the DAL. It does **not** make PostgreSQL-specific capabilities or transaction semantics portable, and Flyway migrations intentionally describe the PostgreSQL schema in SQL.

## Database access

| Alternative | Why it was not selected |
| --- | --- |
| Spring JDBC (`JdbcClient`) as the SQL-access standard | It simplifies JDBC connection handling, parameters and result mapping, but queries remain handwritten SQL. That couples DAL query definitions to vendor syntax and loses jOOQ's generated schema types, composable DSL and dialect rendering. jOOQ is the selected abstraction for SQL-oriented adapters, rather than maintaining a parallel raw-SQL style. |
| Spring Data JDBC | Its aggregate mapping can simplify basic persistence, but complex projections and conditional updates still require SQL strings or separate query logic. It adds another mapping model without providing the SQL composability and dialect separation sought for SQL-oriented adapters, or the managed-entity lifecycle available from JPA. |
| MyBatis | SQL mapping is explicit, but the SQL is primarily authored as vendor-sensitive statements rather than built with a schema-typed, dialect-rendering DSL. Its mapping advantages do not justify a parallel DAL technology alongside the selected jOOQ/JPA combination. |
| Reactive database access (R2DBC) | The application uses imperative Spring MVC services and a JDBC-backed transaction model. Reactive database access would introduce a separate execution and transaction model, complicating adapter composition without a demonstrated requirement for nonblocking database I/O. |
| JPA/Hibernate for every adapter | Managed entities are useful when identity tracking, relationships or cascades matter. For targeted projections, conditional writes and immutable financial history, an ORM-centric approach adds persistence-context behavior and less direct SQL composition where jOOQ better expresses the operation. |
| jOOQ for every adapter | jOOQ also supports simple CRUD, but requiring it everywhere would forgo JPA's automatic dirty checking and relationship lifecycle handling even where these materially simplify an adapter. The decision is **one primary technology per adapter according to its responsibilities**, not a uniform technology across the application. |

## Schema management and verification

| Alternative | Why it was not selected |
| --- | --- |
| Liquibase | Its changelog formats, diffs and abstraction features do not provide a compelling benefit over Flyway's straightforward, reviewed SQL migrations. The PostgreSQL schema is intentionally explicit; database dialect abstraction is valued in **DAL query composition**, not at the cost of obscuring physical schema definitions. |
| Hibernate schema generation or automatic updates | ORM-derived schema mutation would compete with Flyway as the source of truth, bypass reviewed migration history and make data-preserving evolution harder to control. Hibernate validates its mappings against the migrated database instead. |
| H2 or SQLite as the persistence-correctness baseline | Their SQL dialects, locking, isolation and constraint behavior differ from PostgreSQL. They cannot verify the financial revision protocol or PostgreSQL-specific queries; database integration and concurrency tests therefore use PostgreSQL, while database-independent unit tests use mocks or fakes. |
| Generating jOOQ types from parsed migration SQL alone | A parser or emulated schema may interpret PostgreSQL-specific DDL differently from the live database. Code generation from the **migrated PostgreSQL schema** is the reference; a script-based offline generator is acceptable only after its output has been verified against that reference. |

The selected libraries can participate in the same Spring-managed transaction, but that interoperability is not a reason to mix them casually within one adapter or make them competing writers of the same managed tables. Changes to the stack remain subject to review against the [persistence contracts](persistence.md), especially schema ownership, transaction correctness and database-independent unit testing.
