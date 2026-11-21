package de.hsh.favs.sharewise.groups.internal.domain;

import de.hsh.favs.sharewise.shared.result.Result;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The editable metadata of a group.
 *
 * @param name the group name
 * @param description the optional description
 */
public record GroupMetadata(GroupName name, Optional<GroupDescription> description) {

    public GroupMetadata {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
    }

    /** Validates untrusted name and description input, reporting every rejected field. */
    public static Result<GroupMetadata, List<MetadataViolation>> parse(String rawName, String rawDescription) {
        Result<GroupName, MetadataViolation> name = GroupName.parse(rawName);
        Result<Optional<GroupDescription>, MetadataViolation> description = GroupDescription.parse(rawDescription);
        List<MetadataViolation> violations = new ArrayList<>();
        if (name instanceof Result.Err<GroupName, MetadataViolation>(MetadataViolation violation)) {
            violations.add(violation);
        }
        if (description instanceof Result.Err<Optional<GroupDescription>, MetadataViolation>(MetadataViolation violation)) {
            violations.add(violation);
        }
        if (name instanceof Result.Ok<GroupName, MetadataViolation>(GroupName validName)
                && description instanceof Result.Ok<Optional<GroupDescription>, MetadataViolation>(
                    Optional<GroupDescription> validDescription)) {
            return Result.ok(new GroupMetadata(validName, validDescription));
        }
        return Result.err(List.copyOf(violations));
    }
}
