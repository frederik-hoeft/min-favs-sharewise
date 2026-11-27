# Persistence foundation

ShareWise uses PostgreSQL as its relational database and Flyway migrations as the authority for the physical schema. Spring Boot configures an application `DataSource` and a separate Flyway migration connection with schema-changing privileges. jOOQ and JPA/Hibernate are integrated with Spring's explicit transaction segments through operation-scoped persistence accessors.

The shared persistence infrastructure supplies transaction-bound database access to independently owned business schemas. IAM uses it for accounts, required profiles, sessions and refresh tokens; Group Management for its registry of locally known IAM users, groups, memberships and invitations; Finance for group-root provisioning and locking. The [application architecture](../architecture.md) explains module ownership, and [transaction management](transactions.md) defines transaction decisions, completion and failure semantics.

## Database topology and ownership

A single PostgreSQL database contains the following schemas:

| Schema | Current responsibility |
| --- | --- |
| `identity` | IAM accounts, required profiles, sessions and refresh tokens. |
| `groups` | Group Management's registry of locally known IAM user IDs, groups, memberships and invitations. |
| `finance` | Finance group roots and retained actor identities. |
| `files`, `notifications` | Reserved bounded-context namespaces. |
| `flyway` | Flyway's migration history, separate from the context namespaces. |

Schema boundaries make ownership visible in the physical layout, but do not provide database-enforced isolation between contexts: the single runtime database role has privileges across all five context schemas. The backend's [Spring Modulith boundaries](../architecture.md#module-structure-and-published-apis) govern dependencies between application modules; schema separation does not authorize cross-context data access.

## Schema lifecycle and database authority

Flyway runs against PostgreSQL during application startup using the migration role. Versioned SQL migrations define the database objects and are tracked in Flyway's history schema. Hibernate's `ddl-auto=validate` setting validates mapped objects against the database without generating or updating tables; it is not a substitute for reviewing or testing database constraints and indexes.

Database initialization separates two credentials and responsibilities:

| Role | Granted access |
| --- | --- |
| `sharewise_migrator` | Connect and create at database level; owns schemas and objects created by Flyway and performs schema evolution. |
| `sharewise_app` | Connect and use the context schemas; read and modify tables and sequences granted through migration-owned default privileges. It cannot create objects in those schemas or read the Flyway history. |

Development and test provisioning scripts establish their roles using disposable credentials. The [production Compose startup](../development/production-compose.md) instead reads required role passwords from protected files and creates the roles only when initializing an empty PostgreSQL data volume. On a populated volume, rotation and recovery are explicit administrative operations. Flyway's baseline migration creates the five context schemas, grants `USAGE` to the runtime role, and configures default table and sequence privileges for objects created by the migration role. Those default privileges depend on the **object-creating role**; creating an object under a different role does not automatically grant the application access.

Application requests use the runtime connection; they do not inherit the migrator's DDL authority. IAM and Finance revoke default `PUBLIC` usage on their domain types and explicitly grant it to the runtime role; Group Management does the same for its role enum and invitation count domains, and Finance does the same for its lifecycle enum. The runtime role may insert, read and delete invitation rows, but may update only their usage counter. Configuration and bootstrap commands are documented in [Backend development](../development.md#database).

### Database-first SQL metadata

IAM, Group Management and Finance use jOOQ metadata generated from their Flyway-migrated PostgreSQL schemas. Each context keeps its generated tables, records and types internal to its persistence adapters; its ports expose application-owned values instead. The generated Java sources are committed alongside migrations so ordinary builds compile against the same metadata without accessing a database or invoking the generator.

One opt-in `jooq-codegen` Maven profile generates every context's metadata. It has one execution per context schema; shared generator settings are declared once, and each execution names only its schema and its owning module's private target package, so a context that introduces tables adds an execution rather than a second mechanism. The profile fails on jOOQ metadata problems, so generating against a database that lacks a context schema aborts instead of replacing that context's committed sources with an empty package. The dedicated CI generation gate runs this actual Maven profile against a freshly Flyway-migrated database and compares the regenerated tree with committed sources. Generator and migration-tool plugin dependencies are confined to the opt-in profile; ordinary compilation and tests use only the checked-in metadata. The [development guide](../development.md#database) describes regeneration.

