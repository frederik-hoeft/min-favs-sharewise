/** Decode the actual Fetch destination for route-oriented test fixtures. */
export function apiTarget(input: RequestInfo | URL): string {
    const url: URL = new URL(input instanceof Request ? input.url : String(input));
    const origin: string = typeof location !== 'undefined' ? location.origin : 'http://sharewise.invalid';
    if (url.origin !== origin || !url.pathname.startsWith('/api/')) throw new Error('Unexpected API destination');
    return url.pathname + url.search;
}
