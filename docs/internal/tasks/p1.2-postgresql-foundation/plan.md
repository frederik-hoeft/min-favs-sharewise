# P1.2 – Implementation plan

**Status:** Implemented in MR !10 (phases A–F). Implements the [P1.2 specification](specification.md); requirement IDs (D1–D18) and test cases (TC-1–TC-12) refer to it. A checked box means the phase is implemented, verified **and approved** in review.

**Starting point:** `main` **after P1.3b (MR !16) is merged**; the P1.2 branch is updated from `main` before Phase B. P1.3b adds `spring-boot-starter-jooq`, `spring.jpa.open-in-view=false`, the `Transactions`/`JooqAccess`/`JpaAccess` infrastructure and H2-based transaction tests with their own in-memory datasources. The P1.3a/P1.3b tests stay database-free and must keep passing in `./mvnw test`. Only `ShareWiseApplicationTests` (`@SpringBootTest`, production context) needs PostgreSQL once H2 leaves the runtime classpath.

**Coordination with P1.3c:** P1.2 delivers PostgreSQL provisioning, the role bootstrap and a reusable "migrate this database" routine (D15). Rollback harnesses, per-fixture database lifecycle and parallel execution are P1.3c. The test-support API introduced in Phase D is kept small so P1.3c can build on it.

## Phase A – PostgreSQL in the dev container

