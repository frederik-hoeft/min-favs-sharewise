package de.hsh.favs.sharewise.identity.internal.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import de.hsh.favs.sharewise.groups.internal.application.projection.IamUserProjectionService;
import de.hsh.favs.sharewise.identity.events.UserRegisteredV1;
import de.hsh.favs.sharewise.identity.internal.auth.credential.PasswordPolicy;
import de.hsh.favs.sharewise.shared.infra.di.OperationDispatcher;
import de.hsh.favs.sharewise.shared.result.Result;
import de.hsh.favs.sharewise.testsupport.e2e.E2ETestBase;
import de.hsh.favs.sharewise.testsupport.integration.IntegrationTestContext;
import de.hsh.favs.sharewise.testsupport.postgres.ProductionEntityScanConfiguration;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.events.FailedEventPublications;
import org.springframework.modulith.events.ResubmissionOptions;

/**
 * P2.4a Phase C: end-to-end IAM → Group user projection with the real Group consumer on
 * PostgreSQL (TC-01, TC-04 sequential and racing first insert, TC-05, TC-09). The test lives
 * beside the IAM fixtures it reuses; Group state is observed through SQL only.
 */
@Tag("e2e")
class IamUserProjectionE2E extends E2ETestBase {

    private static final String GROUP_LISTENER_ID = "groups.iam-user-projection.v1";
    private static final String BOOTSTRAP_MIGRATION =
        "db/migration/platform/V202611281010__platform_bootstrap_group_users_from_identity.sql";
    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private static final String PASSWORD = "long-enough-password";

    @Test
    void registrationIsProjectedIntoGroupUsersAndThePublicationCompleted() {
        runTest(fixture -> {
            UUID userId = register(fixture, "projected@example.org");

            await("groups.users projection", () -> projected(fixture, userId));
            await("publication completion", () -> groupPublications(fixture) == 0);
        }, ProductionEntityScanConfiguration.class, IamAuthenticationE2E.Fixtures.class);
    }

    @Test
    void duplicateDeliveryProvisionsExactlyOnce() {
        runTest(fixture -> {
            UUID userId = UUID.randomUUID();
            UserRegisteredV1 event = new UserRegisteredV1(UUID.randomUUID(), userId, Instant.now());

            project(fixture, event);
            project(fixture, event);

            assertEquals(1, count(fixture, "select count(*) from groups.users where id = ?", userId));
        }, ProductionEntityScanConfiguration.class, IamAuthenticationE2E.Fixtures.class);
    }

    @Test
    void concurrentFirstDeliveryWaitsForTheCompetingInsertAndSucceeds() {
        runTest(fixture -> {
            UUID userId = UUID.randomUUID();
            UserRegisteredV1 event = new UserRegisteredV1(UUID.randomUUID(), userId, Instant.now());
            ExecutorService pool = Executors.newSingleThreadExecutor();
            try (Connection competing = fixture.connectAsApplication()) {
                // A competing delivery has inserted the same new ID but not committed yet.
                competing.setAutoCommit(false);
                try (PreparedStatement insert = competing.prepareStatement(
                        "insert into groups.users (id) values (?) on conflict do nothing")) {
                    insert.setObject(1, userId);
                    assertEquals(1, insert.executeUpdate());
                }

                Future<?> delivery = pool.submit(() -> project(fixture, event));
                await("projection blocked on the uncommitted key", () -> waitingLocks(fixture) > 0);
                assertFalse(delivery.isDone(), "the second insert must wait for the first transaction");

                competing.commit();
                delivery.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            } catch (Exception failure) {
                throw new AssertionError("Concurrent first delivery failed", failure);
            } finally {
                pool.shutdownNow();
            }

            assertEquals(1, count(fixture, "select count(*) from groups.users where id = ?", userId));
        }, ProductionEntityScanConfiguration.class, IamAuthenticationE2E.Fixtures.class);
    }

