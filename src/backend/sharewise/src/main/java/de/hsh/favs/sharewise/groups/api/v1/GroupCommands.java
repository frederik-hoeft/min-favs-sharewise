package de.hsh.favs.sharewise.groups.api.v1;

import de.hsh.favs.sharewise.groups.internal.application.CreateGroupCommand;
import de.hsh.favs.sharewise.groups.internal.application.ReplaceGroupMetadataCommand;
import de.hsh.favs.sharewise.groups.internal.domain.GroupDescription;
import de.hsh.favs.sharewise.groups.internal.domain.GroupMetadata;
import de.hsh.favs.sharewise.groups.internal.domain.GroupName;
import de.hsh.favs.sharewise.groups.internal.domain.MetadataViolation;
import de.hsh.favs.sharewise.shared.result.Result;
import de.hsh.favs.sharewise.shared.validation.ValidationError;
import de.hsh.favs.sharewise.shared.validation.ValidationErrors;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Converts Group request bodies into application commands and maps metadata violations to
 * transport-independent field errors.
 *
 * <p>Metadata rules are owned by the domain values and enforced by the application services,
 * which also serve non-HTTP callers. This adapter checks only what the HTTP contract adds,
 * a present body and {@code expectedVersion}. When it rejects a replacement it also reports the
 * metadata violations, so a client sees every invalid field at once.
 */
final class GroupCommands {

    static final String NAME_FIELD = "name";
    static final String DESCRIPTION_FIELD = "description";
    static final String EXPECTED_VERSION_FIELD = "expectedVersion";

    private GroupCommands() {
    }

    static Result<CreateGroupCommand, ValidationErrors> create(CreateGroupRequest request) {
        if (request == null) {
            return Result.err(missingBody());
        }
        return Result.ok(new CreateGroupCommand(request.name(), request.description()));
    }

    static Result<ReplaceGroupMetadataCommand, ValidationErrors> replace(
            UUID groupId, ReplaceGroupMetadataRequest request) {
        Objects.requireNonNull(groupId, "groupId");
        if (request == null) {
            return Result.err(missingBody());
        }
        Long expectedVersion = request.expectedVersion();
        if (expectedVersion != null && expectedVersion >= 0) {
            return Result.ok(new ReplaceGroupMetadataCommand(
                groupId, request.name(), request.description(), expectedVersion));
        }
        List<ValidationError> errors = new ArrayList<>();
        if (GroupMetadata.parse(request.name(), request.description())
                instanceof Result.Err<GroupMetadata, List<MetadataViolation>>(List<MetadataViolation> violations)) {
            errors.addAll(fieldErrors(violations));
        }
        errors.add(expectedVersion == null
            ? new ValidationError(EXPECTED_VERSION_FIELD, "required", "The version of the group you edited is required.")
            : new ValidationError(EXPECTED_VERSION_FIELD, "invalid", "The group version cannot be negative."));
        return Result.err(new ValidationErrors(errors));
    }

    /** Field errors for metadata that the application service rejected. */
    static ValidationErrors validationErrors(List<MetadataViolation> violations) {
        return new ValidationErrors(fieldErrors(violations));
    }

    private static List<ValidationError> fieldErrors(List<MetadataViolation> violations) {
        return violations.stream().map(GroupCommands::fieldError).toList();
    }

    private static ValidationError fieldError(MetadataViolation violation) {
        return switch (violation.field()) {
            case NAME -> new ValidationError(NAME_FIELD, code(violation.reason()), switch (violation.reason()) {
                case REQUIRED -> "Enter a group name.";
                case TOO_LONG -> "Use at most " + GroupName.MAX_CODE_POINTS + " characters.";
                case CONTROL_CHARACTER -> "Remove tabs, line breaks and other control characters.";
            });
            case DESCRIPTION -> new ValidationError(DESCRIPTION_FIELD, code(violation.reason()),
                switch (violation.reason()) {
                    case REQUIRED -> "Enter a description.";
                    case TOO_LONG -> "Use at most " + GroupDescription.MAX_CODE_POINTS + " characters.";
                    case CONTROL_CHARACTER -> "Line breaks are allowed; remove tabs and other control characters.";
                });
        };
    }

    private static String code(MetadataViolation.Reason reason) {
        return switch (reason) {
            case REQUIRED -> "required";
            case TOO_LONG -> "too_long";
            case CONTROL_CHARACTER -> "control_character";
        };
    }

    private static ValidationErrors missingBody() {
        return ValidationErrors.single("request", "required", "A request body is required.");
    }
}
