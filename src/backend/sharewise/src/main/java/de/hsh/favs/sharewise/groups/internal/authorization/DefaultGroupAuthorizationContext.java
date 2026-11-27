package de.hsh.favs.sharewise.groups.internal.authorization;

import de.hsh.favs.sharewise.groups.GroupAuthorizationContext;
import de.hsh.favs.sharewise.groups.GroupAuthorizationFailure;
import de.hsh.favs.sharewise.groups.GroupRole;
import de.hsh.favs.sharewise.identity.IdentityContext;
import de.hsh.favs.sharewise.shared.infra.claims.AuthorizationContext;
import de.hsh.favs.sharewise.shared.result.Result;
import java.util.Objects;
import java.util.Map;
import java.util.HashMap;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

/** Interprets only configured, admitted Group membership evidence for this operation. */
@Component
@Scope("operation")
final class DefaultGroupAuthorizationContext implements GroupAuthorizationContext {
    private final IdentityContext identity;
    private final Map<UUID, GroupRole> roles;

    DefaultGroupAuthorizationContext(IdentityContext identity, AuthorizationContext authorization,
            ObjectProvider<GroupMembershipTrust> trust) {
        this.identity = Objects.requireNonNull(identity);
        GroupMembershipTrust configured = trust.getIfAvailable();
        Map<UUID, GroupRole> admitted = new HashMap<>();
        if (configured != null && identity.isAuthenticated()) {
            var profile = configured.codec().profile();
            for (var credential : authorization.credentialsFrom(profile.issuer(), profile.name())) {
                var assertion = GroupMembershipEvidence.parse(credential);
                GroupRole previous = admitted.putIfAbsent(assertion.groupId(), assertion.role());
                if (previous != null && previous != assertion.role()) {
                    throw new IllegalStateException("Conflicting Group evidence escaped credential admission");
                }
            }
        }
        this.roles = Map.copyOf(admitted);
    }

    @Override public Optional<GroupRole> role(UUID groupId) {
        return Optional.ofNullable(roles.get(Objects.requireNonNull(groupId)));
    }

    @Override public Result<GroupRole, GroupAuthorizationFailure> requireMember(UUID groupId) {
        Objects.requireNonNull(groupId);
        if (!identity.isAuthenticated()) return Result.err(new GroupAuthorizationFailure.Unauthenticated());
        return role(groupId).<Result<GroupRole, GroupAuthorizationFailure>>map(Result::ok)
            .orElseGet(() -> Result.err(new GroupAuthorizationFailure.AttestationRequired(GroupMembershipTrust.requirement(groupId))));
    }

    @Override public Result<GroupRole, GroupAuthorizationFailure> requireAdministrator(UUID groupId) {
        return requireMember(groupId).flatMap(role -> role.isAdministrator() ? Result.ok(role)
            : Result.err(new GroupAuthorizationFailure.InsufficientPermissions(groupId)));
    }
}
