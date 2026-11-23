import { ApiError, createApiClient, scopeApiClient, type ApiClient } from '@/lib/api';
import { AuthCoordinator } from './auth-coordinator.ts';
import { AuthCookieLock } from './auth-cookie-lock.ts';
import { AuthSession } from './auth-session.ts';
import { ANONYMOUS_IDENTITY_PATHS, IdentityApi, PASSWORD_CHANGE_PATH } from './identity-api.ts';
import { SessionContextChannel, SESSION_CONTEXT_CHANNEL } from './session-context-channel.ts';

export { AUTH_COOKIE_LOCK, AuthCookieLock } from './auth-cookie-lock.ts';
export { AuthSession } from './auth-session.ts';
export { CSRF_HEADER, IdentityApi } from './identity-api.ts';
export { SessionContextChannel, SESSION_CONTEXT_CHANNEL } from './session-context-channel.ts';
export type { AccountProfile, AuthenticationResponse, AuthState, ChangePasswordRequest, IdentityCredentials, SessionContextEvent } from './models.ts';

export interface AuthenticatedApis {
    readonly api: ApiClient;
    readonly account: Pick<IdentityApi, 'getAccount' | 'setDisplayName' | 'clearDisplayName'>;
    readonly security: Pick<IdentityApi, 'changePassword'>;
}

export interface IdentityRuntime {
    /** Unscoped transport for identity infrastructure; workspace callers use forSession. */
    readonly api: ApiClient;
    readonly auth: AuthCoordinator;
    forSession(sessionId: string): AuthenticatedApis;
    readonly cookieLock: AuthCookieLock;
    readonly session: AuthSession;
    connect(): void;
    disconnect(): void;
    dispose(): void;
}

export interface IdentityRuntimeOptions {
    readonly fetchImpl?: typeof fetch;
    /** Lazily evaluated so server prerendering never touches browser-only APIs. */
    readonly locks?: () => LockManager | undefined;
    readonly channel?: () => BroadcastChannel | undefined;
}

const PRE_DISPATCH_401_CODES = new Set(['invalid_credential', 'authentication_required']);

// Only this IAM command defines session_unavailable as a terminal bearer-session denial.
function isPasswordSessionDenial(path: string, method: string, code: string): boolean {
    return path === PASSWORD_CHANGE_PATH && method === 'POST' && code === 'session_unavailable';
}

function sessionContextChanged(): ApiError {
    return new ApiError({
        status: 0, code: 'session_context_changed', title: 'Sign-in Changed',
        detail: 'The active session changed before the request completed.',
    });
}

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
        captureSessionId(path, headers) {
            if (ANONYMOUS_IDENTITY_PATHS.has(path) || headers.has('Authorization')) return;
            return session.getSessionId();
        },
        async onRequest(path, headers, signal) {
            if (ANONYMOUS_IDENTITY_PATHS.has(path) || headers.has('Authorization')) return;
            await auth.ensureAuthenticated(signal);
            const token = session.getAccessToken();
            const state = session.getSnapshot();
            if (!token || state.status !== 'authenticated') throw sessionContextChanged();
            headers.set('Authorization', `Bearer ${token}`);
            return state.sessionId;
        },
        async onUnauthorized(path, problem, sentHeaders, context, signal) {
            if (ANONYMOUS_IDENTITY_PATHS.has(path) || context.hasExplicitAuthorization) return false;
            const rejected = sentHeaders.get('Authorization');
            if (!rejected?.startsWith('Bearer ') || !context.sessionId) return false;
            // A confirmed post-dispatch session denial concerns the entire session,
            // not the bearer generation. Never replay the mutation.
            if (isPasswordSessionDenial(path, context.method, problem.code)) {
                if (session.getSessionId() === context.sessionId) {
                    session.invalidate('unauthorized');
                }
                return false;
            }
            if (!context.replayable) return false;
            // Only Spring's pre-dispatch bearer failures are eligible for renewal.
            if (!PRE_DISPATCH_401_CODES.has(problem.code)) return false;
            await auth.recover(rejected.slice(7), context.sessionId, signal);
            return true;
        },
        async onReplayUnauthorized(path, problem, sentHeaders, context) {
            if (ANONYMOUS_IDENTITY_PATHS.has(path) || context.hasExplicitAuthorization || !context.sessionId) return;
            const rejected = sentHeaders.get('Authorization');
            if (!rejected?.startsWith('Bearer ')) return;
            if (session.getSessionId() === context.sessionId &&
                (isPasswordSessionDenial(path, context.method, problem.code) ||
                    (PRE_DISPATCH_401_CODES.has(problem.code) && session.matchesAccessToken(rejected.slice(7))))) {
                session.invalidate('unauthorized');
            }
        },
        assertSessionContext(context) {
            if (!context.sessionId) return;
            if (session.getSessionId() !== context.sessionId || session.getSnapshot().status !== 'authenticated') {
                throw sessionContextChanged();
            }
        },
    }, options.fetchImpl ?? fetch);
    const identityApi = new IdentityApi(api);
    const auth = new AuthCoordinator(identityApi, session, cookieLock, channel);
    return {
        api, auth, cookieLock, session,
        forSession(sessionId) {
            const lifetime = session.getSessionId() === sessionId ? session.getAuthenticatedLifetime() : undefined;
            const scopedApi = scopeApiClient(api, (path, headers) => {
                if (!lifetime || session.getAuthenticatedLifetime() !== lifetime) {
                    throw sessionContextChanged();
                }
                if (ANONYMOUS_IDENTITY_PATHS.has(path) || headers.has('Authorization')) {
                    throw new ApiError({
                        status: 0, code: 'invalid_request_context', title: 'Invalid Request Context',
                        detail: 'Workspace APIs require the current session bearer.',
                    });
                }
            });
            const scopedIdentity = new IdentityApi(scopedApi);
            return { api: scopedApi, account: scopedIdentity, security: scopedIdentity };
        },
        connect: () => channel.connect(),
        disconnect: () => channel.disconnect(),
        dispose: () => { auth.dispose(); channel.dispose(); session.dispose(); },
    };
}
