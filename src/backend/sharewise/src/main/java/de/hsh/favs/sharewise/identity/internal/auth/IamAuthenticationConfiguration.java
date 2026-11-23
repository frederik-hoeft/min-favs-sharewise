package de.hsh.favs.sharewise.identity.internal.auth;

import de.hsh.favs.sharewise.identity.internal.auth.account.AccountStore;
import de.hsh.favs.sharewise.identity.internal.auth.account.LoginLockoutPolicy;
import de.hsh.favs.sharewise.identity.internal.auth.credential.Pbkdf2CredentialEncoder;
import de.hsh.favs.sharewise.identity.internal.auth.credential.CredentialEncoder;
import de.hsh.favs.sharewise.identity.internal.auth.credential.PasswordPolicy;
import de.hsh.favs.sharewise.identity.internal.auth.session.RefreshCredentialGenerator;
import de.hsh.favs.sharewise.identity.internal.auth.session.RefreshTokenStore;
import de.hsh.favs.sharewise.identity.internal.auth.session.SessionPolicy;
import de.hsh.favs.sharewise.identity.internal.auth.session.SessionLifecycleStore;
import de.hsh.favs.sharewise.identity.internal.auth.session.SessionStore;
import de.hsh.favs.sharewise.identity.internal.auth.token.AccessTokenIssuer;
import de.hsh.favs.sharewise.identity.internal.auth.token.IamPemKeyLoader;
import de.hsh.favs.sharewise.identity.internal.auth.token.PrimaryAccessTokenIssuer;
import de.hsh.favs.sharewise.shared.infra.security.Es256JwtSignatureService;
import de.hsh.favs.sharewise.shared.infra.security.JwtCredentialCodec;
import de.hsh.favs.sharewise.shared.infra.security.JwtCredentialProfile;
import de.hsh.favs.sharewise.shared.infra.security.JwtEcKey;
import de.hsh.favs.sharewise.shared.infra.transactions.Transactions;
import de.hsh.favs.sharewise.identity.IdentityContext;
import de.hsh.favs.sharewise.identity.internal.profile.AccountProfileService;
import de.hsh.favs.sharewise.identity.internal.profile.AccountProfileStore;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;

/**
 * Fail-closed production authentication wiring. Enable only with provisioned IAM
 * signing/verification keys; no test fixtures are scanned.
 * F1 owns exposure of these application services through HTTP/operation ingress.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "sharewise.iam.auth", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(IamAuthenticationSettings.class)
class IamAuthenticationConfiguration {

    @Bean("iamClock")
    Clock iamClock() {
        return Clock.systemUTC();
    }

    @Bean
    PasswordPolicy iamPasswordPolicy() {
        return PasswordPolicy.initial();
    }

    @Bean
    CredentialEncoder iamCredentialEncoder(PasswordPolicy policy) {
        return new Pbkdf2CredentialEncoder(policy);
    }

    @Bean
    DummyVerifier iamDummyVerifier(CredentialEncoder encoder) {
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        String password = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        java.util.Arrays.fill(random, (byte) 0);
        return new DummyVerifier(encoder.encode(password));
    }

    record DummyVerifier(String encoded) { }

    @Bean("iamPrimaryJwtCredentialCodec")
    JwtCredentialCodec iamPrimaryJwtCredentialCodec(IamAuthenticationSettings settings,
            @Qualifier("iamClock") Clock clock) {
        if (settings.issuer() == null || settings.issuer().isBlank()
                || settings.audience() == null || settings.audience().isBlank()
                || settings.activeKeyId() == null || settings.activeKeyId().isBlank()
                || settings.keys() == null || settings.keys().isEmpty()) {
            throw new IllegalStateException("IAM primary JWT trust and signing configuration is required");
        }
        List<JwtEcKey> keys = new ArrayList<>();
        int active = 0;
        for (IamAuthenticationSettings.Key key : settings.keys()) {
            if (settings.activeKeyId().equals(key.id())) {
                active++;
                keys.add(IamPemKeyLoader.signing(key.id(), key.publicKeyFile(), key.privateKeyFile()));
            } else {
                if (key.privateKeyFile() != null && !key.privateKeyFile().isBlank()) {
                    throw new IllegalStateException("Only the active IAM signing key may contain private material");
                }
                keys.add(IamPemKeyLoader.verification(key.id(), key.publicKeyFile()));
            }
        }
        if (active != 1) {
            throw new IllegalStateException("Exactly one active IAM signing key must be configured");
        }
        JwtCredentialProfile profile = new JwtCredentialProfile(
                "iam-access", settings.issuer(), "sw-iam-access+jwt", Set.of(settings.audience()),
                JwtCredentialProfile.Kind.PRIMARY, Set.of(), settings.session().accessLifetime(),
                Duration.ofSeconds(30), 4096);
        return new JwtCredentialCodec(profile,
                new Es256JwtSignatureService(keys, Optional.of(settings.activeKeyId())), clock);
    }

    @Bean
    AccessTokenIssuer iamAccessTokenIssuer(@Qualifier("iamPrimaryJwtCredentialCodec") JwtCredentialCodec codec) {
        return new PrimaryAccessTokenIssuer(codec);
    }

    @Bean
    SessionPolicy iamSessionPolicy(IamAuthenticationSettings settings) {
        return settings.session().toPolicy();
    }

    @Bean
    LoginLockoutPolicy iamLoginLockoutPolicy(IamAuthenticationSettings settings) {
        return settings.lockout().toPolicy();
    }

    @Bean
    RefreshCredentialGenerator iamRefreshCredentialGenerator() {
        return new RefreshCredentialGenerator();
    }

    @Bean
    @Scope("operation")
    InitialAuthentication iamInitialAuthentication(SessionStore sessions, RefreshTokenStore refreshTokens,
            RefreshCredentialGenerator secrets, SessionPolicy policy, AccessTokenIssuer accessTokens) {
        return new InitialAuthentication(sessions, refreshTokens, secrets, policy, accessTokens);
    }

    @Bean
    @Scope("operation")
    SessionLifecycleService iamSessionLifecycleService(Transactions transactions, SessionLifecycleStore sessions,
            RefreshTokenStore refreshTokens, RefreshCredentialGenerator credentials, SessionPolicy policy,
            AccessTokenIssuer accessTokens, @Qualifier("iamClock") Clock clock) {
        return new SessionLifecycleService(transactions, sessions, refreshTokens, credentials,
                policy, accessTokens, clock);
    }

    @Bean
    @Scope("operation")
    RegistrationService iamRegistrationService(Transactions transactions, AccountStore accounts,
            CredentialEncoder credentials, InitialAuthentication authentication,
            @Qualifier("iamClock") Clock clock) {
        return new RegistrationService(transactions, accounts, credentials, authentication, clock);
    }

    @Bean
    @Scope("operation")
    LoginService iamLoginService(Transactions transactions, AccountStore accounts,
            CredentialEncoder credentials, InitialAuthentication authentication,
            LoginLockoutPolicy lockouts, @Qualifier("iamClock") Clock clock, DummyVerifier dummy) {
        return new LoginService(transactions, accounts, credentials, authentication, lockouts,
                clock, dummy.encoded());
    }

    @Bean
    @Scope("operation")
    AccountProfileService iamAccountProfileService(IdentityContext identity, Transactions transactions,
            AccountProfileStore profiles) {
        return new AccountProfileService(identity, transactions, profiles);
    }

}
