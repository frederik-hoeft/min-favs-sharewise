import { readFileSync } from 'node:fs';
import { request as httpsRequest } from 'node:https';
import { checkServerIdentity } from 'node:tls';

export interface Trace {
    readonly method: string;
    readonly target: string;
    readonly status: number;
    readonly code?: string;
    readonly credentials: readonly number[];
    readonly acquisition?: { authority: string; kind: string; scope: string };
}

/**
 * Test-only physical transport adapter: no fabricated HTTP responses or auth
 * policy. Connect to Compose DNS:443 while retaining the real public URL, Host,
 * SNI, certificate verification and same-origin cookie context. The production
 * client above this boundary is unchanged. Not a browser cookie/Web Locks test.
 */
export class DeployedHttpsTransport {
    readonly origin: string;
    private readonly host: string;
    private readonly port: number;
    private readonly ca: Buffer;
    private readonly cookies = new Map<string, string>();
    private readonly credentialIds = new Map<string, number>();
    readonly traces: Trace[] = [];
    /** Optional deterministic test barrier after real issuance, before the client consumes it. */
    afterResponse?: (trace: Trace) => Promise<void>;
    /** Simulate a corrupted supplementary value on the wire, exercising real ingress rejection. */
    corruptNextSupplementary = false;
    /** Simulate lost success after a real mutation; never synthesize an HTTP response. */
    loseNextSuccess = false;

    constructor() {
        const origin = process.env.SHAREWISE_E2E_ORIGIN;
        const ca = process.env.SHAREWISE_E2E_CA;
        const host = process.env.SHAREWISE_E2E_CONNECT_HOST;
        const port = Number(process.env.SHAREWISE_E2E_CONNECT_PORT);
        if (!origin || !ca || !host || !Number.isInteger(port) || port < 1 || port > 65535) {
            throw new Error('Deployed E2E requires origin, connection host/port and public CA file');
        }
        const url = new URL(origin);
        if (url.protocol !== 'https:' || url.origin !== origin || url.username || url.password) throw new Error('Invalid E2E HTTPS origin');
        this.origin = origin;
        this.host = host;
        this.port = port;
        this.ca = readFileSync(ca);
    }

    private id(token: string): number {
        let id = this.credentialIds.get(token);
        if (!id) { id = this.credentialIds.size + 1; this.credentialIds.set(token, id); }
        return id;
    }

    private acceptCookies(fields: string[]): void {
        // Both actual IAM cookies are host-only, Secure, root-path cookies.
        // Fail if that narrow test assumption changes rather than silently
        // claiming browser-equivalent cookie behavior.
        for (const field of fields) {
            const [pair, ...attributes] = field.split(';').map(part => part.trim());
            const split = pair.indexOf('=');
            const name = pair.slice(0, split);
            const value = pair.slice(split + 1);
            const lower = attributes.map(attribute => attribute.toLowerCase());
            if (split <= 0 || !lower.includes('path=/') || !lower.includes('secure') ||
                lower.some(attribute => attribute.startsWith('domain='))) throw new Error('Unexpected deployed cookie contract');
            if (lower.includes('max-age=0') || !value) this.cookies.delete(name);
            else this.cookies.set(name, value);
        }
    }

