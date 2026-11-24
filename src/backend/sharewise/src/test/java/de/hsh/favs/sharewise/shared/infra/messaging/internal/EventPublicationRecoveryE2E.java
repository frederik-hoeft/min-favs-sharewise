package de.hsh.favs.sharewise.shared.infra.messaging.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import de.hsh.favs.sharewise.testsupport.e2e.E2ETestBase;
import de.hsh.favs.sharewise.testsupport.integration.IntegrationTestContext;
import de.hsh.favs.sharewise.testsupport.postgres.ProductionEntityScanConfiguration;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.util.ClassUtils;

/**
 * P2.4a Phase D: production recovery wiring. A publication stuck in {@code PUBLISHED} (as after a
 * lost executor hand-off) is marked failed by the staleness monitor and delivered by the
 * scheduled resubmitter; nothing abandons publications automatically.
 */
@Tag("e2e")
class EventPublicationRecoveryE2E extends E2ETestBase {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Test
    void stuckPublicationIsMarkedStaleAndResubmittedWithoutOperatorAction() {
        runTestWithSchema(EventPublicationRegistryE2E.PROBE_SCHEMA, fixture -> {
            UUID id = UUID.randomUUID();
            insertStuckPublication(fixture, id);

            await("staleness + resubmission delivered the stuck publication", () -> projected(fixture, id));
            await("publication deletion", () -> publications(fixture, id) == 0);
        }, ProductionEntityScanConfiguration.class, EventPublicationRegistryE2E.Fixtures.class, FastRecovery.class);
    }

    @Test
    void publicationsAreNeverAbandonedAutomatically() {
        runTest(fixture -> {
            ConfigurableApplicationContext context = fixture.context();
            assertEquals("-1", context.getEnvironment()
                .getProperty("spring.modulith.events.resubmission.abandon-after-attempts"));
            List<String> abandonPolicies = java.util.Arrays.stream(context.getBeanDefinitionNames())
                .filter(name -> {
                    Class<?> type = context.getType(name);
                    return type != null && ClassUtils.getAllInterfacesForClassAsSet(type).stream()
                        .anyMatch(candidate -> candidate.getSimpleName().equals("AbandonPolicy"));
                })
                .toList();
            assertTrue(abandonPolicies.isEmpty(), "DELETE mode would delete abandoned publications: " + abandonPolicies);
            assertTrue(context.getBeanNamesForType(FailedPublicationResubmitter.class).length == 1);
        }, ProductionEntityScanConfiguration.class);
    }

    /** Simulates a lost hand-off: the row exists, but no delivery was ever attempted. */
    private static void insertStuckPublication(IntegrationTestContext fixture, UUID id) {
        try (Connection connection = fixture.connectAsApplication();
             PreparedStatement statement = connection.prepareStatement("""
                 insert into messaging.event_publication
                     (id, listener_id, event_type, serialized_event, publication_date, status, completion_attempts)
                 values (?, ?, ?, ?, now() - interval '1 hour', 'PUBLISHED', 0)
                 """)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setString(2, EventPublicationRegistryE2E.LISTENER_ID);
            statement.setString(3, EventPublicationRegistryE2E.ProbeEvent.class.getName());
            statement.setString(4, "{\"id\":\"" + id + "\"}");
            assertEquals(1, statement.executeUpdate());
        } catch (SQLException failure) {
            throw new AssertionError("Unable to insert the stuck publication", failure);
        }
    }

    private static boolean projected(IntegrationTestContext fixture, UUID id) {
        return count(fixture, "select count(*) from groups.event_registry_probe where id = ?", id) == 1;
    }

    private static int publications(IntegrationTestContext fixture, UUID id) {
        return count(fixture, "select count(*) from messaging.event_publication where serialized_event like ?",
            "%" + id + "%");
    }

    private static int count(IntegrationTestContext fixture, String sql, Object parameter) {
        try (Connection connection = fixture.connectAsApplication();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, parameter);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                return rows.getInt(1);
            }
        } catch (SQLException failure) {
            throw new AssertionError("Unable to count rows", failure);
        }
    }

    private static void await(String description, BooleanSupplier condition) {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for " + description, interrupted);
            }
        }
        fail("Timed out waiting for " + description);
    }

    /** Shortens the production recovery timings for this context only. */
    @TestConfiguration(proxyBeanMethods = false)
    static class FastRecovery {
        @Bean
        static BeanFactoryPostProcessor fastRecoveryTimings(ConfigurableEnvironment environment) {
            return beanFactory -> environment.getPropertySources().addFirst(new MapPropertySource(
                "fast-event-recovery", Map.of(
                    "spring.modulith.events.staleness.check-interval", "PT1S",
                    "spring.modulith.events.staleness.published", "PT2S",
                    "sharewise.events.resubmission.interval", "PT1S",
                    "sharewise.events.resubmission.initial-backoff", "PT1S")));
        }
    }
}
