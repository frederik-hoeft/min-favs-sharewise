/** Browser-side hint mirroring IAM's Unicode scalar and text policy. IAM is authoritative. */
export function validateDisplayName(value: string): string | undefined {
    const characters = Array.from(value);
    if (characters.length === 0 || characters.length > 80 ||
        !characters.some((character) => !/\p{White_Space}/u.test(character)) ||
        characters.some((character) => {
            const point = character.codePointAt(0)!;
            return (point >= 0xd800 && point <= 0xdfff) ||
                /\p{Cc}/u.test(character) || point === 0x2028 || point === 0x2029;
        })) {
        return 'Use 1–80 Unicode characters, including non-whitespace text, without control characters or line separators.';
    }
    return undefined;
}
