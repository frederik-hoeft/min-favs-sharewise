package de.hsh.favs.sharewise.groups;

/** Organizational role of a group member, persisted as the {@code groups.group_role} enum. */
public enum GroupRole {
    MEMBER,
    ADMIN;

    /** Whether this role may administer the group's metadata and its membership. */
    public boolean isAdministrator() {
        return this == ADMIN;
    }
}
