package de.hsh.favs.sharewise.testsupport.messaging;

import de.hsh.favs.sharewise.testsupport.execution.Eventually;
import de.hsh.favs.sharewise.testsupport.integration.IntegrationTestContext;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

/**
 * Committed rows of the Spring Modulith publication registry, read on an independent
 * application-role connection. With completion mode {@code delete}, a completed publication is
 * deleted, so an absent row for a listener means every committed obligation was delivered.
 */
public final class EventPublications {

    /** A stored publication; {@code status} is null until the framework first updates the row. */
    public record StoredPublication(String listenerId, String eventType, String serializedEvent, String status,
            int completionAttempts) {
    }

    private EventPublications() {
    }

    /** The publications of the given listeners, oldest first. */
    public static List<StoredPublication> forListeners(IntegrationTestContext fixture, List<String> listenerIds)
            throws SQLException {
        Objects.requireNonNull(fixture, "fixture");
        try (Connection connection = fixture.connectAsApplication();
             PreparedStatement statement = connection.prepareStatement("""
                 SELECT listener_id, event_type, serialized_event, status, coalesce(completion_attempts, 0)
                 FROM messaging.event_publication
                 WHERE listener_id = ANY (?)
                 ORDER BY publication_date, id
                 """)) {
            statement.setArray(1, connection.createArrayOf("text", List.copyOf(listenerIds).toArray()));
            List<StoredPublication> found = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    found.add(new StoredPublication(rows.getString(1), rows.getString(2), rows.getString(3),
                        rows.getString(4), rows.getInt(5)));
                }
            }
            return found;
        }
    }

    public static int count(IntegrationTestContext fixture, String listenerId) throws SQLException {
        return forListeners(fixture, List.of(listenerId)).size();
    }

    public static int count(IntegrationTestContext fixture, String listenerId, String status) throws SQLException {
        Objects.requireNonNull(status, "status");
        return (int) forListeners(fixture, List.of(listenerId)).stream()
            .filter(publication -> status.equals(publication.status())).count();
    }

    /** Waits until the listeners have no outstanding publications. */
    public static void awaitCompleted(IntegrationTestContext fixture, List<String> listenerIds) {
        Eventually.await("completion of " + listenerIds, () -> forListeners(fixture, listenerIds).isEmpty());
    }

    /**
     * Waits until the listener has exactly one publication and it satisfies {@code condition},
     * then returns that publication as read.
     */
    public static StoredPublication awaitSingle(IntegrationTestContext fixture, String listenerId, String description,
            Predicate<StoredPublication> condition) {
        Objects.requireNonNull(listenerId, "listenerId");
        Objects.requireNonNull(condition, "condition");
        AtomicReference<StoredPublication> found = new AtomicReference<>();
        Eventually.await(description, () -> {
            List<StoredPublication> publications = forListeners(fixture, List.of(listenerId));
            if (publications.size() == 1 && condition.test(publications.getFirst())) {
                found.set(publications.getFirst());
                return true;
            }
            return false;
        });
        return found.get();
    }
}
