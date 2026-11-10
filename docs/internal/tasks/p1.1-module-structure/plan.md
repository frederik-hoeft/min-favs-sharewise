# P1.1 - Implementation plan: application module structure and verification

**Status:** Approved plan; Phase A is accepted and Phase B is next. It implements the approved [P1.1 specification](specification.md). **Execution gate:** Each implementation phase requires its own human review and approval before the next phase begins. The [AI development workflow](../../../development/ai-development-workflow.md) governs incremental review, independent merge-request review and final acceptance.

## Baseline and prerequisites

Work starts from branch `task/p1.1-module-structure` at the commit that adds the approved specification and [manual acceptance procedure](manual-acceptance.md). The backend is a Java 25 / Spring Boot 4.1.2 Maven project under `src/backend/sharewise/`. Its only module code is `de.hsh.favs.sharewise.shared.infra.di` with the implementation package `shared.infra.di.internal`, whose types are all package-private. The root package contains `ShareWiseApplication` and the starter `GreetingController`. Spring Modulith is not installed.

P1.1 is backend-only and needs neither the frontend nor a database. Module verification is static bytecode analysis, so its tests start no Spring `ApplicationContext`.

The following Spring Modulith 2.1.2 behavior was checked against its published sources and determines the design below:

- `ApplicationModules.of(ShareWiseApplication.class)` and `ApplicationModules.of(String)` import classes with ArchUnit's `DoNotIncludeTests` option. Fixtures in test sources are therefore only visible through `ApplicationModules.of(String, ImportOption)` with an option that includes test locations.
- `verify()` runs once per `ApplicationModules` instance and throws `Violations`; `detectViolations()` always runs and returns `Violations`, whose individual messages can be asserted.
- `ApplicationModuleInformation.of(module.getBasePackage()).getDeclaredDependencies()` returns `List.of(ApplicationModule.OPEN_TOKEN)` when a module has no `@ApplicationModule` annotation or leaves `allowedDependencies` at its default, and an empty list for an explicit `allowedDependencies = {}`. The whitelist convention check (MOD-09) relies on this distinction through public API only.

Java code follows the repository rule of explicit local-variable types; `var` is not used.

**Phase completion rule:** Check a task box only when its changes have been validated **and human-approved**. Each phase produces one reviewable change set, reports the exact validation commands and results, and stays on the task branch until approved. If Spring Modulith cannot meet a requirement as specified, the specification returns to review before replanning.

## Phase A - Dependencies, module declarations and real-application verification

**Covers:** MOD-01, MOD-04, MOD-06, MOD-07, MOD-08, the dependency decisions, and MOD-T01, MOD-T02.

- [x] In `src/backend/sharewise/pom.xml`, add a `spring-modulith.version` property set to `2.1.2` and import `org.springframework.modulith:spring-modulith-bom` in `dependencyManagement`. Add `spring-modulith-api` in compile scope and `spring-modulith-core` in test scope, both version-managed by the BOM. No Modulith starter, runtime or event artifact is added.
- [x] Create `de/hsh/favs/sharewise/shared/package-info.java` declaring the module with `@ApplicationModule(allowedDependencies = {})` and Javadoc stating the `shared` rules: business-independent infrastructure, no dependencies on other modules, capabilities published only through named interfaces. The default `CLOSED` type is not restated.
- [x] Annotate the existing `shared/infra/di/package-info.java` with `@NamedInterface("di")` and adjust its Javadoc to name the published reference `shared::di` and state that `internal` is not published.
- [x] Add `ApplicationModuleStructureTests` in the test package `de.hsh.favs.sharewise`:
    - **MOD-T01:** `ApplicationModules.of(ShareWiseApplication.class).verify()` passes.
    - **MOD-T02:** the model contains exactly one module, `shared`; it is not open; its declared dependencies are empty; its named interface `di` contains exactly `OperationDispatcher`, `ServiceScopeFactory`, `ServiceScope` and `ServiceProvider`, and no type from `shared.infra.di.internal`. The exact-content assertion compares type names from `NamedInterface.asJavaClasses()`, excluding the `package-info` type if Modulith includes it.
- [x] Confirm that the existing DI and application-context tests are unaffected.

**Gate A:** From `src/backend/sharewise`, `./mvnw -B -ntp clean verify` passes, with Spring Modulith 2.1.2 resolved from the network or from a refreshed offline handoff, and `git diff --check` is clean. The report lists the new resolved artifacts so the offline handoff can be regenerated. The human reviews the POM and annotation changes before Phase B.

## Phase B - Whitelist convention check and fixture-based detection tests

**Covers:** MOD-05, MOD-09, MOD-10, MOD-11, and MOD-T03 through MOD-T10.

