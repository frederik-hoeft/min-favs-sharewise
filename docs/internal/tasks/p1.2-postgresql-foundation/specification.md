# P1.2 – PostgreSQL and migration foundation

**Status:** Specification for P1.2, revised after the first team review (MR !10) and aligned with P1.3b (MR !16). Covers the P0.3 database-contract decisions required by P1.2 and the P1.2 implementation scope. Implemented in MR !10; see the [implementation plan](plan.md) for phases and verification.

**Related documents:** [persistence architecture](../../../architecture/persistence.md), [application architecture](../../../architecture.md), [development guide](../../../development.md), [backlog](../backlog.md) (P0.3, P1.2).

## Goal

ShareWise replaces the starter's H2 database with **PostgreSQL** as its authoritative store. **Flyway** SQL migrations define the schema, each bounded context owns a dedicated PostgreSQL schema, and the application connects with a **least-privilege application role** from the start. Every developer, test run and CI job uses the same PostgreSQL major version. Unit tests stay database-independent; integration tests run against real PostgreSQL. The result is the persistence foundation on which P1.3 (transactions) and the first feature slices build.

## Architectural solution

### Database and runtime topology

| Environment | PostgreSQL provisioning | Role bootstrap |
| --- | --- | --- |
| Dev container | `postgres` service in `.devcontainer/docker-compose.yml`, named data volume, health check | Shared bootstrap script as container init script |
| Integration tests (local) | The dev-container `postgres` service; each test class creates and drops its own database | Test fixture runs the shared bootstrap scripts |
| CI (GWDG runner) | `postgres:18` GitLab CI service; the GWDG runner does not offer Docker-in-Docker | Fixture runs the shared bootstrap script against the service |
| Production / Compose deployment | Out of scope (P8.1) | Out of scope (P8.x) |

- **D1 – PostgreSQL version:** PostgreSQL **18**, pinned to the same major version in the dev container and the CI service.
- **D2 – Database roles:** Two roles are used from the start.
  - **Migration role** (for example `sharewise_migrator`): owns the context schemas and executes Flyway migrations (DDL).
  - **Application role** (for example `sharewise_app`): used by the application's runtime connection. It has `USAGE` on the context schemas and `SELECT`, `INSERT`, `UPDATE`, `DELETE` on their tables and sequences, granted through default privileges so that tables created by later migrations are covered automatically. It cannot create, alter or drop database objects (including temporary tables: `TEMPORARY` is revoked from `PUBLIC`) and has no access to the Flyway history schema.
  - Roles are cluster-level objects and are created by idempotent **bootstrap SQL scripts** (`01-create-roles.sql` per server, `02-grant-database-access.sql` per database) shared by the dev container, the integration-test fixture and CI. Flyway migrations create schemas and grants but not login roles.
- **D3 – Connection configuration:** The runtime datasource reads `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME` and `SPRING_DATASOURCE_PASSWORD` (application role). Flyway uses separate credentials (`SPRING_FLYWAY_USER`, `SPRING_FLYWAY_PASSWORD`, migration role). Defaults target the dev-container database and are dev-only credentials. No production credentials are committed.

### Schema ownership

- **D4 – One schema per bounded context:** `identity`, `groups`, `finance`, `files`, `notifications`. A context creates tables only in its own schema. Flyway's history table lives in a separate infrastructure schema (`flyway`). Infrastructure tables owned by other designs (for example the Spring Modulith event-publication registry, see [messaging](../../../stack/messaging.md)) receive their schema in their own task (P2.4).
- **D5 – No cross-context constraints:** No foreign key, view, trigger or cascade references a table in another context's schema. This is verified by an automated integration test (TC-5), not only by review.
- **D6 – Identifiers:** Entity primary keys and cross-context references use the native PostgreSQL **`uuid`** type (16 bytes; no `bytea`/binary column needed). ID columns default to PostgreSQL 18's built-in **`uuidv7()`** (time-ordered, good index locality). Adapters may also supply an application-generated UUIDv7 when the ID must be known before insert, for example for integration events or idempotency keys. How JPA adapters obtain generated IDs (database default with insert `RETURNING`, or application-side generation) is decided with the first JPA entity (P2.x), since P1.2 introduces none. Finance revision-row keys are decided with P3.4/P4.

### Migrations