## Transaction-bound persistence access

`JooqAccess` and `JpaAccess` are access contracts backed by operation-scoped Spring components. An application operation may retain an accessor across sequential transaction segments, but an accessor supplies a database facade only while a compatible segment is active. Acquiring an accessor does not start a transaction.

The runtime and migration connections serve the same database through separate authorities. At runtime, the operation scope and physical transaction also have distinct responsibilities:

```mermaid
flowchart LR
    subgraph runtime["Application · sharewise_app"]
        operation["Operation scope"] --> transaction["Transactions.execute(...)"]
        operation --> access["JooqAccess / JpaAccess"]
        transaction -->|"owns physical transaction"| resources["Spring-bound database resources"]
        access -->|"segment-guarded facade"| resources
    end
    resources -->|"queries and DML"| db[("PostgreSQL")]
    flyway["Flyway · sharewise_migrator"] -->|"schema migrations and history"| db
```

`Transactions.execute(...)` establishes a Spring-managed physical transaction and provides a segment identity. Each accessor captures that identity when it returns a facade:

- `JooqAccess.dsl()` derives a jOOQ `DSLContext` from Spring Boot's transaction-aware configuration. Its guards check the active segment during connection acquisition and SQL execution; jOOQ's own transaction entry points are rejected.
- `JpaAccess.entityManager()` requires a Spring-bound JPA persistence context and returns a segment-guarded `EntityManager`. A JDBC transaction without the corresponding JPA context is insufficient.

The returned facades belong to their originating segment and owning operation/thread. They expire when the segment callback exits, even if the operation scope continues and opens another segment. A retained facade or its derived executable query cannot silently acquire the later segment's resources. The guards cover the supported jOOQ/JPA execution paths, not arbitrary JDBC access or references escaped outside them.

