# Backend testing

This guide is for contributors adding or running ShareWise backend tests. The [test architecture](../architecture/tests.md) defines what each test suite can prove; this guide covers how to select a suite, use its fixtures and diagnose failures. All commands run from `src/backend/sharewise` unless stated otherwise.

## Choose the test boundary

| Test | Name and base class | Typical use |
| --- | --- | --- |
| **Unit** | `*Test` or `*Tests`; ordinary JUnit test | Domain and application logic with mocked/fake outbound ports; validation; lightweight Spring wiring or MockMvc without external dependencies. |
| **Integration** | `*IT`, normally `extends IntegrationTestBase` | Real PostgreSQL adapters, service collaboration, transaction leases and uncommitted SQL state in a **mandatory rollback** transaction. |
| **E2E** | `*E2E`, normally `extends E2ETestBase` | Real commits, independent-connection visibility, multi-segment workflows, migration/role checks or HTTP scenarios against a disposable database. |

The `@ComponentTest` convention denotes a lightweight Spring test **within Unit**. The future Smoke / system-level E2E category has no runner or naming convention yet. A test of a "workflow" is not automatically E2E: classify it by whether it requires physical commits or separately deployed infrastructure.

If a test needs real SQL execution, it is not a Unit test. If it requires more than one logical `Transactions.execute(...)` segment or proof that a write actually committed, it belongs in E2E rather than Integration. See the [operation-scope](../architecture/dependency-injection.md) and [transaction](../architecture/transactions.md) contracts for production behavior.

## Run the suites

```bash
cd src/backend/sharewise
./mvnw -B -ntp -Punit-tests clean verify
./mvnw -B -ntp -Pintegration-tests clean verify
./mvnw -B -ntp -Pe2e-tests clean verify
```

To run a focused class instead of a full suite, use Surefire's `-Dtest` or Failsafe's `-Dit.test` selector:

```bash
./mvnw -B -ntp -Punit-tests -Dtest=ServiceScopeTests test
./mvnw -B -ntp -Pintegration-tests -Dit.test=PostgresRollbackOperationIT verify
./mvnw -B -ntp -Pe2e-tests -Dit.test=PostgresDatabaseLifecycleE2E verify
```

Maven Surefire selects the Unit `*Test`/`*Tests` classes; Failsafe selects `*IT` or `*E2E` under the corresponding PostgreSQL profile. Each database-backed profile skips Unit execution; the Unit profile skips the database-backed suites. Plain `./mvnw clean verify` runs all three when PostgreSQL is available. The three selectors are implemented in the backend `pom.xml`, not separate Maven modules.

### Bounded parallel execution

All backend suites use one JUnit worker by default, so `./mvnw clean verify` retains sequential test execution. Set the Maven property `testParallelism` to a positive integer to use the same bounded JUnit worker count for Unit, Integration and E2E tests without changing Maven forks or enabling parallel module builds:

```bash
./mvnw -B -ntp clean verify                          # sequential (default: 1)
./mvnw -B -ntp -DtestParallelism=2 clean verify       # all three suites, 2 workers each
./mvnw -B -ntp -Pe2e-tests -DtestParallelism=3 verify # E2E only, 3 workers
```

JUnit runs Unit test classes concurrently, but keeps the methods within each unannotated class sequential. Tests extending `IntegrationTestBase` or `E2ETestBase` explicitly permit method-level concurrency; both use JUnit's default per-method test instances. Individual classes or methods can override the inherited setting with `@Execution(ExecutionMode.SAME_THREAD)` when they depend on shared mutable state, ordering, or other non-isolated resources. The pool size is bounded per test runner rather than globally across Maven or CI jobs.

When component tests import the same mutable Spring fixture, give them a shared JUnit resource lock (or independent fixture instances) rather than relying on sequential execution. Run affected suites at CI worker counts to expose context-cache and fixture-lifecycle races.

Integration methods share one migrated database and Spring application context, but each operation owns an independent rollback-only physical transaction, operation scope and connection. Their concurrency contract requires disjoint write keys, no cross-test modification of committed fixture state, and no mutable application singletons shared between test methods. The existing Integration tests follow these constraints, including the dedicated concurrent-rollback test. New tests that cannot meet them must opt out of concurrent execution or use a separate E2E fixture. E2Es generally own a database and Spring context per method; tests that create additional contexts still need to account for server and JVM-global resources.

