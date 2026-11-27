import { ApiError, createApiClient, scopeApiClient, sessionContextChanged, type ApiClient } from '@/lib/api';
import { ProfileResolver, type ProfileResolution } from './profile-resolver.ts';
import type { AccountProfile } from './models.ts';
import { AuthCoordinator } from './auth-coordinator.ts';
import { AuthCookieLock } from './auth-cookie-lock.ts';
import { AuthSession } from './auth-session.ts';
import { ANONYMOUS_IDENTITY_PATHS, IdentityApi } from './identity-api.ts';
import { SessionContextChannel, SESSION_CONTEXT_CHANNEL } from './session-context-channel.ts';
import { iamRequestHandler } from './iam-request-handler.ts';
import type { AuthorizationSnapshot } from '@/core/authorization/diagnostics.ts';
import { AttestationHandler, type AttestationCacheOptions } from '@/core/authorization/attestation-handler.ts';

export { AUTH_COOKIE_LOCK, AuthCookieLock } from './auth-cookie-lock.ts';
export { AuthSession } from './auth-session.ts';
export { CSRF_HEADER, IdentityApi } from './identity-api.ts';
export { SessionContextChannel, SESSION_CONTEXT_CHANNEL } from './session-context-channel.ts';
export type { ProfileResolution } from "./profile-resolver.ts";
export type { AccountProfile, AuthenticationResponse, AuthState, ChangePasswordRequest, IdentityCredentials, RegistrationRequest, PublicProfile, SessionContextEvent } from './models.ts';

export interface AuthenticatedApis {
    readonly api: ApiClient;
    readonly account: Pick<IdentityApi, 'getAccount' | 'setDisplayName'>;
    readonly profiles: Pick<ProfileResolver, 'resolve'>;
    readonly security: Pick<IdentityApi, 'changePassword'>;
}

export interface IdentityRuntime {
    /** Unscoped transport for identity infrastructure; workspace callers use forSession. */
    readonly api: ApiClient;
    readonly auth: AuthCoordinator;
    forSession(sessionId: string): AuthenticatedApis;
    readonly cookieLock: AuthCookieLock;
    readonly session: AuthSession;
    authorizationSnapshot(): AuthorizationSnapshot;
    connect(): void;
    disconnect(): void;
    dispose(): void;
}

export interface IdentityRuntimeOptions {
    readonly fetchImpl?: typeof fetch;
    readonly origin?: string;
    readonly attestationCaches?: AttestationCacheOptions;
    readonly profileClock?: () => number;
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
    let profileCache: { lifetime: number; resolver: ProfileResolver } | undefined;
    const unsubscribeProfiles: () => void = session.subscribe((): void => {
        if (profileCache && profileCache.lifetime !== session.getAuthenticatedLifetime()) {
            profileCache.resolver.close(); profileCache = undefined;
        }
    });
    return {
        api, auth, cookieLock, session,
        forSession(sessionId: string): AuthenticatedApis {
            const lifetime: number | undefined = session.getSessionId() === sessionId ? session.getAuthenticatedLifetime() : undefined;
            const assertCurrent: () => void = () => {
                if (!lifetime || session.getAuthenticatedLifetime() !== lifetime) throw sessionContextChanged();
            };
            const scopedApi: ApiClient = scopeApiClient(api, (path: string, headers: Headers): void => {
                assertCurrent();
                if (ANONYMOUS_IDENTITY_PATHS.has(path) || headers.has('Authorization')) {
                    throw new ApiError({
                        status: 0, code: 'invalid_request_context', title: 'Invalid Request Context',
                        detail: 'Workspace APIs require the current session bearer.',
                    });
                }
            });
            const scopedIdentity: IdentityApi = new IdentityApi(scopedApi);
            const resolver: ProfileResolver = profileCache?.lifetime === lifetime && profileCache !== undefined ? profileCache.resolver
                : new ProfileResolver(scopedIdentity.lookupProfiles.bind(scopedIdentity), assertCurrent, options.profileClock);
            if (lifetime !== undefined) profileCache = { lifetime, resolver };
            return { api: scopedApi, security: scopedIdentity,
                account: { getAccount: scopedIdentity.getAccount.bind(scopedIdentity),
                    async setDisplayName(name: string): Promise<AccountProfile> {
                        const profile: AccountProfile = await scopedIdentity.setDisplayName(name);
                        resolver.remember(profile);
                        return profile;
                    } },
                profiles: { resolve: (ids: readonly string[], signal?: AbortSignal): Promise<ReadonlyMap<string, ProfileResolution>> => resolver.resolve(ids, signal) } };

        },
        authorizationSnapshot: (): AuthorizationSnapshot => attestations.snapshot(),
        connect: () => channel.connect(),
        disconnect: () => channel.disconnect(),
        dispose: () => { unsubscribeProfiles(); profileCache?.resolver.close(); profileCache = undefined; attestations.dispose(); auth.dispose(); channel.dispose(); session.dispose(); },
    };
}
