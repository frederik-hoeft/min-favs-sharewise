import { ApiError, parseErrorResponse } from './api-error.ts';

export interface ApiRequestOptions extends Omit<RequestInit, 'body'> {
    readonly body?: unknown;
    /** Timeout for one HTTP attempt, including response reading, excluding authentication preparation. 0 disables it. */
    readonly timeoutMs?: number;
    /** A strict success status when an operation has an exact acknowledgment contract (e.g., 204). */
    readonly expectedStatus?: number;
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
    /** Capture the initiating session synchronously, before credential preparation. */
    readonly captureSessionId?: (path: string, headers: Readonly<Headers>) => string | undefined;
    /** Prepare credentials for an attempt. The signal cancels this caller, not an already-submitted shared refresh. */
    readonly onRequest?: (path: string, headers: Headers, signal?: AbortSignal) =>
        Promise<string | undefined | void> | string | undefined | void;
    /** Only the initial pre-dispatch 401 may request recovery and one replay. */
    readonly onUnauthorized?: (
        path: string, failure: ApiError, sentHeaders: Readonly<Headers>, context: SentRequestContext, signal?: AbortSignal
    ) => Promise<boolean>;
    /** Observe a replay's terminal 401; never a second recovery opportunity. */
    readonly onReplayUnauthorized?: (
        path: string, failure: ApiError, sentHeaders: Readonly<Headers>, context: SentRequestContext
    ) => Promise<void> | void;
    /** Guard dispatch and response delivery against a changed session. */
    readonly assertSessionContext?: (context: SentRequestContext) => void;
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
    if (body instanceof URLSearchParams) {
        if (!headers.has('Content-Type')) headers.set('Content-Type', 'application/x-www-form-urlencoded;charset=UTF-8');
        return { body: body.toString(), replayable: true };
    }
    if (typeof body === 'string' || body instanceof Blob) return { body, replayable: true };
    if (body instanceof FormData) return { body, replayable: false };
    if (typeof ReadableStream !== 'undefined' && body instanceof ReadableStream) {
        return { body, replayable: false };
    }
    if (!headers.has('Content-Type')) headers.set('Content-Type', 'application/json');
    return { body: JSON.stringify(body), replayable: true };
}

function assertNotCanceled(signal?: AbortSignal): void {
    if (signal?.aborted) {
        throw new ApiError({
            status: 0, code: 'request_aborted', title: 'Request Canceled',
            detail: 'The request was canceled before completion.',
        });
    }
}

function networkError(cause: unknown): ApiError {
    return new ApiError({
        status: 0, code: 'network_error', title: 'Network Error',
        detail: cause instanceof Error ? cause.message : 'Unable to reach the server.',
    });
}