Spring Boot's per-context logging initialization reconfigures a JVM-global Logback instance and is not safe when test-created contexts start simultaneously. The test runners disable Boot's logging-system initialization and use a static test-only Logback configuration at WARN level, with startup banners disabled. This preserves failure diagnostics without interleaving framework startup banners, and does not affect production logging. Disposable E2E contexts use a test-only Hikari pool of at most three connections with no minimum idle connections; this bounds connection demand when many contexts share one PostgreSQL server while leaving the shared Integration context sized for concurrent rollback operations. The limit also applies to the Spring TestContext-managed E2E fixtures, and does not change production datasource settings. Parallel PostgreSQL suites still increase startup, migration and database load; tune the worker count against the actual server rather than assuming that more workers are always faster. GitLab CI uses 16 JUnit workers per backend Unit, Integration and E2E job, independently of how many GitLab runners execute jobs. The default unparameterized local build remains sequential.

An offline development environment can run the Unit gate with cached Maven dependencies:

```bash
./mvnw -B -ntp -o -Punit-tests clean verify
```

Use `-Dmaven.repo.local=/path/to/cache` when the supplied offline handoff is unpacked into an isolated Maven repository. The Unit gate requires neither PostgreSQL nor a Docker daemon. A successful Unit run does **not** validate the PostgreSQL suites.

Test reports are written to `target/surefire-reports/` (Unit) and `target/failsafe-reports/` (Integration/E2E). If a database-backed suite cannot connect to PostgreSQL, it fails; no H2 fallback or implicit skip exists.

## PostgreSQL prerequisites

The dev container supplies a PostgreSQL 18 service at `postgres:5432` through `.devcontainer/docker-compose.yml`. GitLab CI supplies `postgres:18-trixie` as a separate job service. Both use the same test provisioner over JDBC; neither requires Testcontainers, Docker-in-Docker or a Docker socket inside the Maven job.

The test fixture reads these environment variables (the defaults are for the supplied development environment):

| Variable | Default | Meaning |
| --- | --- | --- |
| `SHAREWISE_TEST_DB_URL` | `jdbc:postgresql://postgres:5432/postgres` | **Administrative** JDBC endpoint with an existing database in the URL; not the disposable test database. |
| `SHAREWISE_TEST_DB_ADMIN_USER` | `postgres` | Server account permitted to create/drop fixture databases and bootstrap roles. |
| `SHAREWISE_TEST_DB_ADMIN_PASSWORD` | `postgres_dev` | Administrative password. |
| `SHAREWISE_TEST_DB_MIGRATOR_PASSWORD` | `sharewise_migrator_dev` | Password for the migrator role created by P1.2 bootstrap. |
| `SHAREWISE_TEST_DB_APPLICATION_PASSWORD` | `sharewise_app_dev` | Password for the runtime application role created by P1.2 bootstrap. |

The bootstrap SQL establishes the fixed role names `sharewise_migrator` and `sharewise_app` and their **development** passwords. Nondefault role passwords must match **already provisioned** roles; changing only the environment variables does not change the bootstrap SQL. Do not use the development defaults on a shared or production server. The administrator needs privileges to create databases and roles; a restricted role that cannot do so will fail during fixture setup.

Each fixture creates a uniquely named `sharewise_it_*` database, executes P1.2's cluster-role and database-grant bootstrap, applies Flyway as the migrator and starts a production-configured application context using the application role. Application connections cannot perform migrations or access the Flyway history. After the scenario, the context is closed before the database is dropped. The Integration suite does this once per test launcher/fork; E2E does it once per test. The fixture database is **not** the persistent development database `sharewise`.

For local execution, start the dev container's PostgreSQL service and run the commands above inside its `dev` container. When running Maven outside that network, set `SHAREWISE_TEST_DB_URL` to a PostgreSQL 18 endpoint reachable from the **Maven process**, along with matching credentials. A separately launched Testcontainers-managed PostgreSQL instance can provide such an endpoint, but the current fixture does not start Testcontainers itself. The ordinary development datasource variables (`SPRING_DATASOURCE_*`, `SPRING_FLYWAY_*`) configure application startup, not which PostgreSQL server the **test provisioner** contacts.

## Author tests

### Unit: directly constructed collaborators and narrow Spring tests

