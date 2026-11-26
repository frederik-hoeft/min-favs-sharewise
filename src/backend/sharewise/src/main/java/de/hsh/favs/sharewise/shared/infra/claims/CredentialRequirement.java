package de.hsh.favs.sharewise.shared.infra.claims;

import java.util.Objects;
import java.util.regex.Pattern;

/** Logical acquisition identity; never a URL, verification policy or authorization grant. */
public record CredentialRequirement(String authority, String kind, String scope) {
    private static final Pattern IDENTIFIER = Pattern.compile("[a-z][a-z0-9]*(?:-[a-z0-9]+)*");

    public CredentialRequirement {
        requireIdentifier(authority);
        requireIdentifier(kind);
        Objects.requireNonNull(scope, "scope");
        if (scope.isBlank() || scope.length() > 256 || scope.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid credential scope");
        }
    }

    private static void requireIdentifier(String value) {
        if (value == null || value.length() > 64 || !IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid credential acquisition identifier");
        }
    }
}
