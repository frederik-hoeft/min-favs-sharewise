package de.hsh.favs.sharewise.shared.infra.claims;

import java.util.List;

/** Immutable, operation-scoped snapshot of independently verified claim collections. */
public interface ClaimsContext {

    /** Each credential remains a separate collection, even for the same issuer. */
    List<ClaimCollection> claimCollections();

    /** All occurrences of one claim type, each retaining its verified origin. */
    List<Claim> find(String claimType);

    /** Exact issuer/profile-qualified occurrences, never a cross-credential role union. */
    List<Claim> find(String issuer, String profile, String claimType);
}
