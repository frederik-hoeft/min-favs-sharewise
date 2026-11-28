/** The refresh credential is exclusively an HttpOnly cookie and never appears in these DTOs. */
export interface IdentityCredentials {
    readonly email: string;
    readonly password: string;
}

export interface RegistrationRequest extends IdentityCredentials {
    readonly displayName: string;
}

export interface PublicProfile {
    readonly userId: string;
    readonly displayName: string;
}

export interface AuthenticationResponse {
    readonly accessToken: string;
    readonly tokenType: 'Bearer';
    readonly expiresAt: string;
    /** Stable identity of the login session; refresh generations retain this identifier. */
    readonly sessionId: string;
}

export type AuthState =
    | { readonly status: 'reauthentication-required'; readonly reason: 'initial' | 'retryable' | 'expired' | 'unauthorized' | 'uncertain' }
    | { readonly status: 'restoring' }
    | { readonly status: 'context-changed' }
    | { readonly status: 'authenticated'; readonly sessionId: string; readonly expiresAt: string };

/** Never contains a JWT or refresh secret. */
export type SessionContextEvent =
    | { readonly type: 'login'; readonly sessionId: string }
    | { readonly type: 'logout'; readonly sessionId: string };

/** Own-account projection returned by IAM; the bearer, not a UI-supplied ID, selects the account. */
export interface AccountProfile {
    readonly userId: string;
    readonly email: string;
    readonly displayName: string;
}

/** Credentials for bearer-authorized password replacement; never persist or log these values. */
export interface ChangePasswordRequest {
    readonly currentPassword: string;
    readonly newPassword: string;
}
