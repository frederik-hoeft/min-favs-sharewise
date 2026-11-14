export interface FieldError {
    readonly field: string;
    readonly code: string;
    readonly message: string;
}

export interface ProblemDetailPayload {
    readonly type?: string;
    readonly title?: string;
    readonly status?: number;
    readonly detail?: string;
    readonly instance?: string;
    readonly code?: string;
    readonly fieldErrors?: ReadonlyArray<FieldError>;
}

/**
 * Typed error thrown by the frontend API client when a request fails or returns a non-2xx
 * HTTP response (including RFC 9457 {@code application/problem+json} payloads).
 */
export class ApiError extends Error {
    readonly status: number;
    readonly code: string;
    readonly title: string;
    readonly detail: string;
    readonly fieldErrors: ReadonlyArray<FieldError>;

    constructor(params: {
        status: number;
        code: string;
        title: string;
        detail: string;
        fieldErrors?: ReadonlyArray<FieldError>;
    }) {
        super(params.detail || params.title);
        this.name = 'ApiError';
        this.status = params.status;
        this.code = params.code;
        this.title = params.title;
        this.detail = params.detail;
        this.fieldErrors = params.fieldErrors ?? [];
    }
}

export interface ApiRequestOptions extends Omit<RequestInit, 'body'> {
    readonly body?: unknown;
}

export interface ApiClientHooks {
    /**
     * Optional hook for Phase 2 to attach bearer tokens, domain attestations, or CSRF headers.
     */
    readonly onRequest?: (path: string, headers: Headers) => Promise<void> | void;
    /**
     * Optional hook for Phase 2 to handle bounded 401 token renewal and single-retry.
     * Return {@code true} to retry the request once with updated credentials.
     */
    readonly onUnauthorized?: (path: string, response: Response) => Promise<boolean>;
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

async function parseErrorResponse(response: Response): Promise<ApiError> {
    const contentType = response.headers.get('content-type') ?? '';
    if (contentType.includes('json')) {
        try {
            const payload = (await response.json()) as ProblemDetailPayload;
            return new ApiError({
                status: response.status,
                code: typeof payload.code === 'string' && payload.code.length > 0 ? payload.code : 'http_error',
                title: typeof payload.title === 'string' && payload.title.length > 0 ? payload.title : (response.statusText || 'Request Failed'),
                detail: typeof payload.detail === 'string' && payload.detail.length > 0 ? payload.detail : `Request failed with status ${response.status}.`,
                fieldErrors: Array.isArray(payload.fieldErrors) ? payload.fieldErrors : [],
            });
        } catch {
            // Fall through to generic status error if JSON body is malformed.
        }
    }

    return new ApiError({
        status: response.status,
        code: 'http_error',
        title: response.statusText || 'Request Failed',
        detail: `Request failed with status ${response.status}.`,
    });
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
            const shouldRetry = await hooks.onUnauthorized(targetPath, response);
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
