# Dependency injection: remaining integration work

**Status:** The operation-scoped Spring DI foundation is implemented. Its [steady-state architecture](../architecture/dependency-injection.md) is authoritative for current behavior; the [alternatives and selection rationale](dependency-injection-alternatives.md) remain here as a technology decision record.

The custom `operation` scope, the MEDI-inspired scope/provider interfaces, lexical activation and `OperationDispatcher` exist in the backend. They are transport-independent and **do not start physical transactions**. The separate [transaction architecture](../architecture/transactions.md) describes how operation-scoped services coordinate physical segments. The contracts are published to other modules as the Spring Modulith named interface `shared::di`; `shared.infra.di.internal` is not published. The repository does not yet have domain application services using these scopes in production workflows.

## Planned integrations

- **P1.3c, integration-test transactions:** Build rollback-isolated and real-commit fixtures on the existing scope factory and Spring resolution path, without a second container or an alternate production dispatcher. The [test-infrastructure specification](../internal/tasks/p1.3c-integration-tests/specification.md) owns the fixture and database contracts.

These remaining integrations do not change who owns DI scopes, how Spring resolves beans, or when scoped beans are destroyed. They need separate design and acceptance evidence rather than being presented as implemented DI behavior.
