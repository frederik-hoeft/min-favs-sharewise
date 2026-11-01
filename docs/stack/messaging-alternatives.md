# Cross-context messaging alternatives

**Status:** Supporting rationale for the [preliminary messaging architecture](messaging.md), not a menu of interchangeable communication mechanisms. The selected direction uses Spring Modulith's JDBC-backed Event Publication Registry for local durable publication, consumer-owned transactional inboxes by default when necessary for persistent effects, and a real broker only when service extraction warrants it.

## Delivery and coupling

| Alternative | Why it was not selected |
| --- | --- |
| Database triggers that update other contexts' tables | A PostgreSQL trigger's writes participate in the originating transaction, providing atomicity but silently coupling physical schemas and business ownership. This breaks the independent transactions and eventual replication required for extraction. Triggers remain valid for constraints within their own database owner, not cross-context propagation. |
| PostgreSQL `LISTEN/NOTIFY` as the event bus | Notifications are emitted on commit but are not durable subscriptions with consumer acknowledgments or replay. They can wake a worker that reads a durable table, but cannot replace the publication registry. |
| Plain Spring application events or custom in-memory bus/channels | Typed local dispatch is easy, but a crash after producer commit can lose an untracked event. Building custom retry, publication tracking and listener recovery duplicates framework infrastructure. |
| An external broker from the first modulith phase | RabbitMQ or Kafka are appropriate between independently deployed services, but add deployment, offline-test and operational dependencies before transport requires them. They do not atomically coordinate business database commits with acknowledgments or remove the need for idempotent consumers. |
| Synchronous APIs for all cross-context interactions | Direct calls remain suitable for immediate authoritative decisions, but using them to propagate independent state couples availability and control flow and becomes a distributed synchronous chain after extraction. |
| Distributed transactions across contexts | They would force a shared consistency and availability boundary that contradicts the agreed ownership and eventual-consistency contracts. Strong atomicity remains within the owning context's transaction. |

## Publication and consumer reliability

| Alternative | Why it was not selected |
| --- | --- |
| JPA-backed Modulith Event Publication Registry as the default | Supported and viable, but the framework manages its own simple publication state, so entity mapping adds little application value. JDBC avoids involving Hibernate's persistence context in otherwise jOOQ-based operations and shares the existing Spring transaction. It may be substituted after demonstrated integration benefit. |
| Retain all completed publication payloads indefinitely | This adds unbounded storage and potentially sensitive historical payloads without serving the business financial journal. Completed publications are deleted; outstanding or failed ones are retained for recovery. |
| Always create a separate inbox for every event handler | Uniformity can simplify policy, but an immutable business record with a unique origin event ID or an authoritative versioned state update may already give stronger durable idempotency. A redundant ledger adds maintenance and cleanup cost; equivalent protection must be proved before omitting the default inbox for stateful consumers. |
| Publish directly to the broker after committing business state | If the process crashes or the broker is unavailable between the database commit and publication, no durable record remains from which to recover the event. Record pending publication in the producer's transaction instead. |
| Always add a custom outbox alongside Modulith's registry | The registry already provides durable, transactionally registered externalization publications. Duplicate delivery ledgers add synchronization and maintenance burden; choose a dedicated outbox only when verified broker handoff, ordering or operational needs exceed the registry's capabilities. |
| Rely solely on broker acknowledgments/offsets | A crash after committing business data but before acknowledging delivery leads to redelivery. Broker progress alone cannot establish exactly-once effects in PostgreSQL. |
| Short-lived in-memory replay cache | Restart, multiple instances and delayed replay can defeat ephemeral deduplication. Persist the marker with the business effect or use a durable domain invariant. |
| Fixed short inbox retention for all consumers | The permitted replay, retry and dead-letter horizons vary. Premature deletion allows an old event to apply again; retention follows the consumer's actual replay contract. |

Consumer-side idempotency and business authorization remain independent of the delivery transport. Revisit technology choices through explicit design review when failure, throughput or deployment requirements justify a change; preserve [context ownership and persistence guarantees](persistence.md) throughout.
