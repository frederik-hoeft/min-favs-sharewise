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

## Portable offline dependency handoff

The optional [`scripts/create-offline-handoff.sh`](../scripts/create-offline-handoff.sh) prepares a reusable
**Linux x86-64 offline toolchain and dependency bundle**. It includes Java 25, Maven 3.10.0, Node 24/npm, a
complete Maven local repository and npm cache. **It does not include source files or Git history.** Supply the
project source separately through Git, a source ZIP, or another handoff. Update the dependency bundle only
when the dependency manifests, build tooling, or required platform change—not for routine source edits.

Run on a network-connected Linux x64 machine with the source checkout available:

```sh
scripts/create-offline-handoff.sh pack
```

The script automatically downloads and checksum-verifies toolchains using `wget`, caching archives under the
Git-ignored `.offline-handoff-cache/toolchains/`. By default, the generated archive and `.sha256` sidecar are
written to `.offline-handoff-cache/sharewise-offline-deps-linux-x64.tar.gz[.sha256]`. Use `--output` to change
the destination; use `--jdk`, `--node`, `--maven`, or `--cache-dir` to override toolchain inputs or download
cache location. Explicitly supplied toolchain archives must be verified independently.

`pack` uses **temporary copies** of the current backend and frontend sources, excluding existing build output,
`node_modules`, and local `.env` files. It runs connected builds to acquire transitive dependencies and Maven
plugins, followed by strict offline Maven and npm builds before packaging. Source code is discarded afterward.
The archive contains only the toolchains, dependency caches, the verifier, and checksums of `pom.xml`, Maven
wrapper properties, `package.json`, and `package-lock.json`.

On the receiving Linux x64 machine, verify the archive's sidecar SHA-256, extract it, obtain the source
separately, and run from the extracted bundle directory:

```sh
./verify-offline.sh verify . --source /path/to/sharewise
```

Verification requires an exact match of the **dependency descriptors**, not an identical source commit. It
runs offline backend tests and frontend lint/build in a fresh temporary source copy, leaving the supplied source
checkout unchanged. If those descriptors change, regenerate the bundle with `pack`; otherwise reuse it for
subsequent source revisions. See `README-OFFLINE.md` inside the generated archive for environment setup.

Neither a container image nor PostgreSQL is included in this archive; offline integration tests requiring
external infrastructure need corresponding separate provisioning. The starter's generated remote Google Fonts
were replaced with local system font stacks to avoid an unrelated network request during Next.js builds.
