# P1.3a - Operation-scoped dependency injection

**Status:** Approved and implemented; all P1.3a phases accepted. **Backlog item:** [P1.3a](../backlog.md#phase-1---modular-application-and-persistence-foundation). The [DI architecture](../../../architecture/dependency-injection.md) is the authoritative system model; its [alternatives](../../../stack/dependency-injection-alternatives.md) explain the selected Spring integration. [P1.3b](../p1.3-transactions/specification.md) and [P1.3c](../p1.3c-integration-tests/specification.md) own transaction and test-fixture behavior respectively. The [development workflow](../../../development/ai-development-workflow.md) defines approval gates.

## Goal and scope

Provide a deterministic **application-operation DI lifetime** independent of HTTP requests, event listeners, scheduled jobs and physical transactions. An inbound adapter dispatches an application entry service once; Spring constructs the complete constructor-injected graph, while beans registered with the custom `operation` scope have one identity and cleanup lifetime for that dispatch. A test harness can use the **same scope machinery** to resolve and inspect multiple collaborators before teardown.

P1.3a supplies the production scope, its Spring integration, a small MEDI-inspired owner/resolver API, and a production `OperationDispatcher`. It does **not** implement the domain service graphs, PostgreSQL, `Transactions`, per-transaction resource access, rollback-test transaction interception, or business-specific inbound adapters. Those are separate vertical slices. The starter's existing greeting endpoint remains unchanged.

This task is independent of P1.2 and can be validated with focused Spring/JUnit tests and the supplied offline Java/Maven toolchain. No database, dependency-version change, Spring Modulith setup or custom bean construction engine is required to deliver its acceptance contract.

## Architectural solution and ownership

One Spring `ApplicationContext` retains its normal `BeanFactory`, bean definitions, singleton cache, candidate selection, injection, proxies and lifecycle machinery. The custom Spring `Scope` SPI is registered under `operation` in that **same bean factory**. No child Spring containers or per-scope bean factories are created.

The following roles form one infrastructure boundary:

| Role | Owner and responsibility |
| --- | --- |
| `ServiceScopeFactory` | Spring singleton, one per `ApplicationContext`; creates independent scope instances and owns a private Java 25 `ScopedValue` key for lexical selection. |
| `ServiceScope` | Caller-owned, `AutoCloseable` unit of lifetime; contains one scoped-bean cache and destruction registrations. `run(...)` activates the instance for a callback; `close()` performs terminal cleanup. |
| `ServiceProvider` | Scope-bound, deliberately narrow facade over that context's Spring `BeanFactory`; resolves a service only while its owning scope is active. It does not choose constructors or maintain another registration/cache. |
| Custom Spring `Scope` adapter | Container-facing integration that gets the currently bound `ServiceScope` through its factory and delegates caching, removal and callback registration to it. |
| `OperationDispatcher` | Singleton production composition root; creates, activates and closes one fresh scope, resolves the entry service through its provider, and invokes the caller's action. |

These names describe roles rather than prescribe separate interfaces and implementation classes for every row. An implementation may keep internal components package-private where no useful public contract is required. The scope factory and adapter must register with the correct bean factory before application-scoped beans are instantiated. A second application context has its **own** factory, adapter and binding key, even when tests bootstrap both in the same JVM.

The minimum intended consumer API is illustrative; exact Java package names and exception types are chosen in the implementation plan:

```java
interface ServiceScopeFactory {
    ServiceScope createScope();
}

interface ServiceScope extends AutoCloseable {
    <T> T run(Function<ServiceProvider, T> action);
    @Override void close();
}

interface ServiceProvider {
    <T> T getRequiredService(Class<T> serviceType);
}

interface OperationDispatcher {
    <S, R> R dispatch(Class<S> entryType, Function<S, R> operation);
}
```

The production path is conceptually:

```java
try (ServiceScope scope = scopeFactory.createScope()) {
    return scope.run(provider ->
        operation.apply(provider.getRequiredService(entryType)));
}
```

A scope instance is **not** the ambient binding itself. `ServiceScope.run(...)` binds its owner factory's `ScopedValue<ServiceScope>` key for the duration of the synchronous callback. Spring's ordinary recursive `BeanFactory` calls can then discover the bound instance through the custom `Scope` adapter, including when resolving nested constructor dependencies or an `@Bean` factory method. Binding automatically unwinds on success and failure; closing the scope is a separate owner-controlled step performed in `finally`/try-with-resources. All entry-service creation passes through Spring; there is no `BeanDefinition` inspection or `ActivatorUtilities`-style recursive alternative resolver.

## Requirements and invariants

### Resolution, identity and ownership

- **DI-01 - Spring ownership:** Spring remains the sole DI container and the only bean construction and dependency-selection pipeline. Bean-name-based `operation` caching follows the `Scope` SPI. Standard Spring singleton and prototype semantics are unchanged.
- **DI-02 - Scoped identity:** Repeated resolution of one operation-scoped bean, including indirect constructor injection into multiple collaborators, returns the same instance inside one active `ServiceScope`. Separately created scopes never share scoped bean caches; application-context singletons remain shared.
- **DI-03 - Scoped provider:** `ServiceProvider` delegates `getRequiredService(Class<T>)` to Spring's bean factory and rejects resolution unless its own `ServiceScope` is active, current, open and owned by the calling thread. It cannot be used as a cross-scope or post-close escape hatch. Product/application services use constructor injection, not service location.
- **DI-04 - Container isolation:** Scope activation and scoped beans belong to their owning `ApplicationContext`. An active scope in context A does not satisfy a scoped lookup through context B's bean factory. No static mutable scope registry or global thread-to-scope mapping is introduced.

### Activation, nesting and thread confinement

- **DI-05 - Lexical activation:** A new scope starts with an empty scoped-bean cache. The thread creating the scope owns it; the scope runs one synchronous callback on that thread; the binding is present for all recursive Spring resolutions and absent/restored on exit, even when the callback fails. Repeated or reentrant activation of the **same instance** is unsupported and rejected.
- **DI-06 - Independent nesting:** During an active scope A, the factory may create and activate a fresh scope B. B shadows A only for the inner callback, uses its own scoped beans, and leaves A's binding and instances intact on exit. No bean inheritance, parent pointer, implicit transaction propagation or mixing of A-owned providers with B's activation exists.
- **DI-07 - Production dispatch:** `OperationDispatcher` rejects recursive dispatch while *any* scope of its owning context is already active, including a manually created test/child scope. Other participating service methods within the same dispatch are regular calls and may reuse its scoped graph. A separate scope may be nested explicitly through the low-level factory, but this does not authorize nested business operations.
- **DI-08 - Thread safety:** Scope activation, resolution and teardown require the owning thread. Structured child threads that inherit a `ScopedValue` binding cannot use the owner's mutable scoped state. Independently dispatched operations on distinct platform/virtual threads remain isolated.

### Cleanup and failures

- **DI-09 - Terminal closure:** `ServiceScope.close()` is deterministic, terminal and safe on a never-activated scope. It executes Spring-registered destruction callbacks once per still-owned bean, clears cache/registrations and rejects subsequent activation or resolution. A failed bean creation is not retained as a successful scoped instance. Neither the scope nor its provider extends the lifetime of cached beans.
- **DI-10 - Destruction semantics:** Spring determines bean destruction behavior (`@PreDestroy`, `DisposableBean`, configured destroy methods) and supplies the registered callbacks. The scope attempts every registered callback in reverse registration order despite individual failures, preserving diagnostics and not hiding an earlier business exception. The exact callback failure aggregation follows ordinary Java suppressed-exception behavior where failures propagate. Do not assume Spring rethrows failures it may handle internally. Destruction does not permit fresh scoped bean resolution, even though the lexical execution binding has already unwound. Scoped beans are not independently closed in a second generic `AutoCloseable` sweep.
- **DI-11 - Spring SPI semantics:** `get`, `registerDestructionCallback`, `remove` and other required `Scope` methods have consistent active/closed-state rules. `remove` either implements Spring's individual-removal contract (including dropping the matching callback without running it) or explicitly rejects unsupported removal; no removed object is returned again by accident. Creation/callback-registration failure must leave the scope in a cleanable state.
- **DI-12 - Dispatcher failure handling:** An application exception is propagated after scope teardown. If teardown also fails, the original failure remains primary and cleanup failure is diagnosable; cleanup is attempted even when resolution or construction of the entry service fails. The scope remains closed and no stale `ScopedValue` binding survives.

No scope is bound to a physical database transaction. P1.3b separately specifies transaction-segment state, rollback on unfinished work, and guards for transaction-specific resources. Closing an active operation must not make an outstanding physical transaction appear committed or leak resources.

## Usage constraints and exclusions

Singletons cannot retain concrete operation-scoped dependencies. Beans and providers must not escape their owning lifetime or be used inside an unrelated active scope; these are development and review rules. Spring cannot prove that arbitrary Java references never escape, so **automatic singleton-capture detection and generic escaped-object invalidation are not P1.3a acceptance requirements**. Any later constructor/dependency-graph validation is a best-effort, independently reviewed enhancement; tests must not claim comprehensive lifetime analysis.

No async-context propagation, general hierarchy of inherited scopes, manual thread-local scope stack, `@Transactional`-on-dispatch behavior, Spring replacement container, or global service locator is part of the design. Prototype beans are not silently adopted for automatic end-of-scope destruction; Spring's prototype ownership rules remain in force. While the low-level API supports *independent nested DI scopes*, normal business ingress uses exactly one dispatcher-owned operation scope.

## Acceptance tests

Tests use real Spring bean definitions and a real registered custom scope, not a hand-built map that only mimics Spring. All are database-independent; test names/fixtures may differ from the case IDs. Every case describes an observable setup, action and expected result.

| ID | Setup and action | Expected evidence |
| --- | --- | --- |
| **DI-T01** | Register an operation-scoped bean injected into two collaborators; dispatch a service that accesses both. | Both collaborators observe the same scoped instance and mutable operation-local state, constructed by Spring. |
| **DI-T02** | Dispatch twice sequentially, then dispatch concurrently on two threads; resolve the same scoped type. | Distinct scoped instances per dispatch; shared Spring singleton identity; no leaked state across scopes. |
| **DI-T03** | Configure Spring features used by real beans (e.g., `@Bean` factory, `@Qualifier`/`@Primary`, bean post-processing) within a scoped graph. | Normal Spring selection, initialization and injection work; no competing constructor resolver is used. |
| **DI-T04** | Resolve an operation-scoped bean without an active scope, use a retained provider outside `run`, and try using parent A's provider while independent child B is active. | All invalid resolutions fail; no bean is created in an arbitrary or wrong scope. |
| **DI-T05** | Nest fresh scope B inside scope A and resolve the same type in both; return to A, including when B's callback throws. | B gets an independent instance; after B unwinds, A's original identity and binding are restored. Both scopes close independently. |
| **DI-T06** | Attempt reentrant or repeated `run` on one scope; attempt nested `OperationDispatcher.dispatch` from within an active scope. | Same-instance reactivation and recursive business dispatch fail before creating an unintended operation graph. Ordinary collaborator calls still work. |
| **DI-T07** | Activate a scope and try to resolve/close it from another thread, including an inherited binding if feasible. | Wrong-thread access is rejected; the owner's binding, cache and cleanup remain valid. |
| **DI-T08** | Create two independent Spring contexts and scope factories; activate a scope in A, resolve a scoped bean through B, then activate B independently. | B cannot borrow A's binding or objects; each context has separate scoped and singleton instances. |
| **DI-T09** | Resolve a bean with `@PreDestroy`/destroy-method cleanup; close normally and after a business exception; attempt resolution and activation after closure. | Spring destruction occurs once in both paths; cache is cleared; closed scope/provider cannot be reused. |
| **DI-T10** | Register several destruction callbacks, one throwing; separately have the business callback throw before cleanup. | Callbacks execute in reverse registration order and remaining callbacks are attempted; original business failure remains primary, cleanup failures remain diagnosable; no lingering binding. |
| **DI-T11** | Attempt supported `Scope.remove(...)` or confirm explicit unsupported behavior; arrange a bean-construction failure and a never-activated scope. | No duplicate destruction, abandoned cached bean or retained callback; cleanup behaves deterministically. |
| **DI-T12** | Use an operation-test-style callback to resolve an entry service and a collaborator, assert state, and return. | Both share the same production scope/lifecycle mechanism without substituting a test DI container or starting a database transaction. |

The developer runs the repository's offline Maven `clean verify`, checks `git diff --check`, and records the results. P1.3a has no database-facing manual user journey. Human acceptance can use a targeted Spring integration-test invocation and a review of the dispatcher/scope failure cases; P1.3c later adds PostgreSQL rollback-operation and real-commit modes.

## Deferred decisions and handoff

The [implementation plan](plan.md) assigns cohesive increments for Spring registration, owned-scope mechanics, dispatcher integration, edge-case testing and final documentation reconciliation. It may select the concrete packages, private adapter class names, exception taxonomy and callback storage details without weakening the contracts above. If Spring integration demonstrates that one of these requirements cannot be met without a material change to the architecture, the specification returns to human review before implementation proceeds.

The approved specification and plan govern the phased implementation and review workflow. No P1.3b transaction behavior or P1.3c database-test infrastructure is implied to exist merely because the DI scope has been specified.
