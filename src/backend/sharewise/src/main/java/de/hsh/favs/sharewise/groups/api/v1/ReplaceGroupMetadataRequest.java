package de.hsh.favs.sharewise.groups.api.v1;

/**
 * Body of {@code PUT /api/groups/v1/groups/{groupId}}, replacing all editable metadata.
 *
 * @param name the requested name
 * @param description the description; absent, {@code null} or blank clears it
 * @param expectedVersion the group version the client last read; required
 */
public record ReplaceGroupMetadataRequest(String name, String description, Long expectedVersion) {
}
