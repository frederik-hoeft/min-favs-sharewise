package de.hsh.favs.sharewise.shared.infra.messaging.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import de.hsh.favs.sharewise.shared.infra.claims.AuthorizationContext;
import de.hsh.favs.sharewise.shared.infra.di.OperationDispatcher;
import de.hsh.favs.sharewise.shared.infra.persistence.jooq.JooqAccess;
import de.hsh.favs.sharewise.shared.infra.transactions.TransactionOutcome;
import de.hsh.favs.sharewise.shared.infra.transactions.Transactions;
import de.hsh.favs.sharewise.testsupport.e2e.E2ETestBase;
import de.hsh.favs.sharewise.testsupport.postgres.ProductionEntityScanConfiguration;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Scope;
import org.springframework.modulith.events.FailedEventPublications;
import org.springframework.modulith.events.ResubmissionOptions;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * P2.4a Phase A: verifies the Spring Modulith publication registry with the production
 * {@code Transactions}, operation dispatch, executor and PostgreSQL configuration before any
 * business event relies on it. Uses a test-only event, listener and projection table.
 */
@Tag("e2e")
class EventPublicationRegistryE2E extends E2ETestBase {

    static final String LISTENER_ID = "test.registry-probe.v1";
    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    /** Probe projection; the deferred self-reference lets a write fail only at physical COMMIT. */
    static final String PROBE_SCHEMA = """
        create table groups.event_registry_probe (
            id uuid primary key,
            parent_id uuid references groups.event_registry_probe(id)
                deferrable initially deferred
        )
        """;

    private OperationDispatcher dispatcher;
    private ProbeBehavior behavior;

    @BeforeEach
    void initialize() {
        startWithSchema(PROBE_SCHEMA, ProductionEntityScanConfiguration.class, Fixtures.class);
        dispatcher = context().getBean(OperationDispatcher.class);
        behavior = context().getBean(ProbeBehavior.class);
    }

    @Test
    void publicationCommitsWithTheProducerIsDeliveredAsynchronouslyAndThenDeleted() {
        UUID id = UUID.randomUUID();
        behavior.holdNextDelivery();

        assertEquals(1, publish(id, true), "publication is written inside the producer transaction");

        // The consumer is held, so the committed row is observable on an independent connection.
        await("delivery started", () -> behavior.invocations() == 1);
        Publication pending = publication(id).orElseThrow(
            () -> new AssertionError("committed publication must exist before delivery completes"));
        assertEquals(LISTENER_ID, pending.listenerId());
        assertFalse(projected(id));

        behavior.release();
        await("projection row", () -> projected(id));
        await("publication deletion", () -> publication(id).isEmpty());
        assertTrue(behavior.lastOperationWasAnonymous(), "listener operations carry no caller evidence");
        assertTrue(behavior.lastThreadName().startsWith("sharewise-async-"),
            "listener runs on the bounded application executor, was " + behavior.lastThreadName());
    }

    @Test
    void rolledBackProducerLeavesNoPublicationAndNoDelivery() {
        UUID id = UUID.randomUUID();

        assertEquals(1, publish(id, false), "publication is written inside the producer transaction");

        assertTrue(publication(id).isEmpty(), "rolled-back publication must not persist");
        assertFalse(projected(id));
        assertEquals(0, behavior.invocations(), "rolled-back events are never handed to the listener");
    }

    @Test
    void consumerRollbackKeepsAFailedPublicationThatResubmissionCompletes() {
        UUID id = UUID.randomUUID();
        behavior.plan(Mode.THROW_AFTER_WRITE, Mode.APPLY);
        publish(id, true);

        await("failed publication", () -> publication(id).map(p -> "FAILED".equals(p.status())).orElse(false));
        Publication failed = publication(id).orElseThrow();
        assertEquals(LISTENER_ID, failed.listenerId());
        assertTrue(failed.completionAttempts() >= 1, "failed attempt is counted");
        assertFalse(projected(id), "the consumer write rolled back with its exception");

        context().getBean(FailedEventPublications.class).resubmit(ResubmissionOptions.defaults());

        await("projection after resubmission", () -> projected(id));
        await("publication deletion after resubmission", () -> publication(id).isEmpty());
        assertEquals(2, behavior.invocations());
    }

    @Test
    void consumerCommitFailureDoesNotCompleteThePublication() {
        UUID id = UUID.randomUUID();
        behavior.plan(Mode.FAIL_AT_COMMIT);
        publish(id, true);

        await("listener finished", () -> behavior.finished() == 1);
        await("failed publication", () -> publication(id).map(p -> "FAILED".equals(p.status())).orElse(false));
        assertFalse(projected(id), "the deferred constraint rolled back the consumer write");
    }

    /** Returns the number of registry rows visible inside the producer transaction. */
    private int publish(UUID id, boolean commit) {
        return dispatcher.dispatch(ProbeProducer.class, producer -> producer.publish(id, commit));
    }

    private boolean projected(UUID id) {
        try (Connection connection = fixture().connectAsApplication();
             PreparedStatement statement = connection.prepareStatement(
                 "select count(*) from groups.event_registry_probe where id = ?")) {
            statement.setObject(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                return rows.getInt(1) == 1;
            }
        } catch (SQLException failure) {
            throw new AssertionError("Unable to inspect the probe projection", failure);
        }
    }

