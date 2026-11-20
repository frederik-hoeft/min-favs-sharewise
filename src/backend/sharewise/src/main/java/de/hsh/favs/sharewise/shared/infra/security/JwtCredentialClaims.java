package de.hsh.favs.sharewise.shared.infra.security;

import de.hsh.favs.sharewise.shared.infra.claims.AuthorizationCredential;
import de.hsh.favs.sharewise.shared.infra.claims.Claim;
import de.hsh.favs.sharewise.shared.infra.claims.ClaimOrigin;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;

/** Validates and translates typed JWT claims for one trusted source profile. */
final class JwtCredentialClaims {
    static final Set<String> RESERVED_CLAIMS = Set.of(
        "iss", "sub", "aud", "iat", "nbf", "exp", "jti", "sid", "principal_iss", "principal_sid");
    private static final Set<String> METADATA_CLAIMS = Set.of("iss", "aud", "iat", "nbf", "exp", "jti");
    private static final int MAX_REQUIRED_STRING_LENGTH = 256;

    private final JwtCredentialProfile profile;
    private final Clock clock;

    JwtCredentialClaims(JwtCredentialProfile profile, Clock clock) {
        this.profile = Objects.requireNonNull(profile, "profile");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    JwtClaimsSet issue(JwtIssueRequest request) {
        Objects.requireNonNull(request, "request");
        if ((profile.kind() == JwtCredentialProfile.Kind.PRIMARY) != request.principalIssuer().isEmpty()) {
            throw new IllegalArgumentException("JWT binding does not match profile kind");
        }
        validateLifetime(request.issuedAt(), request.notBefore(), request.expiresAt());
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
            .issuer(profile.issuer())
            .subject(request.subject().toString())
            .audience(List.copyOf(profile.audiences()))
            .issuedAt(request.issuedAt())
            .notBefore(request.notBefore())
            .expiresAt(request.expiresAt())
            .id(request.tokenId());
        if (profile.kind() == JwtCredentialProfile.Kind.PRIMARY) {
            claims.claim("sid", request.sessionId());
        } else {
            claims.claim("principal_iss", request.principalIssuer().orElseThrow());
            claims.claim("principal_sid", request.sessionId());
        }
        request.assertions().forEach(claims::claim);
        return claims.build();
    }

    VerifiedJwtCredential accept(Jwt jwt, VerifiedJwtCredential primary) {
        Map<String, Object> claims = jwt.getClaims();
        if (!profile.issuer().equals(claims.get("iss"))
            || !(claims.get("aud") instanceof List<?> audienceList)
            || !audienceList.stream().allMatch(String.class::isInstance)
            || audienceList.size() != profile.audiences().size()
            || !new HashSet<>(audienceList).equals(profile.audiences())) {
            throw new JwtCredentialException();
        }
        UUID subject = parseUuid(claims.get("sub"));
        String session;
        if (profile.kind() == JwtCredentialProfile.Kind.PRIMARY) {
            if (claims.containsKey("principal_iss") || claims.containsKey("principal_sid")) {
                throw new JwtCredentialException();
            }
            session = requiredString(claims.get("sid"));
        } else {
            if (claims.containsKey("sid") || primary == null
                || !primary.credential().origin().issuer().equals(requiredString(claims.get("principal_iss")))
                || !primary.userId().equals(subject)) {
                throw new JwtCredentialException();
            }
            session = requiredString(claims.get("principal_sid"));
            if (!primary.sessionId().equals(session)) {
                throw new JwtCredentialException();
            }
        }
        String tokenId = requiredString(claims.get("jti"));
        Instant issuedAt = Objects.requireNonNull(jwt.getIssuedAt());
        Instant notBefore = Objects.requireNonNull(jwt.getNotBefore());
        Instant expiresAt = Objects.requireNonNull(jwt.getExpiresAt());
        validateLifetime(issuedAt, notBefore, expiresAt);
        Instant now = clock.instant();
        Duration skew = profile.clockSkew();
        if (now.isBefore(issuedAt.minus(skew)) || now.isBefore(notBefore.minus(skew))
            || !now.isBefore(expiresAt.plus(skew))) {
            throw new JwtCredentialException();
        }
        ClaimOrigin origin = new ClaimOrigin(profile.issuer(), profile.name(),
            profile.audiences(), Optional.of(subject.toString()), Optional.of(tokenId),
            Optional.of(issuedAt), Optional.of(notBefore), Optional.of(expiresAt));
        List<Claim> translated = new ArrayList<>();
        // JSON object member order is not significant; preserve repeated array element order.
        claims.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            if (METADATA_CLAIMS.contains(entry.getKey())) {
                return;
            }
            if (entry.getValue() instanceof String value) {
                translated.add(new Claim(entry.getKey(), value, origin));
            } else if (entry.getValue() instanceof List<?> values
                && values.stream().allMatch(String.class::isInstance)) {
                for (Object value : values) {
                    translated.add(new Claim(entry.getKey(), (String) value, origin));
                }
            } else {
                throw new JwtCredentialException();
            }
        });
        return new VerifiedJwtCredential(new AuthorizationCredential(origin, translated),
            subject, session, profile.kind(), profile.clockSkew());
    }

    private void validateLifetime(Instant issuedAt, Instant notBefore, Instant expiresAt) {
        if (!expiresAt.isAfter(issuedAt) || !expiresAt.isAfter(notBefore)
            || Duration.between(issuedAt, expiresAt).compareTo(profile.maximumLifetime()) > 0) {
            throw new IllegalArgumentException("JWT lifetime is outside configured profile limits");
        }
    }

    private static UUID parseUuid(Object value) {
        String text = requiredString(value);
        UUID uuid = UUID.fromString(text);
        if (!uuid.toString().equals(text)) {
            throw new JwtCredentialException();
        }
        return uuid;
    }

    /**
     * Checks the common string-claim bound used by issuance and verification.
     *
     * @param text candidate required claim value
     * @return whether the text is nonblank and within the accepted length
     */
    static boolean isValidRequiredString(String text) {
        return text != null && !text.isBlank() && text.length() <= MAX_REQUIRED_STRING_LENGTH;
    }

    private static String requiredString(Object value) {
        if (!(value instanceof String text) || !isValidRequiredString(text)) {
            throw new JwtCredentialException();
        }
        return text;
    }
}
