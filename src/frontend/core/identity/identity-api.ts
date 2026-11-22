import { ApiError, type ApiClient } from '@/lib/api';
import type { AuthenticationResponse, IdentityCredentials } from './models.ts';

const ROOT = '/iam/v1';
export const CSRF_HEADER = 'X-XSRF-TOKEN';

/** Cookie-backed endpoints must neither receive access JWTs nor enter bearer renewal. */
export const ANONYMOUS_IDENTITY_PATHS = new Set([
    '/api/iam/v1/auth/csrf',
    '/api/iam/v1/auth/register',
    '/api/iam/v1/auth/login',
    '/api/iam/v1/auth/refresh',
    '/api/iam/v1/auth/logout',
]);

/** IAM-specific paths, DTOs and CSRF header; HTTP transport remains in lib/api. */
export class IdentityApi {
    constructor(private readonly client: ApiClient) {}

    async getCsrfToken(): Promise<string> {
        const response = await this.client.get<unknown>(`${ROOT}/auth/csrf`);
        if (
            typeof response !== 'object' || response === null ||
            !('token' in response) || typeof response.token !== 'string' || !response.token
        ) {
            throw new ApiError({
                status: 0,
                code: 'invalid_csrf_response',
                title: 'CSRF Bootstrap Failed',
                detail: 'Unable to prepare a secure authentication request. Please try again.',
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

    refresh(expectedSessionId: string | undefined, csrfToken: string): Promise<AuthenticationResponse> {
        return this.client.post<AuthenticationResponse>(`${ROOT}/auth/refresh`,
            expectedSessionId ? { expectedSessionId } : {},
            { headers: { [CSRF_HEADER]: csrfToken } });
    }

    logout(expectedSessionId: string | undefined, csrfToken: string): Promise<void> {
        return this.client.post<void>(`${ROOT}/auth/logout`,
            expectedSessionId ? { expectedSessionId } : {},
            { headers: { [CSRF_HEADER]: csrfToken } });
    }
}
