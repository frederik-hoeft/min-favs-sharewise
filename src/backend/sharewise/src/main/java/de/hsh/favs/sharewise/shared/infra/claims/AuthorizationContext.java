package de.hsh.favs.sharewise.shared.infra.claims;

import java.util.List;

/**
 * Read-only, operation-scoped snapshot of verified authorization credentials.
 *
 * <p>This context exposes evidence, not a general authorization decision. Domain
 * semantic contexts (such as IAM identity or Group authorization) consume it
 * to enforce their own resource and operation policies. Credentials must have
 * passed source-specific verification and any required identity/session binding
 * before they become visible here.
 */
public interface AuthorizationContext {

    /**
     * Returns separate credentials in provider order, even for the same issuer.
     *
     * @return immutable ordered credentials for this operation
     */
    List<AuthorizationCredential> credentials();

    /**
     * Finds all occurrences of a claim type, each retaining its origin.
     *
     * @param claimType type to match
     * @return immutable ordered matches across credentials
     */
    List<Claim> find(String claimType);

    /**
     * Finds exact issuer/profile-qualified claim occurrences without applying policy.
     *
     * @param issuer trusted issuer identifier to match
     * @param profile verified credential profile to match
     * @param claimType claim type to match
     * @return immutable ordered matches, each retaining its origin
     */
    List<Claim> find(String issuer, String profile, String claimType);
}
