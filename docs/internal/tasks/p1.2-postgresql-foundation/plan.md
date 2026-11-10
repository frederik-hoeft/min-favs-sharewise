# P1.2 – Implementation plan

**Status:** Draft plan for review. Implements the [P1.2 specification](specification.md); requirement IDs (D1–D18) and test cases (TC-1–TC-12) refer to it. A checked box means the phase is implemented, verified **and approved** in review.

**Starting point:** `main` **after P1.3b (MR !16) is merged**; the P1.2 branch is updated from `main` before Phase B. P1.3b adds `spring-boot-starter-jooq`, `spring.jpa.open-in-view=false`, the `Transactions`/`JooqAccess`/`JpaAccess` infrastructure and H2-based transaction tests with their own in-memory datasources. The P1.3a/P1.3b tests stay database-free and must keep passing in `./mvnw test`. Only `ShareWiseApplicationTests` (`@SpringBootTest`, production context) needs PostgreSQL once H2 leaves the runtime classpath.

**Coordination with P1.3c:** P1.2 delivers PostgreSQL provisioning, the role bootstrap and a reusable "migrate this database" routine (D15). Rollback harnesses, per-fixture database lifecycle and parallel execution are P1.3c. The test-support API introduced in Phase D is kept small so P1.3c can build on it.

## Phase A – PostgreSQL in the dev container

- [ ] **Scope:** Add a `postgres:18` service to `.devcontainer/docker-compose.yml` with a named volume (PG 18 images store data under `/var/lib/postgresql`), a `pg_isready` health check, `POSTGRES_DB=sharewise` and dev-only admin credentials. Mount the role bootstrap script as an init script. Add the `docker-outside-of-docker` feature (D17) and the `ms-ossdata.vscode-pgsql` extension (D18) to `devcontainer.json`. Set dev datasource and Flyway environment variables on the `dev` service (D3).
- **New files:** `src/backend/sharewise/src/test/resources/db/bootstrap/01-create-roles.sql` – idempotent creation of `sharewise_migrator` and `sharewise_app` (cluster-level, D2); grant of `CREATE` on the `sharewise` database to the migrator. Shared by dev container, tests and CI.
- **Verification:** Rebuild the dev container; `docker ps` works inside it; the PostgreSQL extension connects as both roles; the init script runs only on an empty volume (documented reset: remove the volume).
- **Gate:** Dev container starts cleanly with a healthy database and both roles.

## Phase B – Dependencies and configuration

- [ ] **Scope:** Update the branch from `main` (after MR !16). In `pom.xml`, remove `spring-boot-h2console`, change `h2` to test scope (used by the P1.3b transaction tests); add the PostgreSQL JDBC driver (runtime), Spring Boot's Flyway starter and `flyway-database-postgresql`; add Spring Boot Testcontainers support and Testcontainers PostgreSQL (test scope); configure Maven Failsafe for `*IT` classes (D14). Artifact names and versions are taken from the Spring Boot 4.1 dependency management and verified with `./mvnw dependency:tree`.
- In `application.properties`: datasource from `SPRING_DATASOURCE_*` with dev-container defaults (application role); Flyway credentials from `SPRING_FLYWAY_USER`/`SPRING_FLYWAY_PASSWORD` (migration role); `spring.flyway.locations=classpath:db/migration` (sub-folders per context); Flyway history in schema `flyway`; `spring.jpa.hibernate.ddl-auto=validate` (D11); keep P1.3b's `spring.jpa.open-in-view=false` (D12).
- Rename `ShareWiseApplicationTests` to `ShareWiseApplicationIT` (including the P1.3b checks it contains) so `./mvnw test` no longer needs a database; it runs against PostgreSQL in Phase D.
- **Tests:** TC-2 (`./mvnw test` without Docker/PostgreSQL, including the P1.3a DI tests), TC-7 (`h2` only in test scope).
- **Gate:** Unit build green offline; dependency changes reviewed.

## Phase C – Baseline migration

- [ ] **Scope:** `db/migration/platform/V<timestamp>__platform_create_context_schemas.sql` (D8, D10): create schemas `identity`, `groups`, `finance`, `files`, `notifications`; grant `USAGE` on them to `sharewise_app`; set default privileges for tables (`SELECT, INSERT, UPDATE, DELETE`) and sequences (`USAGE, SELECT`) created by `sharewise_migrator`; no grants on schema `flyway`.
- **Tests:** TC-1 (manual: backend starts in the dev container, Flyway applies the baseline as migrator, greeting flow works), TC-9 (manual: edited committed migration fails checksum validation).
- **Gate:** Application runs against PostgreSQL with the application role.

## Phase D – Integration-test provisioning and tests

- [ ] **Scope:** Test support in `src/test/java/.../shared/infra/persistence/testing/` (name to be aligned with P1.3c):
    - Server provisioning: PostgreSQL 18 Testcontainer by default; external server when `SHAREWISE_TEST_DB_URL` and admin credentials are set (CI).
    - Bootstrap: run `01-create-roles.sql` once per server.
    - `migrate(database)`: run Flyway with the migrator role against a given database and expose application-role connection properties (D15). P1.3c later calls this for each disposable database.
    - Clear failure when neither Docker nor an external URL is available (D16).
- **Integration tests (`*IT`):** `ShareWiseApplicationIT` (TC-4, plus P1.3b's transaction-manager and jOOQ checks against PostgreSQL), `BaselineMigrationIT` (TC-3, TC-5), `DatabaseRolePermissionsIT` (TC-10, TC-11, TC-12 using a temporary test table created by the migrator).
- **Verification:** TC-6 checked manually by running `./mvnw verify` with Docker stopped.
- **Gate:** `./mvnw verify` green in the dev container; test-support API reviewed with the P1.3c owner.

## Phase E – CI

- [ ] **Scope:** In `.gitlab-ci.yml`, give `build-backend` a `postgres:18` service with admin credentials and set `SHAREWISE_TEST_DB_URL` so the fixture uses the external server (Q2: no Docker-in-Docker on the GWDG runner). Ensure `./mvnw clean verify` runs both Surefire and Failsafe and reports which suites ran.
- **Tests:** TC-8 (merge request pipeline green with integration tests against the CI service).
- **Gate:** Green pipeline on the GWDG runner.

## Phase F – Documentation and close-out

- [ ] **Scope:** Reconcile steady-state docs: `docs/development.md` (database, roles, reset, `test` vs. `verify`, PostgreSQL extension; remove H2 references), `docs/stack/persistence.md` (schema per context, roles, `uuid`/`uuidv7()`, migration naming and immutability, jOOQ deferral), `docs/stack/overview.md` (implementation status), backlog (check P0.3 and P1.2).
- **Manual acceptance:** Repeat TC-1 on a freshly rebuilt dev container; record the result in the MR.
- **After merge:** Discord FYI about the dependency changes (offline handoff).
- **Gate:** Docs describe the implemented state; MR approved and merged.

## Open points for plan review

- Test naming: P1.3a uses `*IntegrationTests` for database-free Spring-context tests in Surefire. This plan uses `*IT` + Failsafe for PostgreSQL tests. Alternative: a JUnit tag (for example `@Tag("postgres")`), as P1.3c allows either.
- Package name for the test support (`shared.infra.persistence.testing` proposed).
