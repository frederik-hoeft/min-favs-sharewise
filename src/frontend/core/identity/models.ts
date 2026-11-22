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