- [ ] Add the package-private test helper `ModuleDependencyDeclarations` in test package `de.hsh.favs.sharewise`. Its single static method returns the names of all modules in an `ApplicationModules` model whose declared dependencies equal `List.of(ApplicationModule.OPEN_TOKEN)`, sorted for deterministic messages. It is a pure, stateless function over Modulith's public model and needs no Spring bean.
- [ ] Extend `ApplicationModuleStructureTests` with **MOD-T03**: the helper returns no modules for the real application.
- [ ] Create fixture applications under the test-source root package `de.hsh.favs.modulithfixtures`, outside the application root (MOD-11). Each scenario is its own root package whose direct sub-packages are the fixture modules. Fixtures contain plain classes with minimal fields or constructor parameters that create the intended dependency, and no Spring annotations:

| Scenario package | Modules and dependency | Test |
| --- | --- | --- |
| `valid` | `consumer` uses `provider`'s top-level API and `shared`'s named interface `di`; `consumer` declares `{"provider", "shared :: di"}`, the others declare `{}` | **MOD-T04** |
| `internalaccess` | `consumer` uses a public type in `provider.internal`; `provider` is whitelisted | **MOD-T05** |
| `unpublishedinterface` | `consumer` uses a public type in `shared.infra.di.internal`; `shared :: di` is whitelisted | **MOD-T06** |
| `undeclareddependency` | `consumer` uses `provider`'s top-level API but declares `{}` | **MOD-T07** |
| `sharedtobusiness` | `shared` declares `{}` and uses `business`'s top-level API | **MOD-T08** |
| `cycle` | `alpha` and `beta` use each other's top-level APIs and whitelist each other | **MOD-T09** |
| `missingdeclaration` | `consumer` has no `@ApplicationModule` annotation and no outgoing dependency | **MOD-T10** |

- [ ] Add `ModuleVerificationFixtureTests` in test package `de.hsh.favs.sharewise`. A private method loads a scenario with `ApplicationModules.of("de.hsh.favs.modulithfixtures.<scenario>", <import option including test classes>)`.
    - MOD-T04 asserts that `detectViolations()` has no violations and the whitelist helper returns no modules.
    - MOD-T05 to MOD-T09 assert that `detectViolations()` reports **exactly one** violation whose message names the offending source type and the target type or module (for the cycle, both modules).
    - MOD-T10 asserts that verification reports no violations and the whitelist helper returns exactly `consumer`. This proves the scenario fails only because of the missing declaration.
    - Assertions use stable identifiers (fully qualified type names and module names) rather than Spring Modulith's complete message wording. The observed messages are recorded in the phase report.
- [ ] If a scenario produces additional or different violations than expected, adjust the fixture so it isolates a single rule. Do not weaken the assertion.

**Gate B:** `./mvnw -B -ntp clean verify` and `git diff --check` pass. Each negative test has been seen to fail for its intended reason: temporarily removing the violating reference from its fixture makes that test fail. The report records this check; the temporary change is not committed. The human reviews the helper, fixtures and tests before Phase C.

## Phase C - Documentation reconciliation and acceptance handoff

**Covers:** all MOD requirements; the specification's documentation reconciliation and manual acceptance.

- [ ] Reconcile the steady-state documentation with the verified implementation, following the [documentation skill](../../../../.agents/skills/documentation/SKILL.md):
    - [architecture](../../../architecture.md): extend *System organization* with the module convention (direct sub-packages, top-level API, closed modules, named interfaces, mandatory whitelists, `shared` rules); update *Architecture checks* to describe the executable verification; update the status line.
    - [development guide](../../../development.md): replace the statement that Spring Modulith is not installed with the installed artifacts and their scopes; add a short "adding an application module" section with an example `package-info.java` declaration and the command that runs verification.
    - [stack overview](../../../stack/overview.md): state that Spring Modulith is installed for module verification while the event publication registry remains planned.
    - [backlog](../backlog.md) framework projection: mark Spring Modulith 2.1.2 as adopted for structure verification.
- [ ] Run `./mvnw -B -ntp clean verify` and `git diff --check`; confirm that no unrelated files, frontend files or lockfiles changed, and that the relative links in changed documents resolve.
- [ ] Hand over to the human for the independent merge-request review and the [manual acceptance procedure](manual-acceptance.md), steps A1 to A4. If the observed Spring Modulith messages differ materially from the procedure's expected results, update the procedure before acceptance.

**Gate C:** The independent review findings are resolved or justified, the human has executed and recorded the manual acceptance results, and the offline handoff has been regenerated for the changed POM where the team relies on it. As the final pre-merge commit, mark [P1.1](../backlog.md#phase-1---modular-application-and-persistence-foundation) complete in the backlog, then merge after human authorization.

## Scope boundaries and deferred work

No business module packages, placeholder APIs or Spring beans are added. Spring Modulith's event publication registry, `@ApplicationModuleTest`, `Documenter`-generated diagrams, observability and runtime starters remain out of scope, as do changes to the DI implementation and the starter `GreetingController`. Business modules apply the conventions when their first vertical slice creates them.
