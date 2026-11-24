/** Restrict sign-in return navigation to application pages on this origin. */
export function safeAppReturnPath(candidate: string | null): string {
    if (!candidate || !/^\/app(?:[/?#]|$)/.test(candidate) ||
        /[\\\x00-\x1f\x7f]/.test(candidate) || /%(?:2e|2f|5c)/i.test(candidate)) return '/app';
    try {
        const parsed = new URL(candidate, 'https://sharewise.invalid');
        if (parsed.origin !== 'https://sharewise.invalid' ||
            !/^\/app(?:\/|$)/.test(parsed.pathname)) return '/app';
        return parsed.pathname + parsed.search + parsed.hash;
    } catch {
        return '/app';
    }
}