    readonly fetch: typeof fetch = async (input, init) => {
        if (typeof input !== 'string') throw new Error('Noncanonical E2E request');
        const url = new URL(input);
        if (!url.pathname.startsWith('/api/') || init?.mode !== 'same-origin') throw new Error('Noncanonical E2E request');
        if (url.origin !== this.origin) throw new Error('Cross-origin E2E request');
        const method = init?.method ?? 'GET';
        const headers = new Headers(init?.headers);
        headers.set('Host', url.host);
        if (method !== 'GET' && method !== 'HEAD') headers.set('Origin', this.origin);
        if (init?.credentials !== 'omit' && this.cookies.size) headers.set('Cookie', [...this.cookies].map(([name, value]) => `${name}=${value}`).join('; '));
        const supplied = headers.get('ShareWise-Attestation')?.split(', ').filter(Boolean) ?? [];
        if (supplied.length && this.corruptNextSupplementary) {
            this.corruptNextSupplementary = false;
            const [header, payload, signature] = supplied[0].split('.');
            // Change the first signature byte, not unused base64 padding bits.
            const damaged = `${header}.${payload}.${signature[0] === 'A' ? 'B' : 'A'}${signature.slice(1)}`;
            headers.set('ShareWise-Attestation', [damaged, ...supplied.slice(1)].join(', '));
        }
        const sentTokens = headers.get('ShareWise-Attestation')?.split(', ').filter(Boolean) ?? [];
        const body = init?.body;
        let bytes: Buffer | undefined;
        if (typeof body === 'string') bytes = Buffer.from(body);
        else if (body instanceof FormData) {
            // Only exercise real non-replayable dispatch, not multipart encoding policy.
            const encoded = new Response(body);
            bytes = Buffer.from(await encoded.arrayBuffer());
            headers.set('Content-Type', encoded.headers.get('Content-Type')!);
        } else if (body !== undefined && body !== null) throw new Error('Unsupported E2E request body');
        if (bytes) headers.set('Content-Length', String(bytes.length));
        const result = await new Promise<{ status: number; headers: Headers; body: Buffer }>((resolve, reject) => {
            const req = httpsRequest({ hostname: this.host, port: this.port, servername: url.hostname,
                checkServerIdentity: (_hostname, certificate) => checkServerIdentity(url.hostname, certificate),
                ca: this.ca, rejectUnauthorized: true, method, path: url.href.slice(this.origin.length),
                headers: Object.fromEntries(headers), signal: init?.signal ?? undefined }, response => {
                const chunks: Buffer[] = [];
                response.on('data', chunk => chunks.push(Buffer.from(chunk)));
                response.on('error', () => reject(new Error('Deployed HTTPS response interrupted')));
                response.on('end', () => {
                    try {
                        if (init?.credentials !== 'omit') this.acceptCookies(response.headers['set-cookie'] ?? []);
                        const received = new Headers();
                        for (const [name, value] of Object.entries(response.headers)) {
                            if (name === 'set-cookie' || value === undefined) continue;
                            received.set(name, Array.isArray(value) ? value.join(', ') : value);
                        }
                        resolve({ status: response.statusCode!, headers: received, body: Buffer.concat(chunks) });
                    } catch (error) { reject(error); }
                });
            });
            req.on('error', () => reject(new Error('Deployed HTTPS request failed')));
            req.end(bytes);
        });
        if (result.status >= 300 && result.status < 400) throw new Error('Deployed HTTPS redirect rejected');
        let code: string | undefined;
        if (result.status >= 400 && result.headers.get('Content-Type')?.includes('json')) {
            try { const problem = JSON.parse(result.body.toString()); if (typeof problem.code === 'string') code = problem.code; } catch { /* Let the actual client classify malformed responses. */ }
        }
        const authority = /^\/api\/([^/]+)\/v1\/domain-principal\/attestations$/.exec(url.pathname)?.[1];
        let acquisition: Trace['acquisition'];
        if (authority && typeof body === 'string') {
            const { kind, scope } = JSON.parse(body);
            acquisition = { authority, kind, scope };
        }
        const trace = { method, target: url.href.slice(this.origin.length), status: result.status, code,
            credentials: sentTokens.map(token => this.id(token)), acquisition };
        this.traces.push(trace);
        await this.afterResponse?.(trace);
        if (this.loseNextSuccess && result.status >= 200 && result.status < 300) {
            this.loseNextSuccess = false;
            throw new Error('Simulated lost success after real HTTP response');
        }
        const response = new Response(result.status === 204 ? null : new Uint8Array(result.body), { status: result.status, headers: result.headers });
        Object.defineProperty(response, 'url', { value: url.href });
        return response;
    };
}
