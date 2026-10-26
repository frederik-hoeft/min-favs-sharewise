# Development environment

**Status:** Setup guidance for the supplied starter. The repository contains a backend and frontend skeleton;
architecture documents describe the intended system rather than features already implemented.

## Current toolchain

The backend is a **Java 25 / Spring Boot 4.1.2 Maven** project under `src/backend/favsdemobe/`. The Maven
wrapper requests Maven **3.10.0** and is configured as a script-only wrapper (it does not bundle Maven).
The current backend dependencies include Spring MVC, JPA, H2/H2 console, Lombok and test starters.
PostgreSQL persistence, Spring Modulith and event publication remain architectural targets rather than
installed backend features.

The frontend under `src/frontend/` uses **Next.js 16.4.1**, React **19.3.0**, TypeScript, npm, and a checked-in
`package-lock.json`. The devcontainer provisions Java 25, Maven and Node.js 24; initial container/image
setup requires network access unless these dependencies have been prepared independently.

## Build, test and run

Backend, from the repository root:

```sh
cd src/backend/favsdemobe
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

The starter defaults to frontend port 3000 and backend port 8080. Consult the backend `pom.xml`, wrapper
configuration, frontend `package-lock.json`, and frontend-specific `src/frontend/AGENTS.md` for authoritative
build and dependency details. Use project-owned commands rather than modifying dependencies merely to work
around a developer's environment.

## Development conventions

- Use UTF-8 without BOM, LF line endings and final newlines, following repository formatting configuration.
  Prefer four-space indentation for Java unless an adopted formatter specifies otherwise.
- Keep application/core contracts separated from transport DTOs and persistence implementation details; refer
  to [architecture](architecture.md) and [domain model](data-model-design.md).
- Prefer constructor-injected, cohesive services and operation-specific data access. Keep operation-local
  mutable state out of shared services.
- Add and run focused tests for changed behavior. Validate database concurrency and query contracts against
  the intended database once those adapters are introduced.
- When a build or test cannot run, identify whether the blocker is tooling, dependency resolution, project
  compilation, a test failure, or external infrastructure. Do not conflate missing local prerequisites with
  source-code defects.

Offline SDK/cache provisioning is an **external developer-environment workflow**, not a product repository
contract. Keep downloaded JDKs, Maven repositories, npm caches, container images, credentials and other
machine-specific setup artifacts outside the source tree.

## Portable offline development handoff

A connected **Linux x86-64** preparation machine can package a clean, committed checkout and the complete
backend/frontend toolchains and dependency caches with
[`scripts/create-offline-handoff.sh`](../scripts/create-offline-handoff.sh). The resulting large archive lives
**outside the repository** and must not be committed. This is an optional environment-preparation utility,
not an application runtime dependency or part of the agent engineering-quality skill.

On a Linux x64 machine with network access, from a clean, committed checkout:

```sh
scripts/create-offline-handoff.sh pack \
    --output ../sharewise-offline-linux-x64.tar.gz
```

The script downloads the latest stable **Eclipse Temurin JDK 25** (Linux x64), the latest **Node.js 24** patch
release (Linux x64), and **Maven 3.10.0** (matching the Maven wrapper) using `wget`.
It validates downloaded archives against vendor-published SHA-256/SHA-512 checksums and **reuses a verified
archive** from the Git-ignored `.offline-handoff-cache/toolchains/` directory when available. Toolchain
versions are resolved at handoff time, then recorded by their archive checksums in the handoff manifest.
The source snapshot and generated handoff do not contain this preparation cache.

Use `--cache-dir /path/to/cache` to share that download cache between workspaces, or supply any of
`--jdk /path/to/archive`, `--node /path/to/archive`, and `--maven /path/to/archive` to override a download.
Manually supplied archives are not checked against external metadata; verify their origin and checksums
independently before use. `pack` still requires network access for downloading Maven/npm dependencies.

The script resolves **Maven transitive dependencies and build plugins** into an isolated local repository,
fetches all npm packages (including Linux x64 native optional packages) using `npm ci`, and performs online
and **strict offline** Maven and frontend verification in clean source copies. It aborts on missing packages,
compilation, test, lint, or frontend-build failures. It doesn't rely on existing global package caches.

Before extracting on the receiving machine, check the generated sidecar SHA-256 file alongside the archive.
After unpacking the handoff on another Linux x64 host:

```sh
./verify-offline.sh verify .
```

The bundle includes a source snapshot, JDK, Maven, Node/npm, Maven repository, npm cache, verification
script, commit identifier, and checksums of dependency descriptors. It intentionally excludes generated
`node_modules`, build outputs, database services, secrets, and Docker/devcontainer images. Container-based
offline development additionally requires preloaded matching container images and OS packages. A later
change to `pom.xml`, `package-lock.json`, platform, or build lifecycle can require regenerating the cache.

The original Next.js starter imported Google Fonts dynamically at build time. Its layout now uses local system
font stacks so that building the frontend does not independently require Google's font service. If remote font
loading is reintroduced, the fonts should be vendored or handled by a deliberately verified offline strategy.