Prefer ordinary JUnit tests with constructor-injected fakes or Mockito mocks at genuine outbound boundaries. Avoid a full production `@SpringBootTest` merely to exercise one business rule. For component wiring or MVC tests, a lightweight Spring context is acceptable if it remains fully offline. The existing `@ComponentTest` convention covers these cases.

Production components use their ordinary Spring stereotypes. Test-only fixtures use `@TestConfiguration` or `@TestComponent` and must be explicitly imported by the test that owns them. Avoid `@Configuration`/`@Component` for test fixtures and avoid broad ad hoc `@ComponentScan`; the shared lightweight scan configurations provide the intended infrastructure boundaries. `TestComponentDiscoveryConventionTests` guards this rule. Because `@TestConfiguration` is meta-annotated with `@TestComponent`, it appears in bean-factory discovery queries when a test explicitly imports it. Discovery assertions must distinguish those expected fixtures from accidentally scanned components.

### Integration: one rollback-owned operation

Extend `IntegrationTestBase`. Its JUnit extension shares the migrated database and Spring context for the suite. The inherited helpers each establish a **fresh physical transaction followed by a fresh operation scope**; the transaction is rolled back even if an assertion or callback fails. Application services still own their own logical `Transactions.execute(...)` segment.

```java
class SomePersistenceIT extends IntegrationTestBase {

    @Test
    void recordsTheRequestedDecision() {
        runTransactionTest((transactions, scope) -> {
            String result = transactions.execute(() -> TransactionOutcome.commit("ok"));
            assertEquals("ok", result);
            assertEquals(TransactionDecision.COMMIT, scope.observation().requestedDecision());
        });
    }
}
```

This example tests the **logical decision**, not a physical commit. Import JUnit's `@Test` and `assertEquals`, along with the production `TransactionDecision`/`TransactionOutcome` types. For ordinary application service tests, prefer `runServiceTest(MyService.class, (service, scope) -> { ... })`; it resolves the service through the real operation scope without starting a logical segment on the service's behalf. `runIntegrationTest(scope -> { ... })` is available when the test needs to resolve several collaborators.

After a logical segment completes, `scope.observation()` exposes its decision, result and failure. To inspect pending rows, use `scope.inspect(inspector -> inspector.queryForObject(...))`. The inspector reads through the original Spring-bound transaction and sees **flushed** SQL; JPA writes must be flushed explicitly within the active logical segment. Application `JooqAccess`/`JpaAccess` leases expire at logical completion and are not substitutes for the inspector.

An Integration operation permits one logical segment. It must not commit seed changes, mutate shared schemas or roles, depend on transactional rollback of sequence values, or escape into asynchronous external work. Tests running concurrently must use disjoint write keys and avoid conflicting locks. Specialized tests of the transaction infrastructure may use `IntegrationTestRunner` directly instead of an inherited helper.

### E2E: a database and context owned by each test

Extend `E2ETestBase`, declare `@Tag("e2e")` on the concrete test class and use its callback-owned lifecycle. For application tests, select `ProductionEntityScanConfiguration` explicitly so Hibernate validates only context-owned production entities:

```java
@Tag("e2e")
class SomeApplicationE2E extends E2ETestBase {

    @Test
    void startsWithAnIsolatedDatabase() {
        runTest(fixture -> {
            assertNotNull(fixture.context());
            try (Connection connection = fixture.connectAsApplication()) {
                assertTrue(connection.isValid(2));
            }
        }, ProductionEntityScanConfiguration.class);
    }
}
```

The E2E example uses JUnit's `@Tag`, `@Test`, `assertNotNull` and `assertTrue`, `java.sql.Connection` and `testsupport.postgres.ProductionEntityScanConfiguration`. The helper creates, migrates, configures and closes the fixture, including on assertion failure. `runServiceTest(MyService.class, (service, fixture) -> { ... })` resolves an application service inside a production operation scope while allowing it to commit real physical transactions. `runTestWithSchema(ddl, ...)` is for infrastructure tests requiring additional schema objects, not normal application tests; normal production tables come from Flyway migrations. Tests that need JPA probe entities select their own entity scan and matching schema setup. Without a constrained scan, Hibernate can discover probe entities on the test classpath whose tables do not exist in a normal migrated database.

