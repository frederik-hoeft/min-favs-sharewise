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

    /**
     * Returns the authenticated IAM session binding from the same verified primary
     * credential as {@link #userId()}. Anonymous operations have neither value.
     * The binding is a canonical IAM session UUID, but it is not a credential
     * or a grant of current session eligibility.
     */
    Optional<UUID> sessionId();

    /** Returns the current session ID or fails when no authenticated caller is present. */
    default UUID requireSessionId() {
        return sessionId().orElseThrow(UnauthenticatedCallerException::new);
    }

    /** Returns the user ID or fails when no authenticated caller is present. */
    default UUID requireUserId() {
        return userId().orElseThrow(UnauthenticatedCallerException::new);
    }

    /** Returns whether the operation has an authenticated IAM caller. */
    default boolean isAuthenticated() {
        return userId().isPresent();
    }
}
