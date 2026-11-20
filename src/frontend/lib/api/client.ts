import { ApiError, parseErrorResponse } from './api-error.ts';

export interface ApiRequestOptions extends Omit<RequestInit, 'body'> {
    readonly body?: unknown;
}

export interface ApiClientHooks {
    /**
     * Optional hook for Phase 2 to attach bearer tokens, domain attestations, or CSRF headers.
     */
    readonly onRequest?: (path: string, headers: Headers) => Promise<void> | void;
    /**
     * Observe unauthorized responses, clear a rejected bearer, or later perform bounded renewal.
     * Sent headers identify the credential actually used, avoiding stale-401 races.
     * Return {@code true} to retry the request once with updated credentials.
     */
    readonly onUnauthorized?: (path: string, response: Response, sentHeaders: Readonly<Headers>) => Promise<boolean>;
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
    if (path.startsWith('/api/') || path === '/api') {
        return path;
    }
    if (path.startsWith('/')) {
        return `/api${path}`;
    }
    return `/api/${path}`;
}

export function createApiClient(
    hooks: ApiClientHooks = {},
    fetchImpl: typeof fetch = fetch
): ApiClient {
    async function executeRequest<T>(
        path: string,
        options: ApiRequestOptions = {},
        allowUnauthorizedRetry: boolean = true
    ): Promise<T> {
        const targetPath = normalizeApiPath(path);
        const headers = new Headers(options.headers);
        if (!headers.has('Accept')) {
            headers.set('Accept', 'application/json, application/problem+json');
        }

        let serializedBody: BodyInit | undefined;
        if (options.body !== undefined) {
            if (
                typeof options.body === 'string' ||
                options.body instanceof FormData ||
                options.body instanceof URLSearchParams ||
                options.body instanceof Blob
            ) {
                serializedBody = options.body;
            } else {
                if (!headers.has('Content-Type')) {
                    headers.set('Content-Type', 'application/json');
                }
                serializedBody = JSON.stringify(options.body);
            }
        }

        if (hooks.onRequest) {
            await hooks.onRequest(targetPath, headers);
        }

        let response: Response;
        try {
            response = await fetchImpl(targetPath, {
                ...options,
                headers,
                body: serializedBody,
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

        if (response.status === 401 && allowUnauthorizedRetry && hooks.onUnauthorized) {
            const shouldRetry = await hooks.onUnauthorized(targetPath, response, headers);
            if (shouldRetry) {
                return executeRequest<T>(path, options, false);
            }
        }

        if (!response.ok) {
            throw await parseErrorResponse(response);
        }

        if (response.status === 204) {
            return undefined as T;
        }

        const contentType = response.headers.get('content-type') ?? '';
        if (contentType.includes('json')) {
            return (await response.json()) as T;
        }

        return (await response.text()) as unknown as T;
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