export function createApiClient(hooks: ApiClientHooks = {}, fetchImpl: typeof fetch = fetch): ApiClient {
    async function executeRequest<T>(path: string, options: ApiRequestOptions = {}): Promise<T> {
        const targetPath = normalizeApiPath(path);
        const requestSignal = options.signal ?? undefined;
        const initialHeaders = new Headers(options.headers);
        if (!initialHeaders.has('Accept')) initialHeaders.set('Accept', 'application/json, application/problem+json');
        // Capture before body serialization or asynchronous credential preparation.
        // First-use restoration may establish the binding when none exists yet.
        const initiatingSessionId = hooks.captureSessionId?.(targetPath, initialHeaders);
        const prepared = prepareBody(options.body, initialHeaders);
        const method = (options.method ?? 'GET').toUpperCase();
        const timeoutMs = options.timeoutMs ?? DEFAULT_API_TIMEOUT_MS;
        if (!Number.isFinite(timeoutMs) || timeoutMs < 0) throw new RangeError('Invalid HTTP request timeout');
        const hasExplicitAuthorization = initialHeaders.has('Authorization');
        const requestOptions = { ...options };
        delete requestOptions.timeoutMs;
        delete requestOptions.expectedStatus;
        delete requestOptions.body;

        function assertBinding(expected: string | undefined, actual: string | undefined): void {
            if (expected !== undefined && expected !== actual) {
                throw new ApiError({
                    status: 0, code: 'session_context_changed', title: 'Sign-in Changed',
                    detail: 'The active session changed during the request.',
                });
            }
        }

        async function attempt(allowRetry: boolean, boundSessionId?: string): Promise<T> {
            assertNotCanceled(requestSignal);
            if (hooks.captureSessionId) assertBinding(boundSessionId, hooks.captureSessionId(targetPath, initialHeaders));
            const headers = new Headers(initialHeaders);
            const sessionId = hooks.onRequest ? await hooks.onRequest(targetPath, headers, requestSignal) : undefined;
            assertNotCanceled(requestSignal);
            assertBinding(boundSessionId, typeof sessionId === 'string' ? sessionId : undefined);
            const context: SentRequestContext = {
                sessionId: typeof sessionId === 'string' ? sessionId : undefined,
                method,
                replayable: prepared.replayable,
                hasExplicitAuthorization,
                attempt: allowRetry ? 'initial' : 'replay',
            };
            hooks.assertSessionContext?.(context);
            const controller = new AbortController();
            const abort = () => controller.abort();
            requestSignal?.addEventListener('abort', abort, { once: true });
            if (requestSignal?.aborted) controller.abort();
            // A fetch attempt has its own timeout. Renewal may take longer, but must not inherit a stale timer.
            const timeout = timeoutMs > 0 ? setTimeout(abort, timeoutMs) : undefined;
            let response: Response;
            let rejection: ApiError;
            try {
                if (controller.signal.aborted) assertNotCanceled(requestSignal);
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
                    if (requestSignal?.aborted) assertNotCanceled(requestSignal);
                    throw networkError(cause);
                }
                if (controller.signal.aborted) {
                    assertNotCanceled(requestSignal);
                    throw networkError(new Error('HTTP request timed out.'));
                }
                if (response.status !== 401) {
                    if (!response.ok) throw await parseErrorResponse(response);
                    if (options.expectedStatus !== undefined && response.status !== options.expectedStatus) {
                        throw new ApiError({
                            status: 0, code: 'unexpected_response', title: 'Unexpected Response',
                            detail: 'The server returned an unexpected success status.',
                        });
                    }
                    let value: T;
                    if (response.status === 204) {
                        value = undefined as T;
                    } else {
                        try {
                            const contentType = response.headers.get('content-type') ?? '';
                            value = contentType.includes('json')
                                ? (await response.json()) as T
                                : (await response.text()) as unknown as T;
                        } catch {
                            throw new ApiError({
                                status: 0, code: 'invalid_response', title: 'Response Unavailable',
                                detail: 'Unable to read the server response.',
                            });
                        }
                    }
                    if (controller.signal.aborted) {
                        assertNotCanceled(requestSignal);
                        throw networkError(new Error('HTTP request timed out.'));
                    }
                    assertNotCanceled(requestSignal);
                    hooks.assertSessionContext?.(context);
                    return value;
                }
                rejection = await parseErrorResponse(response);
                if (controller.signal.aborted) {
                    assertNotCanceled(requestSignal);
                    throw networkError(new Error('HTTP request timed out.'));
                }
            } catch (error) {
                hooks.assertSessionContext?.(context);
                throw error;
            } finally {
                if (timeout !== undefined) clearTimeout(timeout);
                requestSignal?.removeEventListener('abort', abort);
            }

            assertNotCanceled(requestSignal);
            // A stale denial must not trigger recovery or invalidate another session.
            hooks.assertSessionContext?.(context);
            if (allowRetry && hooks.onUnauthorized) {
                const shouldRetry = await hooks.onUnauthorized(targetPath, rejection, headers, context, requestSignal);
                assertNotCanceled(requestSignal);
                if (shouldRetry && prepared.replayable) return attempt(false, context.sessionId);
            } else if (!allowRetry) {
                await hooks.onReplayUnauthorized?.(targetPath, rejection, headers, context);
            }
            assertNotCanceled(requestSignal);
            // A terminal hook may deliberately invalidate the current bearer (retaining its ID).
            // Preserve its original denial, but never deliver a denial across a session replacement.
            if (hooks.captureSessionId) assertBinding(context.sessionId, hooks.captureSessionId(targetPath, initialHeaders));
            throw rejection;
        }
        return attempt(true, initiatingSessionId);
    }

    return createApiMethods(executeRequest);
}

/** Expose the same transport through a narrower request lifetime. */
export function scopeApiClient(client: ApiClient, assertRequest: (path: string, headers: Headers) => void): ApiClient {
    return createApiMethods(async <T>(path: string, options?: ApiRequestOptions): Promise<T> => {
        const normalizedPath = normalizeApiPath(path);
        const headers = new Headers(options?.headers);
        const scopedOptions = { ...options, headers };
        assertRequest(normalizedPath, headers);
        return client.request<T>(normalizedPath, scopedOptions);
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
