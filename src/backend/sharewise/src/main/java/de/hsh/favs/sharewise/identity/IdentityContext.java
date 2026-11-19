package de.hsh.favs.sharewise.identity;

import java.util.Optional;
import java.util.UUID;

/**
 * The authenticated IAM caller associated with the current application operation.
 *
 * <p>The identity is stable for the operation. An empty result represents an
 * anonymous operation; invalid credentials and provider failures must instead
 * propagate as errors. Presence alone does not imply current account eligibility
 * or authorization to access a particular resource.
 *
 * <p>Implementations are supplied through operation-scoped dependency injection.
 */
public interface IdentityContext {

    /** Returns the authenticated user's stable IAM ID, if one is present. */
    Optional<UUID> userId();

    /** Returns the user ID or fails when no authenticated caller is present. */
    default UUID requireUserId() {
        return userId().orElseThrow(UnauthenticatedCallerException::new);
    }

    /** Returns whether the operation has an authenticated IAM caller. */
    default boolean isAuthenticated() {
        return userId().isPresent();
    }
}
