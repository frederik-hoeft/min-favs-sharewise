# Persistence: implementation status and planned adapters

**Status:** The PostgreSQL persistence foundation is implemented. [Persistence architecture](../architecture/persistence.md) is authoritative for the current database topology, migration privileges and transaction-bound jOOQ/JPA access. The [technology selection rationale](persistence-alternatives.md) records the alternatives considered.

The backend uses PostgreSQL 18, Flyway-managed context schemas, separate migration and application roles, and operation-scoped jOOQ/JPA accessors. [Transaction management](../architecture/transactions.md) owns physical segment completion; [backend testing](../architecture/tests.md) distinguishes rollback-isolated Integration tests from real-commit E2E tests.

## Planned persistence work

**Context-owned persistence adapters.** Feature slices will introduce their own business tables, SQL migrations, ports and use-case-oriented adapters. jOOQ is the selected tool for explicit SQL queries, projections and conditional mutations; JPA/Hibernate is available where managed entity identity and relationship lifecycles are useful. Vendor-specific records, entities and persistence facades remain inside adapters; application contracts expose domain values and materialized projections. A table should normally have one primary writer technology within an operation.

**Database-first jOOQ generation.** Extend the [existing schema-generation approach](../architecture/persistence.md#database-first-sql-metadata) to additional contexts as they introduce tables: a context adds an execution to the single generation profile, and the Integration gate's freshness check then covers its committed metadata. Ordinary builds compile the committed metadata without regenerating it. Generating from parsed SQL alone is only an optional aid if verified against actual PostgreSQL output.

**Feature-specific consistency.** Finance projection operations and financial revision coordination build on the [existing group-root lock](../architecture/persistence.md#group-root-provisioning-and-locking). Business operations reuse the same lookup readiness contract with the appropriate transaction or snapshot strategy; the [Phase 3 foundation](../internal/integration/phase-3-foundation.md) defines the event, readiness and actor revision rules. The [domain history contract](../data-model-design.md#financial-revisions-and-history) defines the financial write protocol, including lifecycle and revision preconditions, immutable revised state, journal atomicity and durable publication intent when required. The [messaging design](messaging.md) owns delivery and recovery, not business history.

Developer setup, credentials, Flyway paths and test commands are in the [development guide](../development.md#database) and [backend testing guide](../development/tests.md).