Use `fixture.connectAsApplication()` for assertions from an independent connection after a real commit or rollback. For legacy infrastructure tests, the base class also supports `start(...)` in `@BeforeEach` and closes the fixture in `@AfterEach`. Tests intentionally inspecting provisioning or failure during startup may use the lower-level `IntegrationTestContext` and `TestDatabase` APIs directly. The fixture's PostgreSQL-specific implementation is `PostgresTestDatabase` when server administration or PostgreSQL semantics are the subject.

The higher-order callbacks allow checked exceptions without adding `throws` declarations to each test. They preserve assertion failures, wrap unexpected exceptions as assertion failures with the original cause and suppressed cleanup failures, and retain thread interruption. The test must still assert **expected** failures explicitly; the callback wrapper is not a substitute for `assertThrows`.

### Authenticated callers

Tests bind callers in one of two ways, depending on whether HTTP ingress is part of the subject. Service-level tests import `IdentityTestConfiguration` and wrap each dispatch in `IdentityTestFixture.withUser(...)`. The fixture supplies `IdentityContext` directly, bypassing credential verification. HTTP tests instead exercise the real API security chain and bearer ingress. They import `TestAccessTokenConfiguration` and send `Authorization: Bearer` headers from `TestAccessTokens`. The configuration publishes the fixture codec as IAM's primary codec, so production code verifies the test-signed tokens and derives `IdentityContext` from them. `TestAccessTokens` also issues expired and wrong-profile tokens for rejection tests. An HTTP test must not import `IdentityTestConfiguration`, because its primary `IdentityContext` would replace the bearer-derived identity. Import `TestAccessTokenConfiguration` only into contexts with IAM authentication disabled, because IAM's authentication configuration defines the primary codec when enabled. HTTP E2E scenarios start the servlet application with `IntegrationTestContext.startServlet(...)`, as `GroupHttpE2E` does, and send requests to its random port.

## CI and diagnostics

`.gitlab-ci.yml` first runs source-only checks in `pre-build`: tracked-source encoding checks (the same checker used by the opt-in Git pre-commit hook), frontend lint and application/test typechecks, GitLab SAST and secret detection, offline-handoff tooling tests, CI image-version/registry-contract tests, and the production deployment/Nginx regression suite. Both `build-backend` and `build-frontend` then gate the shared `test` stage containing backend Unit, frontend Vitest and static-export verification. Backend Unit gates Integration through `needs`, and Integration gates E2E; those PostgreSQL suites can overlap independent test jobs, but image publishing waits for every preceding stage. GitLab security analyzers retain their template-defined failure policies. Each backend suite recompiles its own sources instead of downloading the packaged JAR, and publishes Surefire/Failsafe JUnit reports, including on failure. Both PostgreSQL jobs have their **own** `postgres:18-trixie` service and wait for its TCP port before running Maven. The suite fixture still owns database creation and Flyway migration; GitLab owns only the server process. `build-backend` packages the application without running the tests.

The `test-deployment-contract` job runs `python3 -m unittest discover -s deploy/tests -p 'test_*.py' -v` against the production Nginx version, installing Python, OpenSSL, Bash and Git and requiring `nginx`, `envsubst` and `tar` as well. The job fails if prerequisites are missing instead of silently skipping the Nginx/TLS tests. Docker and PostgreSQL integration remain independent acceptance checks: these source-level tests use mock Docker clients and a disposable HTTP backend, not a live Compose stack. Their success gates image publication but does not prove production deployment.

Image version construction is tested by `test-image-version` in every pipeline's `pre-build` stage, using `sh scripts/ci/test-construct-image-version.sh` from the repository root. The registry scripts are separately exercised without a live registry by `test-image-registry-contract` (`python3 -m unittest discover -s scripts/ci/tests -p 'test_image_registry.py' -v`, requiring `jq`). Only protected `main` pushes and protected `dev-<slug>` Git tag pushes in the authoritative GitLab project construct an image version. `main` uses `0.1.<CI_PIPELINE_IID>`; `dev-demo` uses `0.1.<CI_PIPELINE_IID>-demo`. Ordinary feature pushes, merge requests, unrelated tags, schedules, API and manual pipelines do not publish images.

