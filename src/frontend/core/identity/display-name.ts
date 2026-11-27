/** Browser-side hint mirroring IAM's Unicode scalar and text policy. IAM is authoritative. */
export const MAX_DISPLAY_NAME_CHARACTERS: number = 80;

/** HTML maxLength counts UTF-16 code units, while IAM counts Unicode scalar values. */
export function withinDisplayNameLimit(value: string): boolean {
    return Array.from(value).length <= MAX_DISPLAY_NAME_CHARACTERS;
}
export function validateDisplayName(value: string): string | undefined {
    const characters: string[] = Array.from(value);
    if (characters.length > MAX_DISPLAY_NAME_CHARACTERS) {
        return 'Enter at most 80 characters.';
    }
    if (characters.length === 0 ||
        !characters.some((character: string): boolean => !/\p{White_Space}/u.test(character)) ||
        characters.some((character: string): boolean => {
            const point: number = character.codePointAt(0)!;
            return (point >= 0xd800 && point <= 0xdfff) ||
                /\p{Cc}/u.test(character) || point === 0x2028 || point === 0x2029;
        })) {
        return 'Enter a name containing non-whitespace text, without control characters or line separators.';
    }
    return undefined;
}
