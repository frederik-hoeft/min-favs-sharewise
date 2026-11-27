package de.hsh.favs.sharewise.shared.infra.security;

import com.nimbusds.jwt.SignedJWT;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.Map;

/** Routes bounded compact JWS values to explicitly registered trusted profiles. */
public final class SupplementaryCredentialRouter {
    public static final int MAX_TOKEN_LENGTH = 2048;
    private final Map<Route, SupplementaryCredentialVerifier> verifiers;

    public SupplementaryCredentialRouter(List<SupplementaryCredentialVerifier> registered) {
        Map<Route, SupplementaryCredentialVerifier> routes = new HashMap<>();
        Set<JwtCredentialProfile.Id> profiles = new HashSet<>();
        for (SupplementaryCredentialVerifier verifier : registered) {
            JwtCredentialProfile profile = verifier.profile();
            if (!profiles.add(profile.id()) || profile.maximumTokenLength() > MAX_TOKEN_LENGTH
                    || routes.putIfAbsent(new Route(profile.issuer(), profile.tokenType()), verifier) != null) {
                throw new IllegalStateException("Ambiguous or oversized supplementary verification profile");
            }
        }
        verifiers = Map.copyOf(routes);
    }

    public VerifiedJwtCredentialCandidate verify(String token) {
        if (token == null || token.length() > MAX_TOKEN_LENGTH) {
            throw new JwtCredentialException();
        }
        SupplementaryCredentialVerifier verifier;
        try {
            SignedJWT parsed = SignedJWT.parse(token);
            String issuer = parsed.getJWTClaimsSet().getIssuer();
            String type = parsed.getHeader().getType() == null ? null : parsed.getHeader().getType().toString();
            verifier = verifiers.get(new Route(issuer, type));
        } catch (Exception invalid) {
            throw new JwtCredentialException();
        }
        if (verifier == null) {
            throw new JwtCredentialException();
        }
        return verifier.verify(token);
    }

    private record Route(String issuer, String type) { }
}
