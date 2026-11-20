package de.hsh.favs.sharewise.shared.infra.claims;

import java.util.List;

/**
 * An operation-scoped, Spring-managed source of fully verified claim sets.
 *
 * <p>Return an empty list when an optional credential is absent. Invalid, malformed
 * or unbound supplied evidence must throw, not silently become anonymous.
 * Verification and any applicable primary-principal/session binding must be
 * complete before returning claim sets. When ordering is required, Spring
 * {@code @Order} metadata on a provider bean determines composition order.
 * Callers must not construct providers
 * from untrusted input or use this contract to bypass issuer verification.
 */
public interface ClaimProvider {
    List<ClaimSet> claims();
}
