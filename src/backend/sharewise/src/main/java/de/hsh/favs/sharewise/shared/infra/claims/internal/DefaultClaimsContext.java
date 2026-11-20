package de.hsh.favs.sharewise.shared.infra.claims.internal;

import de.hsh.favs.sharewise.shared.infra.claims.Claim;
import de.hsh.favs.sharewise.shared.infra.claims.ClaimMatch;
import de.hsh.favs.sharewise.shared.infra.claims.ClaimSet;
import de.hsh.favs.sharewise.shared.infra.claims.ClaimsContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Materialized once, after all providers have produced valid evidence. */
final class DefaultClaimsContext implements ClaimsContext {

    private final List<ClaimSet> sets;

    DefaultClaimsContext(List<ClaimSet> sets) {
        this.sets = List.copyOf(Objects.requireNonNull(sets, "sets"));
    }

    @Override
    public List<ClaimSet> claimSets() {
        return sets;
    }

    @Override
    public List<ClaimMatch> find(String claimType) {
        requireNonBlank(claimType, "claimType");
        return matches(null, null, claimType);
    }

    @Override
    public List<ClaimMatch> find(String issuer, String profile, String claimType) {
        requireNonBlank(issuer, "issuer");
        requireNonBlank(profile, "profile");
        requireNonBlank(claimType, "claimType");
        return matches(issuer, profile, claimType);
    }

    private List<ClaimMatch> matches(String issuer, String profile, String claimType) {
        List<ClaimMatch> matches = new ArrayList<>();
        for (ClaimSet set : sets) {
            if (issuer != null && (!issuer.equals(set.source().issuer())
                    || !profile.equals(set.source().profile()))) {
                continue;
            }
            for (Claim claim : set.claims()) {
                if (claimType.equals(claim.type())) {
                    matches.add(new ClaimMatch(set, claim));
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
