package de.hsh.favs.sharewise.shared.infra.claims;

import java.util.List;
import java.util.Optional;

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
     * Returns all credentials whose full origin equals the supplied origin.
     * Multiple independently issued credentials may share equivalent origin metadata.
     *
     * @param origin exact, immutable provenance metadata
     * @return immutable ordered matches
     */
    List<AuthorizationCredential> credentialsFrom(ClaimOrigin origin);

    /**
     * Returns the credentials issued under a particular trusted issuer/profile.
     * These identifiers filter evidence; they do not establish business authority.
     *
     * @param issuer issuer identifier
     * @param profile source-specific credential profile
     * @return immutable ordered matches
     */
    List<AuthorizationCredential> credentialsFrom(String issuer, String profile);

    /**
     * Finds one uniquely identified credential within an issuer and profile.
     * Credential identifiers are optional provenance metadata, not global IDs;
     * ambiguity is rejected rather than selecting an arbitrary credential.
     *
     * @param issuer issuer identifier
     * @param profile credential profile
     * @param credentialId issuer-scoped credential identifier
     * @return the credential, if exactly one exists
     * @throws IllegalStateException when more than one credential matches
     */
    Optional<AuthorizationCredential> findCredential(String issuer, String profile, String credentialId);

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
