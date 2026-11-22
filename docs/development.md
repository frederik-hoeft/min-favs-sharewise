# Development environment

**Status:** Development setup for the implemented platform foundations, IAM initial authentication and frontend demonstration workspace. Architecture documents also describe features that are not yet implemented. For how the technologies fit together, start with the [technical stack overview](stack/overview.md).

## VS Code Dev Containers

A [Dev Container](https://code.visualstudio.com/docs/devcontainers/containers) is a repository-defined Docker development environment to which VS Code attaches. ShareWise's [`.devcontainer/`](../.devcontainer/devcontainer.json) configuration uses Docker Compose to provide a shared Linux environment with Java 25, Maven, Node.js 24 and project-relevant VS Code extensions. The repository is mounted at `/workspace`, and Maven/npm caches are kept in Docker volumes. This avoids requiring each developer to install and align language toolchains on the host.

To get started:

1. Install **Docker** and **VS Code** with Microsoft's **Dev Containers** extension, and start Docker.
2. Open the repository root in VS Code. From the Command Palette (`Ctrl+Shift+P`), select **Dev Containers: Reopen in Container**. VS Code builds and starts the configured container and runs its initialization steps; the initial setup requires network access to obtain images and dependencies.
3. Use VS Code's terminal inside the container to run the commands below. The backend and frontend ports (`8080` and `3000`) are explicitly forwarded to the host; automatic forwarding of other detected ports is disabled. After changing `.devcontainer/` configuration, run **Dev Containers: Rebuild Container**.

The source checkout remains on the host and survives container rebuilds. Tools or files installed only inside an individual container should not be treated as persistent project state.

Git stores executable permissions independently of host/mount permissions. Windows/NTFS-mounted files may appear executable inside a devcontainer even when Git records mode `100644`, which breaks fresh Linux CI checkouts. Keep executable shebang scripts at Git mode `100755` (`git update-index --chmod=+x path/to/script`) and run `python3 scripts/ci/check-script-modes.py` before pushing; the `pre-build` CI gate runs the same check. Source-only shell fragments should not have a shebang.

## Current toolchain

The backend is a **Java 25 / Spring Boot 4.2.0 Maven** project under `src/backend/sharewise/`. The Maven wrapper requests Maven **3.10.0** and is configured as a script-only wrapper (it does not bundle Maven). The current backend dependencies include Spring MVC, JPA/Hibernate, jOOQ, the PostgreSQL JDBC driver, Flyway, operation-scoped DI and transaction management, test starters and Spring Modulith 2.2.0 for [module structure verification](#application-modules). Spring Modulith event publication remains an architectural target rather than an installed backend feature.

The frontend under `src/frontend/` uses **Next.js 16.4.6**, React **19.3.1**, TypeScript, npm, and a checked-in `package-lock.json`. **Mantine 9.8** supplies standard UI components and theme management; Sass/SCSS Modules supply component-local styles. The homepage uses the selected [Quiet clarity](design/sharewise-design-foundation.md) visual direction with static illustrative financial previews, not implemented financial workflows. Ordinary Next.js development/build commands produce the standalone-server output; the frontend production image builds a static export and serves it with Nginx. The production Nginx image serves the exported assets with TLS and forwards same-origin `/api/*` requests to the backend. A separate production-style Compose stack supplies PostgreSQL roles, runtime secrets and health-based startup; see [local Compose acceptance](development/production-compose.md).

## Database

The application uses **PostgreSQL 18**. The [persistence architecture](architecture/persistence.md) defines schema ownership, migration authority and adapter boundaries; this section covers local setup and commands. The dev container starts a `postgres` service next to the `dev` service (host `postgres`, port `5432`, database `sharewise`); it is reachable from the dev container and other compose services, not published on the host. On first start with an empty `postgres-data` volume, the scripts in `src/backend/sharewise/src/test/resources/db/bootstrap/` create two roles:

| Role | Used by | Permissions |
| --- | --- | --- |
| `sharewise_migrator` | Flyway at application startup | Owns the context schemas; creates and changes tables (DDL) |
| `sharewise_app` | Application runtime connection | Reads and writes data in the context schemas (DML); no DDL, no access to the Flyway history |

Flyway applies the migrations in `src/main/resources/db/migration/` at startup. Each bounded context owns one schema (`identity`, `groups`, `finance`, `files`, `notifications`); the Flyway history lives in schema `flyway`. Hibernate only validates its mappings (`spring.jpa.hibernate.ddl-auto=validate`). Migration files follow `db/migration/<context>/V<yyyyMMddHHmm>__<context>_<description>.sql` (cross-cutting migrations such as the schema baseline use `db/migration/platform/`) and are immutable once committed and pushed; local work-in-progress migrations may be edited after resetting the local database.

The IAM persistence adapters use committed jOOQ schema classes generated against **migrated PostgreSQL**, not handwritten table definitions. After a Flyway schema change, regenerate them from the development container with the database running and migrated:

```bash
cd src/backend/sharewise
./mvnw -Pjooq-codegen-identity \
  -Djooq.codegen.jdbc.url=jdbc:postgresql://postgres:5432/sharewise \
  -Djooq.codegen.jdbc.user=sharewise_migrator \
  org.jooq:jooq-codegen-maven:generate
```

The development container supplies the development-only `JOOQ_CODEGEN_PASSWORD`; outside it, set that environment variable explicitly for the migration role. Review and commit the generated sources under `identity/internal/auth/persistence/generated/` alongside the Flyway change. Ordinary Maven builds use those checked-in sources and do not contact PostgreSQL for code generation. The generator profile is opt-in and requires its plugin artifacts, which are not included in the Maven-only offline handoff; CI generation-freshness checking is still planned.

`application.properties` contains dev-only defaults for the dev-container database. The production image uses the `production` profile and required password files via the [Compose runtime configuration](development/production-compose.md). Other non-production environments may override the dev values with `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`, `SPRING_FLYWAY_USER` and `SPRING_FLYWAY_PASSWORD`. To inspect the database, use the PostgreSQL extension installed in the dev container (server `postgres`) or `docker exec <folder>_devcontainer-postgres-1 psql -U postgres -d sharewise`, where `<folder>` is the name of your checkout folder (`docker ps --filter name=postgres` shows the exact name). To reset the local database, stop the dev container and remove the `<folder>_devcontainer_postgres-data` volume (`docker volume ls --filter name=postgres-data`); the roles are recreated on the next start.

The backend has independent **Unit**, **Integration** and **E2E** test gates. Unit tests run offline; Integration and E2E tests require PostgreSQL 18. The [test architecture](architecture/tests.md) explains their isolation and evidence boundaries; [Backend testing](development/tests.md) documents local configuration, fixture APIs, CI and diagnostics. The deferred Smoke / system-level E2E suite has no execution gate yet.

## Initial IAM authentication (P2.1a)

Production IAM password encoding and access-token signing are enabled only with explicitly provisioned settings. Without `sharewise.iam.auth.enabled=true`, the production authentication services and signing codec are **not** registered; the existing bearer ingress remains fail-closed. The `/api/iam/v1/auth/{register,login}` and `/api/iam/v1/auth/csrf` HTTP routes are enabled by the same flag. Without configured trust and valid signing keys, the API rejects bearer and credential-entry requests rather than falling back to test credentials.

The production password encoder uses Spring Security's `Pbkdf2PasswordEncoder` and the JDK's PBKDF2-HMAC-SHA256 provider, with **600,000 iterations, a random 16-byte (128-bit) salt, and a 32-byte (256-bit) subkey**. Its fixed, unversioned stored format is 96 lowercase hex characters (`salt || subkey`); unknown/malformed formats fail verification. Registration accepts **8–256 UTF-16 code units**, consistent with Java `String.length()` and JavaScript `string.length`; login allows shorter previously issued passwords but enforces the same maximum. Passwords are neither trimmed nor normalized, and no bcrypt-style UTF-8 byte limit or truncation applies. Unpaired UTF-16 surrogates are rejected. Hash upgrading and alternate stored profiles are outside this WIP branch.

The demo does not provision a common-password blocklist or implement an in-process hashing semaphore. PBKDF2 requires CPU time per verification, not Argon2-style working memory; benchmark concurrency before public exposure. Source/edge throttling or rate limiting can be supplied by a reverse proxy/WAF; account lockout remains implemented by IAM. Do not silently lower the configured PBKDF2 work factor to address load.

In the local production-style Compose deployment, **IAM key provisioning is automated** by the one-shot `iam-key-init` service: it generates a P-256 ES256 key into a protected Docker private-key volume if explicitly permitted, stores the corresponding public key in an independent public-only volume, and blocks backend startup on missing or mismatched keys. Public verification material can be exported for other services without mounting or exporting the private volume. See [the deployment IAM key runbook](../deploy/README.md#iam-jwt-signing-key-provisioning-and-distribution) for first-use provisioning, production `require-existing` mode, export, permissions, backup and rotation constraints.

Outside that Compose deployment, the application accepts **externally provisioned** unencrypted PKCS#8 P-256 PEM private keys and SPKI PEM public keys via `sharewise.iam.auth.keys[]`, along with the configured issuer, audience, active key ID and `sharewise.iam.auth.enabled=true`. Exactly one key is active for signing; additional configured keys may be public-only verification keys for rotation. The signer never generates production keys implicitly at application startup.

The configured IAM issuer and audience must be the same trusted values used by bearer verification. The signed primary profile has `typ=sw-iam-access+jwt`, a default five-minute access lifetime (configurable below), 30-second clock skew and a 4 KiB token limit. The JWT profile maximum lifetime follows the configured access lifetime. Both issuance and verification use the same named `iamPrimaryJwtCredentialCodec` from the P2.1.0 infrastructure; a valid signature alone does not prove the current session is still eligible (P2.1d). No JWT signing key or fake credential fallback is bundled in the application image. Never log keys, raw credentials or hashes.

Session and lockout settings are bound from `sharewise.iam.auth` and validated at startup. Defaults are `session.access-lifetime=5m`, `session.minimum-access-lifetime=1m`, `session.idle-lifetime=7d`, `session.absolute-lifetime=30d`, `lockout.threshold=5` and `lockout.duration=15m`. The minimum access lifetime may be configured but cannot fall below the two-second JWT timestamp-precision safety floor; `access-lifetime` must meet that minimum and cannot exceed one hour. The idle deadline must be at least the access lifetime and no longer than 90 days; the absolute deadline must be at least the idle lifetime and no longer than 365 days. The lockout threshold must be 1–100 and its duration greater than zero and at most one day. Invalid configuration prevents startup.

**Browser credential entry.** `GET /api/iam/v1/auth/csrf` returns a masked request token with `Cache-Control: no-store` and a host-only `__Host-sharewise-csrf` cookie (`Secure`, `HttpOnly`, `SameSite=Strict`, `Path=/`). Registration and login POSTs require the matching `X-XSRF-TOKEN` request header, CSRF cookie, and an exact same-origin `Origin` header. The Spring Security CSRF and Origin filters reject missing/invalid proof before the IAM operation is dispatched. Accepted registration returns `201` and login `200`, with a short-lived bearer JWT in JSON and a distinct `__Host-sharewise-refresh` cookie, also `Secure`, `HttpOnly`, `SameSite=Strict`, host-only, and `Path=/`. The refresh cookie expires no later than the committed session's idle/absolute deadline; its value is never sent in JSON. Successful authentication clears the CSRF cookie; the browser obtains a new token before any later mutation. Rejected credentials, invalid requests, and duplicate registrations never issue refresh cookies.

Local HTTP test clients may inspect the `Secure` cookies, but Phase G browser acceptance uses a real **HTTPS** origin so storage/sending of the host-prefixed cookies is verified rather than assumed. The production Nginx TLS terminator forwards the external origin through its trusted proxy network; Spring's production profile interprets only those proxy-provided forwarded headers. Do not enable forwarded-header trust for arbitrary untrusted client traffic. The same CSRF and Origin boundary protects `/api/iam/v1/auth/refresh` and `/api/iam/v1/auth/logout`. A valid refresh rotates its verifier generation and returns a new access JWT and cookie; logout revokes the session and clears refresh/CSRF cookies. Both operations accept a nullable `expectedSessionId` to bind subsequent mutations to the tab's known session; an unbound refresh is used only when restoring an unknown cookie context. Invalid/missing refresh credentials cannot renew, while a valid cookie belonging to another session returns `409 session_context_changed` without cookie mutation. Refresh/logout require no access bearer and never authenticate other API operations through cookies alone. See [the security architecture](stack/security.md#api-boundaries-and-client-behavior) and [P2.1b acceptance record](internal/tasks/p2.1b-session-lifecycle/acceptance.md). Before public exposure, configure edge-level request size/rate limits and benchmark PBKDF2 load.

For real-browser registration/login acceptance and evidence collection, use the [P2.1a Phase G acceptance checklist](internal/tasks/p2.1a-registration-auth/acceptance.md).

## Build, test and run

Backend, from the repository root (`clean verify` without a profile runs all three when PostgreSQL is available). See [Backend testing](development/tests.md) for prerequisites, focused execution and troubleshooting:

```sh
cd src/backend/sharewise
./mvnw -Punit-tests clean verify
./mvnw -Pintegration-tests clean verify
./mvnw -Pe2e-tests clean verify
./mvnw spring-boot:run
```

Frontend development, API-client tests, styling conventions and quality checks are documented together in the [frontend development guide](development/frontend.md). Its commands run from `src/frontend/` using the checked-in npm lockfile; the isolated Vitest project includes the API-client test suite.

The frontend `.npmrc` rejects unreviewed dependency lifecycle scripts. Keep the policy in place; see the [frontend guide](development/frontend.md) for the full validation workflow.

The starter defaults to frontend port 3000 and backend port 8080. Consult the backend `pom.xml`, wrapper configuration, frontend `package-lock.json`, and frontend-specific `src/frontend/AGENTS.md` for authoritative build and dependency details. Use project-owned commands rather than modifying dependencies merely to work around a developer's environment.

## Application images (P2.6a)

The backend and frontend Dockerfiles are buildable directly from a clean source checkout. The backend Dockerfile uses Maven 3.10.0/JDK 25 in its build stage, then a Java 25 JRE runtime with a non-root UID and a `curl` HTTP readiness check at `/api/system/v1/health`. That endpoint requires a working PostgreSQL connection; running the backend image without correctly provisioned database roles will not result in a ready service. Maven test gates run separately in CI.

The frontend Dockerfile builds the Next.js static export using Node.js 24 and the checked-in npm lockfile, then packages the generated HTML/assets in an Nginx-only runtime. The edge serves HTTPS on container port 443 and redirects HTTP on port 80 to the configured public HTTPS address. It accepts one explicit `SHAREWISE_PUBLIC_HOST` (DNS name or IPv4 address), and `SHAREWISE_HTTPS_PORT` (default `443`) identifies the **externally published** TLS port for redirects and forwarding metadata. Unknown hostnames are rejected. It serves exported extensionless routes, never falls back to `/` for unknown paths, and applies `no-store` to HTML/API responses while caching fingerprinted Next.js assets immutably.

Nginx expects an externally supplied certificate and key at `/etc/nginx/tls/fullchain.pem` and `/etc/nginx/tls/privkey.pem`; the image contains neither. Missing files or an invalid public hostname prevent startup. For local builds, HTTPS checks, and Docker-outside-of-Docker-compatible Compose deployment, use the [deployment guide](../deploy/README.md) and its scripts. Deployment HTTP/HTTPS host ports are separate from the explicitly forwarded development ports.

The Nginx `/api/*` location forwards unchanged paths, queries, methods, bodies, authorization and cookies to the service named `backend` on port 8080. It ignores client-supplied forwarding headers and supplies trusted public origin/client values. When the backend is absent, only API requests fail; static content remains available. The Spring production profile (`SPRING_PROFILES_ACTIVE=production`) enables framework forwarded-header interpretation and is safe only behind the **private, trusted proxy network** established in Phase D. Development mode does not trust arbitrary forwarded headers. Nginx never rewrites application cookies. Production certificate issuance, trusted ingress provisioning and their lifecycle belong to P2.6c/d, not this image.

For a full image check on a Docker-capable host with Docker, Git and OpenSSL installed, run `bash deploy/scripts/test-images.sh`. It builds both images without layer cache, inspects runtime contents and labels, creates a disposable TLS certificate, and checks the static HTTPS routes and 404 handling from *within* the frontend container. It transfers the certificate via the Docker API and therefore works with Docker-outside-of-Docker/remote Docker without needing a shared temporary host path. For proxy/TLS/forwarding tests using a controlled local backend, run `python3 -m unittest discover -s deploy/tests -p 'test_*.py' -v` (requires Nginx, OpenSSL and `envsubst`). For real PostgreSQL-backed `/api/system/v1/health` and service-startup validation, follow [local deployment and Compose acceptance](../deploy/README.md); this requires a Docker-capable host and an empty disposable test volume. Local development still uses `npm run dev` (port 3000) with the `BACKEND_URL` rewrite.

GitLab publishes versioned images through a daemonless builder only for protected `main` pushes and protected `dev-<slug>` Git tags. It records matching, verified image digests in the `image-release/release.json` pipeline artifact. See [CI and diagnostics](development/tests.md#ci-and-diagnostics) for the publishing contract and its remaining acceptance limits.

## Application modules

The backend is divided into Spring Modulith application modules; the [application architecture](architecture.md#module-structure-and-published-apis) defines their structure and dependency rules. The POM imports the Spring Modulith 2.2.0 BOM. `spring-modulith-api` provides the `@ApplicationModule` and `@NamedInterface` annotations on the compile classpath, and `spring-modulith-core` performs verification in test scope only. No Spring Modulith runtime feature, such as event publication, is installed.

A vertical slice that introduces a module creates its package directly below `de.hsh.favs.sharewise` and declares every module API or named interface that the module uses in its `package-info.java`:

```java
// Example for a future module; no business modules exist yet.
@ApplicationModule(allowedDependencies = { "shared :: di", "groups" })
package de.hsh.favs.sharewise.finance;

import org.springframework.modulith.ApplicationModule;
```

A module without dependencies declares `allowedDependencies = {}`. `ApplicationModuleStructureTests` pins the current list of modules, so the slice that adds a module also updates that expectation. To publish a sub-package to other modules, annotate its `package-info.java` with `@NamedInterface("<name>")`; consumers then declare `"<module> :: <name>"`. Keep a named interface limited to the contracts other modules need.

The full backend build runs module verification. From `src/backend/sharewise`, run only the verification tests with:

```bash
./mvnw test -Dtest=ApplicationModuleStructureTests,ModuleVerificationFixtureTests
```

A violation names the modules and types involved, for example `Module 'consumer' depends on module 'provider' via ...Consumer -> ...ProviderApi. Allowed targets: none.` Fix it by using the other module's published API or, when the dependency is intended, by adding it to the consumer's `allowedDependencies` for review. Do not publish internal packages or open a module merely to silence verification; `ApplicationModuleStructureTests` rejects open modules and publication from any module’s internal implementation packages, which Spring Modulith alone accepts.

The verification tests also run small fixture applications under `de.hsh.favs.modulithfixtures` in the test sources, one per rule, to prove that each kind of violation is detected. They live outside the application root, so they are never part of the real application's module model or Spring context.

## Operation scope usage

The backend provides an [explicit application-operation DI scope](architecture/dependency-injection.md), independent of Spring web request scope and physical transactions. Public contracts live in `de.hsh.favs.sharewise.shared.infra.di`, published to other modules as the named interface `shared::di`; Spring-specific wiring under `shared.infra.di.internal` is not published. The DI foundation, transaction manager and jOOQ/JPA accessors are implemented and tested; domain-specific business modules are not yet present.

Mark a Spring-managed service with `@Scope("operation")` when one operation needs a shared instance of its mutable state. Constructor dependencies are resolved by **Spring**, not by a custom activator. Inbound adapters dispatch an entry-service type rather than obtaining an operation-scoped bean during construction. HTTP controllers use the request-scoped `OperationIngressDispatcher`, which composes caller-side inputs; non-HTTP adapters may use the core `OperationDispatcher` directly or compose their own contributors. The example below illustrates a core dispatcher call:

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

The Unit suite exercises these contracts without PostgreSQL. From `src/backend/sharewise`, run `./mvnw -Punit-tests test -Dtest=ServiceScopeTests,OperationDispatcherTests,MultipleApplicationContextIsolationTests,OperationScopeTestSeamTests`, or run all Unit tests with `./mvnw -Punit-tests clean verify`. Offline cache settings and PostgreSQL-backed test commands are documented in [Backend testing](development/tests.md).

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

From `src/backend/sharewise`, run fake-manager transaction contract tests with `mvn -Punit-tests test -Dtest=TransactionsTests`. Run rollback-isolated PostgreSQL operations with `mvn -Pintegration-tests verify`, or real-commit mixed-adapter workflows with `mvn -Pe2e-tests verify -Dit.test=RelationalTransactionE2E`. The PostgreSQL suites exercise physical SQL, isolation and commit/failure behavior; the Unit suite does not contact a database.

## Development conventions

- Use UTF-8 without BOM, LF line endings and final newlines, following repository formatting configuration. Prefer four-space indentation for Java unless an adopted formatter specifies otherwise.

The repository includes a Git pre-commit hook that enforces UTF-8 without a BOM for staged tracked text (including Java, shell scripts, Maven XML, GitLab YAML and documentation). Enable it once per clone with `git config core.hooksPath .githooks`. The hook checks the **index**, so unstaged fixes are not treated as committed. The `pre-build` CI encoding job runs the same `python3 scripts/ci/check-source-encoding.py` checker against all tracked working-tree files and verifies its test fixtures; only files explicitly marked binary in `.gitattributes` are exempt from UTF-8 decoding; all other tracked files must also be NUL-free, including those covered by Git `text=auto`. Add new binary formats to `.gitattributes` explicitly rather than relying on automatic classification. Python 3 and Git are required for local execution. Line-ending normalization is handled separately by `.gitattributes`.
- Keep application/core contracts separated from transport DTOs and persistence implementation details; refer to [architecture](architecture.md) and [domain model](data-model-design.md).
- Use other modules only through their top-level API or named interfaces, and declare every module dependency in `allowedDependencies`; see [application modules](#application-modules).
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

The Maven cache is populated by running the actual `-Punit-tests clean verify` lifecycle, including Spring Boot repackaging and Surefire/Failsafe plugins, rather than prefetching every plugin managed by the Spring Boot parent. The opt-in `jooq-codegen-identity` profile is excluded: generated sources are already committed, and the handoff does not provide PostgreSQL. Running code generation separately requires a migrated PostgreSQL schema and its plugin dependencies.

The verifier hashes the required original toolchain archives, extracts them to temporary storage, checks the selected source dependency descriptors and runs the corresponding backend tests and/or frontend lint/build without network access. If the vendor archives live elsewhere, supply `--toolchains /path/to/archives`. Verification needs Linux x64, `tar` and basic Unix checksum utilities, plus Python 3 when verifying Maven builds (to unpack the original Maven ZIP), but not preinstalled language toolchains. See `README-OFFLINE.md` within the dependency archive for more information.

Ordinary source changes do **not** invalidate the handoff. Changes to the **selected** project's `pom.xml`, Maven wrapper properties, `package.json` or `package-lock.json` invalidate it conservatively. Container images, PostgreSQL and external services are not included and require separate provisioning. The frontend starter avoids build-time Google Fonts network requests.
