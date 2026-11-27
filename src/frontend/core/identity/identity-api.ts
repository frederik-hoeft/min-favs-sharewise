import { validateDisplayName } from "./display-name.ts";
import { ApiError, type ApiClient } from '@/lib/api';
import type { AccountProfile, AuthenticationResponse, ChangePasswordRequest, IdentityCredentials, RegistrationRequest, PublicProfile } from './models.ts';

const ROOT: string = '/iam/v1';
export const PASSWORD_CHANGE_PATH: string = '/api/iam/v1/auth/password';
export const CSRF_HEADER: string = 'X-XSRF-TOKEN';

/** Cookie-backed endpoints must neither receive access JWTs nor enter bearer renewal. */
export const ANONYMOUS_IDENTITY_PATHS: Set<string> = new Set([
    '/api/iam/v1/auth/csrf',
    '/api/iam/v1/auth/register',
    '/api/iam/v1/auth/login',
    '/api/iam/v1/auth/refresh',
    '/api/iam/v1/auth/logout',
]);

/** IAM-specific paths, DTOs and CSRF header; HTTP transport remains in lib/api. */
export class IdentityApi {
    constructor(private readonly client: ApiClient) {}

    async getCsrfToken(signal?: AbortSignal): Promise<string> {
        const response: unknown = await this.client.get<unknown>(`${ROOT}/auth/csrf`, { signal });
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

    register(credentials: RegistrationRequest, csrfToken: string): Promise<AuthenticationResponse> {
        return this.client.post<AuthenticationResponse>(`${ROOT}/auth/register`, { email: credentials.email, password: credentials.password, displayName: credentials.displayName }, {
            headers: { [CSRF_HEADER]: csrfToken },
        });
    }

    async getAccount(options?: { readonly signal?: AbortSignal }): Promise<AccountProfile> {
        return parseAccountProfile(await this.client.get<unknown>(`${ROOT}/account`, options));
    }

    async setDisplayName(displayName: string): Promise<AccountProfile> {
        const profile: AccountProfile = parseAccountProfile(await this.client.patch<unknown>(`${ROOT}/account`, { displayName }));
        if (profile.displayName !== displayName) throw invalidProfileResponse();
        return profile;
    }

    async lookupProfiles(userIds: readonly string[], signal: AbortSignal): Promise<readonly PublicProfile[]> {
        const response: unknown = await this.client.post<unknown>(`${ROOT}/profiles/lookup`, { userIds }, { signal });
        if (typeof response !== 'object' || response === null || !('profiles' in response) || !Array.isArray(response.profiles)) {
            throw invalidProfileResponse();
        }
        const requested: Set<string> = new Set(userIds);
        const profiles: PublicProfile[] = [];
        for (const profile of response.profiles as unknown[]) {
            if (typeof profile !== 'object' || profile === null || !('userId' in profile) || typeof profile.userId !== 'string' ||
                !requested.delete(profile.userId) || !('displayName' in profile) || typeof profile.displayName !== 'string' ||
                validateDisplayName(profile.displayName) !== undefined) {
                throw invalidProfileResponse();
            }
            profiles.push({ userId: profile.userId, displayName: profile.displayName });
        }
        return profiles;
    }

    /** The only authenticated /auth mutation: bearer-only, no CSRF bootstrap or response credentials. */
    changePassword(request: ChangePasswordRequest): Promise<void> {
        return this.client.post<void>(PASSWORD_CHANGE_PATH, request, { expectedStatus: 204 });
    }

    login(credentials: IdentityCredentials, csrfToken: string): Promise<AuthenticationResponse> {
        return this.client.post<AuthenticationResponse>(`${ROOT}/auth/login`, { email: credentials.email, password: credentials.password }, {
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

function invalidProfileResponse(): ApiError {
    return new ApiError({ status: 0, code: 'invalid_response', title: 'Invalid Profile Response', detail: 'Unable to validate the profile response.' });
}

function parseAccountProfile(response: unknown): AccountProfile {
    if (typeof response !== 'object' || response === null || !('userId' in response) || typeof response.userId !== 'string' ||
        !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(response.userId) ||
        !('email' in response) || typeof response.email !== 'string' || !response.email ||
        !('displayName' in response) || typeof response.displayName !== 'string' || validateDisplayName(response.displayName) !== undefined) {
        throw invalidProfileResponse();
    }
    return { userId: response.userId, email: response.email, displayName: response.displayName };
}
