# Cross-context event messaging

ShareWise propagates business facts between bounded contexts as durable, asynchronous application events. A producer records an event in the same database transaction as the state change it describes; consumers react later in their own transactions. The guarantee is **at-least-once delivery with idempotent effects**: a committed fact is never lost, a rolled-back one is never delivered, and a consumer may see the same fact more than once. The [messaging design](../stack/messaging.md) explains the target model beyond what is implemented here, including consumer inboxes and broker externalization; this document describes the system as it runs.

## Publication registry

Delivery is built on Spring Modulith's JDBC Event Publication Registry. When an operation publishes an event, the registry writes one row per registered transactional listener into `messaging.event_publication` within the producer's current transaction. Producer rollback therefore discards the business change and its delivery obligations together, and a committed change always leaves recoverable publications behind. The registry rows track delivery, not business history: they are shared infrastructure owned by no bounded context.

Flyway owns the registry's physical schema like every other table. The `messaging` schema and the framework's PostgreSQL table are created by a platform migration, framework schema initialization is disabled, and the runtime role receives only DML privileges (see the [persistence architecture](persistence.md)). Payloads are serialized as JSON together with the event's fully qualified class name and the listener's ID, which makes both part of the persisted contract.

The registry runs in **`DELETE` completion mode**: a successfully processed publication is removed. An empty table therefore means that every recorded obligation has been delivered, and the rows that remain are exactly the outstanding or failed ones.

## Delivery to consumers

A consumer is a thin ingress adapter annotated with `@Async` and `@TransactionalEventListener` and an explicit, stable listener ID. After the producer commits, the listener runs on Spring Boot's bounded application task executor (four threads, a bounded queue, graceful shutdown, thread prefix `sharewise-async-`). It owns no transaction itself. It dispatches the consuming context's application operation through an event-side `OperationDispatcher` without caller identity, and that operation commits through its own [`Transactions`](transactions.md) segment.

Completion follows the consumer's outcome. When the operation returns normally, Spring Modulith's completion interceptor marks the publication complete in a separate transaction after the listener method has returned, which is after the consumer's commit. When the operation throws, including a failed or deferred-constraint commit, the publication stays and is marked `FAILED`. Because completion is a separate step, a crash between the consumer's commit and the completion can redeliver a fact the consumer has already applied. Every consumer is therefore idempotent, either through a natural invariant of its data or through an inbox.

## Recovery

Three mechanisms bring outstanding publications to completion without manual work:

- **Restart republication.** On startup the registry republishes every incomplete publication. This assumes a single backend instance; several instances would deliver the same rows concurrently.
- **Staleness detection.** A scheduled check marks publications as `FAILED` when they remain `PUBLISHED`, `PROCESSING` or `RESUBMITTED` for longer than ten minutes, so a delivery lost with a crashed thread becomes visible and retryable.
- **Scheduled resubmission.** `FailedPublicationResubmitter` resubmits `FAILED` publications every minute with exponential backoff per publication, from one to thirty minutes.

The resubmitter runs on the scheduler thread outside any business transaction and touches no business data. Each registry access it triggers is a short framework-managed transaction. The registry claims a publication for resubmission with a conditional `UPDATE` that succeeds only while the row is not yet `RESUBMITTED`, so concurrent runs never deliver the same row twice. The listener then runs asynchronously and opens its own operation scope and transaction exactly as during first delivery. Per run, the framework reads up to fifty of the oldest failed rows, and the filter accepts at most four that are due. The backoff check deliberately avoids deserializing payloads, so one incompatible event cannot block the others.

Publications are **never abandoned automatically**. The abandon threshold is disabled and no `AbandonPolicy` exists, because in `DELETE` mode abandoning a publication would delete it. A publication that keeps failing stays in the table, is retried at most every thirty minutes and remains visible to operators. The [development guide](../development.md#inspect-and-resubmit-event-publications) shows how to inspect it.

## Event contracts

A published event is a versioned, immutable record in the producing context's `events` named interface, for example `identity::events`. It carries a random event ID, the identifiers a consumer needs and an occurrence timestamp, but no credentials or personal data beyond those identifiers. The event ID identifies one publication across retries. Consumers that rely on a natural idempotency invariant do not need it, and nothing indexes it, so it is a random UUID rather than a time-ordered one.

Persisted payloads are deserialized strictly: unknown properties fail and record components are required. Adding, removing or renaming a field is therefore a new event version, not an in-place change. Renaming or moving an event class, or renaming or removing a listener ID, first requires draining its outstanding publications. Rows for a listener that no longer exists are never completed; an operator deletes them explicitly as the migration role after confirming that their effect is not needed.

The registry timestamps publications with an unqualified default `Clock` bean if one exists and otherwise with the system UTC clock. Module clocks such as IAM's and Group's are qualifier-only beans, so production uses the system clock and several modules can define clocks without making that lookup ambiguous. A test that defines a default or primary fixed clock also fixes the registry's notion of "now", so tests that depend on resubmission age their rows explicitly.

## IAM to Group user projection

The first integration keeps Group Management's local user registry in step with IAM. Registration publishes `UserRegisteredV1(eventId, userId, occurredAt)` on its commit path, inside the registration transaction. Group's listener `groups.iam-user-projection.v1` dispatches `IamUserProjectionService`, which inserts the user ID into `groups.users` with `INSERT … ON CONFLICT DO NOTHING`. Duplicate and concurrent deliveries therefore have the same effect as one, and Group needs no inbox. Until the event is delivered, a Group request from the newly registered user fails with `403 group_user_unknown` and succeeds on retry.

Accounts created before the listener existed are copied once by the bootstrap data migration `platform/V202611281010__platform_bootstrap_group_users_from_identity.sql`, applied while the application is stopped. This deployment-time migration is the only place where one context's data is read for another; at runtime, Group never reads IAM tables.

## Verification

PostgreSQL-backed E2E tests establish the guarantees above. They cover publication atomicity with the producer transaction, completion after a successful consumer commit, retention after consumer failures and failed commits, staleness and resubmission, and idempotent projection under duplicate and racing delivery. Database-free component tests exclude the registry auto-configuration together with the other database auto-configurations. Restart republication is verified manually on the production-style Compose stack, because the test harness cannot restart an application context against the same database.