- [x] **Scope:** Add a `postgres:18` service to `.devcontainer/docker-compose.yml` with a named volume (PG 18 images store data under `/var/lib/postgresql`), a `pg_isready` health check, `POSTGRES_DB=sharewise` and dev-only admin credentials; the `dev` service waits for it to be healthy. Mount the bootstrap scripts as init scripts. Add the `docker-outside-of-docker` feature (D17) and the `ms-ossdata.vscode-pgsql` extension (D18) to `devcontainer.json`. The datasource and Flyway environment variables (D3) are added in Phase B together with the PostgreSQL driver, so the H2-based starter keeps working until then.
- **New files** in `src/backend/sharewise/src/test/resources/db/bootstrap/`, shared by dev container, tests and CI:
    - `01-create-roles.sql` – idempotent creation of `sharewise_migrator` and `sharewise_app` (cluster-level, once per server, D2).
    - `02-grant-database-access.sql` – `CONNECT`/`CREATE` for the migrator and `CONNECT` for the application role on the **current** database (once per database, so it also serves P1.3c's disposable databases).
- **Verification:** Rebuild the dev container; `docker ps` works inside it; the PostgreSQL extension connects to host `postgres` as both roles; the init scripts run only on an empty volume (documented reset: remove the `postgres-data` volume).
- **Gate:** Dev container starts cleanly with a healthy database and both roles.

## Phase B – Dependencies and configuration

- [x] **Scope:** Update the branch from `main` (after MR !16). In `pom.xml`, remove `spring-boot-h2console`; move `h2` to test scope (used by the P1.3a/P1.3b tests); add `spring-boot-starter-flyway`, `flyway-database-postgresql` and the PostgreSQL JDBC driver (runtime). Versions come from the Spring Boot 4.1 dependency management.
- In `application.properties`: datasource defaults for the dev-container database with the application role, overridable via `SPRING_DATASOURCE_*` (D3); Flyway credentials for the migration role, overridable via `SPRING_FLYWAY_USER`/`SPRING_FLYWAY_PASSWORD`; `spring.flyway.locations=classpath:db/migration` (sub-folders per context); Flyway history in schema `flyway`; `spring.jpa.hibernate.ddl-auto=validate` (D11); keep P1.3b's `spring.jpa.open-in-view=false` (D12). The dev defaults live in `application.properties` rather than in compose environment variables, so test configuration can override them.
- **Interim test configuration:** `src/test/resources/config/application.properties` points the database-free Spring tests (including the full-context `ShareWiseApplicationTests` and `TransactionWebScopeIntegrationTests`) at an in-memory H2 database with Flyway disabled, so `./mvnw test` keeps running them without PostgreSQL. Moving these tests to PostgreSQL is part of the PostgreSQL test provisioning (Phase D / P1.3c).
- **Tests:** TC-2 (`./mvnw test` without Docker/PostgreSQL, all existing tests green), TC-7 (`h2` only in test scope).
- **Gate:** Unit build green; dependency changes reviewed.

## Phase C – Baseline migration

- [x] **Scope:** `db/migration/platform/V202611131000__platform_create_context_schemas.sql` (D8, D10): create schemas `identity`, `groups`, `finance`, `files`, `notifications`; grant `USAGE` on them to `sharewise_app`; default privileges for tables (`SELECT, INSERT, UPDATE, DELETE`) and sequences (`USAGE, SELECT`) created by `sharewise_migrator`; no grants on schema `flyway`.
- **Tests:** TC-1 (manual: backend starts in the dev container, Flyway applies the baseline as migrator, greeting flow works), TC-9 (manual: edited committed migration fails checksum validation). Role permissions (TC-10, TC-11) were checked manually against PostgreSQL with `psql`; automated coverage follows with the PostgreSQL test provisioning.
- **Gate:** Application runs against PostgreSQL with the application role.

## Phase D – Integration-test provisioning and tests

- [x] **Scope:** Test support `PostgresTestDatabase` in `src/test/java/.../shared/infra/persistence/testing/` (D15):
    - Server: the dev-container `postgres` service by default; another server via `SHAREWISE_TEST_DB_URL`, `SHAREWISE_TEST_DB_ADMIN_USER` and `SHAREWISE_TEST_DB_ADMIN_PASSWORD` (CI).
    - Bootstrap: `01-create-roles.sql` per server (idempotent), a disposable `sharewise_it_*` database per test class, `02-grant-database-access.sql` in that database, dropped again after the class.
    - `createMigrated()` applies the Flyway migrations as the migration role; `createBootstrapped()` leaves migration to the application under test.
    - Clear `IllegalStateException` when no server is reachable (D16).
- `maven-failsafe-plugin` runs `*IT` classes in `./mvnw verify`; `./mvnw test` stays database-free.
- **Integration tests:** `BaselineMigrationIT` (TC-3 schemas and owners, TC-5 no cross-schema foreign keys, TC-10 no DDL and no Flyway-history access for the application role, TC-11 default privileges, TC-12 `uuidv7()` keys), `ShareWiseApplicationPostgresIT` (TC-4: production context on PostgreSQL with `ddl-auto=validate` over production entity packages, Flyway applied as migrator, runtime connection as `sharewise_app`, `Transactions` + jOOQ against PostgreSQL 18).
- **Verification:** TC-6 by running `./mvnw verify` with the PostgreSQL service stopped.
- **Gate:** `./mvnw verify` green in the dev container.

## Phase E – CI

- [x] **Scope:** In `.gitlab-ci.yml`, give `build-backend` a `postgres:18` service (alias `postgres`) with admin credentials and the `SHAREWISE_TEST_DB_*` variables (Q2: no Docker-in-Docker on the GWDG runner). `./mvnw clean verify` runs Surefire and Failsafe.
- **Tests:** TC-8 (merge request pipeline green with integration tests against the CI service).
- **Gate:** Green pipeline on the GWDG runner.

## Phase F – Documentation and close-out

- [x] **Scope:** Reconcile steady-state docs: `docs/development.md` (new "Database" section: service, roles, migrations, overrides, inspection, reset, H2 test configuration; separate-container note), `docs/stack/persistence.md` and `docs/stack/overview.md` (implementation status).
- **Manual acceptance:** TC-1 on the rebuilt dev container (application starts against PostgreSQL 18 as `sharewise_app`, Flyway creates the five context schemas and `flyway` as `sharewise_migrator`); TC-2 (`./mvnw test` green without PostgreSQL); `./mvnw verify` green with the `*IT` tests; TC-8 green pipeline.
- **After merge:** Discord FYI about the dependency changes (offline handoff).
- **Gate:** Docs describe the implemented state; MR approved and merged.
