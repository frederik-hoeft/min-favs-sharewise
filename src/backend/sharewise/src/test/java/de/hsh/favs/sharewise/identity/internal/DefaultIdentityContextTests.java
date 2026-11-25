package de.hsh.favs.sharewise.identity.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.hsh.favs.sharewise.identity.UnauthenticatedCallerException;
import de.hsh.favs.sharewise.shared.infra.claims.AuthorizationContext;
import de.hsh.favs.sharewise.shared.infra.claims.AuthorizationCredential;
import de.hsh.favs.sharewise.shared.infra.claims.Claim;
import de.hsh.favs.sharewise.shared.infra.claims.ClaimOrigin;
import de.hsh.favs.sharewise.shared.infra.security.JwtCredentialCodec;
import de.hsh.favs.sharewise.shared.infra.security.JwtCredentialProfile;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** User and session identity come from one uniquely selected verified primary credential. */
class DefaultIdentityContextTests {

    private static final JwtCredentialProfile PROFILE = new JwtCredentialProfile(
            "iam-access", "https://iam.sharewise.test", "sw-iam-access+jwt", Set.of("sharewise-api"),
            JwtCredentialProfile.Kind.PRIMARY, Set.of(), Duration.ofMinutes(5),
            Duration.ofSeconds(30), 4096);
    private static final UUID USER = UUID.fromString("019ab800-0000-7000-8000-000000000201");
    private static final UUID SESSION = UUID.fromString("019ab800-0000-7000-8000-000000000202");

    @Test
    void anonymousOperationsHaveNeitherUserNorSession() {
        DefaultIdentityContext identity = context(List.of());
        assertEquals(Optional.empty(), identity.userId());
        assertEquals(Optional.empty(), identity.sessionId());
        assertThrows(UnauthenticatedCallerException.class, identity::requireUserId);
        assertThrows(UnauthenticatedCallerException.class, identity::requireSessionId);
    }

    @Test
    void primaryEvidenceProvidesUserAndSessionFromOneCredential() {
        DefaultIdentityContext identity = context(List.of(primary(USER.toString(), SESSION.toString())));
        assertEquals(USER, identity.requireUserId());
        assertEquals(SESSION, identity.requireSessionId());
        assertEquals(Optional.of(SESSION), identity.sessionId());
    }

    @Test
    void noncanonicalAndInvalidSessionClaimsFailClosed() {
        for (String invalid : List.of("", "session-alice", SESSION.toString().toUpperCase(),
                "1-1-1-1-1", "00000000-0000-0000-0000-00000000000g")) {
            assertThrows(IllegalStateException.class,
                    () -> context(List.of(primary(USER.toString(), invalid))), invalid);
        }
    }

    @Test
    void duplicateSessionClaimsOrPrimaryCredentialsFailClosed() {
        ClaimOrigin origin = origin(USER.toString());
        AuthorizationCredential duplicate = new AuthorizationCredential(origin,
                List.of(new Claim("sid", SESSION.toString(), origin),
                        new Claim("sid", SESSION.toString(), origin)));
        assertThrows(IllegalStateException.class, () -> context(List.of(duplicate)));
        assertThrows(IllegalStateException.class, () -> context(List.of(
                primary(USER.toString(), SESSION.toString()),
                primary(USER.toString(), SESSION.toString()))));
    }

    private static DefaultIdentityContext context(List<AuthorizationCredential> credentials) {
        AuthorizationContext authorization = mock(AuthorizationContext.class);
        when(authorization.credentialsFrom(PROFILE.issuer(), PROFILE.name())).thenReturn(credentials);
        when(authorization.credentials()).thenReturn(credentials);
        IamCredentialVerifier verifier = mock(IamCredentialVerifier.class);
        JwtCredentialCodec codec = mock(JwtCredentialCodec.class);
        when(verifier.requireCodec()).thenReturn(codec);
        when(codec.profile()).thenReturn(PROFILE);
        return new DefaultIdentityContext(authorization, verifier);
    }

    private static AuthorizationCredential primary(String userId, String sessionId) {
        ClaimOrigin origin = origin(userId);
        return new AuthorizationCredential(origin, List.of(new Claim("sid", sessionId, origin)));
    }

    private static ClaimOrigin origin(String userId) {
        return new ClaimOrigin(PROFILE.issuer(), PROFILE.name(), Set.of("sharewise-api"),
                Optional.of(userId), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }
}
