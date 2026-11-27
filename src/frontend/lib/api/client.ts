import { ApiError, parseErrorResponse, requestCanceled } from './api-error.ts';
import { RecoveryBudget, type ApiRequestContext, type ApiRequestHandler, type RequestHandler, type SentRequest, type RecoveryAction } from './handlers.ts';

export interface ApiRequestOptions extends Omit<RequestInit, 'body'> {
    readonly body?: unknown;
    /** Timeout for one HTTP attempt, including response reading, excluding authentication preparation. 0 disables it. */
    readonly timeoutMs?: number;
    /** A strict success status when an operation has an exact acknowledgment contract (e.g., 204). */
    readonly expectedStatus?: number;
}

export const DEFAULT_API_TIMEOUT_MS: number = 45_000;

export interface ApiClient {
    request<T>(path: string, options?: ApiRequestOptions): Promise<T>;
    get<T>(path: string, options?: Omit<ApiRequestOptions, 'method' | 'body'>): Promise<T>;
    post<T>(path: string, body?: unknown, options?: Omit<ApiRequestOptions, 'method' | 'body'>): Promise<T>;
    put<T>(path: string, body?: unknown, options?: Omit<ApiRequestOptions, 'method' | 'body'>): Promise<T>;
    patch<T>(path: string, body?: unknown, options?: Omit<ApiRequestOptions, 'method' | 'body'>): Promise<T>;
    delete<T>(path: string, options?: Omit<ApiRequestOptions, 'method' | 'body'>): Promise<T>;
}

/** Keep routing policy and HTTP dispatch on the same unambiguous API path. */
function normalizeApiPath(path: string): { pathname: string; target: string } {
    const queryIndex: number = path.indexOf('?');
    const rawPathname: string = queryIndex === -1 ? path : path.slice(0, queryIndex);
    const query: string = queryIndex === -1 ? '' : path.slice(queryIndex);
    const rooted: string = rawPathname.startsWith('/') ? rawPathname : `/${rawPathname}`;
    const pathname: string = rooted === '/api' || rooted.startsWith('/api/') ? rooted : `/api${rooted}`;
    // Route segments use unreserved ASCII characters. Reject spellings that a
    // browser, proxy or server could decode or normalize to a different route.
    if (!/^\/api(?:\/[A-Za-z0-9._~-]+)*$/.test(pathname) ||
        pathname.split('/').some((segment: string): boolean => segment === '.' || segment === '..') ||
        path.includes('#') || /\p{Cc}/u.test(path)) {
        throw new ApiError({
            status: 0, code: 'invalid_api_path', title: 'Invalid API Path',
            detail: 'API requests require a canonical relative route path.',
        });
    }
    return { pathname, target: pathname + query };
}

function isReplayableBody(body: unknown): boolean {
    return !(body instanceof FormData || (typeof ReadableStream !== 'undefined' && body instanceof ReadableStream));
}

function prepareBody(body: unknown, headers: Headers): BodyInit | undefined {
    if (body === undefined) return undefined;
    if (body instanceof URLSearchParams) {
        if (!headers.has('Content-Type')) headers.set('Content-Type', 'application/x-www-form-urlencoded;charset=UTF-8');
        return body.toString();
    }
    if (typeof body === 'string' || body instanceof Blob) return body;
    if (body instanceof FormData) return body;
    if (typeof ReadableStream !== 'undefined' && body instanceof ReadableStream) {
        return body;
    }
    if (!headers.has('Content-Type')) headers.set('Content-Type', 'application/json');
    return JSON.stringify(body);
}

function assertNotCanceled(signal?: AbortSignal): void {
    if (signal?.aborted) {
        throw requestCanceled();
    }
}

function networkError(cause: unknown): ApiError {
    return new ApiError({
        status: 0, code: 'network_error', title: 'Network Error',
        detail: cause instanceof Error ? cause.message : 'Unable to reach the server.',
    });
}

interface DetourContext {
    readonly budget: RecoveryBudget;
    readonly assertCurrent: () => void;
}

