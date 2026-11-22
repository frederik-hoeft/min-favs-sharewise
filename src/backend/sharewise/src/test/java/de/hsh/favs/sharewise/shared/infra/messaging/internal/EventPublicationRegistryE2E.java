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
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeEach;
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
 * {@code Transactions}, operation dispatch and PostgreSQL configuration before any business
 * event relies on it. Uses a test-only event, listener and projection table.
 */
@org.junit.jupiter.api.Tag("e2e")
class EventPublicationRegistryE2E extends E2ETestBase {

    static final String LISTENER_ID = "test.registry-probe.v1";
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private OperationDispatcher dispatcher;
    private ProbeBehavior behavior;

    @BeforeEach
    void initialize() {
        // The deferred self-reference lets a consumer write a row that only fails at physical COMMIT.
        startWithSchema("""
            create table groups.event_registry_probe (
                id uuid primary key,
                parent_id uuid references groups.event_registry_probe(id)
                    deferrable initially deferred
            )
            """, ProductionEntityScanConfiguration.class, Fixtures.class);
        dispatcher = context().getBean(OperationDispatcher.class);
        behavior = context().getBean(ProbeBehavior.class);
    }

    @Test
    void committedPublicationIsDeliveredInASeparateAnonymousOperationAndThenDeleted() {
        UUID id = UUID.randomUUID();
        publish(id, true);

        await("projection row", () -> projected(id));
        await("publication deletion", () -> publication(id).isEmpty());
        assertEquals(1, behavior.invocations());
        assertTrue(behavior.lastOperationWasAnonymous(), "listener operations carry no caller evidence");
    }

    @Test
    void rolledBackProducerLeavesNoPublicationAndNoDelivery() throws InterruptedException {
        UUID id = UUID.randomUUID();
        publish(id, false);

        Thread.sleep(1_000);
        assertTrue(publication(id).isEmpty(), "rolled-back publication must not persist");
        assertFalse(projected(id));
        assertEquals(0, behavior.invocations());
    }

    @Test
    void failingConsumerKeepsAFailedPublicationThatResubmissionCompletes() {
        UUID id = UUID.randomUUID();
        behavior.plan(Mode.THROW, Mode.APPLY);
        publish(id, true);

        await("failed publication", () -> publication(id).map(p -> "FAILED".equals(p.status())).orElse(false));
        Publication failed = publication(id).orElseThrow();
        assertEquals(LISTENER_ID, failed.listenerId());
        assertFalse(projected(id));

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

    private void publish(UUID id, boolean commit) {
        dispatcher.dispatch(ProbeProducer.class, producer -> {
            producer.publish(id, commit);
            return null;
        });
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
                 select listener_id, status from messaging.event_publication
                 where event_type = ? and serialized_event like ?
                 """)) {
            statement.setString(1, ProbeEvent.class.getName());
            statement.setString(2, "%" + id + "%");
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return Optional.empty();
                }
                Publication found = new Publication(rows.getString(1), rows.getString(2));
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

    record Publication(String listenerId, String status) { }

    /** Test-only integration fact; serialized into the registry like a real event. */
    record ProbeEvent(UUID id) { }

    enum Mode { APPLY, THROW, FAIL_AT_COMMIT }

    /** Thread-safe scripted consumer behavior shared between the test and the async listener. */
    static final class ProbeBehavior {
        private final Deque<Mode> planned = new ArrayDeque<>();
        private final AtomicInteger invocations = new AtomicInteger();
        private final AtomicInteger finished = new AtomicInteger();
        private volatile boolean lastAnonymous;

        synchronized void plan(Mode... modes) {
            planned.clear();
            planned.addAll(java.util.List.of(modes));
        }

        synchronized Mode next() {
            invocations.incrementAndGet();
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
    }

    /** Producer operation: publishes inside its own segment and commits or rolls back. */
    record ProbeProducer(Transactions transactions, ApplicationEventPublisher events) {
        void publish(UUID id, boolean commit) {
            transactions.execute(() -> {
                events.publishEvent(new ProbeEvent(id));
                return commit ? TransactionOutcome.commit(null) : TransactionOutcome.rollback(null);
            });
        }
    }

    /** Consumer operation: owns its physical transaction, as production consumers will. */
    record ProbeConsumer(Transactions transactions, JooqAccess jooq, AuthorizationContext authorization,
            ProbeBehavior behavior) {
        void apply(ProbeEvent event) {
            behavior.recordAnonymous(authorization.credentials().isEmpty());
            Mode mode = behavior.next();
            if (mode == Mode.THROW) {
                throw new IllegalStateException("Simulated consumer failure");
            }
            UUID parent = mode == Mode.FAIL_AT_COMMIT ? UUID.randomUUID() : null;
            transactions.execute(() -> {
                jooq.dsl().execute("insert into groups.event_registry_probe (id, parent_id) values (?, ?)",
                    event.id(), parent);
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
        ProbeProducer probeProducer(Transactions transactions, ApplicationEventPublisher events) {
            return new ProbeProducer(transactions, events);
        }

        @Bean
        @Scope("operation")
        ProbeConsumer probeConsumer(Transactions transactions, JooqAccess jooq,
                AuthorizationContext authorization, ProbeBehavior behavior) {
            return new ProbeConsumer(transactions, jooq, authorization, behavior);
        }
    }
}
