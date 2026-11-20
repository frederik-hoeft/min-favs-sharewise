package de.hsh.favs.sharewise.shared.infra.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jwt.SignedJWT;
import de.hsh.favs.sharewise.shared.infra.claims.AuthorizationCredential;
import de.hsh.favs.sharewise.shared.infra.claims.Claim;
import de.hsh.favs.sharewise.shared.infra.claims.ClaimOrigin;
import java.security.interfaces.RSAPrivateKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * Explicitly configured, local-key JWT signer/verifier for one trusted profile.
 *
 * <p>The caller selects this codec using trusted configuration; unverified token
 * fields never select an issuer, algorithm, key source or profile. RS256 is the
 * only supported algorithm. Verification and credential-level primary session
 * binding must finish before the returned evidence may be published by an
 * {@code AuthorizationCredentialProvider}. No remote JWKS discovery occurs.
 *
 * <p>Instances are immutable and safe to share. Rotation is an explicit
 * configuration change: overlap old/new verification keys, then remove the old
 * key to revoke it. This service does not perform account/session status checks
 * or grant business permissions.
 */
public final class JwtCredentialCodec {
    static final Set<String> RESERVED_CLAIMS = Set.of(
        "iss", "sub", "aud", "iat", "nbf", "exp", "jti", "sid", "principal_iss", "principal_sid");
    private static final Set<String> REJECTED_HEADERS = Set.of("jku", "jwk", "x5u", "x5c", "crit");
    private static final int MAX_TOKEN_LENGTH = 16_384;

    private final JwtCredentialProfile profile;
    private final Clock clock;
    private final Map<String, JwtRsaKey> keys;
    private final NimbusJwtDecoder decoder;
    private final JwtEncoder encoder;
    private final String activeSigningKeyId;

    /**
     * Creates a codec from independently supplied trusted key material.
     *
     * @param profile expected issuer, protected type, audience, time policy and trust role
     * @param trustedKeys nonempty verification keys with distinct identifiers
     * @param signingKeyId ID of the active signing key; empty means verification-only
     * @param clock trusted clock used for validation (also injectable in tests)
     */
    public JwtCredentialCodec(JwtCredentialProfile profile, List<JwtRsaKey> trustedKeys,
                              Optional<String> signingKeyId, Clock clock) {
        this.profile = Objects.requireNonNull(profile, "profile");
        this.clock = Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(trustedKeys, "trustedKeys");
        Objects.requireNonNull(signingKeyId, "signingKeyId");
        if (trustedKeys.isEmpty()) {
            throw new IllegalArgumentException("JWT verification requires trusted keys");
        }
        Map<String, JwtRsaKey> byId = new HashMap<>();
        List<JWK> publicJwks = new ArrayList<>();
        for (JwtRsaKey key : trustedKeys) {
            Objects.requireNonNull(key, "trusted key");
            if (byId.putIfAbsent(key.id(), key) != null) {
                throw new IllegalArgumentException("Duplicate trusted JWT key ID");
            }
            publicJwks.add(new RSAKey.Builder(key.publicKey()).keyID(key.id())
                .keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.RS256).build());
        }
        this.keys = Map.copyOf(byId);
        this.decoder = NimbusJwtDecoder.withJwkSource(new ImmutableJWKSet<>(new JWKSet(publicJwks)))
            .jwsAlgorithm(SignatureAlgorithm.RS256)
            .jwtProcessorCustomizer(processor -> processor.setJWSTypeVerifier((type, context) -> {
                if (type == null || !profile.tokenType().equals(type.toString())) {
                    throw new com.nimbusds.jose.proc.BadJOSEException("Unexpected JWT type");
                }
            }))
            .build();
        JwtTimestampValidator timestampValidator = new JwtTimestampValidator(profile.clockSkew());
        timestampValidator.setAllowEmptyExpiryClaim(false);
        timestampValidator.setAllowEmptyNotBeforeClaim(false);
        timestampValidator.setClock(clock);
        decoder.setJwtValidator(timestampValidator);

