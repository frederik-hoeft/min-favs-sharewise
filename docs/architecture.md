# Application architecture

**Status:** Target modular architecture. The [operation-scoped DI](architecture/dependency-injection.md) and [transaction management](architecture/transactions.md) infrastructure and executable module-boundary verification are implemented; business modules, PostgreSQL bootstrap and messaging remain planned. Except where stated otherwise, the component contracts here describe the intended system rather than code already in the repository.

## System organization

ShareWise starts as a **modular monolith** with a hexagonal (ports-and-adapters) architecture **inside each bounded context**. The primary modules are User & Identity Management, Group Management, Financial Management, File Management, and Notification Management. They are ownership and language boundaries, not automatically one deployment each. Consult [the domain model](data-model-design.md) for their responsibilities and invariants.

Each module owns its data, domain model, commands, queries, published APIs and outbound ports. Other modules access it **only via its supported API or integration events**. The initial shared PostgreSQL deployment must not become a license for cross-context joins, foreign keys, repositories, or transactions. Extraction to separate services should replace integration adapters rather than rewrite domain operations. Cross-context synchronous calls are not categorically forbidden, but are not a default composition mechanism; a concrete use case must establish a reviewed integration boundary and independent operation-scope/transaction semantics before introducing them. Event-driven propagation is preferred for independent, asynchronously consistent state.

```mermaid
flowchart TB
    inbound[REST / scheduled jobs / event consumers] --> api[Published module API]
    api --> app[Operation-specific application services]
    app --> rules[Domain rules and value objects]
    app --> ports[Outbound ports]
    ports --> data[Database adapters]
    ports --> integration[Event and external-service adapters]
    data --> db[(Module-owned database tables)]
    integration --> others[Other contexts / broker]
```

Runtime calls typically travel from inbound adapters to the application core and through outbound ports. Source dependencies point toward the interfaces owned by the core; the infrastructure adapters implement those interfaces. Diagram layers are conceptual: do not create a facade, interface, mapper, or package for every trivial method merely to mirror this picture.

### Module structure and published APIs

Each bounded context is one direct sub-package of the application root `de.hsh.favs.sharewise`: `identity`, `groups`, `finance`, `files` and `notifications`. Business-independent infrastructure lives in the `shared` module. Spring Modulith derives the application modules from this layout. A module's package is created by the first vertical slice that needs it; `shared` is currently the only module.

A module's **top-level package is its published API**: the operation-focused command and query contracts and immutable records that other modules may use. Implementation, domain internals and adapters live in sub-packages. These remain internal to the module even when Java requires a type to be `public` for wiring across the module's own packages. A sub-package becomes part of the API only through an explicit Spring Modulith **named interface**, referenced as `module::name`, and only where a module publishes a genuinely separate contract. Types that need no access from other packages stay package-private.

Every module is a closed module and declares its permitted dependencies with `@ApplicationModule(allowedDependencies = {...})` in its base `package-info.java`, listing each module API or named interface it uses. A new cross-module dependency therefore changes that declaration and is visible in review. The declaration records a dependency but does not approve it: a synchronous cross-context call still requires the reviewed integration boundary described above. Dependencies on `shared` are listed explicitly, for example `"shared :: di"`, rather than implicitly allowed for all modules.

`shared` declares `allowedDependencies = {}`, so infrastructure can never depend on a business context, and the module graph stays acyclic and extractable. Its top-level package publishes nothing; each infrastructure capability has its own named interface. The DI contracts in `shared.infra.di` are published as `shared::di`, while their Spring implementations in `shared.infra.di.internal` are not.

## Operation- and data-flow-oriented services

Published interfaces expose meaningful command and query operations. DI-managed, normally stateless application services implement them; small cohesive services can be used directly without a pass-through facade. Application input and output contracts use **immutable Java records** where suitable, independently of JPA entities and transport-specific types. Cross-context references use stable business terminology and opaque IDs; navigable entity graphs remain within their owning module.

Typical names and responsibilities:

| Contract | Example | Meaning |
| --- | --- | --- |
| Command | `CorrectExpense` | Input to a business mutation, including expected revision |
| Query | `HistoricalBalancesQuery` | Parameterizes a read at a group revision |
| Operation result | `CorrectionResult` | Success or typed expected failure such as not-found/conflict |
| Read projection | `ExpenseSummary`, `GroupBalances` | Consumer-specific, minimal, immutable data |
| Application service | `ExpenseModificationService`, `BalanceQueryService` | Workflow, authorization, result mapping and commit decision |
| Outbound port | `ExpenseCorrectionPort`, `BalanceReadPort` | Data capabilities required by that workflow |
| Adapter | `ExpensePersistenceAdapter` | Efficient implementation of port semantics |

Outbound data ports describe use-case operations instead of exposing generic `findAll()/save()` repositories. A read port may perform an `EXISTS`, joined validation, filtered pagination, aggregation, window-function history reconstruction, or a conditional SQL mutation **inside the database**. Domain invariants and port semantics remain application-owned, while physical queries and ORM entities remain internal to adapters. Database-resident facts are evaluated with targeted SQL operations rather than by loading entire aggregates. Integration tests verify database-enforced invariants.

