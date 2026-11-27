package de.hsh.favs.sharewise.shared.infra.claims.internal;

import de.hsh.favs.sharewise.shared.infra.claims.AuthorizationContext;
import de.hsh.favs.sharewise.shared.infra.claims.AuthorizationCredential;
import de.hsh.favs.sharewise.shared.infra.claims.Claim;
import de.hsh.favs.sharewise.shared.infra.claims.ClaimOrigin;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Materialized once, after independent verification and contextual admission. */
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
    public List<AuthorizationCredential> credentialsFrom(ClaimOrigin origin) {
        Objects.requireNonNull(origin, "origin");
        return credentials.stream().filter(credential -> credential.origin().equals(origin)).toList();
    }

    @Override
    public List<AuthorizationCredential> credentialsFrom(String issuer, String profile) {
        requireNonBlank(issuer, "issuer");
        requireNonBlank(profile, "profile");
        return credentials.stream().filter(credential ->
            credential.origin().issuer().equals(issuer) && credential.origin().profile().equals(profile)).toList();
    }

    @Override
    public Optional<AuthorizationCredential> findCredential(String issuer, String profile, String credentialId) {
        requireNonBlank(credentialId, "credentialId");
        List<AuthorizationCredential> matches = credentialsFrom(issuer, profile).stream()
            .filter(credential -> credential.origin().credentialId().filter(credentialId::equals).isPresent()).toList();
        if (matches.size() > 1) {
            throw new IllegalStateException("Ambiguous credential identifier");
        }
        return matches.stream().findFirst();
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
        List<AuthorizationCredential> selected = issuer == null
            ? credentials : credentialsFrom(issuer, profile);
        for (AuthorizationCredential credential : selected) {
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