        if (signingKeyId.isPresent()) {
            JwtRsaKey key = this.keys.get(signingKeyId.get());
            if (key == null || key.privateKey() == null) {
                throw new IllegalArgumentException("Configured JWT signing key is unavailable");
            }
            RSAPrivateKey privateKey = key.privateKey();
            RSAKey signingJwk = new RSAKey.Builder(key.publicKey())
                .privateKey(privateKey).keyID(key.id())
                .keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.RS256).build();
            this.encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(signingJwk)));
            this.activeSigningKeyId = key.id();
        } else {
            this.encoder = null;
            this.activeSigningKeyId = null;
        }
    }

    /**
     * Returns the immutable trusted profile associated with this codec.
     *
     * @return the trusted, caller-selected verification profile
     */
    public JwtCredentialProfile profile() {
        return profile;
    }

    /**
     * Signs an issuer-owned credential using the explicitly configured active key.
     * The caller must already have authorized issuance and selected the proper
     * profile. No user account or Group permission is checked here.
     *
     * @param request typed issuer-controlled claim values
     * @return signed compact JWS
     */
    public String sign(JwtIssueRequest request) {
        Objects.requireNonNull(request, "request");
        if (encoder == null) {
            throw new IllegalStateException("JWT codec is verification-only");
        }
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
        // The configured active key, not any token field, selects the signer.
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256)
            .type(profile.tokenType()).keyId(activeSigningKeyId).build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
        if (token.length() > MAX_TOKEN_LENGTH) {
            throw new IllegalArgumentException("JWT payload exceeds configured size limit");
        }
        return token;
    }

    /**
     * Verifies a primary identity JWT. A subordinate profile cannot be used
     * without a previously verified primary identity/session.
     *
     * @param token untrusted compact JWS
     * @return verified primary evidence
     * @throws JwtCredentialException for any invalid, expired or untrusted token
     */
    public VerifiedJwtCredential verifyPrimary(String token) {
        if (profile.kind() != JwtCredentialProfile.Kind.PRIMARY) {
            throw new JwtCredentialException();
        }
        return verify(token, null);
    }

    /**
     * Verifies subordinate evidence and strictly matches the already verified
     * primary issuer, user UUID and session ID before exposing any claims.
     *
     * @param token untrusted subordinate compact JWS
     * @param primary primary result produced by a trusted primary verifier
     * @return verified and bound subordinate evidence
     * @throws JwtCredentialException for any invalid or unbound token
     */
    public VerifiedJwtCredential verifySubordinate(String token, VerifiedJwtCredential primary) {
        if (profile.kind() != JwtCredentialProfile.Kind.SUBORDINATE || primary == null
            || primary.kind() != JwtCredentialProfile.Kind.PRIMARY
            || !profile.trustedPrimaryIssuers().contains(primary.credential().origin().issuer())
            || !primary.validAt(clock.instant())) {
            throw new JwtCredentialException();
        }
        return verify(token, primary);
    }

    private VerifiedJwtCredential verify(String token, VerifiedJwtCredential primary) {
        try {
            if (token == null || token.length() > MAX_TOKEN_LENGTH) {
                throw new JwtCredentialException();
            }
            SignedJWT parsed = SignedJWT.parse(token);
            Map<String, Object> headers = parsed.getHeader().toJSONObject();
            String kid = parsed.getHeader().getKeyID();
            if (!JWSAlgorithm.RS256.equals(parsed.getHeader().getAlgorithm())
                || !profile.tokenType().equals(parsed.getHeader().getType() == null
                    ? null : parsed.getHeader().getType().toString())
                || kid == null || !keys.containsKey(kid)
                || headers.keySet().stream().anyMatch(REJECTED_HEADERS::contains)) {
                throw new JwtCredentialException();
            }
            Jwt jwt = decoder.decode(token);
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
                    || !primary.credential().origin().issuer().equals(claims.get("principal_iss"))
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
            if (issuedAt.isAfter(clock.instant().plus(profile.clockSkew()))) {
                throw new JwtCredentialException();
            }
            ClaimOrigin origin = new ClaimOrigin(profile.issuer(), profile.name(),
                profile.audiences(), Optional.of(subject.toString()), Optional.of(tokenId),
                Optional.of(issuedAt), Optional.of(notBefore), Optional.of(expiresAt));
            List<Claim> translated = new ArrayList<>();
            // JSON object member order is not significant: sort to make query
            // order stable while preserving repeated array-claim element order.
            claims.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
                if (Set.of("iss", "aud", "iat", "nbf", "exp", "jti").contains(entry.getKey())) {
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
        } catch (Exception exception) {
            // Never propagate codec/library errors containing untrusted token text,
            // header/payload contents or key material through application exceptions.
            throw new JwtCredentialException();
        }
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

    private static String requiredString(Object value) {
        if (!(value instanceof String text) || text.isBlank() || text.length() > 256) {
            throw new JwtCredentialException();
        }
        return text;
    }
}
