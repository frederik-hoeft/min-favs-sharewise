# P1.1 - Manual acceptance procedure

**Status:** Procedure for the [P1.1 specification](specification.md), executed on commit `9bdec18`; all steps passed, with results recorded below. The procedure is executed by the human task owner once all implementation phases pass their automated tests.

P1.1 has no user-facing journey. Manual acceptance instead demonstrates, in the real application rather than in test fixtures, that module verification rejects an undeclared dependency, accepts the same dependency once it is declared, and rejects a module that declares no whitelist.

The demonstration uses the public `OperationDispatcher` contract from `shared::di`. The types in `shared.infra.di.internal` are package-private, so Java's compiler already prevents other packages from using them; a violation that only module verification can detect must therefore use a type that Java permits.

## Preconditions

- The P1.1 implementation is checked out with a clean working tree (`git status --short` prints nothing).
- The backend tests pass:

```bash
cd src/backend/sharewise
./mvnw clean test
```

All paths below are relative to `src/backend/sharewise/`. The temporary files must **never be committed**.

Every step runs `clean` before testing. Module verification analyzes compiled bytecode, and Maven's incremental compilation does not remove class files whose sources were deleted. Without `clean`, a stale `acceptancedemo/package-info.class` keeps the A2 declaration in effect during A3, and a stale `AcceptanceDemoService.class` keeps the demo module present during A4.

## Temporary demo module

The demo module `acceptancedemo` is a direct sub-package of the application root, so Spring Modulith detects it as an application module. It contains plain classes only and defines no Spring bean; module verification analyzes compiled bytecode and does not need the application to run.

`src/main/java/de/hsh/favs/sharewise/acceptancedemo/package-info.java`, initially declaring **no** permitted dependencies:

```java
/**
 * Temporary module for P1.1 manual acceptance. Do not commit.
 */
@ApplicationModule(allowedDependencies = {})
package de.hsh.favs.sharewise.acceptancedemo;

import org.springframework.modulith.ApplicationModule;
```

`src/main/java/de/hsh/favs/sharewise/acceptancedemo/AcceptanceDemoService.java`, which depends on the published `shared::di` contract:

```java
package de.hsh.favs.sharewise.acceptancedemo;

import de.hsh.favs.sharewise.shared.infra.di.OperationDispatcher;

/**
 * Temporary class for P1.1 manual acceptance. Do not commit.
 */
public final class AcceptanceDemoService {

    private final OperationDispatcher dispatcher;

    public AcceptanceDemoService(OperationDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    public OperationDispatcher dispatcher() {
        return dispatcher;
    }
}
```

## Steps

Steps A1 to A3 run only the test that checks the rule under examination. A full `./mvnw test` would fail in all three steps for an additional reason: `ApplicationModuleStructureTests` also pins the current module list to `shared` alone (MOD-T02), so any new module, including the demo, fails that test until the list is updated.

| Step | Action | Expected result |
| --- | --- | --- |
| **A1** | Create both files above and run `./mvnw clean test -Dtest='ApplicationModuleStructureTests#applicationModuleStructureIsValid'`. | The test fails with a Spring Modulith violation: `Module 'acceptancedemo' depends on named interface(s) 'shared :: di' via ...acceptancedemo.AcceptanceDemoService -> ...shared.infra.di.OperationDispatcher. Allowed targets: none.` |
| **A2** | Change the declaration to `@ApplicationModule(allowedDependencies = "shared :: di")` and run the same command as in A1. | The test passes: the dependency is permitted because it is declared and targets a published named interface. |
| **A3** | Delete `acceptancedemo/package-info.java` and run `./mvnw clean test -Dtest='ApplicationModuleStructureTests#everyModuleDeclaresAllowedDependencies'`. | The test fails: `every module must declare @ApplicationModule(allowedDependencies = {...}) ==> expected: <[]> but was: <[acceptancedemo]>`. |
| **A4** | Delete the whole `acceptancedemo` directory, confirm that `git status --short` shows no `acceptancedemo` files, and run the full `./mvnw clean test`. | No demo files remain and the full build passes. |

The quoted messages were observed with Spring Modulith 2.1.2. Their exact wording is not part of the acceptance contract; the result must identify the modules and the offending dependency clearly enough for a developer to fix it without further investigation.

## Results

| Step | Date | Commit | Outcome and notes |
| --- | --- | --- | --- |
| A1 | 2026-11-11 | `9bdec18` | Passed. Verification failed with the expected violation naming `acceptancedemo`, `AcceptanceDemoService` and `shared :: di`. |
| A2 | 2026-11-11 | `9bdec18` | Passed. With `allowedDependencies = "shared :: di"` the test passed. A misspelled first attempt (`"sharred :: di"`) was also rejected, reporting an invalid explicit module dependency because no module named `sharred` exists. |
| A3 | 2026-11-11 | `9bdec18` | Passed after correcting the procedure. The first run without `clean` passed incorrectly because the stale `package-info.class` from A2 remained in `target/classes`. Rerun with `clean`, it failed as expected: `expected: <[]> but was: <[acceptancedemo]>`. The steps now always run `clean`. |
| A4 | 2026-11-11 | `9bdec18` | Passed. After the demo directory was deleted, `git status --short` was empty and the full `./mvnw clean test` succeeded. |
