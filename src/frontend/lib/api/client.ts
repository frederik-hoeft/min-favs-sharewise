import { ApiError, parseErrorResponse, requestCanceled } from './api-error.ts';
import { RecoveryBudget, type ApiRequestContext, type ApiRequestHandler, type RequestHandler, type SentRequest } from './handlers.ts';

export interface ApiRequestOptions extends Omit<RequestInit, 'body'> {
    readonly body?: unknown;
    /** Timeout for one HTTP attempt, including response reading, excluding authentication preparation. 0 disables it. */
    readonly timeoutMs?: number;
    /** A strict success status when an operation has an exact acknowledgment contract (e.g., 204). */
    readonly expectedStatus?: number;
}

export const DEFAULT_API_TIMEOUT_MS = 45_000;

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
    const queryIndex = path.indexOf('?');
    const rawPathname = queryIndex === -1 ? path : path.slice(0, queryIndex);
    const query = queryIndex === -1 ? '' : path.slice(queryIndex);
    const rooted = rawPathname.startsWith('/') ? rawPathname : `/${rawPathname}`;
    const pathname = rooted === '/api' || rooted.startsWith('/api/') ? rooted : `/api${rooted}`;
    // Route segments use unreserved ASCII characters. Reject spellings that a
    // browser, proxy or server could decode or normalize to a different route.
    if (!/^\/api(?:\/[A-Za-z0-9._~-]+)*$/.test(pathname) ||
        pathname.split('/').some(segment => segment === '.' || segment === '..') ||
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
    origin = typeof location !== 'undefined' ? location.origin : 'http://sharewise.invalid'): ApiClient {
    const pipeline = [...handlers];
    if (pipeline.some((handler, index) => handler.kind === 'primary' && index !== 0)) {
        throw new Error('Exactly one primary handler may precede supplementary handlers');
    }
    const apiOrigin = new URL(origin).origin;
    if (apiOrigin === 'null' || !/^https?:/.test(apiOrigin)) throw new Error('Invalid API origin');

    async function executeRequest<T>(path: string, options: ApiRequestOptions = {}, detour?: DetourContext): Promise<T> {
        const normalized = normalizeApiPath(path);
        // Use the URL serialization used by Fetch, including significant query ordering/empty values.
        const url = new URL(normalized.target, apiOrigin);
        const target = url.href.slice(apiOrigin.length);
        const requestSignal = options.signal ?? undefined;
        const initialHeaders = new Headers(options.headers);
        if (!initialHeaders.has('Accept')) initialHeaders.set('Accept', 'application/json, application/problem+json');
        const method = (options.method ?? 'GET').toUpperCase();
        const timeoutMs = options.timeoutMs ?? DEFAULT_API_TIMEOUT_MS;
        if (!Number.isFinite(timeoutMs) || timeoutMs < 0) throw new RangeError('Invalid HTTP request timeout');
        const hasExplicitAuthorization = initialHeaders.has('Authorization');
        const body = options.body;
        const request: ApiRequestContext = Object.freeze({ origin: apiOrigin, pathname: normalized.pathname,
            target, method, replayable: isReplayableBody(body), signal: requestSignal });
        assertNotCanceled(requestSignal);
        detour?.assertCurrent();
        const active: { handler: ApiRequestHandler; state: RequestHandler }[] = [];
        if (!hasExplicitAuthorization) {
            for (const handler of pipeline) {
                if (handler.kind === 'supplementary' && (detour || active.length === 0)) continue;
                const state = handler.begin(request);
                if (state) active.push({ handler, state });
            }
        }
        if (detour && active.length === 0) throw new ApiError({ status: 0, code: 'invalid_request_context',
            title: 'Invalid Request Context', detail: 'Credential acquisition requires a managed primary credential.' });
        const prepared = prepareBody(body, initialHeaders);
        const expectedStatus = options.expectedStatus;
        const requestOptions = { ...options };
        delete requestOptions.timeoutMs;
        delete requestOptions.expectedStatus;
        delete requestOptions.body;
        const budget = detour?.budget ?? new RecoveryBudget();
        const assertCurrent = () => {
            detour?.assertCurrent();
            for (const { state } of active) state.assertCurrent?.();
        };
        // Only a recovery action can open a primary-only detour, sharing the outer budget and binding.
        const primaryOnly = createApiMethods(<R>(path: string, options?: ApiRequestOptions) => {
            if (detour) throw new Error('Nested credential acquisition is not allowed');
            return executeRequest<R>(path, options, { budget, assertCurrent });
        });

        for (let attempt = 0; ; attempt++) {
            assertNotCanceled(requestSignal);
            assertCurrent();
            const headers = new Headers(initialHeaders);
            for (const { state } of active) {
                await state.prepare?.(headers);
                assertNotCanceled(requestSignal);
                assertCurrent();
            }
            const sent: SentRequest = { headers: new Headers(headers), attempt };
            const controller = new AbortController();
            const abort = () => controller.abort();
            requestSignal?.addEventListener('abort', abort, { once: true });
            if (requestSignal?.aborted) controller.abort();
            const timeout = timeoutMs > 0 ? setTimeout(abort, timeoutMs) : undefined;
            const assertAttempt = () => {
                assertNotCanceled(requestSignal);
                if (controller.signal.aborted) throw networkError(new Error('HTTP request timed out.'));
                assertCurrent();
            };
            let rejection: ApiError;
            try {
                assertAttempt();
                let response: Response;
                try {
                    response = await fetchImpl(target, {
                        ...requestOptions, method, signal: controller.signal, headers, body: prepared,
                        credentials: options.credentials ?? 'same-origin', cache: options.cache ?? 'no-store',
                        redirect: 'error',
                    });
                } catch (cause) {
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
            } catch (error) {
                assertCurrent();
                throw error;
            } finally {
                if (timeout !== undefined) clearTimeout(timeout);
                requestSignal?.removeEventListener('abort', abort);
            }

            assertNotCanceled(requestSignal);
            assertCurrent();
            let recovered = false;
            if (request.replayable) {
                for (const { handler, state } of active) {
                    const action = state.recover?.(rejection, sent);
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
        const { pathname, target } = normalizeApiPath(path);
        const headers = new Headers(options?.headers);
        const scopedOptions = { ...options, headers };
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
