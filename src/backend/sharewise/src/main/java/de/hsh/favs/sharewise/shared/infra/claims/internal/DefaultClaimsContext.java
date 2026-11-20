package de.hsh.favs.sharewise.shared.infra.claims.internal;

import de.hsh.favs.sharewise.shared.infra.claims.Claim;
import de.hsh.favs.sharewise.shared.infra.claims.ClaimCollection;
import de.hsh.favs.sharewise.shared.infra.claims.ClaimsContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Materialized once, after all providers have produced valid evidence. */
final class DefaultClaimsContext implements ClaimsContext {

    private final List<ClaimCollection> collections;

    DefaultClaimsContext(List<ClaimCollection> collections) {
        this.collections = List.copyOf(Objects.requireNonNull(collections, "collections"));
    }

    @Override
    public List<ClaimCollection> claimCollections() {
        return collections;
    }

    @Override
    public List<Claim> find(String claimType) {
        requireNonBlank(claimType, "claimType");
        return matches(null, null, claimType);
    }

    @Override
    public List<Claim> find(String issuer, String profile, String claimType) {
        requireNonBlank(issuer, "issuer");
        requireNonBlank(profile, "profile");
        requireNonBlank(claimType, "claimType");
        return matches(issuer, profile, claimType);
    }

    private List<Claim> matches(String issuer, String profile, String claimType) {
        List<Claim> matches = new ArrayList<>();
        for (ClaimCollection collection : collections) {
            if (issuer != null && (!issuer.equals(collection.origin().issuer())
                    || !profile.equals(collection.origin().profile()))) {
                continue;
            }
            for (Claim claim : collection.claims()) {
                if (claimType.equals(claim.type())) {
                    matches.add(claim);
                }
            }
        }
        return List.copyOf(matches);
    }

    private static void requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
