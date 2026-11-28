package de.hsh.favs.sharewise.shared.validation;

import java.util.Optional;

/**
 * Stateless singleton acceptance policy for human-entered free text, not an output encoder.
 * Domain types own length, required content, normalization and multiline semantics.
 */
public enum UnicodeTextValidator {
    INSTANCE;

    public enum Violation { CONTROL_CHARACTER, UNSUPPORTED_CHARACTER }

    /** Ordinary Unicode text, optionally including LF and CRLF line breaks. */
    public Optional<Violation> validate(String text, boolean multiline) {
        boolean markBase = false;
        for (int offset = 0; offset < text.length();) {
            int point = text.codePointAt(offset);
            offset += Character.charCount(point);
            if (multiline && (point == '\n' || (point == '\r' && offset < text.length()
                    && text.charAt(offset) == '\n'))) {
                markBase = false;
                continue;
            }
            if (Character.isISOControl(point) || point == 0x2028 || point == 0x2029) {
                return Optional.of(Violation.CONTROL_CHARACTER);
            }
            if (isDefaultIgnorable(point)) return Optional.of(Violation.UNSUPPORTED_CHARACTER);
            int type = Character.getType(point);
            if (isLetterOrNumber(type)) {
                markBase = true;
                continue;
            }
            switch (type) {
                case Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK -> {
                    if (!markBase) return Optional.of(Violation.UNSUPPORTED_CHARACTER);
                }
                case Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION,
                        Character.START_PUNCTUATION, Character.END_PUNCTUATION,
                        Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION,
                        Character.OTHER_PUNCTUATION, Character.CURRENCY_SYMBOL, Character.SPACE_SEPARATOR -> markBase = false;
                default -> {
                    if ("+<=>^`|~¬±×÷−°©®™".indexOf(point) < 0) return Optional.of(Violation.UNSUPPORTED_CHARACTER);
                    markBase = false;
                }
            }
        }
        return Optional.empty();
    }

    /** Names require at least one non-ignorable letter or number in addition to acceptance. */
    public boolean hasLetterOrNumber(String text) {
        return text.codePoints().anyMatch(point -> !isDefaultIgnorable(point)
                && isLetterOrNumber(Character.getType(point)));
    }

    private static boolean isLetterOrNumber(int type) {
        return switch (type) {
            case Character.UPPERCASE_LETTER, Character.LOWERCASE_LETTER, Character.TITLECASE_LETTER,
                    Character.MODIFIER_LETTER, Character.OTHER_LETTER, Character.DECIMAL_DIGIT_NUMBER,
                    Character.LETTER_NUMBER, Character.OTHER_NUMBER -> true;
            default -> false;
        };
    }

    // Unicode 16.0.0 DerivedCoreProperties.txt, Default_Ignorable_Code_Point.
    // https://www.unicode.org/Public/16.0.0/ucd/DerivedCoreProperties.txt
    // Adjacent ranges merged; review when the JDK's Unicode version changes.
    private static boolean isDefaultIgnorable(int point) {
        return point == 0x00AD || point == 0x034F || point == 0x061C
                || between(point, 0x115F, 0x1160) || between(point, 0x17B4, 0x17B5)
                || between(point, 0x180B, 0x180F) || between(point, 0x200B, 0x200F)
                || between(point, 0x202A, 0x202E) || between(point, 0x2060, 0x206F)
                || point == 0x3164 || between(point, 0xFE00, 0xFE0F) || point == 0xFEFF
                || point == 0xFFA0 || between(point, 0xFFF0, 0xFFF8)
                || between(point, 0x1BCA0, 0x1BCA3) || between(point, 0x1D173, 0x1D17A)
                || between(point, 0xE0000, 0xE0FFF);
    }

    private static boolean between(int point, int first, int last) {
        return point >= first && point <= last;
    }
}
