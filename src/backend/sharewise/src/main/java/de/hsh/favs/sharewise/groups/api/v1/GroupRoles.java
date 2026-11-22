package de.hsh.favs.sharewise.groups.api.v1;

import de.hsh.favs.sharewise.groups.internal.domain.GroupRole;

/** The API representation of group roles, matching the {@code groups.group_role} values. */
final class GroupRoles {

    private GroupRoles() {
    }

    static String toApi(GroupRole role) {
        return switch (role) {
            case ADMIN -> "admin";
            case MEMBER -> "member";
        };
    }
}
