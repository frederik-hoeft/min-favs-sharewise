# Dependency injection: implementation status

**Status:** The operation-scoped Spring DI foundation is implemented. Its [steady-state architecture](../architecture/dependency-injection.md) is authoritative for current behavior; the [alternatives and selection rationale](dependency-injection-alternatives.md) remain here as a technology decision record.

The custom `operation` scope, the MEDI-inspired scope/provider interfaces, lexical activation and `OperationDispatcher` exist in the backend. They are transport-independent and **do not start physical transactions**. The separate [transaction architecture](../architecture/transactions.md) describes how operation-scoped services coordinate physical segments. The contracts are published to other modules as the Spring Modulith named interface `shared::di`; `shared.infra.di.internal` is not published. The repository does not yet have domain application services using these scopes in production workflows.

## Test integration

The [backend test architecture](../architecture/tests.md) uses the same Spring operation scopes as production. Integration tests own a rollback-only physical transaction and one logical segment, while E2E tests use production transactions and disposable databases. Neither introduces another dependency-injection container or alters production scope ownership. [Backend testing](../development/tests.md) documents the test-authoring helpers.
