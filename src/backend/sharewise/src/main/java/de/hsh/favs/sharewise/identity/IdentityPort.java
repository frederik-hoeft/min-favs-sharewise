package de.hsh.favs.sharewise.identity;

import de.hsh.favs.sharewise.shared.result.Result;

/**
 * Synchronously checks the current operation's admitted identity against authoritative IAM state.
 *
 * <p>Unlike {@link IdentityContext}, this is a potentially failing authority call rather than
 * an operation-local evidence snapshot. Invocations must occur outside an active transaction
 * segment. A successful result describes an authority decision based on the observed state,
 * with temporal validity evaluated at the returned instant. It is not a lease or a guarantee
 * that subsequent mutations are authorized.
 */
public interface IdentityPort {

    /**
     * Verifies the user and session from the same admitted primary IAM credential.
     * An anonymous caller raises {@link UnauthenticatedCallerException}; an authoritative
     * denial or a safely classified lookup outage is returned as a typed failure.
     */
    Result<VerifiedIdentity, IdentityVerificationFailure> verifyCurrent();
}
