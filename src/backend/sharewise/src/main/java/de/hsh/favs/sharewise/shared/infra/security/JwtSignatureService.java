package de.hsh.favs.sharewise.shared.infra.security;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;

/**
 * Trusted, explicitly configured JWS signing and signature-verification strategy.
 *
 * <p>Implementations select algorithms and keys from trusted configuration, never
 * from token-supplied key locations. Verification checks the protected header and
 * cryptographic signature, but does not establish issuer, audience, principal,
 * lifetime or domain authorization. {@link JwtCredentialCodec} completes those
 * checks before exposing an authenticated credential.
 */
public interface JwtSignatureService {
    /**
     * Signs claims with the configured active signing key.
     *
     * @param tokenType expected protected JWT type
     * @param claims issuer-controlled JWT claims
     * @return compact signed JWT
     * @throws IllegalStateException when configured for verification only
     */
    String sign(String tokenType, JwtClaimsSet claims);

    /**
     * Verifies a compact JWS signature and protected header against configured keys.
     *
     * @param token untrusted compact JWT
     * @param expectedType configured protected JWT type
     * @return decoded signature-verified claims (not yet a validated credential)
     * @throws JwtCredentialException for malformed, unsigned or untrusted input
     */
    Jwt verify(String token, String expectedType);
}
