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

## Separate-container execution

The Next.js `/api/greetings` route forwards server-side requests to the Spring Boot `/greeting` endpoint.
`BACKEND_URL` is the **backend's base URL as seen from the frontend server**, not the address used by a
browser. It defaults to `http://localhost:8080` for the devcontainer or two processes on the same host.
When the backend and frontend run in separate containers, their loopback interfaces are different, so provide
an address reachable over their shared Docker network. For example, after building the backend JAR:

```sh
cd src/backend/favsdemobe
./mvnw clean verify
cd ../../..
docker build -t sharewise-backend src/backend/favsdemobe
docker build -t sharewise-frontend src/frontend
docker network create sharewise
docker run -d --name sharewise-backend --network sharewise sharewise-backend
docker run --rm --name sharewise-frontend --network sharewise \
  -e BACKEND_URL=http://sharewise-backend:8080 \
  -p 3000:3000 sharewise-frontend
```

The frontend image build uses the starter's `next/font/google` fonts and therefore needs access to Google
Fonts at build time. The Maven/npm dependencies can be supplied offline, but that alone does not make an
unmodified frontend production build network-independent.

The frontend resolves `sharewise-backend` through Docker's network DNS and exposes the UI at
`http://localhost:3000`. For a Compose or orchestrated deployment, set `BACKEND_URL` to the corresponding
backend service DNS name instead. The variable is read by the Next.js server at runtime; it should not be a
`NEXT_PUBLIC_` setting. The frontend runtime uses Next.js standalone output, with no full development
dependency tree. Both production images run as non-root users. The example backend uses the starter's H2
database and does not provide durable production storage.

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
