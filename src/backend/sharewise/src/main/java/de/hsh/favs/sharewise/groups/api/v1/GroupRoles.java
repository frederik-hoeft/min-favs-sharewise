package de.hsh.favs.sharewise.groups.api.v1;

import de.hsh.favs.sharewise.groups.internal.domain.GroupRole;
import java.util.Optional;

/** The API representation of group roles, matching the {@code groups.group_role} values. */
final class GroupRoles {

    private GroupRoles() {
    }

    static Optional<GroupRole> fromApi(String role) {
        return switch (role) {
            case "admin" -> Optional.of(GroupRole.ADMIN);
            case "member" -> Optional.of(GroupRole.MEMBER);
            case null, default -> Optional.empty();
        };
    }

    static String toApi(GroupRole role) {
        return switch (role) {
            case ADMIN -> "admin";
            case MEMBER -> "member";
        };
    }
}
