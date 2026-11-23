package de.hsh.favs.sharewise.identity.internal.auth;

import java.time.Instant;
import java.util.UUID;

/**
 * IAM-internal port for integration events about user accounts.
 *
 * <p>Must be called inside the transaction segment that commits the corresponding account
 * change, so the business change and the delivery obligation commit or roll back together.
 */
@FunctionalInterface
public interface UserEventPublisher {

    void userRegistered(UUID userId, Instant registeredAt);
}
