import { createApiClient, type ApiClient } from '@/lib/api';
import { AuthCoordinator } from './auth-coordinator.ts';
import { AuthCookieLock } from './auth-cookie-lock.ts';
import { AuthSession } from './auth-session.ts';
import { ANONYMOUS_IDENTITY_PATHS, IdentityApi } from './identity-api.ts';

export { AUTH_COOKIE_LOCK, AuthCookieLock } from './auth-cookie-lock.ts';
export { AuthSession } from './auth-session.ts';
export { CSRF_HEADER, IdentityApi } from './identity-api.ts';
export type { AccountProfile, AuthenticationResponse, AuthState, IdentityCredentials } from './models.ts';

export interface IdentityRuntime {
    /** Use this client for subsequent authenticated domain API operations. */
    readonly api: ApiClient;
    readonly auth: AuthCoordinator;
    readonly account: Pick<IdentityApi, 'getAccount' | 'setDisplayName' | 'clearDisplayName'>;
    readonly cookieLock: AuthCookieLock;
    readonly session: AuthSession;
    dispose(): void;
}

export interface IdentityRuntimeOptions {
    readonly fetchImpl?: typeof fetch;
    /** Lazily evaluated so server prerendering never touches browser-only APIs. */
    readonly locks?: () => LockManager | undefined;
    readonly now?: () => number;
}

/**
 * A new runtime is a new tab-local session. Its bearer is never persisted;
 * reload and new tabs intentionally require reauthentication in P2.1a.
 */
export function createIdentityRuntime(options: IdentityRuntimeOptions = {}): IdentityRuntime {
    const session = new AuthSession(options.now);
    const api = createApiClient({
        onRequest(path, headers) {
            if (ANONYMOUS_IDENTITY_PATHS.has(path) || headers.has('Authorization')) return;
            const token = session.getAccessToken();
            if (token) headers.set('Authorization', `Bearer ${token}`);
        },
        async onUnauthorized(path, _response, sentHeaders) {
            // An anonymous login denial must not invalidate an unrelated bearer.
            // In-flight requests from an older session cannot clear a newer login.
            if (!ANONYMOUS_IDENTITY_PATHS.has(path)) {
                const authorization = sentHeaders.get('Authorization');
                if (authorization?.startsWith('Bearer ') && session.matchesAccessToken(authorization.slice(7))) {
                    session.invalidate('unauthorized');
                }
            }
            return false; // P2.1b owns refresh/retry; never repeat a mutation here.
        },
    }, options.fetchImpl ?? fetch);

    const identityApi = new IdentityApi(api);
    const cookieLock = new AuthCookieLock(
        options.locks ?? (() => typeof navigator === 'undefined' ? undefined : navigator.locks)
    );
    const auth = new AuthCoordinator(identityApi, session, cookieLock);
    return { api, auth, account: identityApi, cookieLock, session, dispose: () => session.dispose() };
}
