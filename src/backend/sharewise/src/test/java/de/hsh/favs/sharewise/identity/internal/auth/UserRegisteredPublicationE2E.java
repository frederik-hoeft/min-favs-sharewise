package de.hsh.favs.sharewise.identity.internal.auth;

import static de.hsh.favs.sharewise.testsupport.execution.Eventually.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.hsh.favs.sharewise.identity.events.UserRegisteredV1;
import de.hsh.favs.sharewise.identity.internal.auth.credential.PasswordPolicy;
import de.hsh.favs.sharewise.shared.infra.di.OperationDispatcher;
import de.hsh.favs.sharewise.shared.result.Result;
import de.hsh.favs.sharewise.testsupport.e2e.E2ETestBase;
import de.hsh.favs.sharewise.testsupport.integration.IntegrationTestContext;
import de.hsh.favs.sharewise.testsupport.postgres.ProductionEntityScanConfiguration;
import de.hsh.favs.sharewise.testsupport.transactions.CommitFailures;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * P2.4a Phase B: {@link RegistrationService} records {@link UserRegisteredV1} in the registration
 * transaction (EV-01, EV-02, EV-07). A test-only listener holds delivery so committed
 * publications can be inspected; the real Group consumer is covered by IamUserProjectionE2E.
 */
@Tag("e2e")
class UserRegisteredPublicationE2E extends E2ETestBase {

    private static final String LISTENER_ID = "test.user-registered-probe.v1";
    private static final Instant NOW = Instant.parse("2026-11-20T12:00:00Z");
    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private static final String EMAIL = "Registered.User@Example.org";
    private static final String PASSWORD = "long-enough-password";

    @Test
    void committedRegistrationRecordsOneMinimalPublicationAndDeliversItOnce() {
        runTest(fixture -> {
            ProbeListener listener = fixture.context().getBean(ProbeListener.class);
            listener.hold();

            AuthenticationResult registered = success(register(fixture, EMAIL));

            List<StoredPublication> stored = publications(fixture);
            assertEquals(1, stored.size(), "exactly one publication for the probe listener");
            StoredPublication publication = stored.getFirst();
            assertEquals(LISTENER_ID, publication.listenerId());
            assertTrue(publication.payload().contains(registered.userId().toString()));
            String payload = publication.payload().toLowerCase();
            assertFalse(payload.contains("registered.user"), "no email in the registry: " + payload);
            assertFalse(payload.contains("password") || payload.contains("hash") || payload.contains("session"),
                    "no credential or session data in the registry: " + payload);

            listener.release();
            await("delivery", () -> listener.received().size() == 1);
            await("publication deletion", () -> publications(fixture).isEmpty());
            UserRegisteredV1 event = listener.received().getFirst();
            assertEquals(registered.userId(), event.userId());
            assertEquals(NOW, event.occurredAt());
            assertNotNull(event.eventId());
        }, ProductionEntityScanConfiguration.class, IamAuthenticationE2E.Fixtures.class, Probe.class);
    }

    @Test
    void unavailableEmailAndFailuresBeforeCommitRecordNoPublication() {
        runTest(fixture -> {
            ProbeListener listener = fixture.context().getBean(ProbeListener.class);
            listener.hold();
            success(register(fixture, EMAIL));
            assertEquals(1, publications(fixture).size());

            // ABSTAIN: the duplicate is rolled back, so no second obligation exists.
            assertEquals(Result.err(RegistrationFailure.EMAIL_UNAVAILABLE), register(fixture, "  " + EMAIL.toUpperCase()));
            assertEquals(1, publications(fixture).size());

            // Signer failure happens before publication: the whole segment rolls back.
            IamAuthenticationE2E.FakeIssuer issuer = fixture.context().getBean(IamAuthenticationE2E.FakeIssuer.class);
            issuer.fail.set(true);
            assertThrows(IllegalStateException.class, () -> register(fixture, "second@example.org"));
            issuer.fail.set(false);
            assertEquals(1, publications(fixture).size());

            // Commit failure after publication: account and publication roll back together (TC-03).
            IllegalStateException expected = CommitFailures.failNextCommit(fixture);
            IllegalStateException actual = assertThrows(IllegalStateException.class,
                    () -> register(fixture, "third@example.org"));
            assertSame(expected, actual);
            assertEquals(1, publications(fixture).size());
            assertEquals(1, count(fixture, "select count(*) from identity.users"));

            listener.release();
            await("delivery of the only committed registration", () -> listener.received().size() == 1);
            await("publication deletion", () -> publications(fixture).isEmpty());
            assertEquals(1, listener.received().size());
        }, ProductionEntityScanConfiguration.class, IamAuthenticationE2E.Fixtures.class, Probe.class);
    }

    private static Result<AuthenticationResult, RegistrationFailure> register(IntegrationTestContext fixture,
            String email) {
        return fixture.context().getBean(OperationDispatcher.class).dispatch(RegistrationService.class,
                service -> service.register(RegistrationCommand.from(email, PASSWORD, PasswordPolicy.initial())));
    }

    private static AuthenticationResult success(Result<AuthenticationResult, RegistrationFailure> result) {
        if (result instanceof Result.Ok<AuthenticationResult, RegistrationFailure> ok) {
            return ok.value();
        }
        throw new AssertionError("Expected successful registration but was " + result);
    }

    private static List<StoredPublication> publications(IntegrationTestContext fixture) {
        try (Connection connection = fixture.connectAsApplication();
             PreparedStatement statement = connection.prepareStatement(
                     "select listener_id, serialized_event from messaging.event_publication"
                     + " where event_type = ? and listener_id = ?")) {
            // The real Group consumer also receives each registration; observe only the probe's row.
            statement.setString(1, UserRegisteredV1.class.getName());
            statement.setString(2, LISTENER_ID);
            List<StoredPublication> found = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    found.add(new StoredPublication(rows.getString(1), rows.getString(2)));
                }
            }
            return found;
        } catch (SQLException failure) {
            throw new AssertionError("Unable to inspect messaging.event_publication", failure);
        }
    }

    private static int count(IntegrationTestContext fixture, String sql) {
        try (Connection connection = fixture.connectAsApplication();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rows = statement.executeQuery()) {
            assertTrue(rows.next());
            return rows.getInt(1);
        } catch (SQLException failure) {
            throw new AssertionError("Unable to count rows", failure);
        }
    }

    record StoredPublication(String listenerId, String payload) { }

    /** Test-only stand-in for the Group consumer; holds delivery until released. */
    static class ProbeListener {
        private final List<UserRegisteredV1> received = new CopyOnWriteArrayList<>();
        private volatile CountDownLatch gate = new CountDownLatch(0);

        void hold() {
            gate = new CountDownLatch(1);
        }

        void release() {
            gate.countDown();
        }

        List<UserRegisteredV1> received() {
            return received;
        }

        @Async
        @TransactionalEventListener(id = LISTENER_ID)
        public void on(UserRegisteredV1 event) throws InterruptedException {
            if (!gate.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                throw new IllegalStateException("Probe delivery was never released");
            }
            received.add(event);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Probe {
        @Bean
        ProbeListener userRegisteredProbeListener() {
            return new ProbeListener();
        }
    }
}
