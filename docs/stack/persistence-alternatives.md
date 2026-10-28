# Persistence technology alternatives

**Status:** Supporting rationale for the [preliminary persistence architecture](persistence.md), not a second implementation specification. The primary decision is to use PostgreSQL with SQL-first Flyway migrations, with jOOQ or Spring Data JPA/Hibernate chosen per use-case-oriented adapter.

## Database access

| Alternative | Decision rationale |
| --- | --- |
| Spring JDBC (`JdbcClient`) | Remains available for small, direct SQL operations; jOOQ is preferred where schema-typed queries and composability provide value. No separate Spring JDBC adapter standard is necessary. |
| Spring Data JDBC | Its simpler aggregate persistence model is viable, but adding a third mapping style has no demonstrated advantage for the intended adapters. |
| MyBatis | Explicit SQL mapping is capable, but duplicates much of the use-case space covered by jOOQ without its schema-derived DSL. |
| Reactive access (R2DBC) | Not selected for an imperative Spring MVC/JDBC modulith. It would introduce a distinct transaction and database-access model without a demonstrated need. |
| JPA/Hibernate for all adapters | Convenient entity lifecycle handling, but less direct for SQL-heavy projections, group-scoped conditional writes and immutable financial history. |
| jOOQ for all adapters | Feasible, including simple generated-record CRUD, but there is no need to discard managed-entity mapping where it materially simplifies a particular adapter. |

## Schema management and verification

| Alternative | Decision rationale |
| --- | --- |
| Liquibase | Suitable migration tool with changelog and diff features; Flyway's reviewed versioned SQL model is sufficient and keeps the PostgreSQL schema explicit. |
| Hibernate automatic schema creation/update | Not selected as authoritative: it would create a second path for schema mutation and could change physical structures without a reviewed migration. Mapping validation remains useful. |
| H2 or SQLite as the database-correctness baseline | Fast and convenient for limited tests, but their locking, isolation, SQL dialect and constraints cannot establish PostgreSQL financial correctness. |
| jOOQ generation from parsed migration SQL | May enable fully offline source generation, but PostgreSQL-specific DDL interpretation must be verified against generation from the actual migrated database. |

The split between jOOQ and Hibernate is not a hard rule per bounded context. What matters is predictable table ownership, operation-oriented adapters, one coordinated transaction when mixing libraries, and explicit synchronization of Hibernate's managed state with direct SQL when necessary. Reconsider individual choices in response to measured integration cost or correctness constraints, rather than requiring uniformity for its own sake.