- **D7 – Single Flyway history in the modulith:** One Flyway configuration applies all migrations in version order at application startup, using the migration role. Extracted services later receive their own history.
- **D8 – Layout and naming:** `src/main/resources/db/migration/<context>/V<yyyyMMddHHmm>__<context>_<description>.sql`, for example `db/migration/groups/V202611051200__groups_create_group_tables.sql`. Cross-cutting migrations that belong to no single context (such as the baseline that creates all context schemas) live in `db/migration/platform/`. Timestamp versions avoid number collisions between parallel branches. A migration that is older than the newest migration on `main` at merge time is re-timestamped before merge; `outOfOrder` stays disabled.
- **D9 – Immutable once committed:** A migration is immutable **once it is committed and pushed** to the shared repository; later changes are follow-up migrations (roll forward). While a migration is still local work in progress, developers may edit or delete it and reset their local database. Flyway's checksum validation runs on every startup.
- **D10 – Baseline migration:** The first migration creates the five context schemas, the application role's schema grants and its default privileges. Business tables are introduced by the feature slices that own them (P2.x and later), not as placeholders.

### Hibernate / JPA

- **D11 – Schema validation only:** `spring.jpa.hibernate.ddl-auto=validate`. Hibernate never creates or alters tables. JPA mappings declare schema and table explicitly.
- **D12 – Open session in view disabled:** `spring.jpa.open-in-view=false`, so no database access happens during view rendering and transaction boundaries stay with the application layer. This setting is already introduced by P1.3b; P1.2 keeps it.

### jOOQ

- **D13 – jOOQ code generation deferred:** The jOOQ runtime (`spring-boot-starter-jooq`) is introduced by P1.3b for its transaction-bound `JooqAccess`. P1.2 does not add jOOQ **code generation**. Following the Phase 0 gate rule (*"no new framework is added solely for scaffolding"*), the code-generation workflow is introduced with the first adapter that queries generated tables (expected in Finance, P3/P4). The persistence architecture's code-generation requirements (pinned versions, offline compilation, CI freshness check) apply then.

### Tests

- **D14 – Test split:** `./mvnw test` runs unit tests and database-free Spring tests (including the P1.3a/P1.3b H2-based tests) and requires neither Docker nor PostgreSQL. PostgreSQL integration tests are named `*IT`, run in `./mvnw verify` (Maven Failsafe) and require real PostgreSQL. The existing full-context Spring tests (`ShareWiseApplicationTests`, `TransactionWebScopeIntegrationTests`) keep running in `./mvnw test` on an in-memory H2 database through a test-only configuration override with Flyway disabled; `ShareWiseApplicationPostgresIT` starts the same production context against PostgreSQL.
- **D15 – PostgreSQL test provisioning:** A reusable test fixture (`PostgresTestDatabase`) connects to an **external PostgreSQL 18 server** with administrative credentials: the dev-container `postgres` service by default, or the server configured via `SHAREWISE_TEST_DB_URL`, `SHAREWISE_TEST_DB_ADMIN_USER` and `SHAREWISE_TEST_DB_ADMIN_PASSWORD` (CI service). It runs the cluster-wide role bootstrap, creates a disposable database per test class, grants database access, optionally applies the Flyway migrations as the migration role, and drops the database afterwards. Because roles are cluster-level but schemas, grants and default privileges are per database, the same routine serves any later per-fixture database. Local tests and CI use the same mechanism, so neither depends on Testcontainers or Docker-in-Docker. Rollback-isolated harnesses, parallel execution and PostgreSQL transaction-contract evidence remain with P1.3c/P1.5.
- **D16 – No silent fallback:** If PostgreSQL is unavailable, integration tests fail with a clear message. They never pass via H2 or mocks.
- **D17 – Docker-outside-of-Docker in the dev container:** The dev container receives access to the host's Docker engine through the `docker-outside-of-docker` Dev Container feature. P1.2's tests do not need it (D15); it remains available for later container-based tooling such as Testcontainers in P1.3c.

### Developer tooling and dependencies

- **D18 – PostgreSQL extension:** The dev container installs the `ms-ossdata.vscode-pgsql` VS Code extension as a visual client for inspecting the database and running test queries.
- Remove `spring-boot-h2console` and remove `h2` from the runtime classpath. P1.3b's H2-based transaction tests (`TransactionsIntegrationTests`, `RelationalTransactionIntegrationTests`, `JooqAccessIntegrationTests`, `JpaAccessIntegrationTests`) configure their own in-memory datasource, so `h2` remains a **test-scope** dependency until P1.3c moves that coverage to PostgreSQL. Add the PostgreSQL JDBC driver, Flyway (core + PostgreSQL module) and the Maven Failsafe plugin for `*IT` tests. Exact artifact names and versions follow the Spring Boot 4.1 dependency management and are confirmed in the plan.
- The offline dependency handoff is a local convenience for the ChatGPT workflow. After this MR is merged, a short FYI in the team Discord announces the dependency change so that members using that workflow regenerate their handoff archives.

