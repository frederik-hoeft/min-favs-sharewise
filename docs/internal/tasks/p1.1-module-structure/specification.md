# P1.1 - Application module structure and verification

**Status:** Approved specification; implementation and documentation are complete, with independent review and manual acceptance pending. **Backlog item:** [P1.1](../backlog.md#phase-1---modular-application-and-persistence-foundation). The [application architecture](../../../architecture.md) defines module ownership and the published-API rule; the [DI architecture](../../../architecture/dependency-injection.md) defines the contracts that `shared` publishes. The [development workflow](../../../development/ai-development-workflow.md) defines approval gates.

## Goal and scope

ShareWise's five bounded contexts may use one another only through published APIs or integration events. Java visibility alone cannot express this rule: a type that Spring wiring or a sibling sub-package needs must be `public`, and `public` types are importable from every package. P1.1 makes module boundaries **explicit and executable**. A build-time verification test fails when code uses another module's internals, creates a dependency cycle between modules, or depends on a module that it has not declared.

P1.1 delivers:

- the **module and published-API conventions** that later vertical slices follow when they create the business modules;
- **Spring Modulith structure verification** for the existing code, with the `shared` module publishing the DI contracts as the named interface `shared::di`;
- **positive and negative verification tests** proving both that valid structures pass and that each forbidden dependency kind is detected;
- reconciled steady-state documentation of the conventions.

P1.1 does **not** create packages, placeholder interfaces or pass-through services for `identity`, `groups`, `finance`, `files` or `notifications`. Those modules are added with their first vertical slice (P2 onward) and apply the conventions at that point. Spring Modulith's event publication registry, `@ApplicationModuleTest`, observability, runtime starters and generated module documentation are out of scope; event infrastructure is introduced with the first cross-context consumer in P2.4. The starter `GreetingController` and the DI implementation behavior remain unchanged.

## Architectural solution

### Spring Modulith model

Spring Modulith derives application modules from the package structure and verifies the dependencies between them with static bytecode analysis (ArchUnit). Verification needs no Spring `ApplicationContext`, database or running application. The relevant rules are:

- Every **direct sub-package** of the application root package `de.hsh.favs.sharewise` is an application module. Types in the root package itself, such as `ShareWiseApplication` and the starter `GreetingController`, belong to no module.
- A module's **top-level package is its published API**. All of its sub-packages are internal unless explicitly published.
- `@NamedInterface("<name>")` on a sub-package's `package-info.java` publishes **exactly that package**, not its sub-packages, under the reference `<module>::<name>`.
- `@ApplicationModule(allowedDependencies = {...})` on a module's base `package-info.java` turns the module's outgoing dependencies into a whitelist. Entries name a module (its top-level API) or one named interface (`module :: name`). An **empty array permits no dependency** on other modules; omitting the attribute leaves dependencies unrestricted.
- `ApplicationModules.verify()` rejects access to another module's non-published types, dependencies outside a declared whitelist, and dependency cycles between closed modules.

### ShareWise conventions

- **MOD-01 - Module identity:** Each bounded context is one direct sub-package of `de.hsh.favs.sharewise`: `identity`, `groups`, `finance`, `files`, `notifications`. Cross-cutting infrastructure lives in the `shared` module. No other modules are introduced without an architecture review. A module package is created with the first vertical slice that needs it, not in advance.
- **MOD-02 - Published API location:** A business module's published API is its **top-level package**: the operation-focused command/query contracts and immutable records that other modules may use. Implementation, domain internals and adapters live in sub-packages. The sub-package layout within a module is chosen by its vertical slice and is not prescribed here. Additional named interfaces are introduced only where a module publishes a genuinely separate contract; each requires a stated purpose in its `package-info.java`.
- **MOD-03 - Minimal visibility:** Within a module, types that need no cross-package access stay package-private, as agreed in P0.1. Module verification covers the cases where Java requires `public` across a module's own sub-packages; it does not replace ordinary Java encapsulation.
- **MOD-04 - Regular (closed) modules:** Every module, including `shared`, uses Spring Modulith's default `CLOSED` type. `OPEN` modules, which expose all internals and are excluded from cycle detection, are not used.
- **MOD-05 - Mandatory dependency whitelist:** Every module declares `@ApplicationModule(allowedDependencies = {...})` in its base `package-info.java`, listing each module API or named interface that it uses. Adding a dependency is therefore a visible, reviewed change to that declaration. This complements the architecture's rule that synchronous cross-context calls require a reviewed integration boundary; the whitelist makes such a dependency explicit but does not by itself approve it. A module that declares no whitelist fails verification (see MOD-09).
- **MOD-06 - Explicit `shared` dependencies:** Business modules list the `shared` named interfaces they use explicitly, for example `"shared :: di"`. Spring Modulith's `@Modulithic(sharedModules = ...)` mechanism, which implicitly allows a module for all others, is not used, so that every dependency remains visible in its consumer's declaration.

### The `shared` module

`shared` holds reusable, business-independent infrastructure. It is a closed module whose top-level package publishes nothing. Each infrastructure capability is published through its own named interface; additional capabilities receive separately named interfaces when they are introduced.

```text
de.hsh.favs.sharewise
├── ShareWiseApplication, GreetingController   (root package; no module)
└── shared                                     module, allowedDependencies = {}
    └── infra.di                               named interface "di"  → shared::di
        │   OperationDispatcher, ServiceScopeFactory, ServiceScope, ServiceProvider
        └── internal                           not published; Spring implementations
```

- **MOD-07 - `shared::di`:** `de.hsh.favs.sharewise.shared.infra.di` is published as the named interface `di`. It contains exactly the public DI contracts `OperationDispatcher`, `ServiceScopeFactory`, `ServiceScope` and `ServiceProvider`. `shared.infra.di.internal` is not published; another module referencing a type in it fails verification. Its current implementation types are package-private, so Java already prevents such references today; module verification additionally protects any `public` type later added to that package.
- **MOD-08 - `shared` has no module dependencies:** `shared` declares `allowedDependencies = {}`. Infrastructure must never depend on a business module, which keeps the dependency graph acyclic and `shared` reusable when contexts are extracted.

### Dependencies

Spring Boot 4.1.2 does not manage Spring Modulith versions. The backend imports `spring-modulith-bom` **2.1.2**, which is built against Spring Boot 4.1.2. Only the annotation artifact (`spring-modulith-api`, needed for `@ApplicationModule` and `@NamedInterface` in production `package-info.java` files) is on the compile classpath. The verification library is **test-scoped**. No Spring Modulith runtime behavior, auto-configuration or starter is added to the application.

Because the POM changes, the separately supplied **offline dependency handoff must be regenerated and revalidated** for offline and ChatGPT-hosted workflows, as required by the [backlog](../backlog.md#framework-projection-to-validate-in-design-reviews). The handoff artifacts are not repository content.

### Executable verification

- **MOD-09 - Verification suite:** The backend test suite verifies the real application structure with Spring Modulith and checks that every detected module declares an explicit `allowedDependencies` whitelist (MOD-05). Spring Modulith does not enforce declaration by itself, so this convention check is project-owned and reusable against any root package.
- **MOD-10 - Proof of detection:** Negative tests run the same verification against small, deliberately invalid **fixture applications** in test sources. Each fixture contains exactly one kind of violation, and its test asserts that verification fails **for that violation**, identifying the offending type or dependency, not merely that an exception occurred. A positive fixture with real cross-module dependencies shows that valid declared dependencies pass, because the real application does not yet contain business modules.
- **MOD-11 - Fixture isolation:** Fixture packages live **outside** `de.hsh.favs.sharewise`, so they are never part of the real application's module model, component scanning or Spring context. Fixtures contain plain types only; they define no Spring beans.

## Acceptance tests

All tests are database-independent and run as part of the normal Maven test phase. Test names and fixture package names may differ from the case IDs.

| ID | Setup and action | Expected evidence |
| --- | --- | --- |
| **MOD-T01** | Verify the real application from `ShareWiseApplication`. | Verification passes. |
| **MOD-T02** | Inspect the real application's module model. | `shared` is the only module, is `CLOSED` and declares no allowed dependencies. Its named interface `di` contains exactly the four DI contract types and no type from `shared.infra.di.internal`. |
| **MOD-T03** | Run the whitelist convention check (MOD-09) on the real application. | The check passes. |
| **MOD-T04** | Positive fixture: a business module uses a second module's top-level API and a shared-style module's named interface, and declares both in its whitelist. | Verification and the whitelist convention check pass. |
| **MOD-T05** | Negative fixture: a module references a type in another module's internal sub-package. | Verification fails, identifying the internal type access. |
| **MOD-T06** | Negative fixture: a module references a shared-style module's unpublished `internal` package while `shared :: di` is whitelisted (mirrors `shared.infra.di.internal`). | Verification fails, identifying the unpublished type. |
| **MOD-T07** | Negative fixture: a module uses another module's top-level API without listing it in its whitelist. | Verification fails, identifying the undeclared dependency. |
| **MOD-T08** | Negative fixture: a shared-style module with `allowedDependencies = {}` references a business module's API. | Verification fails, identifying the forbidden dependency. |
| **MOD-T09** | Negative fixture: two closed modules depend on each other's top-level APIs, both whitelisted. | Verification fails, identifying the cycle. |
| **MOD-T10** | Negative fixture: a module declares no `allowedDependencies`. | The convention check fails, naming the module. |

The developer runs the backend's Maven `verify` build and `git diff --check`, and records the results.

**Manual acceptance:** P1.1 has no user-facing journey. The human task owner follows the [manual acceptance procedure](manual-acceptance.md): a temporary demo module in the real application uses `shared::di` without declaring it, which verification must reject; declaring `"shared :: di"` must make it pass; removing the declaration entirely must fail the whitelist convention check. The owner records the results in that document and removes the demo module afterwards.

## Documentation reconciliation

The final implementation step updates the steady-state documentation so that it describes module conventions as implemented:

- [architecture](../../../architecture.md): module structure, published-API location, named interfaces, `shared` rules and the executable architecture check;
- [development guide](../../../development.md): installed Spring Modulith artifacts, how to add a module and declare its dependencies, and how to run verification;
- [stack overview](../../../stack/overview.md) and the backlog's framework projection: Spring Modulith is installed for structure verification only, while event publication remains planned;
- [backlog](../backlog.md): P1.1 status after acceptance.

## Deferred decisions and handoff

The internal sub-package layout of business modules, the publication of integration-event types (P2.4), and any named interfaces beyond `shared::di` are decided by the slices that introduce them, following MOD-02 and MOD-05. Generated module diagrams or documentation (Spring Modulith `Documenter`) are an optional later addition.

The implementation plan chooses the exact test classes, fixture package names, the convention-check implementation and the assertion technique for violation messages, without weakening the requirements above. If Spring Modulith 2.1.2 cannot meet one of these requirements as specified, the specification returns to human review before implementation proceeds.