    private Optional<Publication> publication(UUID id) {
        try (Connection connection = fixture().connectAsApplication();
             PreparedStatement statement = connection.prepareStatement("""
                 select listener_id, status, completion_attempts from messaging.event_publication
                 where event_type = ? and serialized_event like ?
                 """)) {
            statement.setString(1, ProbeEvent.class.getName());
            statement.setString(2, "%" + id + "%");
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return Optional.empty();
                }
                Publication found = new Publication(rows.getString(1), rows.getString(2), rows.getInt(3));
                assertFalse(rows.next(), "exactly one publication per event and listener");
                return Optional.of(found);
            }
        } catch (SQLException failure) {
            throw new AssertionError("Unable to inspect messaging.event_publication", failure);
        }
    }

    private static void await(String description, BooleanSupplier condition) {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for " + description, interrupted);
            }
        }
        fail("Timed out waiting for " + description);
    }

    record Publication(String listenerId, String status, int completionAttempts) { }

    /** Test-only integration fact; serialized into the registry like a real event. */
    record ProbeEvent(UUID id) { }

    enum Mode { APPLY, THROW_AFTER_WRITE, FAIL_AT_COMMIT }

    /** Thread-safe scripted consumer behavior shared between the test and the async listener. */
    static final class ProbeBehavior {
        private final Deque<Mode> planned = new ArrayDeque<>();
        private final AtomicInteger invocations = new AtomicInteger();
        private final AtomicInteger finished = new AtomicInteger();
        private volatile CountDownLatch gate = new CountDownLatch(0);
        private volatile boolean lastAnonymous;
        private volatile String lastThreadName = "";

        synchronized void plan(Mode... modes) {
            planned.clear();
            planned.addAll(List.of(modes));
        }

        void holdNextDelivery() {
            gate = new CountDownLatch(1);
        }

        void release() {
            gate.countDown();
        }

        /** Called on the listener thread before the consumer operation is dispatched. */
        void enter() {
            lastThreadName = Thread.currentThread().getName();
            invocations.incrementAndGet();
            try {
                if (!gate.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Probe delivery was never released");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while holding the probe delivery", interrupted);
            }
        }

        synchronized Mode next() {
            Mode mode = planned.pollFirst();
            return mode == null ? Mode.APPLY : mode;
        }

        void recordAnonymous(boolean anonymous) {
            lastAnonymous = anonymous;
        }

        void finish() {
            finished.incrementAndGet();
        }

        int invocations() {
            return invocations.get();
        }

        int finished() {
            return finished.get();
        }

        boolean lastOperationWasAnonymous() {
            return lastAnonymous;
        }

        String lastThreadName() {
            return lastThreadName;
        }
    }

    /** Producer operation: publishes inside its own segment and commits or rolls back. */
    record ProbeProducer(Transactions transactions, JooqAccess jooq, ApplicationEventPublisher events) {
        int publish(UUID id, boolean commit) {
            return transactions.execute(() -> {
                events.publishEvent(new ProbeEvent(id));
                int visible = ((Number) jooq.dsl().fetchValue(
                    "select count(*) from messaging.event_publication where serialized_event like ?",
                    "%" + id + "%")).intValue();
                return commit ? TransactionOutcome.commit(visible) : TransactionOutcome.rollback(visible);
            });
        }
    }

    /** Consumer operation: owns its physical transaction, as production consumers will. */
    record ProbeConsumer(Transactions transactions, JooqAccess jooq, AuthorizationContext authorization,
            ProbeBehavior behavior) {
        void apply(ProbeEvent event) {
            behavior.recordAnonymous(authorization.credentials().isEmpty());
            Mode mode = behavior.next();
            UUID parent = mode == Mode.FAIL_AT_COMMIT ? UUID.randomUUID() : null;
            transactions.execute(() -> {
                jooq.dsl().execute("insert into groups.event_registry_probe (id, parent_id) values (?, ?)",
                    event.id(), parent);
                if (mode == Mode.THROW_AFTER_WRITE) {
                    throw new IllegalStateException("Simulated consumer failure after a write");
                }
                return TransactionOutcome.commit(null);
            });
        }
    }

    /** Thin ingress adapter: no transaction of its own, dispatches one operation per delivery. */
    static class ProbeListener {
        private final OperationDispatcher dispatcher;
        private final ProbeBehavior behavior;

        ProbeListener(OperationDispatcher dispatcher, ProbeBehavior behavior) {
            this.dispatcher = dispatcher;
            this.behavior = behavior;
        }

        @Async
        @TransactionalEventListener(id = LISTENER_ID)
        public void on(ProbeEvent event) {
            try {
                behavior.enter();
                dispatcher.dispatch(ProbeConsumer.class, consumer -> {
                    consumer.apply(event);
                    return null;
                });
            } finally {
                behavior.finish();
            }
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Fixtures {
        @Bean
        ProbeBehavior probeBehavior() {
            return new ProbeBehavior();
        }

        @Bean
        ProbeListener probeListener(OperationDispatcher dispatcher, ProbeBehavior behavior) {
            return new ProbeListener(dispatcher, behavior);
        }

        @Bean
        @Scope("operation")
        ProbeProducer probeProducer(Transactions transactions, JooqAccess jooq, ApplicationEventPublisher events) {
            return new ProbeProducer(transactions, jooq, events);
        }

        @Bean
        @Scope("operation")
        ProbeConsumer probeConsumer(Transactions transactions, JooqAccess jooq,
                AuthorizationContext authorization, ProbeBehavior behavior) {
            return new ProbeConsumer(transactions, jooq, authorization, behavior);
        }
    }
}
