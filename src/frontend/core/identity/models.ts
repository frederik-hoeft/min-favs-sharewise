/** Browser-facing IAM contract. Neither type contains the HttpOnly refresh credential. */
export interface IdentityCredentials {
    readonly email: string;
    readonly password: string;
}

export interface AuthenticationResponse {
    readonly accessToken: string;
    readonly tokenType: 'Bearer';
    readonly expiresAt: string;
}

export type AuthState =
    | { readonly status: 'reauthentication-required'; readonly reason: 'initial' | 'expired' | 'unauthorized' }
    | { readonly status: 'authenticated'; readonly expiresAt: string };

/** Own-account projection returned by IAM; the bearer, not a UI-supplied ID, selects the account. */
export interface AccountProfile {
    readonly userId: string;
    readonly email: string;
    readonly displayName: string | null;
}