/** A primary handler precedes supplementary handlers; anonymous requests have no active primary. */
export function createApiClient(handlers: readonly ApiRequestHandler[] = [], fetchImpl: typeof fetch = fetch,
    origin: string = typeof location !== 'undefined' ? location.origin : 'http://sharewise.invalid'): ApiClient {
    const pipeline: ApiRequestHandler[] = [...handlers];
    if (pipeline.some((handler: ApiRequestHandler, index: number): boolean => handler.kind === 'primary' && index !== 0)) {
        throw new Error('Exactly one primary handler may precede supplementary handlers');
    }
    const apiOrigin: string = new URL(origin).origin;
    if (apiOrigin === 'null' || !/^https?:/.test(apiOrigin)) throw new Error('Invalid API origin');

    async function executeRequest<T>(path: string, options: ApiRequestOptions = {}, detour?: DetourContext): Promise<T> {
        const normalized: ReturnType<typeof normalizeApiPath> = normalizeApiPath(path);
        // Use the URL serialization used by Fetch, including significant query ordering/empty values.
        const url: URL = new URL(normalized.target, apiOrigin);
        const target: string = url.href.slice(apiOrigin.length);
        const requestSignal: AbortSignal | undefined = options.signal ?? undefined;
        const initialHeaders: Headers = new Headers(options.headers);
        if (!initialHeaders.has('Accept')) initialHeaders.set('Accept', 'application/json, application/problem+json');
        const method: string = (options.method ?? 'GET').toUpperCase();
        const { timeoutMs: configuredTimeout, expectedStatus, body, ...requestOptions }: ApiRequestOptions = options;
        const timeoutMs: number = configuredTimeout ?? DEFAULT_API_TIMEOUT_MS;
        if (!Number.isFinite(timeoutMs) || timeoutMs < 0) throw new RangeError('Invalid HTTP request timeout');
        const hasExplicitAuthorization: boolean = initialHeaders.has('Authorization');
        const request: ApiRequestContext = Object.freeze({ origin: apiOrigin, pathname: normalized.pathname,
            target, method, replayable: isReplayableBody(body), signal: requestSignal });
        assertNotCanceled(requestSignal);
        detour?.assertCurrent();
        const active: { handler: ApiRequestHandler; state: RequestHandler }[] = [];
        if (!hasExplicitAuthorization) {
            for (const handler of pipeline) {
                if (handler.kind === 'supplementary' && (detour || active.length === 0)) continue;
                const state: RequestHandler | undefined = handler.begin(request);
                if (state) active.push({ handler, state });
            }
        }
        // A nonempty managed pipeline always starts with the active primary handler.
        if (active.length > 0) {
            for (const handler of pipeline) handler.validateHeaders?.(initialHeaders);
        }
        if (detour && active.length === 0) throw new ApiError({ status: 0, code: 'invalid_request_context',
            title: 'Invalid Request Context', detail: 'Credential acquisition requires a managed primary credential.' });
        const prepared: BodyInit | undefined = prepareBody(body, initialHeaders);
        const budget: RecoveryBudget = detour?.budget ?? new RecoveryBudget();
        const assertCurrent: () => void = () => {
            detour?.assertCurrent();
            for (const { state } of active) state.assertCurrent?.();
        };
        // Only a recovery action can open a primary-only detour, sharing the outer budget and binding.
        const primaryOnly: ApiClient = createApiMethods(<R>(path: string, options?: ApiRequestOptions): Promise<R> => {
            if (detour) throw new Error('Nested credential acquisition is not allowed');
            return executeRequest<R>(path, options, { budget, assertCurrent });
        });

        for (let attempt: number = 0; ; attempt++) {
            assertNotCanceled(requestSignal);
            assertCurrent();
            const headers: Headers = new Headers(initialHeaders);
            for (const { state } of active) {
                await state.prepare?.(headers);
                assertNotCanceled(requestSignal);
                assertCurrent();
            }
            const sent: SentRequest = { headers: new Headers(headers), attempt };
            const controller: AbortController = new AbortController();
            const abort: () => void = () => controller.abort();
            requestSignal?.addEventListener('abort', abort, { once: true });
            if (requestSignal?.aborted) controller.abort();
            const timeout: ReturnType<typeof setTimeout> | undefined = timeoutMs > 0 ? setTimeout(abort, timeoutMs) : undefined;
            const assertAttempt: () => void = () => {
                assertNotCanceled(requestSignal);
                if (controller.signal.aborted) throw networkError(new Error('HTTP request timed out.'));
                assertCurrent();
            };
            let rejection: ApiError;
            try {
                assertAttempt();
                let response: Response;
                try {
                    response = await fetchImpl(url.href, {
                        ...requestOptions, method, signal: controller.signal, headers, body: prepared,
                        credentials: options.credentials ?? 'same-origin', cache: options.cache ?? 'no-store',
                        mode: 'same-origin', redirect: 'error',
                    });
                } catch (cause: unknown) {
                    assertNotCanceled(requestSignal);
                    throw networkError(cause);
                }
                assertAttempt();
                if (response.redirected || (response.url && response.url !== url.href)) {
                    throw new ApiError({ status: 0, code: 'invalid_response', title: 'Response Unavailable',
                        detail: 'The response did not originate from the requested API target.' });
                }
                if (!response.ok) {
                    rejection = await parseErrorResponse(response);
                    assertAttempt();
                    if (response.status !== 401) throw rejection;
                } else {
                    if (expectedStatus !== undefined && response.status !== expectedStatus) {
                        throw new ApiError({ status: 0, code: 'unexpected_response', title: 'Unexpected Response',
                            detail: 'The server returned an unexpected success status.' });
                    }
                    let value: T;
                    if (response.status === 204) value = undefined as T;
                    else {
                        try {
                            value = (response.headers.get('content-type') ?? '').includes('json')
                                ? (await response.json()) as T : (await response.text()) as unknown as T;
                        } catch {
                            throw new ApiError({ status: 0, code: 'invalid_response', title: 'Response Unavailable',
                                detail: 'Unable to read the server response.' });
                        }
                    }
                    assertAttempt();
                    for (const { state } of active) state.onSuccess?.(sent);
                    return value;
                }
            } catch (error: unknown) {
                assertCurrent();
                throw error;
            } finally {
                if (timeout !== undefined) clearTimeout(timeout);
                requestSignal?.removeEventListener('abort', abort);
            }

            assertNotCanceled(requestSignal);
            assertCurrent();
            let recovered: boolean = false;
            if (request.replayable) {
                for (const { handler, state } of active) {
                    const action: RecoveryAction | undefined = state.recover?.(rejection, sent);
                    if (!action) continue;
                    // A classified rejection belongs to one handler. Another handler cannot bypass its budget.
                    if (budget.reserve(handler.kind, action.key)) {
                        await action.run(primaryOnly);
                        assertNotCanceled(requestSignal);
                        assertCurrent();
                        recovered = true;
                    }
                    break;
                }
            }
            if (recovered) continue;
            // Notification is synchronous; deliberate invalidation can retain the original terminal denial.
            for (const { state } of active) state.onTerminalFailure?.(rejection, sent, { primaryRecoveryAttempted: budget.primaryRecoveryAttempted });
            throw rejection;
        }
    }
    return createApiMethods(executeRequest);
}

/** Expose the same transport through a narrower request lifetime. */
export function scopeApiClient(client: ApiClient, assertRequest: (path: string, headers: Headers) => void): ApiClient {
    return createApiMethods(async <T>(path: string, options?: ApiRequestOptions): Promise<T> => {
        const { pathname, target }: ReturnType<typeof normalizeApiPath> = normalizeApiPath(path);
        const headers: Headers = new Headers(options?.headers);
        const scopedOptions: ApiRequestOptions = { ...options, headers };
        assertRequest(pathname, headers);
        return client.request<T>(target, scopedOptions);
    });
}

function createApiMethods(executeRequest: <T>(path: string, options?: ApiRequestOptions) => Promise<T>): ApiClient {
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

/** Plain transport for anonymous/public APIs. Authenticated operations use the workspace scope. */
export const apiClient: ApiClient = createApiClient();
