import { ApiError, parseErrorResponse } from './api-error.ts';

export interface ApiRequestOptions extends Omit<RequestInit, 'body'> {
    readonly body?: unknown;
    /** Abort a stalled network request; 0 deliberately disables the deadline. */
    readonly timeoutMs?: number;
}

export const DEFAULT_API_TIMEOUT_MS = 45_000;

export interface SentRequestContext {
    /** Session identity captured when the request's bearer was attached. */
    readonly sessionId?: string;
    readonly method: string;
    readonly replayable: boolean;
    readonly hasExplicitAuthorization: boolean;
    readonly attempt: 'initial' | 'replay';
}

export interface ApiClientHooks {
    /** Attach the current bearer; return its session ID for stale-response protection. */
    readonly onRequest?: (path: string, headers: Headers) => Promise<string | undefined | void> | string | undefined | void;
    /**
     * A caller may request one replay only after confirming pre-dispatch rejection.
     * The HTTP client independently rejects retrying a non-repeatable body.
     */
    readonly onUnauthorized?: (
        path: string, response: Response, sentHeaders: Readonly<Headers>, context: SentRequestContext
    ) => Promise<boolean>;
}

export interface ApiClient {
    request<T>(path: string, options?: ApiRequestOptions): Promise<T>;
    get<T>(path: string, options?: Omit<ApiRequestOptions, 'method' | 'body'>): Promise<T>;
    post<T>(path: string, body?: unknown, options?: Omit<ApiRequestOptions, 'method' | 'body'>): Promise<T>;
    put<T>(path: string, body?: unknown, options?: Omit<ApiRequestOptions, 'method' | 'body'>): Promise<T>;
    patch<T>(path: string, body?: unknown, options?: Omit<ApiRequestOptions, 'method' | 'body'>): Promise<T>;
    delete<T>(path: string, options?: Omit<ApiRequestOptions, 'method' | 'body'>): Promise<T>;
}

function normalizeApiPath(path: string): string {
    if (path.startsWith('/api/') || path === '/api') return path;
    if (path.startsWith('/')) return `/api${path}`;
    return `/api/${path}`;
}

function prepareBody(body: unknown, headers: Headers): { body: BodyInit | undefined; replayable: boolean } {
    if (body === undefined) return { body: undefined, replayable: true };
    if (typeof body === 'string' || body instanceof URLSearchParams || body instanceof Blob) {
        return { body, replayable: true };
    }
    if (body instanceof FormData) return { body, replayable: false };
    if (typeof ReadableStream !== 'undefined' && body instanceof ReadableStream) {
        return { body, replayable: false };
    }
    if (!headers.has('Content-Type')) headers.set('Content-Type', 'application/json');
    return { body: JSON.stringify(body), replayable: true };
}

export function createApiClient(hooks: ApiClientHooks = {}, fetchImpl: typeof fetch = fetch): ApiClient {
    async function executeRequest<T>(path: string, options: ApiRequestOptions = {}): Promise<T> {
        const targetPath = normalizeApiPath(path);
        const initialHeaders = new Headers(options.headers);
        if (!initialHeaders.has('Accept')) initialHeaders.set('Accept', 'application/json, application/problem+json');
        const prepared = prepareBody(options.body, initialHeaders);
        const method = (options.method ?? 'GET').toUpperCase();
        const timeoutMs = options.timeoutMs ?? DEFAULT_API_TIMEOUT_MS;
        if (!Number.isFinite(timeoutMs) || timeoutMs < 0) throw new RangeError('Invalid HTTP request timeout');
        const hasExplicitAuthorization = initialHeaders.has('Authorization');
        const { timeoutMs: _timeoutMs, body: _body, ...requestOptions } = options;

        async function attempt(allowRetry: boolean, boundSessionId?: string): Promise<T> {
            const headers = new Headers(initialHeaders);
            const sessionId = hooks.onRequest ? await hooks.onRequest(targetPath, headers) : undefined;
            if (boundSessionId && sessionId !== boundSessionId) {
                throw new ApiError({
                    status: 0, code: 'session_context_changed', title: 'Sign-in Changed',
                    detail: 'The active session changed before the request could be repeated.',
                });
            }
            const context: SentRequestContext = {
                sessionId: typeof sessionId === 'string' ? sessionId : undefined,
                method,
                replayable: prepared.replayable,
                hasExplicitAuthorization,
                attempt: allowRetry ? 'initial' : 'replay',
            };
            const controller = new AbortController();
            // Never race fetch: a caller may hold the refresh-cookie Web Lock.
            const timeout = timeoutMs > 0 ? setTimeout(() => controller.abort(), timeoutMs) : undefined;
            const abort = () => controller.abort();
            options.signal?.addEventListener('abort', abort, { once: true });
            if (options.signal?.aborted) controller.abort();
            let response: Response;
            try {
                try {
                    response = await fetchImpl(targetPath, {
                        ...requestOptions,
                        method,
                        signal: controller.signal,
                        headers,
                        body: prepared.body,
                        credentials: options.credentials ?? 'same-origin',
                        cache: options.cache ?? 'no-store',
                    });
                } catch (cause) {
                    throw new ApiError({
                        status: 0,
                        code: 'network_error',
                        title: 'Network Error',
                        detail: cause instanceof Error ? cause.message : 'Unable to reach the server.',
                    });
                }

                if (response.status === 401 && hooks.onUnauthorized && !options.signal?.aborted) {
                    const shouldRetry = await hooks.onUnauthorized(targetPath, response, headers, context);
                    if (shouldRetry && allowRetry && prepared.replayable && !options.signal?.aborted) {
                        return attempt(false, sessionId || undefined);
                    }
                }
                if (!response.ok) throw await parseErrorResponse(response);
                if (response.status === 204) return undefined as T;
                try {
                    const contentType = response.headers.get('content-type') ?? '';
                    return contentType.includes('json')
                        ? (await response.json()) as T
                        : (await response.text()) as unknown as T;
                } catch {
                    throw new ApiError({
                        status: 0,
                        code: 'invalid_response',
                        title: 'Response Unavailable',
                        detail: 'Unable to read the server response.',
                    });
                }
            } finally {
                if (timeout !== undefined) clearTimeout(timeout);
                options.signal?.removeEventListener('abort', abort);
            }
        }
        return attempt(true);
    }

    return {
        request<T>(path: string, options?: ApiRequestOptions): Promise<T> {
            return executeRequest<T>(path, options);
        },
        get<T>(path: string, options?: Omit<ApiRequestOptions, 'method' | 'body'>): Promise<T> {
            return executeRequest<T>(path, { ...options, method: 'GET' });
        },
        post<T>(path: string, body?: unknown, options?: Omit<ApiRequestOptions, 'method' | 'body'>): Promise<T> {
            return executeRequest<T>(path, { ...options, method: 'POST', body });
        },
        put<T>(path: string, body?: unknown, options?: Omit<ApiRequestOptions, 'method' | 'body'>): Promise<T> {
            return executeRequest<T>(path, { ...options, method: 'PUT', body });
        },
        patch<T>(path: string, body?: unknown, options?: Omit<ApiRequestOptions, 'method' | 'body'>): Promise<T> {
            return executeRequest<T>(path, { ...options, method: 'PATCH', body });
        },
        delete<T>(path: string, options?: Omit<ApiRequestOptions, 'method' | 'body'>): Promise<T> {
            return executeRequest<T>(path, { ...options, method: 'DELETE' });
        },
    };
}

export const apiClient: ApiClient = createApiClient();