The image pipeline starts **after all existing build, test and security gates**. Kaniko, running without Docker-in-Docker or a daemon socket, publishes versioned backend and frontend images to separate GitLab Container Registry paths. An unused-tag preflight rejects known collisions; the publishing jobs share the version artifact but build independently. The release job re-queries the registry, fails on registry-client errors even when a command emits parseable output, verifies both digest references and OCI version/revision labels, and writes a single `image-release/release.json` artifact only for a complete matching pair. The main-only `latest` promotion is serialized and rejects outdated pipelines; a partially completed promotion is visible as a job failure and can be retried. Neither versioned pushes nor the `latest` updates are cross-repository atomic. Future deployment tooling must use the **paired digest-qualified references** in the descriptor rather than `latest`.

**Registry policy is part of the contract.** Protect Git tags matching `dev-*` with restricted creation rights. Registry-enforced immutable container tags are *optional hardening*, not required for this project's GitLab tier/configuration. If available, a rule such as `^0\.1\.[0-9]+(-[a-z0-9-]+)?$` can protect generated versions while leaving `latest` mutable. Without that enforcement, preflight rejects tags already present when checked, but concurrent pushes or retries can still overwrite them; the paired **digest-qualified references** in `image-release/release.json` identify verified content, not a guarantee that content will be retained forever. Review the project's registry cleanup/retention rules to keep earlier digests pullable for rollback. CI artifact expiration does not guarantee registry retention.

Kaniko builds with `--reproducible`, which removes image creation timestamps; GitLab may display an invalid historical publication date for these images. Use the GitLab pipeline/job timestamp as the publication record rather than the registry UI date.

The protected `dev-ci-smoke-2` preview pipeline has verified Kaniko publishing and paired registry validation on the university runner. Protected `main`/`latest` promotion, pulling both images by digest on an acceptance host, and retention of earlier digests after subsequent releases remain separate unverified acceptance cases. To inspect a successful release, download the `image-release/release.json` job artifact and read its `.images.backend.ref` and `.images.frontend.ref` properties. Both are fully qualified registry references using `@sha256:` and can be passed directly to `docker pull` after registry login. See the [P2.6 implementation plan](../internal/tasks/p2.6-production-packaging/plan.md) for observed evidence and outstanding gates.

When a suite fails, inspect the **first concrete cause** in its Surefire/Failsafe report before secondary `ApplicationContext failure threshold` messages. Connection failures generally indicate the wrong hostname/network, missing service or incorrect administrator credentials. Bootstrap/grant failures indicate insufficient cluster privileges or role-password mismatches. Migration errors should be traced to the migrator-role connection and Flyway output. A failed context or test may also leave teardown errors as suppressed causes; database-drop failures should be treated as fixture cleanup failures, not ignored.

## Operational acceptance

Run these checks against a **disposable PostgreSQL 18 server** using the credentials above. The repository’s `PostgresDatabaseLifecycleE2E` exercises two concurrently owned databases, independent committed state, parallel setup and cleanup after preparation/startup failure. `PostgresRollbackOperationIT` exercises physical rollback and pending-state inspection.

1. **Establish the baseline.** Confirm that PostgreSQL is reachable from Maven and record any pre-existing fixture database names with an administrative connection:

    ```sql
    SELECT datname FROM pg_database
    WHERE datname LIKE 'sharewise_it_%'
    ORDER BY datname;
    ```

2. **Run rollback isolation.** Execute `./mvnw -B -ntp -Pintegration-tests clean verify`. Check the Failsafe report for executed `*IT` tests and verify that each operation’s requested outcome and pending-state assertions succeed without committing test writes.
3. **Run disposable-database scenarios.** Execute `./mvnw -B -ntp -Pe2e-tests clean verify`. In particular, verify `PostgresDatabaseLifecycleE2E` passes: two distinct migrated databases see independent committed state, and the failure-path tests release their owned databases.
4. **Confirm cleanup.** Query `pg_database` again. No new `sharewise_it_*` database should remain after either suite, including on exceptional paths. Investigate any leftover name using its associated test log; **never run an indiscriminate DROP against all matching databases**. Repeat the three CI gates in GitLab and inspect their independently published JUnit reports.

The baseline comparison matters on a shared development server: tests must remove only databases they created, not another job’s or developer’s fixtures. If another suite is running concurrently, wait until it completes before interpreting a remaining name as a leak.

P1.5's [PostgreSQL transaction evidence](../internal/tasks/p1.5-postgresql-transaction-gate/plan.md#tx-03-through-tx-11-evidence-matrix) establishes the production transaction contract using the real-commit E2E suite; the rollback-only Integration harness cannot substitute for those tests. Future messaging and system-level smoke acceptance remain separate feature gates.
