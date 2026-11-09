# P1.1 - Manual acceptance procedure

**Status:** Draft procedure for the [P1.1 specification](specification.md); results are recorded below after implementation. The procedure is executed by the human task owner once all implementation phases pass their automated tests.

P1.1 has no user-facing journey. Manual acceptance instead demonstrates, in the real application rather than in test fixtures, that module verification rejects an undeclared dependency, accepts the same dependency once it is declared, and rejects a module that declares no whitelist.

The demonstration uses the public `OperationDispatcher` contract from `shared::di`. The types in `shared.infra.di.internal` are package-private, so Java's compiler already prevents other packages from using them; a violation that only module verification can detect must therefore use a type that Java permits.

## Preconditions

- The P1.1 implementation is checked out with a clean working tree (`git status --short` prints nothing).
- The backend tests pass:

```bash
cd src/backend/sharewise
./mvnw test
```

All paths below are relative to `src/backend/sharewise/`. The temporary files must **never be committed**.

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

| Step | Action | Expected result |
| --- | --- | --- |
| **A1** | Create both files above and run `./mvnw test`. | The build fails. Module verification reports that `acceptancedemo` depends on `shared` (via `AcceptanceDemoService` → `OperationDispatcher`) although this dependency is not allowed. The message identifies both modules and the offending type. |
| **A2** | Change the declaration to `@ApplicationModule(allowedDependencies = "shared :: di")` and run `./mvnw test`. | The build passes: the dependency is permitted because it is declared and targets a published named interface. |
| **A3** | Delete `acceptancedemo/package-info.java` and run `./mvnw test`. | The build fails. The whitelist convention check names `acceptancedemo` as a module without an explicit `allowedDependencies` declaration. |
| **A4** | Delete the whole `acceptancedemo` directory, confirm `git status --short` prints nothing, and run `./mvnw test`. | The working tree is clean and the build passes. |

Spring Modulith's exact message wording is not part of the acceptance contract; the result must identify the modules and the offending dependency clearly enough for a developer to fix it without further investigation.

## Results

| Step | Date | Commit | Outcome and notes |
| --- | --- | --- | --- |
| A1 | | | |
| A2 | | | |
| A3 | | | |
| A4 | | | |
