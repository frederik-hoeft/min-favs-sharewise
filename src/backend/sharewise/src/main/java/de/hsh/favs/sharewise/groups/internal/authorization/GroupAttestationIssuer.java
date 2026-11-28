package de.hsh.favs.sharewise.groups.internal.authorization;

import de.hsh.favs.sharewise.groups.GroupAuthorizationPort;
import de.hsh.favs.sharewise.groups.GroupAuthorizationVerificationFailure;
import de.hsh.favs.sharewise.groups.VerifiedGroupMembership;
import de.hsh.favs.sharewise.identity.IdentityContext;
import de.hsh.favs.sharewise.shared.infra.claims.AuthorizationContext;
import de.hsh.favs.sharewise.shared.infra.claims.AuthorizationCredential;
import de.hsh.favs.sharewise.shared.infra.security.JwtCredentialProfile;
import de.hsh.favs.sharewise.shared.infra.security.JwtIssueRequest;
import de.hsh.favs.sharewise.shared.result.Result;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

/** Issues only Group-owned facts derived from current membership and admitted IAM identity. */
@Component
@Scope("operation")
public final class GroupAttestationIssuer {
    private static final Logger LOG = LoggerFactory.getLogger(GroupAttestationIssuer.class);
    private final IdentityContext identity;
    private final AuthorizationContext authorization;
    private final GroupAuthorizationPort memberships;
    private final ObjectProvider<GroupMembershipTrust> trust;
    private final Clock clock;

    GroupAttestationIssuer(IdentityContext identity, AuthorizationContext authorization,
            GroupAuthorizationPort memberships, ObjectProvider<GroupMembershipTrust> trust,
            @Qualifier("groupAuthorizationClock") Clock clock) {
        this.identity = Objects.requireNonNull(identity);
        this.authorization = Objects.requireNonNull(authorization);
        this.memberships = Objects.requireNonNull(memberships);
        this.trust = Objects.requireNonNull(trust);
        this.clock = Objects.requireNonNull(clock);
    }

    public Result<IssuedGroupAttestation, GroupAuthorizationVerificationFailure> issue(UUID groupId) {
        UUID userId = identity.requireUserId();
        UUID sessionId = identity.requireSessionId();
        Objects.requireNonNull(groupId);
        GroupMembershipTrust configured = trust.getIfAvailable();
        if (configured == null || !configured.signingEnabled()) return unavailable();
        JwtCredentialProfile.Id primary = configured.codec().profile().trustedPrimaryProfiles().iterator().next();
        List<AuthorizationCredential> admitted = authorization.credentialsFrom(primary.issuer(), primary.name());
        if (admitted.size() != 1 || !admitted.getFirst().origin().subject().equals(Optional.of(userId.toString()))) {
            // Incorrect authority trust configuration must not mint a credential claiming another primary issuer.
            return unavailable();
        }
        return memberships.verifyCurrent(groupId).flatMap(current -> sign(configured, primary, userId, sessionId, groupId, current));
    }

    private Result<IssuedGroupAttestation, GroupAuthorizationVerificationFailure> sign(GroupMembershipTrust configured,
            JwtCredentialProfile.Id primary, UUID userId, UUID sessionId, UUID groupId, VerifiedGroupMembership current) {
        if (!userId.equals(current.userId()) || !groupId.equals(current.groupId())) {
            throw new IllegalStateException("Membership authority returned unrelated evidence");
        }
        // Bound staleness from the observation under the lock, not the later signing instant.
        Instant observed = current.verifiedAt().truncatedTo(ChronoUnit.SECONDS);
        Instant expires = observed.plus(GroupMembershipTrust.LIFETIME);
        if (!clock.instant().isBefore(expires)) return unavailable();
        try {
            String token = configured.codec().sign(new JwtIssueRequest(userId, sessionId.toString(), UUID.randomUUID().toString(),
                observed, observed, expires, Optional.of(primary.issuer()),
                Map.of("group_id", groupId.toString(), "role", current.role().name())));
            return clock.instant().isBefore(expires) ? Result.ok(new IssuedGroupAttestation(token, expires)) : unavailable();
        } catch (RuntimeException failure) {
            // Crypto/configuration diagnostics must not leak JWT contents or private material.
            LOG.warn("Group attestation signing failed ({})", failure.getClass().getSimpleName());
            return unavailable();
        }
    }

    private static Result<IssuedGroupAttestation, GroupAuthorizationVerificationFailure> unavailable() {
        return Result.err(GroupAuthorizationVerificationFailure.UNAVAILABLE);
    }
}
