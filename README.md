# ShareWise

ShareWise is a group expense-sharing and bookkeeping application. Members record expenses and settlements of
payments made outside the application, calculate their current balances, and inspect the group's financial
position at earlier revisions. It does **not** move money, verify external payments, or enforce debts.

The repository is an **early implementation baseline**, not a finished product. The backend and frontend
currently provide starter/example functionality. The architecture and business contracts in `docs/` describe the
intended implementation; they are not a claim that all those features already exist.

## Repository layout

| Path | Purpose |
| --- | --- |
| `src/backend/favsdemobe/` | Java 25 / Spring Boot 4.1 Maven starter |
| `src/frontend/` | Next.js 16.4 / React 19 / TypeScript frontend |
| `docs/data-model-design.md` | Domain ownership, financial invariants, and historical revision semantics |
| `docs/architecture.md` | Component-level hexagonal/modulith architecture and service conventions |
| `docs/development.md` | Local development, build and validation guidance |
| `docs/scoped-authorization-attestations-proposal.md` | Optional **unaccepted** distributed authorization design for later evaluation |
| `.agents/skills/` | Repository-local agent skills |

The diagrams under `docs/assets/` are design material; the ER-style persistence diagram may show physical
revision IDs and storage conventions that are intentionally absent from the conceptual domain model.

## Development

The devcontainer (`.devcontainer/`) provisions a Java 25 toolchain, Maven, Node.js 24 and npm. It requires
container/build-image and network access for initial setup. Without the devcontainer, install the corresponding
toolchains locally.

Backend:

```sh
cd src/backend/favsdemobe
./mvnw test
./mvnw spring-boot:run
```

Frontend (separate terminal):

```sh
cd src/frontend
npm ci
npm run dev
```

The frontend runs on port 3000 and the backend on port 8080 in the provided starter setup. The backend contains
a greeting example; the full financial APIs are not yet implemented.

For setup, builds and tests, consult the [development guide](docs/development.md). The starter
currently includes H2/JPA dependencies; PostgreSQL and database-specific adapter contracts are
architectural targets, not yet implemented dependencies.

## Architecture and contribution guidance

ShareWise starts as a modular monolith with five bounded contexts: **User & Identity**, **Group**,
**Financial**, **File**, and **Notification Management**. The design anticipates independently deployed
services, with published module APIs, durable integration events, and independent data ownership. Finance uses
its own strongly consistent group-scoped transaction boundary and immutable revision history.

Read [architecture](docs/architecture.md) and [domain model](docs/data-model-design.md) before implementing
business features. Coding agents should also read [AGENTS.md](AGENTS.md); its associated [engineering-quality
skill](.agents/skills/engineering-quality/SKILL.md) provides reusable architectural and engineering-quality guidance.
