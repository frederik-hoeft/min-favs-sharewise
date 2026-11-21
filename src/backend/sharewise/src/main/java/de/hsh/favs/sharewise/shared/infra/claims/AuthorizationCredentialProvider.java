package de.hsh.favs.sharewise.shared.infra.claims;

import java.util.List;

/**
 * An operation-scoped, Spring-managed source of verified credentials.
 *
 * <p>A credential is evidence, not permission to perform a business action.
 * Domain-specific semantic contexts are responsible for interpreting it.
 *
 * <p>Return an empty list when an optional credential is absent. Invalid, malformed
 * or unbound supplied evidence must throw, not silently become anonymous.
 * Verification and any applicable primary-principal/session binding must be
 * complete before returning credentials. When ordering is required, Spring
 * {@code @Order} metadata on a provider bean determines composition order.
 * Callers must not construct providers from untrusted input or use this contract
 * to bypass issuer verification.
 */
public interface AuthorizationCredentialProvider {

    /**
     * Returns verified, origin-consistent credentials for the current operation.
     *
     * @return credentials, or an empty list if this source is legitimately absent
     */
    List<AuthorizationCredential> credentials();
}
