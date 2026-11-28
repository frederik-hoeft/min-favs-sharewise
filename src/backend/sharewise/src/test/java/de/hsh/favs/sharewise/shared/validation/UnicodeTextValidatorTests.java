package de.hsh.favs.sharewise.shared.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class UnicodeTextValidatorTests {
    private static final UnicodeTextValidator POLICY = UnicodeTextValidator.INSTANCE;

    @ParameterizedTest
    @ValueSource(strings = {"Thomas Müller", "Jose\u0301 O'Connor", "Łukasz", "محمد أحمد", "שָׁלוֹם",
            "张伟", "山田太郎", "𐐀", "नमस्ते", "Hotel + breakfast (80 €)", "£ ¥ ₹ © ° × = <b>text</b>"})
    void acceptsMultilingualOrdinaryText(String value) {
        assertEquals(Optional.empty(), POLICY.validate(value, false));
        assertTrue(POLICY.hasLetterOrNumber(value));
    }

    @ParameterizedTest
    @ValueSource(strings = {"A\u200b", "A\u200c", "A\u200d", "A\u202e", "A\u2069", "A\ufeff", "A\u00ad",
            "A\u034f", "A\ufe0f", "\u115f", "\u1160", "\u3164", "\uffa0", "A\u17b4", "A\u180f",
            "A\uDB40\uDD00", "\u2800", "1\u20e3", "😀", "\ue000", "\u0378", "\ud800", "\udc00", "\u0301A", "A \u0301"})
    void rejectsUnsupportedCharactersAndUnattachedMarks(String value) {
        assertEquals(Optional.of(UnicodeTextValidator.Violation.UNSUPPORTED_CHARACTER), POLICY.validate(value, false));
    }

    @ParameterizedTest
    @ValueSource(strings = {"A\tB", "A\rB", "A\nB", "A\u0000B", "A\u0085B", "A\u2028B", "A\u2029B"})
    void rejectsSingleLineControls(String value) {
        assertEquals(Optional.of(UnicodeTextValidator.Violation.CONTROL_CHARACTER), POLICY.validate(value, false));
    }

    @Test
    void multilinePermitsOnlyLfAndCrLf() {
        assertEquals(Optional.empty(), POLICY.validate("First\nSecond\r\nThird", true));
        assertTrue(POLICY.validate("First\rSecond", true).isPresent());
        assertTrue(POLICY.validate("First\r", true).isPresent());
        assertTrue(POLICY.validate("First\tSecond", true).isPresent());
        assertTrue(POLICY.validate("First\u2028Second", true).isPresent());
    }

    @Test
    void meaningfulNameIsSeparateFromCharacterPolicy() {
        assertEquals(Optional.empty(), POLICY.validate("... + €", false));
        assertFalse(POLICY.hasLetterOrNumber("... + €"));
        assertFalse(POLICY.hasLetterOrNumber("\u3164"));
        assertTrue(POLICY.hasLetterOrNumber("٣"));
    }
}
