import { ApiError, createApiClient, scopeApiClient, sessionContextChanged, type ApiClient } from '@/lib/api';
import { AuthCoordinator } from './auth-coordinator.ts';
import { AuthCookieLock } from './auth-cookie-lock.ts';
import { AuthSession } from './auth-session.ts';
import { ANONYMOUS_IDENTITY_PATHS, IdentityApi } from './identity-api.ts';
import { SessionContextChannel, SESSION_CONTEXT_CHANNEL } from './session-context-channel.ts';
import { iamRequestHandler } from './iam-request-handler.ts';
import { AttestationHandler, type AttestationCacheOptions } from '@/core/authorization/attestation-handler.ts';

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
    readonly origin?: string;
    readonly attestationCaches?: AttestationCacheOptions;
    /** Lazily evaluated so server prerendering never touches browser-only APIs. */
    readonly locks?: () => LockManager | undefined;
    readonly channel?: () => BroadcastChannel | undefined;
}

/** A new runtime initially has no bearer; it restores the shared cookie on first use. */
export function createIdentityRuntime(options: IdentityRuntimeOptions = {}): IdentityRuntime {
    const session: AuthSession = new AuthSession();
    const channel: SessionContextChannel = new SessionContextChannel(options.channel ?? ((): BroadcastChannel | undefined =>
        typeof window === 'undefined' || typeof BroadcastChannel === 'undefined'
            ? undefined : new BroadcastChannel(SESSION_CONTEXT_CHANNEL)));
    const cookieLock: AuthCookieLock = new AuthCookieLock(
        options.locks ?? ((): LockManager | undefined => typeof navigator === 'undefined' ? undefined : navigator.locks)
    );
    // Handler execution begins only after the runtime is fully constructed.
    const attestations: AttestationHandler = new AttestationHandler({ current: (): number | undefined => session.getAuthenticatedLifetime(), subscribe: session.subscribe },
        options.attestationCaches);
    const api: ApiClient = createApiClient([iamRequestHandler(session, (): AuthCoordinator => auth), attestations], options.fetchImpl ?? fetch, options.origin);
    const identityApi: IdentityApi = new IdentityApi(api);
    const auth: AuthCoordinator = new AuthCoordinator(identityApi, session, cookieLock, channel);
    return {
        api, auth, cookieLock, session,
        forSession(sessionId: string): AuthenticatedApis {
            const lifetime: number | undefined = session.getSessionId() === sessionId ? session.getAuthenticatedLifetime() : undefined;
            const scopedApi: ApiClient = scopeApiClient(api, (path: string, headers: Headers): void => {
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
            const scopedIdentity: IdentityApi = new IdentityApi(scopedApi);
            return { api: scopedApi, account: scopedIdentity, security: scopedIdentity };
        },
        connect: () => channel.connect(),
        disconnect: () => channel.disconnect(),
        dispose: () => { attestations.dispose(); auth.dispose(); channel.dispose(); session.dispose(); },
    };
}
