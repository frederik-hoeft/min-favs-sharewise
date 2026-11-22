package de.hsh.favs.sharewise.groups.api.v1;

/**
 * Body of {@code POST /api/groups/v1/groups}. The creator and their role are never part of
 * the request; unknown properties are ignored.
 *
 * @param name the requested name
 * @param description the optional description; absent, {@code null} or blank means none
 */
public record CreateGroupRequest(String name, String description) {
}
