import { createApiClient, parseErrorResponse, type ApiClient } from '@/lib/api';
import { AuthCoordinator } from './auth-coordinator.ts';
import { AuthCookieLock } from './auth-cookie-lock.ts';
import { AuthSession } from './auth-session.ts';
import { ANONYMOUS_IDENTITY_PATHS, IdentityApi } from './identity-api.ts';
import { SessionContextChannel, SESSION_CONTEXT_CHANNEL } from './session-context-channel.ts';

export { AUTH_COOKIE_LOCK, AuthCookieLock } from './auth-cookie-lock.ts';
export { AuthSession } from './auth-session.ts';
export { CSRF_HEADER, IdentityApi } from './identity-api.ts';
export { SessionContextChannel, SESSION_CONTEXT_CHANNEL } from './session-context-channel.ts';
export type { AuthenticationResponse, AuthState, IdentityCredentials, SessionContextEvent } from './models.ts';

export interface IdentityRuntime {
    /** Use this client for authenticated domain API operations. */
    readonly api: ApiClient;
    readonly auth: AuthCoordinator;
    readonly cookieLock: AuthCookieLock;
    readonly session: AuthSession;
    dispose(): void;
}

export interface IdentityRuntimeOptions {
    readonly fetchImpl?: typeof fetch;
    /** Lazily evaluated so server prerendering never touches browser-only APIs. */
    readonly locks?: () => LockManager | undefined;
    readonly channel?: () => BroadcastChannel | undefined;
}

const PRE_DISPATCH_401_CODES = new Set(['invalid_credential', 'authentication_required']);

/** A new runtime initially has no bearer; it restores the shared cookie on first use. */
export function createIdentityRuntime(options: IdentityRuntimeOptions = {}): IdentityRuntime {
    const session = new AuthSession();
    const channel = new SessionContextChannel(options.channel ?? (() =>
        typeof window === 'undefined' || typeof BroadcastChannel === 'undefined'
            ? undefined : new BroadcastChannel(SESSION_CONTEXT_CHANNEL)));
    const cookieLock = new AuthCookieLock(
        options.locks ?? (() => typeof navigator === 'undefined' ? undefined : navigator.locks)
    );
    // The hooks execute only after the runtime is fully constructed.
    const api = createApiClient({
        async onRequest(path, headers) {
            if (ANONYMOUS_IDENTITY_PATHS.has(path) || headers.has('Authorization')) return;
            await auth.ensureAuthenticated();
            const token = session.getAccessToken();
            if (token) {
                headers.set('Authorization', `Bearer ${token}`);
                return session.getSessionId();
            }
        },
        async onUnauthorized(path, response, sentHeaders, context) {
            if (ANONYMOUS_IDENTITY_PATHS.has(path) || context.hasExplicitAuthorization) return false;
            const rejected = sentHeaders.get('Authorization');
            if (!rejected?.startsWith('Bearer ') || !context.sessionId) return false;
            if (context.attempt === 'initial' && !context.replayable) return false;
            // Only Spring's pre-dispatch bearer failures may affect the tab's credentials.
            const problem = await parseErrorResponse(response.clone());
            if (!PRE_DISPATCH_401_CODES.has(problem.code)) return false;
            if (context.attempt === 'replay') {
                if (session.getSessionId() === context.sessionId && session.matchesAccessToken(rejected.slice(7))) {
                    session.invalidate('unauthorized');
                }
                return false;
            }
            await auth.recover(rejected.slice(7), context.sessionId);
            return true;
        },
    }, options.fetchImpl ?? fetch);
    const identityApi = new IdentityApi(api);
    const auth = new AuthCoordinator(identityApi, session, cookieLock, channel);
    return { api, auth, cookieLock, session, dispose: () => { auth.dispose(); channel.dispose(); session.dispose(); } };
}
