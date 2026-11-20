package de.hsh.favs.sharewise.shared.infra.claims;

import java.util.List;

/** Immutable, operation-scoped snapshot of independently verified claim sets. */
public interface ClaimsContext {

    /** Each credential remains a separate claim set, even for the same issuer. */
    List<ClaimSet> claimSets();

    /** All occurrences of one claim type, retaining each matching claim's source and set. */
    List<ClaimMatch> find(String claimType);

    /** Exact issuer/profile-qualified occurrences; never a cross-credential role union. */
    List<ClaimMatch> find(String issuer, String profile, String claimType);
}
