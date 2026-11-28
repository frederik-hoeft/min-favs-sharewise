package de.hsh.favs.sharewise.shared.infra.claims;

import de.hsh.favs.sharewise.shared.infra.security.VerifiedJwtCredentialCandidate;
import java.util.List;

/**
 * Operation-scoped source of independently verified credential candidates.
 * Providers do not depend on other providers or publish evidence directly.
 * Absence of an optional source is an empty list; invalid supplied evidence
 * must fail. Contextual correlation precedes publication of the snapshot.
 */
public interface AuthorizationCredentialProvider {
    List<VerifiedJwtCredentialCandidate> credentials();
}