## Scope

**In scope:** D1–D18; dev-container PostgreSQL and tooling; role bootstrap; datasource, Flyway and Hibernate configuration; baseline schema migration; integration-test fixture and initial integration tests; CI execution of integration tests via a PostgreSQL service; documentation updates (development guide, persistence architecture, stack overview status, backlog).

**Out of scope:** jOOQ (D13); the `Transactions`/`TransactionSession` abstraction (P1.3); business tables and adapters (feature slices); event-publication registry (P2.4); production credentials, secret management, backups and Compose/Nginx deployment (P8.x); financial history representation (P3.4/P4).

## Invariants

1. The application never starts with a schema that differs from the applied migrations for mapped entities (Hibernate validation, Flyway validation).
2. Every context's tables live in exactly one context-owned schema; no database object couples two context schemas.
3. The application's runtime connection cannot execute DDL; only the migration role changes the schema.
4. Unit tests are executable without Docker, PostgreSQL or network access.
5. Integration tests either run against real PostgreSQL 18 or fail visibly.
6. Committed and pushed migrations are never edited.

## Failure behavior

| Situation | Expected behavior |
| --- | --- |
| Database unreachable at startup | Application startup fails with a datasource connection error; no fallback database |
| Pending migration fails | Startup fails; Flyway reports the failing script; PostgreSQL rolls back the failed migration's DDL transaction |
| Committed migration was edited | Startup fails on Flyway checksum validation |
| JPA mapping does not match schema | Startup fails on Hibernate schema validation |
| Application code attempts DDL | PostgreSQL rejects it with a permission error |
| Docker / PostgreSQL unavailable for integration tests | `./mvnw verify` fails with a missing-infrastructure message; `./mvnw test` still passes |

## Acceptance criteria and test cases

| ID | Type | Setup | Action | Expected result |
| --- | --- | --- | --- | --- |
| TC-1 | Manual E2E | Fresh dev container (rebuild) | Start backend and frontend, request `/api/system/v1/health` through the frontend origin | App starts against PostgreSQL with the application role, Flyway applies the baseline with the migration role, and the proxied health request returns `200` / `UP` |
| TC-2 | Build | Machine without Docker / PostgreSQL | `./mvnw test` | Succeeds |
| TC-3 | Integration | Empty PostgreSQL 18 | Run bootstrap and migrations through the fixture | Schemas `identity`, `groups`, `finance`, `files`, `notifications` and the Flyway history exist |
| TC-4 | Integration | Bootstrapped empty database | Application context starts with Flyway enabled and `ddl-auto=validate`, scanning production entity packages only | Context loads; Flyway applied the baseline as migrator; runtime connection is `sharewise_app` |
| TC-5 | Integration | Migrated database | Query `pg_constraint` for foreign keys whose referencing and referenced tables are in different schemas | Result is empty |
| TC-6 | Integration | No reachable PostgreSQL server | `./mvnw verify` | Fails with a clear infrastructure message, not a pass |
| TC-7 | Build | — | `./mvnw dependency:tree` | `h2` appears only in test scope; the packaged application contains no H2 |
| TC-8 | CI | Merge request pipeline on the GWDG runner | Run backend job | Unit and integration tests run and pass against the `postgres:18` CI service |
| TC-9 | Manual | Dev container with applied baseline | Edit the applied baseline migration locally, restart backend | Startup fails on checksum validation (then revert) |
| TC-10 | Integration | Migrated database, application-role connection | Attempt `CREATE TABLE` in a context schema and a read of the Flyway history | Both are rejected with permission errors |
| TC-11 | Integration | Migrated database, application-role connection | Migration role creates a test table in a context schema; application role inserts and selects a row | Succeeds through default privileges |
| TC-12 | Integration | Migrated database | Insert a row without an ID into a table whose ID column defaults to `uuidv7()` | A version-7 UUID is generated |

TC-12 applies once a test table or the first business table exists; the fixture may create a temporary test table for it.

## Resolved review questions

| ID | Question | Decision |
| --- | --- | --- |
| Q1 | Approve schema per context, identifiers, migration naming and jOOQ deferral? | Approved; identifiers revised to native `uuid` with `uuidv7()` default (D6) |
| Q2 | Does the GWDG runner support Docker-in-Docker for Testcontainers? | No; CI uses a `postgres:18` service, and local tests use the dev-container database through the same fixture (D15) |
| Q3 | Separate migration and application roles already in development? | Yes; least-privilege application role from the start (D2, D3) |
| Q4 | Who regenerates the offline handoff after the POM change? | Local ChatGPT-workflow concern; Discord FYI after merge |
