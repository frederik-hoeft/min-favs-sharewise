/** The refresh credential is exclusively an HttpOnly cookie and never appears in these DTOs. */
export interface IdentityCredentials {
    readonly email: string;
    readonly password: string;
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
    | { readonly status: 'authenticated'; readonly expiresAt: string };

/** Never contains a JWT or refresh secret. */
export type SessionContextEvent =
    | { readonly type: 'login'; readonly sessionId: string }
    | { readonly type: 'logout'; readonly sessionId: string };
