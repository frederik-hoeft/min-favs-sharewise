# Persistence: implementation status and planned adapters

**Status:** The PostgreSQL persistence foundation is implemented. [Persistence architecture](../architecture/persistence.md) is authoritative for the current database topology, migration privileges and transaction-bound jOOQ/JPA access. The [technology selection rationale](persistence-alternatives.md) records the alternatives considered.

The backend uses PostgreSQL 18, Flyway-managed context schemas, separate migration and application roles, and operation-scoped jOOQ/JPA accessors. [Transaction management](../architecture/transactions.md) owns physical segment completion; [backend testing](../architecture/tests.md) distinguishes rollback-isolated Integration tests from real-commit E2E tests.

## Planned persistence work

**Context-owned persistence adapters.** Feature slices will introduce their own business tables, SQL migrations, ports and use-case-oriented adapters. jOOQ is the selected tool for explicit SQL queries, projections and conditional mutations; JPA/Hibernate is available where managed entity identity and relationship lifecycles are useful. Vendor-specific records, entities and persistence facades remain inside adapters; application contracts expose domain values and materialized projections. A table should normally have one primary writer technology within an operation.

**Database-first jOOQ generation.** IAM has an opt-in `jooq-codegen-identity` Maven profile that generates schema-typed jOOQ sources from its *Flyway-migrated PostgreSQL schema*, using matching generator/runtime versions. The generated sources are committed under the IAM persistence adapter for normal offline compilation without PostgreSQL or the generator plugin. Other contexts can adopt the same approach when they introduce tables. CI freshness and regeneration checks are planned but not yet implemented; runtime builds do not automatically regenerate or validate the schema against PostgreSQL. Generating from parsed SQL alone is only an optional aid if verified against actual PostgreSQL output.

**Feature-specific consistency.** Financial history, group revision/locking semantics and durable publication intent require their own tables, adapters and behavioral tests. Their intended contracts are maintained in the [domain model](../data-model-design.md) and [messaging design](messaging.md); their presence must not be inferred from the database foundation.

Developer setup, credentials, Flyway paths and test commands are in the [development guide](../development.md#database) and [backend testing guide](../development/tests.md).