    @Test
    void failedProjectionStaysRecoverableAndResubmissionCompletesIt() {
        runTest(fixture -> {
            // A real database failure in the consumer's own transaction, not a test double.
            asMigrator(fixture, "REVOKE INSERT ON groups.users FROM sharewise_app");
            UUID userId = register(fixture, "recoverable@example.org");

            await("failed publication", () -> failedGroupPublications(fixture) == 1);
            assertFalse(projected(fixture, userId), "the consumer write rolled back");
            assertEquals(1, count(fixture, "select count(*) from identity.users where id = ?", userId),
                "IAM registration is independent of Group processing");

            asMigrator(fixture, "GRANT INSERT ON groups.users TO sharewise_app");
            // Resubmission only selects publications older than "now" of the registry's Clock. The
            // auth Fixtures provide a fixed Clock that Spring Modulith also picks up, so publication
            // date and cutoff would be identical. Back-date the row to make it eligible.
            asMigrator(fixture, "UPDATE messaging.event_publication"
                + " SET publication_date = publication_date - INTERVAL '1 hour'"
                + " WHERE listener_id = '" + GROUP_LISTENER_ID + "'");
            fixture.context().getBean(FailedEventPublications.class).resubmit(ResubmissionOptions.defaults());

            await("projection after resubmission", () -> projected(fixture, userId));
            await("publication completion", () -> groupPublications(fixture) == 0);
        }, ProductionEntityScanConfiguration.class, IamAuthenticationE2E.Fixtures.class);
    }

    @Test
    void bootstrapMigrationProjectsPreExistingAccountsIdempotently() {
        runTest(fixture -> {
            // An account committed before the listener existed: no publication can ever deliver it.
            UUID existing = UUID.randomUUID();
            try (Connection migrator = fixture.database().connectAsMigrator();
                 PreparedStatement insert = migrator.prepareStatement("""
                     insert into identity.users (id, email, normalized_email, password_hash)
                     values (?, 'legacy@example.org', 'legacy@example.org', 'not-a-real-hash')
                     """)) {
                insert.setObject(1, existing);
                assertEquals(1, insert.executeUpdate());
            } catch (SQLException failure) {
                throw new AssertionError("Unable to seed a pre-existing account", failure);
            }
            assertFalse(projected(fixture, existing));

            String bootstrap = classpathText(BOOTSTRAP_MIGRATION);
            asMigrator(fixture, bootstrap);
            asMigrator(fixture, bootstrap);

            assertEquals(1, count(fixture, "select count(*) from groups.users where id = ?", existing));
        }, ProductionEntityScanConfiguration.class, IamAuthenticationE2E.Fixtures.class);
    }

    private static UUID register(IntegrationTestContext fixture, String email) {
        Result<AuthenticationResult, RegistrationFailure> result = fixture.context()
            .getBean(OperationDispatcher.class)
            .dispatch(RegistrationService.class,
                service -> service.register(RegistrationCommand.from(email, PASSWORD, "Test user", PasswordPolicy.initial())));
        if (result instanceof Result.Ok<AuthenticationResult, RegistrationFailure> ok) {
            return ok.value().userId();
        }
        throw new AssertionError("Expected successful registration but was " + result);
    }

    private static Void project(IntegrationTestContext fixture, UserRegisteredV1 event) {
        return fixture.context().getBean(OperationDispatcher.class).dispatch(IamUserProjectionService.class,
            projection -> {
                projection.project(event);
                return null;
            });
    }

    private static boolean projected(IntegrationTestContext fixture, UUID userId) {
        return count(fixture, "select count(*) from groups.users where id = ?", userId) == 1;
    }

    private static int groupPublications(IntegrationTestContext fixture) {
        return count(fixture, "select count(*) from messaging.event_publication where listener_id = ?",
            GROUP_LISTENER_ID);
    }

    private static int waitingLocks(IntegrationTestContext fixture) {
        try (Connection connection = fixture.connectAsApplication();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("select count(*) from pg_locks where not granted")) {
            assertTrue(rows.next());
            return rows.getInt(1);
        } catch (SQLException failure) {
            throw new AssertionError("Unable to inspect pg_locks", failure);
        }
    }

    private static int failedGroupPublications(IntegrationTestContext fixture) {
        return count(fixture,
            "select count(*) from messaging.event_publication where listener_id = ? and status = 'FAILED'",
            GROUP_LISTENER_ID);
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

    private static void asMigrator(IntegrationTestContext fixture, String sql) {
        try (Connection migrator = fixture.database().connectAsMigrator();
             Statement statement = migrator.createStatement()) {
            statement.execute(sql);
        } catch (SQLException failure) {
            throw new AssertionError("Migrator statement failed: " + sql, failure);
        }
    }

    private static String classpathText(String resource) {
        try (InputStream input = IamUserProjectionE2E.class.getClassLoader().getResourceAsStream(resource)) {
            if (input == null) {
                throw new AssertionError("Missing classpath resource " + resource);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new AssertionError("Unable to read " + resource, failure);
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
}
