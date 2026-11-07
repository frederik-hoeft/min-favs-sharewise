# Dependency injection and application-operation scopes

**Status:** Intended architecture for review; the custom scope and dispatcher are not implemented in the starter. The [application architecture](../architecture.md) defines module ownership and application services; [transactions](persistence.md#transactions-and-failure-behavior) define physical database consistency. The [alternatives](dependency-injection-alternatives.md) record the relevant tradeoffs.

## Responsibility and ownership

ShareWise uses Spring's existing container for dependency registration, constructor injection, bean creation, initialization and framework-managed lifetimes. Stateless services and infrastructure factories normally remain Spring singleton beans. A custom **operation** bean scope adds an explicitly bounded lifetime for services and adapters that share mutable operation-local state, independently of REST request handling, message listeners and the lifetime of any particular database transaction.

An **operation** is one invocation of an application entry service, selected by an inbound adapter through an `OperationDispatcher`. The dispatcher is a singleton service and the sole public owner of scope activation. It establishes one operation context, asks Spring to resolve the entry service inside that context, invokes it, and closes the scope. The business service owns its workflow and chooses any necessary database transactions; starting an operation does not itself start a database transaction.

Operation scopes supply dependency lifetime and identity, **not** permission to access another module's data or an implicit business transaction. The module's application ports and dependency-direction rules remain unchanged. A REST controller, event listener, scheduled job or test can dispatch the same application entry service without defining its DI lifetime.

## Resolution and lifetime

Spring beans marked with the operation scope (for example, `@Scope("operation")`) are created by the ordinary Spring bean factory and retained once per bean definition per active operation. Spring resolves their constructor dependencies as usual; two participating services that request the same operation-scoped dependency receive the same instance. Bean scope registration selects a *lifetime kind*; the active operation context identifies the particular scope instance.

An operation-scoped entry service may depend directly on operation-scoped application services, adapters, and a transaction manager. There is no need for per-call `ObjectProvider` lookups in this graph. Singleton inbound adapters and singleton services must not capture a concrete operation-scoped instance: they dispatch into an active scope or, where justified, use Spring's scoped-proxy/deferred-resolution facilities. Stateless shared components remain singleton beans; creating an operation scope does not force all objects to be re-created.

The intended API shape is illustrative, not an already installed source contract:

```java
interface OperationDispatcher {
    <S, R> R dispatch(Class<S> serviceType, Function<S, R> operation);
}

// In any inbound adapter:
Result<Thing, ThingError> result = dispatcher.dispatch(
    ThingService.class,
    service -> service.rename(thingId, newName));
```

`Class<S>` supplies the service type token used by Spring's `getBean(Class<S>)`; Spring injects all constructor dependencies, so dispatching an entry service does not require a nested lambda per dependency. An operation entry service can call multiple participating services through constructor injection. This API is distinct from the database transaction port described in the [P1.3 specification](../internal/tasks/p1.3-transactions/specification.md).

## Activation, nesting and cleanup

The scope implementation integrates through Spring's supported `Scope` API. A single infrastructure-owned `ScopedValue<OperationContext>` identifies the active scope during dispatcher invocation. The `Scope` implementation delegates bean lookup, per-operation caching, removal and destruction callback registration to that context. Spring still handles bean construction, injection and initialization; no parallel DI container or arbitrary service locator is introduced.

The intended lifecycle is:

1. The dispatcher creates a fresh operation context and lexically binds it with Java 25 `ScopedValue`.
2. Inside the binding, Spring resolves the entry service and its operation-scoped dependency graph, creating each scoped bean on first resolution.
3. The service invokes participating services and explicitly opens transaction segments when needed.
4. The dispatcher closes the operation context after the entry service returns or throws, invoking registered destruction callbacks and making all managed operation-local capabilities unusable.
5. The lexical binding then ends automatically, restoring any prior binding without leaving a reusable-thread-local value.

If an operation fails or cleanup throws, the context still attempts cleanup of remaining owned resources. Original operation failures and cleanup failures remain diagnosable. Scoped-resource owners provide explicit close/invalidation behavior when they have state that could otherwise be retained by a caller. A reference escaping its scope never extends its lifetime.

**Initial nesting rule:** recursively dispatching a new operation while another operation scope is active is rejected. Calling participating services within the current operation is supported and does not create another scope. A future nested-scope feature can use lexical rebinding, but must define independent or inherited state, cleanup, and transaction interaction before being enabled. Closing an operation with a still-active transaction is a contract violation; transaction infrastructure must roll it back rather than leak resources.

A scope is confined to its owner thread for mutable service state and database work. Parallel operations have independent contexts. Java's `ScopedValue` may be inherited by structured child tasks, but that does not make Spring JDBC transactions or mutable scoped beans safely shareable across threads. Parallel or asynchronous work must dispatch an independent operation rather than reuse the parent's transaction-bound dependencies. This initial contract targets imperative services, including virtual-thread invocation without crossing to another thread mid-operation.

## Relationship to transactions and persistence

The operation-scoped transaction manager remains a stable bean across the operation, including periods with **no active physical transaction**. It may execute zero or more **sequential** transactional segments; each segment is started and physically finalized explicitly through Spring's `PlatformTransactionManager` (normally via `TransactionTemplate`). Database transactions are not created by bean resolution or by the dispatcher. A later segment does not reverse an earlier committed one.

Persistence adapters can be operation-scoped, with ordinary business-oriented methods and constructor-injected, operation-scoped resource-access capabilities. A `JooqAccess`, for example, resolves the jOOQ `DSLContext` or connection provider associated with the **currently active transaction segment** and rejects database access when no valid segment is active. Transaction-specific handles must not be cached or silently rebound for use in a later segment. Actual connections remain under Spring's transaction manager and the configured persistence infrastructure; transaction identity, capability validity and session state are verified before SQL execution, not merely when a facade is acquired.

Application service and data-port interfaces do not expose `DSLContext`, `EntityManager`, JDBC connections, or Spring transaction status. Expected business results remain separate from commit/rollback decisions. Spring Modulith event publication uses the same physical producer transaction as the corresponding business mutation, with consumer processing in a separate committed transaction. See [messaging](messaging.md) and the [P1.3 transaction specification](../internal/tasks/p1.3-transactions/specification.md).

## Enforcement and validation

The implementation is validated independently of PostgreSQL for DI scope semantics: repeated resolution within one scope preserves identity; repeated or parallel operations do not share scoped instances; use outside an active scope fails; destruction callbacks run on normal, exceptional and failed-cleanup paths; escaped guarded capabilities become unusable; and a singleton cannot accidentally capture an operation-scoped instance across invocations. Nested dispatch is rejected in the initial contract. Integration with an active Spring transaction is tested separately against the real database when P1.2 infrastructure is available.

This is a narrow, reusable Spring scope implementation. It does not introduce a general nested-scope framework, async propagation, per-operation caches outside explicitly scoped services, automatic transactions on dispatch, or custom injection/activation rules. Registration, package placement, scoped-proxy choices and teardown ordering are verified during implementation against the selected Spring version.
