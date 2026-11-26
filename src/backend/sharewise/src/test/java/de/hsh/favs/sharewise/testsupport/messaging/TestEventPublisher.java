package de.hsh.favs.sharewise.testsupport.messaging;

import de.hsh.favs.sharewise.shared.infra.di.OperationDispatcher;
import de.hsh.favs.sharewise.shared.infra.transactions.TransactionOutcome;
import de.hsh.favs.sharewise.shared.infra.transactions.Transactions;
import de.hsh.favs.sharewise.testsupport.integration.IntegrationTestContext;
import java.util.List;
import java.util.Objects;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Scope;

/**
 * Test-only producer for consumer E2E tests: publishes integration event records inside one
 * committed {@code Transactions} segment of the E2E context, exactly as a producing operation
 * does. The registry therefore records one publication per transactional listener with the
 * segment and hands them to the listeners after commit. Consumer tests use it to choose delivery
 * orders, revisions and redeliveries that no production entry point produces; it adds no
 * production entry point. Register {@link Configuration} as an application source.
 */
public final class TestEventPublisher {

    private TestEventPublisher() {
    }

    /**
     * Publishes the events in one segment and returns after its physical commit, when their
     * publications are durable and visible to independent connections.
     */
    public static void publishCommitted(IntegrationTestContext fixture, Object... events) {
        Objects.requireNonNull(fixture, "fixture");
        List<Object> published = List.of(events);
        if (published.isEmpty()) {
            throw new IllegalArgumentException("At least one event is required");
        }
        fixture.context().getBean(OperationDispatcher.class).dispatch(Producer.class, producer -> {
            producer.publish(published);
            return null;
        });
    }

    /** Operation-scoped producer; owns its segment like a production application service. */
    record Producer(Transactions transactions, ApplicationEventPublisher events) {
        void publish(List<Object> published) {
            transactions.execute(() -> {
                for (Object event : published) {
                    events.publishEvent(event);
                }
                return TransactionOutcome.commit(null);
            });
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    public static class Configuration {
        @Bean
        @Scope("operation")
        Producer testEventProducer(Transactions transactions, ApplicationEventPublisher events) {
            return new Producer(transactions, events);
        }
    }
}
