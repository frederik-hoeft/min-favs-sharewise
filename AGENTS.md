# ShareWise contributor and coding-agent instructions

ShareWise is a Java 25/Spring Boot + Next.js project at starter stage. Before changing behavior, read
[architecture](docs/architecture.md), [domain model](docs/data-model-design.md), and [development
setup](docs/development.md). Distinguish documented **intended** contracts from code that exists now; do not
claim planned features are implemented.

Prefer maintainable, correct, well-factored solutions over minimal demonstrations that only pass a happy-path
test. Preserve explicit invariants, transaction ordering, history immutability, module ownership, and failure
semantics. Build focused DI-managed application services with immutable operation DTOs and efficient
database-side projections through ports; keep JPA/JDBC infrastructure private to adapters. Avoid accidental
cross-context data access or synchronous distributed coupling. Treat authorization and financial history as
correctness-critical.

For new features, refactors and reviews, use [engineering-quality](.agents/skills/engineering-quality/SKILL.md).
For architecture/documentation editing, use
[technical-documentation](.agents/skills/technical-documentation/SKILL.md). The authoritative product
documentation lives in `docs/`, not in an agent skill; do not duplicate it across agent configurations.

The backend uses **Maven**, not Gradle; frontend uses **npm**, not pnpm. Follow the actual `pom.xml`,
`package-lock.json`, and the frontend's generated [Next.js agent notes](src/frontend/AGENTS.md). Validate the
strongest applicable checks supported by the environment and state any tooling gaps precisely.

When working on supplied archive baselines, preserve the approved local `main`, use one reviewable feature
branch per coherent task, provide a patch against that baseline, and do **not** merge until the user approves.
Keep changes focused and report a concrete commit message and validation results.
