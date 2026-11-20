package de.hsh.favs.sharewise.shared.infra.claims.internal;

import de.hsh.favs.sharewise.shared.infra.claims.AuthorizationContext;
import de.hsh.favs.sharewise.shared.infra.claims.AuthorizationCredential;
import de.hsh.favs.sharewise.shared.infra.claims.Claim;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Materialized once, after all providers have produced valid evidence. */
final class DefaultAuthorizationContext implements AuthorizationContext {

    private final List<AuthorizationCredential> credentials;

    DefaultAuthorizationContext(List<AuthorizationCredential> credentials) {
        this.credentials = List.copyOf(Objects.requireNonNull(credentials, "credentials"));
    }

    @Override
    public List<AuthorizationCredential> credentials() {
        return credentials;
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
        for (AuthorizationCredential credential : credentials) {
            if (issuer != null && (!issuer.equals(credential.origin().issuer())
                    || !profile.equals(credential.origin().profile()))) {
                continue;
            }
            for (Claim claim : credential.claims()) {
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
