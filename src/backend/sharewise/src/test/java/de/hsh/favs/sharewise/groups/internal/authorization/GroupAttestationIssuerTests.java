package de.hsh.favs.sharewise.groups.internal.authorization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.hsh.favs.sharewise.groups.GroupAuthorizationPort;
import de.hsh.favs.sharewise.groups.GroupAuthorizationVerificationFailure;
import de.hsh.favs.sharewise.groups.GroupRole;
import de.hsh.favs.sharewise.groups.VerifiedGroupMembership;
import de.hsh.favs.sharewise.identity.IdentityContext;
import de.hsh.favs.sharewise.shared.infra.claims.AuthorizationContext;
import de.hsh.favs.sharewise.shared.infra.claims.AuthorizationCredential;
import de.hsh.favs.sharewise.shared.infra.claims.ClaimOrigin;
import de.hsh.favs.sharewise.shared.infra.security.Es256JwtSignatureService;
import de.hsh.favs.sharewise.shared.infra.security.JwtCredentialCodec;
import de.hsh.favs.sharewise.shared.infra.security.JwtCredentialProfile;
import de.hsh.favs.sharewise.shared.infra.security.JwtEcKey;
import de.hsh.favs.sharewise.shared.infra.security.JwtSignatureService;
import de.hsh.favs.sharewise.shared.result.Result;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;

class GroupAttestationIssuerTests {
    private static final Instant OBSERVED = Instant.parse("2026-11-26T12:00:00Z");
    private static final Instant EXPIRY = OBSERVED.plusSeconds(120);
    private static final String IAM = "https://iam.example";
    private static final UUID USER = UUID.randomUUID();
    private static final UUID SESSION = UUID.randomUUID();
    private static final UUID GROUP = UUID.randomUUID();
    private final MutableClock clock = new MutableClock();
    private final IdentityContext identity = mock(IdentityContext.class);
    private final AuthorizationContext authorization = mock(AuthorizationContext.class);
    private final GroupAuthorizationPort memberships = mock(GroupAuthorizationPort.class);
    private SigningFixture signer;
    private JwtCredentialCodec codec;
    private GroupAttestationIssuer issuer;

    @BeforeEach
    void configure() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        signer = new SigningFixture(new Es256JwtSignatureService(List.of(JwtEcKey.signing("key", generator.generateKeyPair())), Optional.of("key")));
        JwtCredentialProfile profile = GroupMembershipTrust.profile("https://groups.example", "sw-api-v1", IAM, Duration.ofSeconds(15));
        codec = new JwtCredentialCodec(profile, signer, clock);
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("trust", new GroupMembershipTrust(codec, true));
        ObjectProvider<GroupMembershipTrust> trust = beans.getBeanProvider(GroupMembershipTrust.class);
        when(identity.requireUserId()).thenReturn(USER);
        when(identity.requireSessionId()).thenReturn(SESSION);
        ClaimOrigin origin = new ClaimOrigin(IAM, "iam-access", Set.of("sw-api-v1"), Optional.of(USER.toString()),
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        when(authorization.credentialsFrom(IAM, "iam-access")).thenReturn(List.of(new AuthorizationCredential(origin, List.of())));
        when(memberships.verifyCurrent(GROUP)).thenReturn(Result.ok(new VerifiedGroupMembership(USER, GROUP, GroupRole.MEMBER, OBSERVED)));
        issuer = new GroupAttestationIssuer(identity, authorization, memberships, trust, clock);
    }

    @Test
    void delayedSigningPreservesObservationBoundInSignedClaimsAndResponse() {
        clock.now = OBSERVED.plusSeconds(20);
        signer.delay = Duration.ofSeconds(20);
        Result<IssuedGroupAttestation, GroupAuthorizationVerificationFailure> result = issuer.issue(GROUP);
        IssuedGroupAttestation issued = result.fold(value -> value, failure -> { throw new AssertionError(failure); });
        assertEquals(EXPIRY, issued.expiresAt());
        assertEquals(Optional.of(EXPIRY), codec.verify(issued.attestation()).evidence().origin().expiresAt());
        assertEquals(OBSERVED.plusSeconds(40), clock.instant());
        assertEquals(1, signer.calls);
    }

    @Test
    void expiredObservationDoesNotInvokeSigner() {
        clock.now = EXPIRY;
        assertUnavailable();
        assertEquals(0, signer.calls);
    }

    @Test
    void expiryDuringSigningDoesNotReturnCredential() {
        clock.now = EXPIRY.minusSeconds(1);
        signer.delay = Duration.ofSeconds(1);
        assertUnavailable();
        assertEquals(1, signer.calls);
    }

    @Test
    void signingFailureReturnsTypedUnavailability() {
        signer.fail = true;
        assertUnavailable();
        assertEquals(1, signer.calls);
    }

    private void assertUnavailable() {
        assertEquals(Result.err(GroupAuthorizationVerificationFailure.UNAVAILABLE), issuer.issue(GROUP));
    }

    private final class SigningFixture implements JwtSignatureService {
        private final JwtSignatureService delegate;
        private Duration delay = Duration.ZERO;
        private boolean fail;
        private int calls;

        SigningFixture(JwtSignatureService delegate) { this.delegate = delegate; }

        @Override public String sign(String tokenType, JwtClaimsSet claims) {
            calls++;
            if (fail) throw new IllegalStateException("fixture signing failure");
            String token = delegate.sign(tokenType, claims);
            clock.now = clock.now.plus(delay);
            return token;
        }

        @Override public Jwt verify(String token, String expectedType) { return delegate.verify(token, expectedType); }
    }

    private static final class MutableClock extends Clock {
        private Instant now = OBSERVED;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
        @Override public Instant instant() { return now; }
    }
}
