package de.hsh.favs.sharewise.identity.events;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A user account was registered and committed by IAM.
 *
 * <p>Deliberately minimal: consumers only need the stable user ID. The event never carries
 * email addresses, credentials or session data, because publications are persisted until
 * every listener has processed them.
 *
 * @param eventId stable identifier of this fact, identical across redeliveries. A random
 *     (version 4) UUID: it lives only inside the serialized payload, no table indexes it, and
 *     the Group consumer is idempotent on {@code userId}. A consumer that later keys an inbox
 *     table by event ID may switch producers to time-ordered UUIDs without a contract change.
 * @param userId IAM-issued user identifier
 * @param occurredAt registration time; metadata only, not an ordering authority
 */
public record UserRegisteredV1(UUID eventId, UUID userId, Instant occurredAt) {

    public UserRegisteredV1 {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }
}