jOOQ statements execute against the active transaction, while Hibernate may buffer entity changes until flush and retain managed state in its first-level cache. The two access models can participate in the same Spring transaction, but their caches and write timing do not automatically synchronize. See [transaction management](transactions.md#persistence-participation-and-lifetimes) for segment lifecycle and completion behavior.

## IAM profile persistence

IAM stores presentation data separately from credentials in `identity.user_profiles`. The profile's `user_id` is both its primary key and its foreign key to the IAM account, and `display_name` is non-nullable with text-content and length constraints. Registration creates the account, profile and initial authentication state in one physical transaction. This gives every account exactly one profile as an application-maintained invariant; the PK/FK alone enforce at most one profile per account and do not require a reverse reference. Missing mandatory profiles are integrity failures, not empty names. Account updates serialize with account disablement and change the existing profile rather than creating one on demand. See the [IAM profile disclosure policy](../stack/security.md#user-profile-visibility) for read semantics.

## Group persistence

Group Management owns its local user registry, group roots, memberships and invitations. Membership foreign keys establish local existence of both the user and group. Member-scoped reads use that invariant instead of checking user existence in a separate query; the group list distinguishes a known caller with no memberships from an unknown caller. Operations requiring local existence independently, and ambiguous projection misses, use the caller admission capability. The [Phase 2 handoff](../internal/integration/phase-2-foundation.md#group-management-groupsusers) specifies the physical schema and the deferred IAM integration. Neither group authorization nor identity provisioning uses cross-context SQL.

Mutations of an existing group serialize on its root row lock. Metadata, membership and invitation administration select the root only if the caller belongs to that group in the selection snapshot; a known nonmember receives the same not-found outcome as for a missing group without waiting on its root row lock. Selection is not authorization: the separate post-lock statement observes a fresh READ COMMITTED snapshot, including changes made by a transaction that previously held the lock. Membership mutation projections join the caller and authorized target and evaluate existence predicates for other members and administrators; the application service decides whether the requested transition is permitted. Group creation inserts the root and first administrator in one transaction. Metadata replacement alone advances the metadata version; membership and invitation operations do not. The future membership replication revision is a separate contract in the [Phase 3 handoff](../internal/integration/phase-3-foundation.md).

Invitation issuance and revocation use the same root lock as membership changes. Redemption first uses a nonlocking, locally admitted invitation projection to verify the secret and availability in the application. Invalid credentials do not enter the group synchronization boundary. For a credential that passes this screening, redemption selects and locks the root by selector and local-user existence, then retrieves a fresh projection of the verifier, availability constraints, database evaluation time and existing membership. The application repeats validation and decides whether to join; membership insertion and usage advancement commit together. The physical foreign keys and row-level constraints complement rather than replace these transactional policies. Invitation policy fields and the verifier remain immutable after issuance.

Read-only operations do not take the mutation lock. Workspace details and roster share one statement snapshot, as do the administrator role and visible invitation list. Authenticated invitation preview obtains only the group name for a valid capability; its projection supplies the facts needed for token and availability validation without reserving a use. Redemption uses the same nonlocking projection and validation policy for preliminary screening, and repeats the checks after locking. A successful new-member redemption uses five SQL statements; the additional screening query runs before the root lock. Operation-oriented projections keep relational work in PostgreSQL while leaving authorization and policy in the application layer. Integration tests count actual SQL executions on selected successful paths; E2E tests exercise commit, rollback and contention rather than treating component fixtures as proof of database behavior.

## Finance persistence

Finance owns its group lifecycle and financial revision independently of organizational group membership. Its physical consistency boundary is the row in `finance.groups` identified by the upstream group UUID. `finance.actors` retains group-local participant identities using the composite primary key `(group_id, user_id)`. The actor's `group_id` references the Finance root without cascading deletion; both upstream identifiers remain opaque, with no foreign keys into Group Management or IAM. A root may have zero actors.

The two tables track different kinds of state. The root contains the Finance-owned `group_state` (`provisioning`, `open`, `closed`, `archived`), its nonnegative `current_revision` and creation timestamp. An actor records explicit membership deletion state, a strictly positive `upstream_revision` and creation/update timestamps. Finance-local PostgreSQL domains `bignatural0` and `bignatural1` enforce the respective revision ranges, with non-nullability enforced on the columns. The root defaults to `provisioning` and revision zero; the actor's deletion state and source revision have no defaults. A row-level update trigger sets the actor's `updated_at` to `now()`, the transaction-start timestamp. A transaction that started earlier but acquired the root lock later can therefore commit a higher source revision with an earlier `updated_at` than the lower revision it supersedes. Timestamps are diagnostic metadata, not a substitute for source revision ordering.

### Group-root provisioning and locking

The internal `FinancialGroupStore` separates creating a missing root from acquiring an existing root's lock. `ensureAndLock` is reserved for trusted Group-originated facts: it inserts a root with database defaults if none exists, then obtains its row lock. The caller establishes trust; this port neither authenticates the fact nor activates the root, creates an actor or advances a revision. `lockExisting` acquires the same lock without inserting a row. Its `FinancialGroupLookup` contains an optional immutable snapshot of the identity, lifecycle and financial revision; absence identifies a missing Finance root, not absence from Group Management, and carries no row lock. `ensureAndLock` returns the snapshot directly because it guarantees a root exists on success.

The lookup's `requireReady()` method provides a common initialization precondition independent of the lookup's locking strategy. It returns a typed `NOT_READY` failure for an absent or provisioning root and a successful snapshot for an open, closed or archived root, regardless of revision. Readiness is neither authorization nor writability nor actor eligibility. The caller must establish scoped authorization before exposing a readiness outcome and enforce operation-specific lifecycle and eligibility policy separately. Technical callers can inspect the optional snapshot without applying this business precondition. The guard is pure: it neither reads the database nor controls transaction completion.

The operation-scoped jOOQ adapter uses `INSERT ... ON CONFLICT (id) DO NOTHING` followed by a separate `SELECT ... FOR UPDATE` for provisioning. Under PostgreSQL's `READ COMMITTED` isolation, the separate select can observe the committed winner of a concurrent first insert. The conflict path does not update an existing root merely to acquire its lock, preserving its state, revision, creation timestamp and tuple version. Existing-root locking executes only the select.

`activate` is the root's only lifecycle transition at this layer. It requires a provisioning snapshot locked in the active segment and executes `UPDATE ... SET state = 'open'` guarded by `state = 'provisioning'`, leaving the financial revision and creation timestamp unchanged. A snapshot in another state is rejected before any SQL, and an update that changes no row fails instead of reporting an outcome. The state guard therefore keeps a stale snapshot from reopening a closed or archived root even if a caller bypasses the lock protocol.

The caller owns the physical transaction across the store invocation and its subsequent work:

```mermaid
sequenceDiagram
    participant Caller as Transaction owner
    participant Store as FinancialGroupStore
    participant DB as PostgreSQL

    Caller->>DB: Begin physical transaction
    Caller->>Store: ensureAndLock or lockExisting
    opt ensureAndLock
        Store->>DB: INSERT ... ON CONFLICT DO NOTHING
    end
    Store->>DB: SELECT ... FOR UPDATE
    alt Root found
        DB-->>Store: Root snapshot (row locked)
        Store-->>Caller: Snapshot or populated lookup
        Note over Caller,DB: Row lock survives store return
    else Root absent (lockExisting)
        DB-->>Store: No row
        Store-->>Caller: Empty lookup (no row lock)
    end
    Caller->>Caller: Evaluate outcome and perform segment work
    Caller->>DB: Commit or rollback
    Note over DB: Release any acquired row lock
```

Both methods require a non-null group ID and an active caller-owned transaction segment within an operation scope, enforced through `JooqAccess` for database access. Returning the snapshot does not release the database lock; physical transaction completion does. Concurrent lock holders for the same root serialize, whereas distinct roots do not share a group-level lock. Rollback undoes an inserted root and releases the lock. Unexpected persistence errors, including lock failures, propagate to the transaction owner rather than being treated as absence or lifecycle rejection. Both Finance stores are Spring repositories, so callers observe Spring's translated exceptions: an adapter's argument or state violation, such as a rejected activation, a failed compare-and-set or use outside an active segment, arrives as `InvalidDataAccessApiUsageException` with the original `IllegalArgumentException` or `IllegalStateException` as its cause, and SQL errors such as key violations and lock failures arrive as other `DataAccessException` subtypes. Stronger isolation levels can require whole-transaction retry; the adapter does not retry or commit independently.

### Membership projection operations

Finance retains a source-versioned projection of organizational membership and activates its root when Group Management reports group creation. The operation-scoped `FinanceProjectionService` in `finance.internal.projection` applies both trusted Group-originated facts: **activation**, the effect of `GroupCreatedV1`, and **membership change**, the effect of any full-state member event, including the initial owner's addition at revision 1. Its inputs are Finance-owned immutable commands rather than Group event types; the commands require every field and a positive source revision, and represent the resulting membership as an explicit `ACTIVE` or `REMOVED` state. Finance's inbound event adapter maps events to these commands and is the only intended caller. The service performs no caller authorization and publishes nothing to other modules, and the actor projection is never an authorization source.

Each operation runs in **one transaction segment** that it owns. It first calls `ensureAndLock`, so a fact for an unknown group provisions the root, and the **root lock precedes every read or write of that group's actors**. Activation then opens a provisioning root through `activate` and leaves any other lifecycle state untouched. If the root was absent, the same segment inserts it as provisioning and opens it, so a creation fact that precedes the owner's membership commits an open root with zero actors. Membership change reads the actor, applies a pure per-actor transition policy and performs at most one actor write. It applies in every lifecycle state, because membership synchronization is organizational replication rather than a financial business operation. Neither operation changes `current_revision`, another group's rows or any lifecycle transition other than `provisioning → open`.

The policy compares the received fact only with the retained actor of the same `(group_id, user_id)`; no group-level watermark participates, so a higher revision of one actor never suppresses a lower revision of another:

| Retained actor | Received fact | Effect | Outcome | Segment |
| --- | --- | --- | --- | --- |
| absent | any revision and state | insert with the received state and revision | `INSERTED` | commit |
| present | greater revision | supersede state and revision | `SUPERSEDED` | commit |
| present | equal revision and state | none | `DUPLICATE` | abstain |
| present | equal revision, other state | none | `ConflictingMembershipFactException` | rolled back |
| present | lower revision | none | `STALE` | abstain |

An unknown removal therefore inserts a removed tombstone, and a later rejoin supersedes the same row. Duplicate and stale facts issue no `UPDATE`, so `updated_at` is unchanged. Likewise, activating a provisioning root reports `ACTIVATED` and commits, whereas activating a root that is already open, closed or archived reports `UNCHANGED` and abstains. All typed outcomes are successes: the fact is reflected in committed state, and the caller may acknowledge it. A same-revision fact with a different state violates the producer's contract. The service throws `ConflictingMembershipFactException`, carrying the group, user, revision and both states, from inside the segment, so the segment rolls back, the retained actor is never overwritten and a caller that does not handle the exception cannot acknowledge the fact. Recovery is an operator decision.

The internal `FinancialActorStore` port reads one actor, inserts an unknown actor and supersedes a retained one. Every method requires the group's root lock in the active segment; because that lock serializes all actor reads and writes of a group, the read takes no row lock of its own. Holding the lock is a **caller-enforced precondition**: the store neither acquires nor verifies it, and `JooqAccess` checks only that a segment is active, not which locks it holds. The projection service satisfies the precondition by calling `ensureAndLock` first. The jOOQ adapter's database guards detect specific persistence conflicts rather than lock ownership. Insert relies on the primary key and the foreign key to the root, so a duplicate actor or a missing root fails. Supersede requires the same identity and a strictly greater revision, and its `UPDATE` is a compare-and-set whose `WHERE` clause names the previously read state and revision; anything other than exactly one changed row fails with that translated state violation rather than a typed outcome. Because an actor's revision only increases, the compare-and-set rejects a write derived from an actor that another transaction has changed since it was read. A write without the root lock nevertheless succeeds when no such conflict exists, even while another transaction holds the lock, so it would break the serialization that root-lock holders rely on.

The service neither catches nor maps persistence and lock failures, and it never retries. Root and actor writes share the segment's physical transaction, so they commit or roll back together. A failure inside the segment, including a contradiction, rolls the segment back, and once that rollback is confirmed nothing of the attempt is committed, including a root it inserted. An exception alone does not prove that no change committed: a failed commit attempt can leave the outcome uncertain, a failure after commit propagates without reversing durable changes, and a failed finalization can surface in place of the original exception, which is then retained as suppressed, as [transaction completion](transactions.md#completion-and-failure-behavior) describes. In every case the operation returns no outcome, so the fact is not acknowledged. Because both operations are idempotent with respect to committed state, redelivery reprocesses the fact safely whether or not the failed attempt committed. The per-actor source revision and the root's lifecycle guard thereby replace a transactional inbox for these facts, as the [messaging design](../stack/messaging.md#consumption-and-idempotency) permits. Concurrent first observations of a group converge on one root through the provisioning insert, operations on the same group serialize on its root lock, and operations on different groups proceed independently. The [Phase 3 foundation](../internal/integration/phase-3-foundation.md#finance-provisioning-ordering-and-concurrency) owns the shared event and ordering contract that these operations implement.

## Verification boundary

The backend's [test architecture](tests.md) separates database-free Unit tests, PostgreSQL Integration tests with rollback-only physical transactions, and E2E tests using independently provisioned databases for real commits. PostgreSQL tests exercise the migration/role setup, guarded jOOQ/JPA access, transaction visibility and failure behavior. Rollback-isolated tests cannot establish durable commit behavior; E2E tests cover that boundary.

Group Management's Integration tests cover its schema constraints, registry and read projections; E2E tests exercise concurrent metadata replacement and the lock ordering of a replacement against a concurrent role change. Finance schema tests exercise the required column, type, key, trigger and privilege contracts without constraining unrelated additive schema changes. Unit tests establish the readiness rule, the membership transition policy, command validation and the projection service's segment protocol against recording fakes. Finance store Integration tests cover the root and actor ports, activation, state mapping and the compare-and-set guard within an active segment. Real-commit E2E tests exercise concurrent first inserts, root locking, rollback, independent groups and lock failure propagation, and the projection operations' activation and membership synchronization in every lifecycle state, every delivery order of an add-remove-add sequence, per-actor ordering, contradiction, rollback of a failed segment, concurrent first observation, same-actor races on the root lock, a higher revision committed with an earlier `updated_at` than the lower revision it supersedes, and independence of other groups. The [persistence stack design](../stack/persistence.md) describes the wider feature contracts.
