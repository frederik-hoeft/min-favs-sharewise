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
