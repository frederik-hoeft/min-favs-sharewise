package sharewise.deploymentfixture;

import de.hsh.favs.sharewise.ShareWiseApplication;
import de.hsh.favs.sharewise.groups.GroupAuthorizationContext;
import de.hsh.favs.sharewise.groups.GroupAuthorizationFailure;
import de.hsh.favs.sharewise.groups.GroupRole;
import de.hsh.favs.sharewise.shared.result.Result;
import de.hsh.favs.sharewise.identity.IdentityContext;
import de.hsh.favs.sharewise.shared.infra.claims.AuthorizationContext;
import de.hsh.favs.sharewise.shared.infra.claims.CredentialRequirement;
import de.hsh.favs.sharewise.shared.infra.di.OperationIngressDispatcher;
import de.hsh.favs.sharewise.shared.infra.security.Es256JwtSignatureService;
import de.hsh.favs.sharewise.shared.infra.security.JwtCredentialCodec;
import de.hsh.favs.sharewise.shared.infra.security.JwtCredentialProfile;
import de.hsh.favs.sharewise.shared.infra.security.JwtEcKey;
import de.hsh.favs.sharewise.shared.infra.security.JwtIssueRequest;
import de.hsh.favs.sharewise.shared.infra.security.SupplementaryCredentialVerifier;
import de.hsh.favs.sharewise.shared.infra.web.ApiAccess;
import de.hsh.favs.sharewise.shared.infra.web.ApiProblems;
import de.hsh.favs.sharewise.shared.infra.web.AttestationAffinityPolicy;
import de.hsh.favs.sharewise.shared.infra.web.AttestationChallenge;
import de.hsh.favs.sharewise.shared.infra.web.AttestationProblems;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Test-only bootstrap outside the production component-scan root. Never package in app.jar. */
@Configuration(proxyBeanMethods = false)
public class AuthorizationSmokeApplication {
    private static final String ROOT = "/api/smoke/v1";
    private static final String AUTHORITY = "smoke";
    private static final String PROFILE = "smoke-fixture";

    public static void main(String[] args) {
        String project = System.getenv("SHAREWISE_SMOKE_PROJECT");
        if (project == null || !project.matches("sharewise-p26ab-smoke[a-z0-9_-]*")) {
            throw new IllegalStateException("Fixture requires a reserved disposable Compose project");
        }
        SpringApplication.run(new Class<?>[] { ShareWiseApplication.class, AuthorizationSmokeApplication.class }, args);
    }

    @Bean FixtureAuthority fixtureAuthority(@Value("${sharewise.iam.auth.issuer}") String issuer,
            @Value("${sharewise.iam.auth.audience:sw-api-v1}") String audience) throws Exception {
        var generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        var profile = new JwtCredentialProfile(PROFILE, issuer + ROOT + "/domain-principal", "sw-smoke+jwt",
            Set.of(audience), JwtCredentialProfile.Kind.SUBORDINATE,
            Set.of(new JwtCredentialProfile.Id(issuer, "iam-access")), Duration.ofSeconds(120), Duration.ofSeconds(15), 2048);
        var codec = new JwtCredentialCodec(profile, new Es256JwtSignatureService(
            List.of(JwtEcKey.signing("smoke", generator.generateKeyPair())), Optional.of("smoke")), Clock.systemUTC());
        return new FixtureAuthority(codec, issuer);
    }

    @Bean SupplementaryCredentialVerifier smokeVerifier(FixtureAuthority authority) {
        return new SupplementaryCredentialVerifier(authority.codec());
    }

    @Bean Effects smokeEffects() { return new Effects(); }
    @Bean @Scope("operation") Consumer smokeConsumer(GroupAuthorizationContext groups, AuthorizationContext evidence,
            IdentityContext identity, FixtureAuthority authority, Effects effects) {
        return new Consumer(groups, evidence, identity, authority, effects);
    }

    record FixtureAuthority(JwtCredentialCodec codec, String primaryIssuer) { }
    static final class Effects {
        private final ConcurrentHashMap<UUID, AtomicInteger> counts = new ConcurrentHashMap<>();
        int count(UUID user) { return counts.computeIfAbsent(user, ignored -> new AtomicInteger()).get(); }
        int add(UUID user) { return counts.computeIfAbsent(user, ignored -> new AtomicInteger()).incrementAndGet(); }
    }

    record Target(UUID target, long timestamp) { }
    record Issue(String kind, String scope) { }
    record Requirement(String id, UUID group, AttestationAffinityPolicy affinity) { }

    static final class Consumer {
        private final GroupAuthorizationContext groups;
        private final AuthorizationContext evidence;
        private final IdentityContext identity;
        private final FixtureAuthority authority;
        private final Effects effects;

        Consumer(GroupAuthorizationContext groups, AuthorizationContext evidence, IdentityContext identity,
                FixtureAuthority authority, Effects effects) {
            this.groups = groups; this.evidence = evidence; this.identity = identity;
            this.authority = authority; this.effects = effects;
        }

