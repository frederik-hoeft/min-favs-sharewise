package de.hsh.favs.sharewise.groups.internal.application;

import java.util.Objects;
import java.util.UUID;

/**
 * Untrusted input that replaces all editable metadata of a group.
 *
 * @param groupId the target group
 * @param name the requested name, validated by the service
 * @param description the requested description; {@code null} or blank clears it
 * @param expectedVersion the version the caller last read
 */
public record ReplaceGroupMetadataCommand(UUID groupId, String name, String description, long expectedVersion) {

    public ReplaceGroupMetadataCommand {
        Objects.requireNonNull(groupId, "groupId");
    }
}
