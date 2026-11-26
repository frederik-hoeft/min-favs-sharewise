import { parseAuthorizationMetadata, type AuthorizationMetadata } from './authorization-protocol.ts';

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
    readonly authorization?: unknown;
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
    readonly authorization?: AuthorizationMetadata;

    constructor(params: {
        status: number;
        code: string;
        title: string;
        detail: string;
        fieldErrors?: ReadonlyArray<FieldError>;
        authorization?: AuthorizationMetadata;
        cause?: unknown;
    }) {
        super(params.detail || params.title, { cause: params.cause });
        this.name = 'ApiError';
        this.status = params.status;
        this.code = params.code;
        this.title = params.title;
        this.detail = params.detail;
        this.fieldErrors = params.fieldErrors ?? [];
        this.authorization = params.authorization;
    }
}

export async function parseErrorResponse(response: Response): Promise<ApiError> {
    const contentType = response.headers.get('content-type') ?? '';
    if (contentType.includes('json')) {
        try {
            const payload = (await response.json()) as ProblemDetailPayload;
            const code = typeof payload.code === 'string' && payload.code.length > 0 ? payload.code : 'http_error';
            return new ApiError({
                status: response.status,
                code,
                authorization: response.status === 401 ? parseAuthorizationMetadata(code, payload.authorization) : undefined,
                title: typeof payload.title === 'string' && payload.title.length > 0 ? payload.title : (response.statusText || 'Request Failed'),
                detail: typeof payload.detail === 'string' && payload.detail.length > 0 ? payload.detail : `Request failed with status ${response.status}.`,
                fieldErrors: Array.isArray(payload.fieldErrors)
                    ? payload.fieldErrors.filter((entry): entry is FieldError =>
                        entry !== null && typeof entry === 'object' &&
                        (entry.field === 'email' || entry.field === 'password' ||
                            (typeof entry.field === 'string' && entry.field.length > 0)) &&
                        typeof entry.code === 'string' && typeof entry.message === 'string')
                    : [],
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

export function requestCanceled(): ApiError {
    return new ApiError({ status: 0, code: 'request_aborted', title: 'Request Canceled',
        detail: 'The request was canceled before completion.' });
}

export function sessionContextChanged(): ApiError {
    return new ApiError({ status: 0, code: 'session_context_changed', title: 'Sign-in Changed',
        detail: 'The active session changed before the request completed.' });
}
