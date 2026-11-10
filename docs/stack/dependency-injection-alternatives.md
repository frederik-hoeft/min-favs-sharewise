# Dependency injection and operation-scope alternatives

**Status:** Technology selection rationale for the [implemented application-operation scope](../architecture/dependency-injection.md). Future transaction-aware integrations are tracked separately in the [stack roadmap](dependency-injection.md).

## Selection criteria

The DI foundation must support the same application operation from HTTP, event, scheduled and test entry points; reuse a deterministic set of operation-local services across nested *service calls*; control disposal without assuming a request or database-transaction lifetime; and support multiple sequential physical transactions within one operation. Spring must remain the sole dependency-injection container, and ordinary stateless singletons should remain simple to inject and test.

## Selected approach: Spring custom operation scope

An independent `ServiceScope` instance owns operation-local bean identity and destruction callbacks; the `ServiceScopeFactory` creates it, the dispatcher controls its lifetime, and a `ServiceProvider` facade delegates all resolution to Spring. Java 25 `ScopedValue` conveys the currently active scope instance lexically to the registered Spring `Scope` adapter. The physical transaction manager and persistence providers remain separate. This preserves constructor injection for stateful operation participants and allows the operation scope to outlive one database transaction without extending any transaction-specific resources.

This adds a small but correctness-sensitive infrastructure layer. The Spring integration is verified by focused tests for teardown, scope/provider misuse, nested-dispatch rejection and thread confinement. Singleton-to-scoped capture is prohibited by development conventions and review; automatic detection is a deferred best-effort enhancement. Spring handles bean construction, constructor injection and dependency resolution; the project does not reimplement those features.

## Alternatives considered

| Alternative | Tradeoff and decision |
| --- | --- |
| Stateless singleton services with `@Transactional` application methods | Idiomatic and economical Spring default. Rejected as the *sole* lifecycle mechanism because it does not supply operation-local stateful DI instances or an explicit dispatcher-owned scope. Programmatic Spring transaction management remains selected for physical transactions. |
| Singleton services plus `TransactionTemplate` and explicit `TransactionSession` parameters | Strong explicit ownership and a straightforward fallback, but transaction membership or factories must be threaded through participating service graphs. Less ergonomic for multiple transaction-aware adapters sharing one operation. |
| Per-transaction assisted factories and prototype beans (`ObjectProvider`) | Enables stateful transaction-bound adapters without a custom scope. It requires coordinating object construction/reuse/cleanup manually for a larger participating graph; prototype creation alone does not define a shared, disposable lifetime. Still useful for isolated specialized objects. |
| Spring web request or HTTP session scope | Couples service lifetime to a transport rather than an application operation. Event listeners and scheduled tasks would require different rules; it cannot be the shared application boundary. |
| Spring `SimpleTransactionScope` | Lifetime follows the Spring physical transaction and can end between two transactional segments inside one application operation. Useful for transaction-local resources, but not the operation's DI graph. |
| Spring `SimpleThreadScope` or independently managed `ThreadLocal` state | Thread identity is not operation identity. Reused threads, missing destruction callbacks, and separate ambient state holders make lifecycle harder to reason about. Spring's standard transaction resource binding remains an internal implementation constraint, not the DI scope identity. |
| Independent `IServiceScopeFactory`-style DI container or recursive provider-driven activator | Duplicates Spring bean candidate selection, constructor injection, post-processors and singleton ownership. A narrow MEDI-inspired scope/provider **facade** is selected, but Spring remains the only bean-construction engine. |
| Direct construction of all scoped service graphs in application code | Explicit and testable, but leaks infrastructure creation into orchestration and scales poorly when a workflow composes several services. Kept as a possible alternative if the custom scope fails its acceptance tests. |

## Consequences and boundaries

`ScopedValue` supplies a lexically bounded identity, but scope *contents* can still be mutable and must not cross threads unsafely. Scoped services must not cache physical transaction connections, JPA persistence contexts or segment-specific jOOQ handles after finalization. Independent nested **DI** scope bindings are supported, but nested application-operation dispatch and cross-thread scope transfer remain excluded from the initial business-operation contract. This decision does not commit ShareWise to using `@Transactional` on application services, to treating a Spring request as an operation, or to using a dedicated DI container.

The main alternative worth revisiting if the scope proves disproportionately complex is explicit transaction sessions plus assisted factories. That fallback preserves the transaction and module ownership contracts while reducing DI lifecycle machinery at the cost of more explicit composition.
