package de.hsh.favs.sharewise.groups.api.v1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import de.hsh.favs.sharewise.groups.internal.application.group.CreateGroupCommand;
import de.hsh.favs.sharewise.groups.internal.application.group.ReplaceGroupMetadataCommand;
import de.hsh.favs.sharewise.groups.internal.domain.MetadataViolation.Field;
import de.hsh.favs.sharewise.groups.internal.domain.MetadataViolation.Reason;
import de.hsh.favs.sharewise.groups.internal.domain.MetadataViolation;
import de.hsh.favs.sharewise.shared.result.Result;
import de.hsh.favs.sharewise.shared.validation.ValidationError;
import de.hsh.favs.sharewise.shared.validation.ValidationErrors;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The HTTP request contract of Group commands and the field errors that clients display. */
class GroupCommandsTests {

    private static final UUID GROUP = UUID.fromString("018f8706-7b6e-7acc-9061-c3e78a1be501");

    @Test
    void creationPassesUntrustedMetadataToTheServiceUnchanged() {
        assertEquals(Result.ok(new CreateGroupCommand("  Trip ", null)),
            GroupCommands.create(new CreateGroupRequest("  Trip ", null)));
    }

    @Test
    void replacementRequiresANonNegativeExpectedVersion() {
        assertEquals(Result.ok(new ReplaceGroupMetadataCommand(GROUP, "Trip", "Summer", 3)),
            GroupCommands.replace(GROUP, new ReplaceGroupMetadataRequest("Trip", "Summer", 3L)));
        assertRejected(GroupCommands.replace(GROUP, new ReplaceGroupMetadataRequest("Trip", null, null)),
            "expectedVersion:required");
        assertRejected(GroupCommands.replace(GROUP, new ReplaceGroupMetadataRequest("Trip", null, -1L)),
            "expectedVersion:invalid");
    }

    @Test
    void aRejectedReplacementAlsoReportsEveryInvalidMetadataField() {
        assertRejected(GroupCommands.replace(GROUP, new ReplaceGroupMetadataRequest(" ", "d".repeat(513), null)),
            "name:required", "description:too_long", "expectedVersion:required");
    }

    @Test
    void aMissingBodyIsAValidationFailureOfTheRequest() {
        assertRejected(GroupCommands.create(null), "request:required");
        assertRejected(GroupCommands.replace(GROUP, null), "request:required");
    }

    @Test
    void everyMetadataViolationMapsToItsFieldAndAStableCodeWithAMessage() {
        List<MetadataViolation> violations = List.of(
            new MetadataViolation(Field.NAME, Reason.REQUIRED),
            new MetadataViolation(Field.NAME, Reason.TOO_LONG),
            new MetadataViolation(Field.NAME, Reason.CONTROL_CHARACTER),
            new MetadataViolation(Field.DESCRIPTION, Reason.REQUIRED),
            new MetadataViolation(Field.DESCRIPTION, Reason.TOO_LONG),
            new MetadataViolation(Field.DESCRIPTION, Reason.CONTROL_CHARACTER));

        ValidationErrors errors = GroupCommands.validationErrors(violations);

        assertEquals(List.of("name:required", "name:too_long", "name:control_character", "description:required",
            "description:too_long", "description:control_character"), keys(errors));
        assertEquals("Use at most 64 characters.", errors.errors().get(1).message());
        assertEquals("Use at most 512 characters.", errors.errors().get(4).message());
        for (ValidationError error : errors.errors()) {
            assertFalse(error.message().isBlank());
        }
    }

    private static void assertRejected(Result<?, ValidationErrors> result, String... expected) {
        ValidationErrors errors = switch (result) {
            case Result.Err<?, ValidationErrors>(ValidationErrors rejected) -> rejected;
            case Result.Ok<?, ValidationErrors> accepted -> throw new AssertionError("expected rejection: " + accepted);
        };
        assertEquals(List.of(expected), keys(errors));
    }

    private static List<String> keys(ValidationErrors errors) {
        return errors.errors().stream().map(error -> error.field() + ":" + error.code()).toList();
    }
}
