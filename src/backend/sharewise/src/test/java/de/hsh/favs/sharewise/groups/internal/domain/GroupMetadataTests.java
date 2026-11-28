package de.hsh.favs.sharewise.groups.internal.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import de.hsh.favs.sharewise.groups.internal.domain.MetadataViolation.Field;
import de.hsh.favs.sharewise.groups.internal.domain.MetadataViolation.Reason;
import de.hsh.favs.sharewise.shared.result.Result;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/** GM-09 / GM-T05: metadata normalization and validation before any database access. */
class GroupMetadataTests {

    private static final String SUPPLEMENTARY_LETTER = "𐐀";

    @Test
    void nameIsTrimmed() {
        assertEquals(Result.ok(new GroupName("Flat share")), GroupName.parse("  Flat share  "));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   ", "\t\n", "　"})
    void missingOrBlankNameIsRequired(String raw) {
        assertEquals(Result.err(new MetadataViolation(Field.NAME, Reason.REQUIRED)), GroupName.parse(raw));
    }

    @Test
    void nameLengthCountsCodePointsNotUtf16Units() {
        String sixtyFourLetters = SUPPLEMENTARY_LETTER.repeat(GroupName.MAX_CODE_POINTS);
        assertEquals(128, sixtyFourLetters.length());
        assertEquals(Result.ok(new GroupName(sixtyFourLetters)), GroupName.parse(sixtyFourLetters));

        assertEquals(Result.err(new MetadataViolation(Field.NAME, Reason.TOO_LONG)),
            GroupName.parse("x".repeat(GroupName.MAX_CODE_POINTS + 1)));
        assertEquals(Result.ok(new GroupName("x".repeat(GroupName.MAX_CODE_POINTS))),
            GroupName.parse("x".repeat(GroupName.MAX_CODE_POINTS)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Line\nbreak", "Tab\there", "\tName", "Name\u2028", "Nul\u0000", "Delete\u007f", "C1\u0085"})
    void nameRejectsControlCharacters(String raw) {
        assertEquals(Result.err(new MetadataViolation(Field.NAME, Reason.CONTROL_CHARACTER)), GroupName.parse(raw));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Trip\u200b", "A\u202eB", "😀", "\u3164", "...", "\u034f", "\u0301A"})
    void nameRejectsUnsupportedText(String raw) {
        assertEquals(Result.err(new MetadataViolation(Field.NAME, Reason.UNSUPPORTED_CHARACTER)), GroupName.parse(raw));
    }

    @Test
    void descriptionRejectsUnsupportedText() {
        assertEquals(Result.err(new MetadataViolation(Field.DESCRIPTION, Reason.UNSUPPORTED_CHARACTER)),
            GroupDescription.parse("Note\u200b"));
        assertEquals(Result.ok(Optional.of(new GroupDescription("80 € + breakfast"))),
            GroupDescription.parse("80 € + breakfast"));
    }

    @Test
    void nameConstructorRejectsUnnormalizedOrInvalidValues() {
        assertThrows(IllegalArgumentException.class, () -> new GroupName(" padded "));
        assertThrows(IllegalArgumentException.class, () -> new GroupName(""));
        assertThrows(IllegalArgumentException.class, () -> new GroupName("x".repeat(65)));
        assertThrows(NullPointerException.class, () -> new GroupName(null));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "  ", "\n "})
    void missingOrBlankDescriptionIsAbsent(String raw) {
        assertEquals(Result.ok(Optional.empty()), GroupDescription.parse(raw));
    }

    @Test
    void descriptionIsTrimmedAndAllowsLineBreaks() {
        assertEquals(Result.ok(Optional.of(new GroupDescription("First line\nSecond\r\nThird"))),
            GroupDescription.parse("  First line\nSecond\r\nThird \n"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Tab\there", "\t", "Note\t", "Lone\rreturn", "Nul\u0000", "Escape\u001b"})
    void descriptionRejectsOtherControlCharacters(String raw) {
        assertEquals(Result.err(new MetadataViolation(Field.DESCRIPTION, Reason.CONTROL_CHARACTER)),
            GroupDescription.parse(raw));
    }

    @Test
    void descriptionLengthCountsCodePoints() {
        String maximum = SUPPLEMENTARY_LETTER.repeat(GroupDescription.MAX_CODE_POINTS);
        assertEquals(Result.ok(Optional.of(new GroupDescription(maximum))), GroupDescription.parse(maximum));
        assertEquals(Result.err(new MetadataViolation(Field.DESCRIPTION, Reason.TOO_LONG)),
            GroupDescription.parse("d".repeat(GroupDescription.MAX_CODE_POINTS + 1)));
    }

    @Test
    void metadataReportsEveryRejectedField() {
        assertEquals(Result.err(List.of(
                new MetadataViolation(Field.NAME, Reason.REQUIRED),
                new MetadataViolation(Field.DESCRIPTION, Reason.TOO_LONG))),
            GroupMetadata.parse(" ", "d".repeat(513)));
    }

    @Test
    void validMetadataCombinesNormalizedValues() {
        assertEquals(Result.ok(new GroupMetadata(new GroupName("Trip"), Optional.empty())),
            GroupMetadata.parse(" Trip ", "   "));
        assertEquals(Result.ok(new GroupMetadata(new GroupName("Trip"), Optional.of(new GroupDescription("Summer")))),
            GroupMetadata.parse("Trip", " Summer "));
    }
}
