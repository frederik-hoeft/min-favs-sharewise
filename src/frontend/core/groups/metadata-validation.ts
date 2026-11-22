/**
 * Client-side mirror of the server's group metadata rules. It gives immediate feedback only;
 * the backend validates and normalizes every request again and remains authoritative.
 */

export const GROUP_NAME_MAX_CODE_POINTS = 64;
export const GROUP_DESCRIPTION_MAX_CODE_POINTS = 512;

export type MetadataField = 'name' | 'description';
export type MetadataViolation = 'required' | 'too_long' | 'control_character';

export type MetadataErrors = Partial<Record<MetadataField, MetadataViolation>>;

export interface MetadataInput {
    readonly name: string;
    readonly description: string;
}

/** Validates both fields and reports every rejected one. */
export function validateGroupMetadata(input: MetadataInput): MetadataErrors {
    const errors: { -readonly [K in MetadataField]?: MetadataViolation } = {};
    const name = stripJavaWhitespace(input.name);
    if (name.length === 0) {
        errors.name = 'required';
    } else if (codePointCount(name) > GROUP_NAME_MAX_CODE_POINTS) {
        errors.name = 'too_long';
    } else if (hasControlCharacter(name, false)) {
        errors.name = 'control_character';
    }

    const description = stripJavaWhitespace(input.description);
    if (codePointCount(description) > GROUP_DESCRIPTION_MAX_CODE_POINTS) {
        errors.description = 'too_long';
    } else if (hasControlCharacter(description, true)) {
        errors.description = 'control_character';
    }
    return errors;
}

/** A blank description is absent, as on the server. */
export function normalizedDescription(description: string): string | null {
    const stripped = stripJavaWhitespace(description);
    return stripped.length === 0 ? null : stripped;
}

export function codePointCount(value: string): number {
    return Array.from(value).length;
}

/** Same as Java's `String.strip()`; `String.prototype.trim()` also removes no-break spaces. */
export function stripJavaWhitespace(value: string): string {
    const codePoints = Array.from(value);
    let start = 0;
    let end = codePoints.length;
    while (start < end && isJavaWhitespace(codePoints[start])) start += 1;
    while (end > start && isJavaWhitespace(codePoints[end - 1])) end -= 1;
    return codePoints.slice(start, end).join('');
}

const NON_BREAKING_SPACES = new Set([' ', ' ', ' ']);
const SEPARATOR = /^[\p{Zs}\p{Zl}\p{Zp}]$/u;

function isJavaWhitespace(codePoint: string): boolean {
    const value = codePoint.codePointAt(0) ?? 0;
    if ((value >= 0x09 && value <= 0x0D) || (value >= 0x1C && value <= 0x1F)) {
        return true;
    }
    return SEPARATOR.test(codePoint) && !NON_BREAKING_SPACES.has(codePoint);
}

/** ISO control characters; descriptions additionally allow `\n` and `\r\n` line breaks. */
function hasControlCharacter(value: string, allowLineBreaks: boolean): boolean {
    for (let index = 0; index < value.length; index += 1) {
        const code = value.charCodeAt(index);
        const control = code <= 0x1F || (code >= 0x7F && code <= 0x9F);
        if (!control) continue;
        const lineBreak = code === 0x0A || (code === 0x0D && value.charCodeAt(index + 1) === 0x0A);
        if (!(allowLineBreaks && lineBreak)) return true;
    }
    return false;
}
