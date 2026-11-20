package de.hsh.favs.sharewise.shared.infra.di.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.hsh.favs.sharewise.shared.infra.claims.AuthorizationContext;
import de.hsh.favs.sharewise.shared.infra.claims.AuthorizationCredentialProvider;
import de.hsh.favs.sharewise.shared.infra.claims.internal.AuthorizationConfiguration;
import de.hsh.favs.sharewise.shared.infra.di.OperationDispatcher;
import de.hsh.favs.sharewise.shared.infra.security.JwtCredentialCodec;
import de.hsh.favs.sharewise.shared.infra.security.JwtCredentialException;
import de.hsh.favs.sharewise.shared.infra.security.JwtCredentialProfile;
import de.hsh.favs.sharewise.shared.infra.security.JwtIssueRequest;
import de.hsh.favs.sharewise.shared.infra.security.JwtEcKey;
import de.hsh.favs.sharewise.shared.infra.security.VerifiedJwtCredential;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Scope;
import org.springframework.core.annotation.Order;

/** Verifies the actual Spring provider-composition publication boundary with crypto-backed evidence. */
class VerifiedJwtPublicationTests {
    private static final Instant NOW = Instant.parse("2026-11-20T12:00:00Z");
    private static final UUID USER = UUID.fromString("12345678-1234-4123-8123-1234567890ab");
    private static final String SESSION = "session-c4e1a87f";
    private static final String IAM_ISSUER = "https://iam.sharewise.test";

    @Test
    void onlyBoundSourceVerifiedEvidenceBecomesAnOperationSnapshot() {
        try (AnnotationConfigApplicationContext spring = context()) {
            State state = spring.getBean(State.class);
            OperationDispatcher dispatcher = spring.getBean(OperationDispatcher.class);
            dispatcher.dispatch(AuthorizationContext.class, evidence -> {
                assertEquals(2, evidence.credentials().size());
                assertEquals(List.of("admin"), evidence.find("role").stream().map(c -> c.value()).toList());
                assertEquals(IAM_ISSUER, evidence.credentials().getFirst().origin().issuer());
                assertEquals("https://groups.sharewise.test", evidence.credentials().getLast().origin().issuer());
                assertEquals(USER.toString(), evidence.find("sub").getFirst().value());
                state.published.incrementAndGet();
                return null;
            });
            assertEquals(1, state.published.get());

            state.subordinateToken = state.group.sign(state.groupIssue("different-session"));
            BeanCreationException failure = assertThrows(BeanCreationException.class,
                () -> dispatcher.dispatch(AuthorizationContext.class, context -> {
                    state.published.incrementAndGet();
                    return null;
                }));
            assertTrue(failure.getMostSpecificCause() instanceof JwtCredentialException);
            assertEquals(1, state.published.get(), "no partial primary-only snapshot may escape");

            state.subordinateToken = state.group.sign(state.groupIssue(SESSION));
            dispatcher.dispatch(AuthorizationContext.class, evidence -> {
                assertEquals(2, evidence.credentials().size());
                state.published.incrementAndGet();
                return null;
            });
            assertEquals(2, state.published.get(), "failed operation must not poison next scope");
        }
    }

    private static AnnotationConfigApplicationContext context() {
        AnnotationConfigApplicationContext spring = new AnnotationConfigApplicationContext();
        spring.register(OperationScopeConfiguration.class, AuthorizationConfiguration.class, Fixtures.class);
        spring.refresh();
        return spring;
    }

    static final class State {
        final JwtCredentialCodec iam;
        final JwtCredentialCodec group;
        final String primaryToken;
        final AtomicInteger published = new AtomicInteger();
        volatile String subordinateToken;

        State() {
            try {
                KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
                generator.initialize(new java.security.spec.ECGenParameterSpec("secp256r1"));
                KeyPair iamKeys = generator.generateKeyPair();
                KeyPair groupKeys = generator.generateKeyPair();
                Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
                iam = new JwtCredentialCodec(new JwtCredentialProfile("iam-access", IAM_ISSUER,
                    "sw-iam-access+jwt", Set.of("api"), JwtCredentialProfile.Kind.PRIMARY, Set.of(),
                    Duration.ofMinutes(5), Duration.ofSeconds(30)),
                    new de.hsh.favs.sharewise.shared.infra.security.Es256JwtSignatureService(
                        List.of(JwtEcKey.signing("iam-key", iamKeys)), Optional.of("iam-key")), clock);
                group = new JwtCredentialCodec(new JwtCredentialProfile("group-attestation",
                    "https://groups.sharewise.test", "sw-group+jwt", Set.of("api"),
                    JwtCredentialProfile.Kind.SUBORDINATE, Set.of(iam.profile().id()), Duration.ofMinutes(5), Duration.ofSeconds(30)),
                    new de.hsh.favs.sharewise.shared.infra.security.Es256JwtSignatureService(
                        List.of(JwtEcKey.signing("group-key", groupKeys)), Optional.of("group-key")), clock);
                primaryToken = iam.sign(new JwtIssueRequest(USER, SESSION, "primary-token",
                    NOW.minusSeconds(1), NOW.minusSeconds(1), NOW.plusSeconds(120),
                    Optional.empty(), Map.of()));
                subordinateToken = group.sign(groupIssue(SESSION));
            } catch (java.security.GeneralSecurityException exception) {
                throw new AssertionError(exception);
            }
        }

        JwtIssueRequest groupIssue(String session) {
            return new JwtIssueRequest(USER, session, "group-token", NOW.minusSeconds(1),
                NOW.minusSeconds(1), NOW.plusSeconds(60), Optional.of(IAM_ISSUER), Map.of("role", "admin"));
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Fixtures {
        @Bean State state() { return new State(); }

        @Bean @Scope("operation") VerifiedJwtCredential verifiedPrimary(State state) {
            return state.iam.verifyPrimary(state.primaryToken);
        }

        @Bean @Order(-10) @Scope("operation") AuthorizationCredentialProvider primaryEvidence(
            VerifiedJwtCredential verifiedPrimary
        ) {
            return () -> List.of(verifiedPrimary.credential());
        }

        @Bean @Order(10) @Scope("operation") AuthorizationCredentialProvider subordinateEvidence(
            VerifiedJwtCredential verifiedPrimary, State state
        ) {
            return () -> List.of(state.group.verifySubordinate(state.subordinateToken, verifiedPrimary).credential());
        }
    }
}
