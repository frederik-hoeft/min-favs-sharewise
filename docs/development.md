# Development environment

**Status:** Setup guidance for the supplied starter. The repository contains a backend and frontend skeleton; architecture documents describe the intended system rather than features already implemented. For how the technologies fit together, start with the [technical stack overview](stack/overview.md).

## VS Code Dev Containers

A [Dev Container](https://code.visualstudio.com/docs/devcontainers/containers) is a repository-defined Docker development environment to which VS Code attaches. ShareWise's [`.devcontainer/`](../.devcontainer/devcontainer.json) configuration uses Docker Compose to provide a shared Linux environment with Java 25, Maven, Node.js 24 and project-relevant VS Code extensions. The repository is mounted at `/workspace`, and Maven/npm caches are kept in Docker volumes. This avoids requiring each developer to install and align language toolchains on the host.

To get started:

1. Install **Docker** and **VS Code** with Microsoft's **Dev Containers** extension, and start Docker.
2. Open the repository root in VS Code. From the Command Palette (`Ctrl+Shift+P`), select **Dev Containers: Reopen in Container**. VS Code builds and starts the configured container and runs its initialization steps; the initial setup requires network access to obtain images and dependencies.
3. Use VS Code's terminal inside the container to run the commands below. The backend and frontend ports (`8080` and `3000`) are forwarded to the host. After changing `.devcontainer/` configuration, run **Dev Containers: Rebuild Container**.

The source checkout remains on the host and survives container rebuilds. Tools or files installed only inside an individual container should not be treated as persistent project state.

## Current toolchain

The backend is a **Java 25 / Spring Boot 4.1.2 Maven** project under `src/backend/sharewise/`. The Maven wrapper requests Maven **3.10.0** and is configured as a script-only wrapper (it does not bundle Maven). The current backend dependencies include Spring MVC, JPA/Hibernate, jOOQ, the PostgreSQL JDBC driver, Flyway, operation-scoped DI and transaction management, Lombok and test starters; H2 is a test-only dependency. Spring Modulith and event publication remain architectural targets rather than installed backend features.

The frontend under `src/frontend/` uses **Next.js 16.4.1**, React **19.3.0**, TypeScript, npm, and a checked-in `package-lock.json`. Its server-side route handler and current container image belong to the starter. The [security architecture](stack/security.md) targets a static Next.js export served by Nginx with same-origin `/api/*` forwarding; that deployment is **not yet implemented**, and the commands below still describe the starter.

## Database

The application uses **PostgreSQL 18**. The dev container starts a `postgres` service next to the `dev` service (host `postgres`, port `5432`, database `sharewise`); it is reachable from the dev container and other compose services, not published on the host. On first start with an empty `postgres-data` volume, the scripts in `src/backend/sharewise/src/test/resources/db/bootstrap/` create two roles:

| Role | Used by | Permissions |
| --- | --- | --- |
| `sharewise_migrator` | Flyway at application startup | Owns the context schemas; creates and changes tables (DDL) |
| `sharewise_app` | Application runtime connection | Reads and writes data in the context schemas (DML); no DDL, no access to the Flyway history |

Flyway applies the migrations in `src/main/resources/db/migration/` at startup. Each bounded context owns one schema (`identity`, `groups`, `finance`, `files`, `notifications`); the Flyway history lives in schema `flyway`. Hibernate only validates its mappings (`spring.jpa.hibernate.ddl-auto=validate`). Migration files follow `db/migration/<context>/V<yyyyMMddHHmm>__<context>_<description>.sql` and are immutable once committed and pushed; local work-in-progress migrations may be edited after resetting the local database.

`application.properties` contains dev-only defaults for the dev-container database. Other environments override them with `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`, `SPRING_FLYWAY_USER` and `SPRING_FLYWAY_PASSWORD`. To inspect the database, use the PostgreSQL extension installed in the dev container (server `postgres`) or `docker exec sharewise_devcontainer-postgres-1 psql -U postgres -d sharewise`. To reset the local database, stop the dev container and remove the `sharewise_devcontainer_postgres-data` volume; the roles are recreated on the next start.

`./mvnw test` does not need PostgreSQL: the existing Spring tests use in-memory H2 through `src/test/resources/config/application.properties` (Flyway disabled, Hibernate `create-drop`). PostgreSQL-backed integration tests and their provisioning are introduced with [P1.3c](internal/tasks/p1.3c-integration-tests/specification.md).

## Build, test and run

Backend, from the repository root:

```sh
cd src/backend/sharewise
./mvnw test
./mvnw spring-boot:run
```

Frontend, in a separate terminal from the repository root:

```sh
cd src/frontend
npm ci
npm run lint
npm run build
npm run dev
```

The starter defaults to frontend port 3000 and backend port 8080. Consult the backend `pom.xml`, wrapper configuration, frontend `package-lock.json`, and frontend-specific `src/frontend/AGENTS.md` for authoritative build and dependency details. Use project-owned commands rather than modifying dependencies merely to work around a developer's environment.

## Current starter: separate-container execution

The Next.js `/api/greetings` route forwards server-side requests to the Spring Boot `/greeting` endpoint. `BACKEND_URL` is the **backend's base URL as seen from the frontend server**, not the address used by a browser. It defaults to `http://localhost:8080` for the devcontainer or two processes on the same host. When the backend and frontend run in separate containers, their loopback interfaces are different, so provide an address reachable over their shared Docker network. For example, after building the backend JAR:

```sh
cd src/backend/sharewise
./mvnw clean verify
cd ../../..
docker build -t sharewise-backend src/backend/sharewise
docker build -t sharewise-frontend src/frontend
docker network create sharewise
docker run -d --name sharewise-backend --network sharewise sharewise-backend
docker run --rm --name sharewise-frontend --network sharewise \
  -e BACKEND_URL=http://sharewise-backend:8080 \
  -p 3000:3000 sharewise-frontend
```

The frontend resolves `sharewise-backend` through Docker's network DNS and exposes the UI at `http://localhost:3000`. For a Compose or orchestrated deployment, set `BACKEND_URL` to the corresponding backend service DNS name instead. The variable is read by the Next.js server at runtime; it should not be a `NEXT_PUBLIC_` setting. The frontend runtime uses Next.js standalone output, with no full development dependency tree. Both production images run as non-root users. The backend container needs a reachable PostgreSQL database with the ShareWise roles: run a `postgres:18` container on the same network with the bootstrap scripts as init scripts and pass the `SPRING_DATASOURCE_*` and `SPRING_FLYWAY_*` variables to `sharewise-backend` (for example `SPRING_DATASOURCE_URL=jdbc:postgresql://<postgres-container>:5432/sharewise`). A Compose setup for this follows with P8.1.

## Operation scope usage

The backend provides an [explicit application-operation DI scope](architecture/dependency-injection.md), independent of Spring web request scope and physical transactions. Public contracts live in `de.hsh.favs.sharewise.shared.infra.di`; Spring-specific wiring under `shared.infra.di.internal` is infrastructure-private. The DI foundation, transaction manager and jOOQ/JPA accessors are implemented and tested; domain-specific business modules are not yet present.

Mark a Spring-managed service with `@Scope("operation")` when one operation needs a shared instance of its mutable state. Constructor dependencies are resolved by **Spring**, not by a custom activator. Production inbound adapters inject the singleton `OperationDispatcher` and dispatch an entry-service type rather than obtaining an operation-scoped bean from a singleton during construction:

```java
// Example service types; no financial business modules exist yet.
@Component
@Scope("operation")
final class ExampleState {
    private int calls;

    int next() { return ++calls; }
    int current() { return calls; }
}

@Service
@Scope("operation")
final class ExampleService {
    private final ExampleState state;

    ExampleService(ExampleState state) { this.state = state; }

    int handle() { return state.next(); }
}

// Inside an inbound adapter:
int result = dispatcher.dispatch(ExampleService.class, ExampleService::handle);
```

Infrastructure and tests that need to inspect multiple beans inside the *same* scope use the scope factory directly. The provider is usable only inside `run(...)`; closing remains the caller's responsibility:

```java
try (ServiceScope scope = scopeFactory.createScope()) {
    scope.run(provider -> {
        ExampleService service = provider.getRequiredService(ExampleService.class);
        ExampleState state = provider.getRequiredService(ExampleState.class);
        int result = service.handle();
        assertEquals(1, result);
        assertEquals(1, state.current()); // Both references share the same state.
        return null;
    });
}
```

The factory may create independent nested DI scopes, but **nested `OperationDispatcher.dispatch(...)` calls are prohibited**. Do not use the outer scope's provider or already-created services from within a nested activation. Scopes and providers are confined to their creating thread; after closure, references must not be retained or used. Singleton-to-scoped dependency capture and escaped references are code-review concerns, not guarantees of automatic lifetime analysis.

The test suite exercises these contracts without PostgreSQL. From `src/backend/sharewise`, run the Spring-focused tests with Maven (`mvn test -Dtest=ServiceScopeIntegrationTests,OperationDispatcherIntegrationTests,MultipleApplicationContextIsolationTests,OperationScopeTestSeamIntegrationTests`) or the full `mvn clean verify`. In an isolated environment, add `-o` and `-Dmaven.repo.local=<isolated-cache-path>` to force offline resolution.

## Transaction and persistence adapter usage

An operation-scoped entry service decides when to open a transaction and what to persist. The returned `TransactionOutcome` controls the physical disposition **after** the callback finishes; expected business errors are ordinary result values, not automatic rollback triggers:

```java
@Service
@Scope("operation")
final class ThingService {
    private final Transactions transactions;
    private final ThingStore things;

    ThingService(Transactions transactions, ThingStore things) {
        this.transactions = transactions;
        this.things = things;
    }

    Optional<Thing> find(UUID id) {
        return transactions.execute(() ->
            TransactionOutcome.abstain(things.getThingById(id)));
    }
}
```

An operation-scoped persistence implementation injects `JooqAccess` or `JpaAccess` rather than a `DSLContext` or `EntityManager` obtained during construction. A repository method obtains its current-segment facade locally and finishes all SQL access before returning:

```java
@Override
public Optional<Thing> getThingById(UUID id) {
    DSLContext db = database.dsl();
    return db.selectFrom(THING)
        .where(THING.ID.eq(id))
        .fetchOptional(this::mapThing);
}
```

The examples use illustrative domain types and a generated `THING` table, not existing business classes. An accessor can be retained by the operation-scoped repository; **the returned jOOQ/JPA facade, queries and managed entities cannot be retained across calls or transaction segments**. Once a segment ends, obtaining another facade through the accessor is valid in a subsequent segment, but previously issued facades remain invalid. See the [transaction architecture](architecture/transactions.md) for outcomes, lifecycle and failure semantics.

From `src/backend/sharewise`, exercise Spring transaction coordination and both adapters using `mvn test -Dtest=TransactionsIntegrationTests,RelationalTransactionIntegrationTests` or `mvn clean verify`. These checks use instrumented transaction managers and test-only H2 datasources, not PostgreSQL. Production datasource interoperability, database isolation and engine-specific failure behavior require separate PostgreSQL integration tests.

## Development conventions

- Use UTF-8 without BOM, LF line endings and final newlines, following repository formatting configuration. Prefer four-space indentation for Java unless an adopted formatter specifies otherwise.
- Keep application/core contracts separated from transport DTOs and persistence implementation details; refer to [architecture](architecture.md) and [domain model](data-model-design.md).
- Prefer constructor-injected, cohesive services and operation-specific data access. Keep operation-local mutable state out of singleton services. Follow the [operation-scope usage rules](#operation-scope-usage) and the [DI architecture](architecture/dependency-injection.md) for lifecycle ownership; singleton capture and escaped-reference misuse are reviewed rather than automatically detected by Spring.
- Add and run focused tests for changed behavior. Validate database concurrency and query contracts against the intended database once those adapters are introduced.
- When a build or test cannot run, identify whether the blocker is tooling, dependency resolution, project compilation, a test failure, or external infrastructure. Do not conflate missing local prerequisites with source-code defects.

Offline SDK/cache provisioning is an **external developer-environment workflow**, not a product repository contract. Keep downloaded JDKs, Maven repositories, npm caches, container images, credentials and other machine-specific setup artifacts outside the source tree.

For task specification, phased agent implementation, independent review and human acceptance, see the [AI-assisted development and QA workflow](development/ai-development-workflow.md). The [ChatGPT workspace guide](development/chatgpt-development-workflow.md) describes the hosted offline-toolchain and GitHub mirror workflow.

## Portable offline dependency handoff

The optional [`scripts/create-offline-handoff.sh`](../scripts/create-offline-handoff.sh) prepares a reusable **Linux x86-64 offline build environment**. It does **not** bundle Git history or project sources. It keeps the required official vendor archives for **Temurin JDK 25**, **Node.js 24**, and **Maven 3.10.0** intact, and separately compresses **Maven and/or npm dependency caches**, manifest checksums and a verification script. This prevents the large language SDKs from being recompressed into a single monolithic archive.

On a network-connected Linux x64 checkout:

```sh
scripts/create-offline-handoff.sh pack
```

The default includes both projects. To prepare a smaller handoff for a single area, use `--deps maven` (backend, JDK + Maven) or `--deps node` (frontend, Node.js + npm):

```bash
scripts/create-offline-handoff.sh pack --deps maven
scripts/create-offline-handoff.sh pack --deps node
```

These selections prepare, package and verify **only** the chosen cache, include checksums only for that project's dependency descriptors and publish only its required vendor archives. The verifier reads `manifest/dependency-selection.txt` automatically. A backend-only handoff does not require frontend source files or Node.js; a frontend-only handoff does not require backend source files, Java or Maven. Existing combined handoffs without the selection manifest remain supported. To exercise the pack/verify selection logic without downloads, run `scripts/test-offline-handoff.sh`; its stand-in toolchains test orchestration and compatibility, not actual Maven/npm dependency completeness.

The script uses `wget` to download and checksum-verify the toolchains and caches their original archives under Git-ignored `.offline-handoff-cache/toolchains/`. Its default output layout is:

```text
.offline-handoff-cache/
├── sharewise-offline-deps-linux-x64.tar.gz
├── sharewise-offline-deps-linux-x64.tar.gz.sha256
└── toolchains/
    ├── OpenJDK25U-jdk_x64_linux_hotspot_*.tar.gz
    ├── node-v24.*-linux-x64.tar.xz
    └── apache-maven-3.10.0-bin.zip
```

For the default bundle, **supply the four files separately**, plus the small dependency-archive checksum file, when transferring the environment. Selective bundles use the default filenames `sharewise-offline-deps-maven-linux-x64.tar.gz` or `sharewise-offline-deps-node-linux-x64.tar.gz`, each with its own `.sha256` file, and need only the two backend toolchains or the single frontend toolchain listed in `manifest/toolchain-files.txt`. A shared output directory may contain additional archives from other bundles; do not transfer them unless the selected manifest requires them. Toolchain archives remain in their original formats and have individually recorded SHA-256 hashes. Use `--output` to choose another destination (its `toolchains/` sibling folder will be populated), `--cache-dir` to choose a shared download cache, or `--jdk`, `--node` and `--maven` to supply your own vendor archives.

`pack` runs connected dependency acquisition and real backend/frontend builds in temporary source copies, then re-verifies using strict offline Maven and npm invocations. Neither the source nor extracted SDKs enter the compressed dependency archive; neither `node_modules` nor compiled output is retained.

On a Linux x64 receiving machine, place the dependency archive and `toolchains/` folder together and unpack **only the dependency archive** into a separate `deps/` directory:

```sh
mkdir -p deps
tar -xzf sharewise-offline-deps-linux-x64.tar.gz -C deps
./deps/verify-offline.sh verify deps --source /path/to/sharewise
```

The verifier hashes the required original toolchain archives, extracts them to temporary storage, checks the selected source dependency descriptors and runs the corresponding backend tests and/or frontend lint/build without network access. If the vendor archives live elsewhere, supply `--toolchains /path/to/archives`. Verification needs Linux x64, `tar` and basic Unix checksum utilities, plus Python 3 when verifying Maven builds (to unpack the original Maven ZIP), but not preinstalled language toolchains. See `README-OFFLINE.md` within the dependency archive for more information.

Ordinary source changes do **not** invalidate the handoff. Changes to the **selected** project's `pom.xml`, Maven wrapper properties, `package.json` or `package-lock.json` invalidate it conservatively. Container images, PostgreSQL and external services are not included and require separate provisioning. The frontend starter avoids build-time Google Fonts network requests.