        ResponseEntity<?> execute(List<Requirement> requirements, String fixtureKind, String fixtureScope, boolean mutate) {
            for (Requirement requirement : requirements) {
                var result = groups.requireMember(requirement.group());
                if (result instanceof Result.Err<GroupRole, GroupAuthorizationFailure>(var error)) return switch (error) {
                    case GroupAuthorizationFailure.AttestationRequired missing -> AttestationProblems.required(
                        new AttestationChallenge(requirement.id(), missing.requirement(), requirement.affinity()), "Group membership evidence is required.");
                    case GroupAuthorizationFailure.InsufficientPermissions denied -> ApiProblems.response(HttpStatus.FORBIDDEN,
                        "insufficient_permissions", "Access Denied", "Required role is not established.");
                    case GroupAuthorizationFailure.Unauthenticated ignored -> ApiProblems.response(HttpStatus.UNAUTHORIZED,
                        "authentication_required", "Authentication Required", "Authentication is required.");
                };
            }
            if (fixtureKind != null) {
                boolean established = evidence.credentialsFrom(authority.codec().profile().issuer(), PROFILE).stream()
                    .anyMatch(credential -> credential.claims().stream().anyMatch(claim -> claim.type().equals("fixture_scope") && claim.value().equals(fixtureScope)));
                if (!established || fixtureKind.equals("repeat")) return challenge(fixtureKind, fixtureScope);
            }
            UUID user = identity.requireUserId();
            return ResponseEntity.ok(Map.of("effects", mutate ? effects.add(user) : effects.count(user)));
        }

        ResponseEntity<?> issue(Issue request) {
            if (evidence.credentials().size() != 1) return AttestationProblems.invalid(java.util.OptionalInt.empty());
            if (request.kind().equals("recursive")) return challenge("fixture", request.scope());
            if (request.kind().equals("unavailable")) return ApiProblems.response(HttpStatus.SERVICE_UNAVAILABLE,
                "fixture_unavailable", "Authority Unavailable", "Fixture authority unavailable.");
            if (!Set.of("fixture", "repeat").contains(request.kind()) || !request.scope().matches("[a-zA-Z0-9-]{1,64}")) {
                return ApiProblems.response(HttpStatus.BAD_REQUEST, "malformed_request", "Invalid Request", "Invalid fixture request.");
            }
            Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
            Instant expiry = now.plusSeconds(120);
            String token = authority.codec().sign(new JwtIssueRequest(identity.requireUserId(), identity.requireSessionId().toString(),
                UUID.randomUUID().toString(), now, now, expiry, Optional.of(authority.primaryIssuer()), Map.of("fixture_scope", request.scope())));
            return ResponseEntity.ok().header("Cache-Control", "no-store").body(Map.of("attestation", token, "expiresAt", expiry.toString()));
        }
    }

    private static ResponseEntity<?> challenge(String kind, String scope) {
        return AttestationProblems.required(new AttestationChallenge("fixture", new CredentialRequirement(AUTHORITY, kind, scope),
            AttestationAffinityPolicy.never()), "Fixture evidence is required.");
    }

    @RestController @ApiAccess
    static final class Controller {
        private final OperationIngressDispatcher ingress;
        private final JdbcTemplate seed;
        Controller(OperationIngressDispatcher ingress, DataSource dataSource) { this.ingress = ingress; this.seed = new JdbcTemplate(dataSource); }

        // Same temporary user-projection seam as api-http-smoke.py. All groups and
        // memberships are subsequently created through the real Group API.
        @PostMapping(ROOT + "/seed-user")
        ResponseEntity<?> seedUser() {
            UUID user = ingress.dispatch(IdentityContext.class, IdentityContext::requireUserId);
            seed.update("INSERT INTO groups.users (id) VALUES (?) ON CONFLICT DO NOTHING", user);
            return ResponseEntity.noContent().build();
        }

        private ResponseEntity<?> group(UUID group, AttestationAffinityPolicy affinity, boolean mutate) {
            return ingress.dispatch(Consumer.class, consumer -> consumer.execute(List.of(new Requirement("member", group, affinity)), null, null, mutate));
        }
        @GetMapping({ROOT + "/path/{group}", ROOT + "/other/{group}"})
        ResponseEntity<?> path(@PathVariable UUID group) { return group(group, AttestationAffinityPolicy.pathAffine(), false); }
        @PostMapping(ROOT + "/path/{group}")
        ResponseEntity<?> mutate(@PathVariable UUID group) { return group(group, AttestationAffinityPolicy.pathAffine(), true); }
        @GetMapping(ROOT + "/request/{group}")
        ResponseEntity<?> request(@PathVariable UUID group) { return group(group, AttestationAffinityPolicy.requestAffine(), false); }
        @GetMapping(ROOT + "/query")
        ResponseEntity<?> query(@RequestParam UUID target) { return group(target, AttestationAffinityPolicy.queryAffine("target", "variant"), false); }
        @PostMapping(ROOT + "/body")
        ResponseEntity<?> body(@RequestBody Target body) { return group(body.target(), AttestationAffinityPolicy.never(), true); }
        /** Intentionally overbroad affinity: tests recovery from a changed server-side requirement. */
        @PostMapping(ROOT + "/dynamic")
        ResponseEntity<?> dynamic(@RequestBody Target body) { return group(body.target(), AttestationAffinityPolicy.pathAffine(), true); }
        @PostMapping(ROOT + "/transfer/{source}")
        ResponseEntity<?> transfer(@PathVariable UUID source, @RequestParam UUID destination) {
            return ingress.dispatch(Consumer.class, consumer -> consumer.execute(List.of(
                new Requirement("source", source, AttestationAffinityPolicy.pathAffine()),
                new Requirement("destination", destination, AttestationAffinityPolicy.queryAffine("destination"))), "fixture", "transfer", true));
        }
        @PostMapping(ROOT + "/failure/{kind}")
        ResponseEntity<?> failure(@PathVariable String kind) {
            return ingress.dispatch(Consumer.class, consumer -> consumer.execute(List.of(), kind, "failure", true));
        }
        @GetMapping(ROOT + "/effects")
        ResponseEntity<?> effects() { return ingress.dispatch(Consumer.class, consumer -> consumer.execute(List.of(), null, null, false)); }
        @PostMapping(ROOT + "/domain-principal/attestations")
        ResponseEntity<?> issue(@RequestBody Issue request) { return ingress.dispatch(Consumer.class, consumer -> consumer.issue(request)); }
    }
}
