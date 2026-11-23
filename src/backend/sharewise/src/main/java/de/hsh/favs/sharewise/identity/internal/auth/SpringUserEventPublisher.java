package de.hsh.favs.sharewise.identity.internal.auth;

import de.hsh.favs.sharewise.identity.events.UserRegisteredV1;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Publishes IAM integration events through Spring; the Spring Modulith registry records one
 * publication per transactional listener in the caller's transaction.
 */
public final class SpringUserEventPublisher implements UserEventPublisher {

    private final ApplicationEventPublisher events;

    public SpringUserEventPublisher(ApplicationEventPublisher events) {
        this.events = Objects.requireNonNull(events, "events");
    }

    @Override
    public void userRegistered(UUID userId, Instant registeredAt) {
        events.publishEvent(new UserRegisteredV1(UUID.randomUUID(), userId, registeredAt));
    }
}
