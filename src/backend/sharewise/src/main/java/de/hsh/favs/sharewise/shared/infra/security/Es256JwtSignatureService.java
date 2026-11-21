package de.hsh.favs.sharewise.shared.infra.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jwt.SignedJWT;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;

/**
 * ES256 JWS strategy backed by locally configured NIST P-256 keys.
 *
 * <p>Verification allows only ES256, configured key IDs, and the expected
 * protected type. All remote or embedded key references are rejected. JWT
 * semantic/time validation belongs to {@link JwtCredentialCodec}, not this
 * signature strategy. Explicit key overlap supports rotation and removal.
 */
public final class Es256JwtSignatureService implements JwtSignatureService {
    private static final Set<String> REJECTED_HEADERS = Set.of("jku", "jwk", "x5u", "x5c", "crit");
    private static final int MAX_TOKEN_LENGTH = JwtCredentialProfile.ABSOLUTE_MAXIMUM_TOKEN_LENGTH;

    private final Map<String, JwtEcKey> keys;
    private final NimbusJwtDecoder decoder;
    private final NimbusJwtEncoder encoder;
    private final String activeSigningKeyId;

    /**
     * Configures independent trusted keys and an optional active signing key.
     *
     * @param trustedKeys nonempty set of distinct local P-256 verification keys
     * @param signingKeyId optional ID of the active locally configured signing key
     */
    public Es256JwtSignatureService(List<JwtEcKey> trustedKeys, Optional<String> signingKeyId) {
        Objects.requireNonNull(trustedKeys, "trustedKeys");
        Objects.requireNonNull(signingKeyId, "signingKeyId");
        if (trustedKeys.isEmpty()) {
            throw new IllegalArgumentException("JWT verification requires trusted keys");
        }
        Map<String, JwtEcKey> byId = new HashMap<>();
        List<JWK> publicJwks = new ArrayList<>();
        for (JwtEcKey key : trustedKeys) {
            Objects.requireNonNull(key, "trusted key");
            if (byId.putIfAbsent(key.id(), key) != null) {
                throw new IllegalArgumentException("Duplicate trusted JWT key ID");
            }
            publicJwks.add(new ECKey.Builder(Curve.P_256, key.publicKey()).keyID(key.id())
                .keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.ES256).build());
        }
        this.keys = Map.copyOf(byId);
        decoder = NimbusJwtDecoder.withJwkSource(new ImmutableJWKSet<>(new JWKSet(publicJwks)))
            .jwsAlgorithm(SignatureAlgorithm.ES256)
            .build();
        // The codec validates all temporal/issuer/profile claims with its injected Clock.
        // Do not use Nimbus's system-clock-based default JWT timestamp validator here.
        decoder.setJwtValidator(jwt -> OAuth2TokenValidatorResult.success());

        if (signingKeyId.isPresent()) {
            JwtEcKey key = keys.get(signingKeyId.get());
            if (key == null || key.privateKey() == null) {
                throw new IllegalArgumentException("Configured JWT signing key is unavailable");
            }
            ECKey signingJwk = new ECKey.Builder(Curve.P_256, key.publicKey())
                .privateKey(key.privateKey()).keyID(key.id())
                .keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.ES256).build();
            encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(signingJwk)));
            activeSigningKeyId = key.id();
        } else {
            encoder = null;
            activeSigningKeyId = null;
        }
    }

    @Override
    public String sign(String tokenType, JwtClaimsSet claims) {
        Objects.requireNonNull(tokenType, "tokenType");
        Objects.requireNonNull(claims, "claims");
        if (encoder == null) {
            throw new IllegalStateException("JWT signature service is verification-only");
        }
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.ES256)
            .type(tokenType).keyId(activeSigningKeyId).build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        if (token.length() > MAX_TOKEN_LENGTH) {
            throw new IllegalArgumentException("JWT payload exceeds configured size limit");
        }
        return token;
    }

    @Override
    public Jwt verify(String token, String expectedType) {
        try {
            if (token == null || token.length() > MAX_TOKEN_LENGTH) {
                throw new JwtCredentialException();
            }
            SignedJWT parsed = SignedJWT.parse(token);
            Map<String, Object> headers = parsed.getHeader().toJSONObject();
            String kid = parsed.getHeader().getKeyID();
            if (!JWSAlgorithm.ES256.equals(parsed.getHeader().getAlgorithm())
                || !expectedType.equals(parsed.getHeader().getType() == null
                    ? null : parsed.getHeader().getType().toString())
                || kid == null || !keys.containsKey(kid)
                || headers.keySet().stream().anyMatch(REJECTED_HEADERS::contains)) {
                throw new JwtCredentialException();
            }
            return decoder.decode(token);
        } catch (Exception exception) {
            // Never expose untrusted header/payload, library diagnostics or key material.
            throw new JwtCredentialException();
        }
    }
}
