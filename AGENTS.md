# ShareWise contributor and coding-agent instructions

ShareWise is a Java 25/Spring Boot + Next.js project at starter stage. Before changing behavior, start with the [technical stack overview](docs/stack/overview.md), then read [architecture](docs/architecture.md), the [implemented DI architecture](docs/architecture/dependency-injection.md), [transaction architecture](docs/architecture/transactions.md), [domain model](docs/data-model-design.md), and [development setup](docs/development.md). Distinguish documented **intended** contracts from code that exists now; do not claim planned features are implemented.

Prefer maintainable, correct, well-factored solutions over minimal demonstrations that only pass a happy-path test. Preserve explicit invariants, transaction ordering, history immutability, module ownership, and failure semantics. Build focused DI-managed application services with immutable operation DTOs and efficient database-side projections through ports; keep JPA/jOOQ/JDBC infrastructure private to adapters. Follow the [implemented persistence architecture](docs/architecture/persistence.md) for schema ownership, migrations and data access; the [messaging design](docs/stack/messaging.md) for durable event publication, idempotency and recovery; and the [security design](docs/stack/security.md) for issuer-qualified authorization, IAM sessions, and token validation. Avoid accidental cross-context data access or synchronous distributed coupling. Each bounded context is a Spring Modulith module: use other modules only through their [published API](docs/architecture.md#module-structure-and-published-apis), and declare every module dependency in `allowedDependencies`; the backend build verifies these rules. Treat authorization and financial history as correctness-critical.

## Repository-local skills

The skill files under `.agents/skills/` are **not necessarily discovered or loaded automatically** by the agent host. Before substantial work, explicitly open and read the applicable `SKILL.md` files below; a link in `AGENTS.md` does not substitute for their contents. Consult supporting references linked from a skill when relevant, rather than loading every reference for a small change. If work spans code and documentation, apply both skills.

| Work | Required skill |
| --- | --- |
| Implementing features, refactoring, debugging, or reviewing code | [Engineering quality](.agents/skills/engineering-quality/SKILL.md): reuse, clear ownership, architectural coherence, invariants, and testing. |
| Writing, editing, reviewing, or updating documentation, including docs accompanying code changes | [Documentation](.agents/skills/documentation/SKILL.md): audience-focused explanations, consistent contracts, steady-state prose, and Markdown conventions. |

Revisit skill selection when a task expands into another kind of work; apply the guidance proportionately to the change. Keep this table current if repository skills are added or renamed. The authoritative product documentation lives in `docs/`, not in agent skills; do not duplicate it across agent configurations.

For Java code, always declare local variables and enhanced-for loop variables with **explicit types**, including in tests and try-with-resources. Do not use Java `var`; favor reviewable types over inferred declarations.

The backend uses **Maven**, not Gradle; frontend uses **npm**, not pnpm. Follow the actual `pom.xml`, `package-lock.json`, and the frontend's generated [Next.js agent notes](src/frontend/AGENTS.md). For backend changes, select Unit, Integration or E2E tests according to the [test architecture](docs/architecture/tests.md) and follow the [test authoring and execution guide](docs/development/tests.md); passing only the offline Unit gate never establishes PostgreSQL correctness. Validate the strongest applicable checks supported by the environment and state any tooling gaps precisely.
