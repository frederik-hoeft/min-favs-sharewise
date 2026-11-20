import { ApiError, type ApiClient } from '@/lib/api';
import type { AuthenticationResponse, IdentityCredentials } from './models.ts';

const ROOT = '/iam/v1';
/** Confirm against the actual Spring CsrfTokenRepository/header policy in Phase F. */
export const CSRF_HEADER = 'X-XSRF-TOKEN';

export const ANONYMOUS_IDENTITY_PATHS = new Set([
    '/api/iam/v1/csrf',
    '/api/iam/v1/auth/register',
    '/api/iam/v1/auth/login',
]);

/** IAM-specific paths, DTOs and CSRF header; HTTP transport remains in lib/api. */
export class IdentityApi {
    constructor(private readonly client: ApiClient) {}

    async getCsrfToken(): Promise<string> {
        const response = await this.client.get<unknown>(`${ROOT}/csrf`);
        if (
            typeof response !== 'object' || response === null ||
            !('token' in response) || typeof response.token !== 'string' || !response.token
        ) {
            throw new ApiError({
                status: 0,
                code: 'invalid_csrf_response',
                title: 'CSRF Bootstrap Failed',
                detail: 'Unable to prepare a secure sign-in request. Please try again.',
            });
        }
        return response.token;
    }

    register(credentials: IdentityCredentials, csrfToken: string): Promise<AuthenticationResponse> {
        return this.client.post<AuthenticationResponse>(`${ROOT}/auth/register`, credentials, {
            headers: { [CSRF_HEADER]: csrfToken },
        });
    }

    login(credentials: IdentityCredentials, csrfToken: string): Promise<AuthenticationResponse> {
        return this.client.post<AuthenticationResponse>(`${ROOT}/auth/login`, credentials, {
            headers: { [CSRF_HEADER]: csrfToken },
        });
    }
}
