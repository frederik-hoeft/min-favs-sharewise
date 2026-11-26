package de.hsh.favs.sharewise.groups;

import de.hsh.favs.sharewise.shared.result.Result;
import java.util.Optional;
import java.util.UUID;

/** Operation-scoped interpretation of admitted Group membership evidence, not current persistence state. */
public interface GroupAuthorizationContext {
    /** Empty means that admitted evidence does not establish a role, not that membership is absent. */
    Optional<GroupRole> role(UUID groupId);

    default boolean isMember(UUID groupId) { return role(groupId).isPresent(); }

    default boolean isAdministrator(UUID groupId) {
        return role(groupId).map(GroupRole::isAdministrator).orElse(false);
    }

    Result<GroupRole, GroupAuthorizationFailure> requireMember(UUID groupId);

    Result<GroupRole, GroupAuthorizationFailure> requireAdministrator(UUID groupId);
}