Command and query paths need not be symmetrical. Queries generally return direct projections without change tracking or rich aggregate reconstruction; commands retrieve only facts or state required to validate and commit the operation. Read/write separation does not imply separate databases, event sourcing, or a generic message bus.

## Transaction-aware ports

The application core decides when to commit through an operation-scoped `Transactions` port. The implemented [transaction architecture](architecture/transactions.md) defines its outcomes, sequential physical segments, rollback-only behavior, lifecycle and Spring-managed persistence participation. The [operation DI scope](architecture/dependency-injection.md) owns service lifetime independently of physical transaction boundaries; inbound adapters dispatch operations without owning their commits.

Application services use domain-oriented outbound ports and interpret typed business results to select the disposition of each segment. Infrastructure adapters implement those ports with segment-bound jOOQ or JPA accessors; business APIs do not expose database connections, persistence sessions or transaction status. A segment is local to the owning application's data boundary, not an authorization to write another context's tables.

Finance applies an additional business protocol **inside** this generic transaction:

1. Lock the FinancialGroup for the relevant group (or use an equivalent proven serialization protocol); enforce lifecycle/authorization and optimistic entity-revision preconditions.
2. Validate local and database-resident invariants using targeted state/fact projections.
3. Allocate the next **group-local financial revision**, persist the immutable Expense/Settlement state and its children, and append the corresponding ChangeRecord.
4. Persist an integration-event publication record in the same transaction when required; only then authorize commit.

All financial mutations in one group must follow the same ordering and locking protocol. A database sequence or independent auto-increment IDs alone cannot guarantee the required logical commit order. Historical reads at one group revision must be consistent. The exact COW tables and indexes are infrastructure choices; the [persistence architecture](stack/persistence.md) describes the preliminary database and adapter technology decisions. See [domain history](data-model-design.md#financial-revisions-and-history).

## Integration events and extraction

The [preliminary messaging architecture](stack/messaging.md) uses versioned integration facts and Spring Modulith's persistent, JDBC-backed Event Publication Registry for durable asynchronous cross-context propagation. A producer records publication intent atomically with its state change; consumers process independently and idempotently, using a transactional inbox or a proven business-data invariant. Delivery may be duplicated, delayed or out of order; unsuccessful listeners remain recoverable. Failed notifications do not roll back Finance.

**Finance's ChangeRecord journal is business-visible financial history, not the delivery registry or an event store.** Group Management remains membership authority; Finance maintains an eventually synchronized local FinancialActor representation. Rejoining reactivates the same financial identity. An external broker is introduced on justified service extraction without changing context-owned business operations. Each context owns its transactions and tables even when deployed in the same process.

The [preliminary security architecture](stack/security.md) separates IAM identity/session assertions from issuer-qualified domain attestations. Group Management owns group permissions; consumers validate independently signed claims and enforce their own resource and business rules. Published authorization capabilities provide immediate checks within the modulith. Authoritative remote introspection remains a possible later extension if an extracted service requires stricter freshness. The [generic file ACL proposal](file-management-acl-proposal.md) is a separate, unaccepted extension of this trust model.

## Infrastructure integration

The [technical stack overview](stack/overview.md) maps the selected frameworks and tools to the runtime layers and distinguishes the starter from the intended system. The [persistence](stack/persistence.md), [messaging](stack/messaging.md) and [security](stack/security.md) designs own their corresponding technology-specific contracts; this document owns the module boundaries and application interaction model.

Backend services use Spring constructor injection. Stateless shared infrastructure normally remains singleton-scoped, while the [application-operation DI scope](architecture/dependency-injection.md) gives stateful entry services, participants and adapters one explicitly dispatched lifetime. Operation-scoped `Transactions` uses Spring's programmatic transaction manager (normally `TransactionTemplate`) to physically finalize each segment. The dispatcher, REST adapters, message listeners and application-core services need no `@Transactional` annotations; event publication records enlist in the business operation's physical Spring transaction. Generated jOOQ types, Hibernate entities and event-delivery infrastructure stay inside adapters.

Application operations generally use straightforward imperative Java; virtual threads remain subject to connection-pool capacity and database locking. Architectural tests enforce allowed module dependencies, and PostgreSQL integration tests establish real persistence and concurrency semantics as these components are implemented.

## Architecture checks

Tests cover domain invariants, invalid input, concurrency races and commit, rollback and failure paths; integration tests exercise real adapters. Fixtures are deterministic, with property-based or invariant tests where they add meaningful coverage. Module dependencies remain acyclic, and architectural changes are reviewable and documented without introducing pass-through abstractions.

Module boundaries are verified with every backend build. A test analyzes the compiled application with Spring Modulith and fails on access to another module's unpublished types, on dependencies missing from a module's `allowedDependencies` declaration, and on cycles between modules. A project-owned check additionally fails for any module that declares no whitelist, because Spring Modulith treats such a module as unrestricted. Small fixture applications in the test sources show that each of these violations is detected and that declared dependencies on published APIs pass. Verification is static bytecode analysis and needs neither a running application nor a database.
