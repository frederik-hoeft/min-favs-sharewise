import { ApiError } from '@/lib/api';
import { AuthCookieLock } from './auth-cookie-lock.ts';
import { AuthSession } from './auth-session.ts';
import { IdentityApi } from './identity-api.ts';
import { SessionContextChannel } from './session-context-channel.ts';
import type { AuthenticationResponse, IdentityCredentials, SessionContextEvent } from './models.ts';

type AuthOperation = 'register' | 'login';

function authError(code: string, title: string, detail: string): ApiError {
    return new ApiError({ status: 0, code, title, detail });
}

function contextChanged(): ApiError {
    return authError('session_context_changed', 'Sign-in Changed', 'The browser is signed in to a different session. Reload this page.');
}

function reauthenticationRequired(): ApiError {
    return authError('reauthentication_required', 'Sign-in Required', 'Sign in again to continue.');
}

/**
 * Owns cookie-backed operations and the tab's bounded renewal state machine.
 * Only the serialized cookie mutation is cross-tab; access bearers remain tab-local.
 */
export class AuthCoordinator {
    private credentialSubmission = false;
    private renewal: Promise<void> | undefined;
    private readonly unsubscribe: () => void;

    constructor(
        private readonly api: IdentityApi,
        private readonly session: AuthSession,
        private readonly cookieLock: AuthCookieLock,
        private readonly channel: SessionContextChannel
    ) {
        this.unsubscribe = channel.subscribe((event) => this.onContextEvent(event));
    }

    register(credentials: IdentityCredentials): Promise<void> {
        return this.authenticate('register', credentials);
    }

    login(credentials: IdentityCredentials): Promise<void> {
        return this.authenticate('login', credentials);
    }

    /** Restore once, on first use of an authenticated API or workspace identity. */
    async ensureAuthenticated(): Promise<void> {
        if (this.session.getSnapshot().status === 'context-changed') throw contextChanged();
        if (this.session.getAccessToken()) return;
        if (this.renewal) return this.renewal;
        const state = this.session.getSnapshot();
        if (state.status !== 'reauthentication-required' || state.reason !== 'initial') {
            throw reauthenticationRequired();
        }
        this.session.markRestoring();
        return this.singleFlightRenewal(undefined);
    }

    /** Recover a pre-dispatch 401 without reviving a stale tab or changing accounts. */
    async recover(rejectedToken: string, expectedSessionId: string): Promise<void> {
        this.assertContext(expectedSessionId);
        const state = this.session.getSnapshot();
        if (state.status === 'reauthentication-required' && state.reason !== 'initial') {
            throw reauthenticationRequired();
        }
        if (this.session.getAccessToken() && !this.session.matchesAccessToken(rejectedToken)) return;
        await this.singleFlightRenewal(expectedSessionId, rejectedToken);
        this.assertContext(expectedSessionId);
        if (!this.session.getAccessToken()) throw reauthenticationRequired();
    }

    /** Logout always binds to the tab's established session, never another tab's cookie. */
    async logout(): Promise<void> {
        if (this.session.getSnapshot().status === 'context-changed') throw contextChanged();
        const expected = this.session.getSessionId();
        await this.cookieLock.runExclusive(async () => {
            if (expected) this.assertContext(expected);
            const csrf = await this.api.getCsrfToken();
            if (expected) this.assertContext(expected);
            try {
                await this.api.logout(expected, csrf);
            } catch (error) {
                if (error instanceof ApiError && error.status === 409 && error.code === 'session_context_changed') {
                    this.session.markContextChanged();
                    throw contextChanged();
                }
                // A logout may have committed even if its response was lost.
                if (error instanceof ApiError && error.status === 0) this.session.invalidate('uncertain');
                throw error;
            }
            this.session.clear();
            if (expected) this.channel.publish({ type: 'logout', sessionId: expected });
        });
    }

    dispose(): void {
        this.unsubscribe();
    }

    private async authenticate(operation: AuthOperation, credentials: IdentityCredentials): Promise<void> {
        if (this.credentialSubmission) throw authError('auth_in_progress', 'Authentication Already In Progress', 'An authentication request is already in progress in this tab.');
        this.credentialSubmission = true;
        try {
            await this.cookieLock.runExclusive(async () => {
                const csrf = await this.api.getCsrfToken();
                try {
                    const response: AuthenticationResponse = await this.api[operation](credentials, csrf);
                    this.session.accept(response);
                    this.channel.publish({ type: 'login', sessionId: response.sessionId });
                } catch (error) {
                    if (error instanceof ApiError && error.status === 0) {
                        // The server may have committed a login/registration before the response was lost.
                        this.session.invalidate('uncertain');
                        if (operation === 'register') {
                            throw authError('registration_outcome_unknown', 'Registration Outcome Unknown',
                                'Your registration may have succeeded. Try signing in.');
                        }
                    }
                    throw error;
                }
            });
        } finally {
            this.credentialSubmission = false;
        }
    }

    private singleFlightRenewal(expectedSessionId: string | undefined, rejectedToken?: string): Promise<void> {
        if (!this.renewal) {
            const action = this.renewInsideLock(expectedSessionId, rejectedToken);
            this.renewal = action;
            void action.finally(() => {
                if (this.renewal === action) this.renewal = undefined;
            }).catch(() => {});
        }
        return this.renewal;
    }

    private async renewInsideLock(expectedSessionId: string | undefined, rejectedToken?: string): Promise<void> {
        let refreshSubmitted = false;
        try {
            await this.cookieLock.runExclusive(async () => {
                // Another request may have renewed while we waited for the lock.
                if (expectedSessionId) this.assertContext(expectedSessionId);
                if (this.session.getAccessToken() &&
                    (rejectedToken === undefined || !this.session.matchesAccessToken(rejectedToken))) return;
                const csrf = await this.api.getCsrfToken();
                let response: AuthenticationResponse;
                try {
                    refreshSubmitted = true;
                    response = await this.api.refresh(expectedSessionId, csrf);
                    // Validate the response BEFORE comparing its session to the bound request.
                    AuthSession.validateResponse(response);
                    if (expectedSessionId && response.sessionId !== expectedSessionId) {
                        this.session.markContextChanged();
                        throw contextChanged();
                    }
                    if (expectedSessionId) this.assertContext(expectedSessionId);
                    if (this.session.getSnapshot().status === 'context-changed') throw contextChanged();
                    this.session.accept(response);
                } catch (error) {
                    if (error instanceof ApiError && error.status === 409 && error.code === 'session_context_changed') {
                        this.session.markContextChanged();
                        throw contextChanged();
                    }
                    throw error;
                }
            });
        } catch (error) {
            if (error instanceof ApiError && error.code === 'session_context_changed') throw error;
            if (this.session.getSnapshot().status === 'context-changed') throw contextChanged();
            const uncertain = refreshSubmitted && error instanceof ApiError &&
                (error.code === 'network_error' || error.code === 'invalid_response' || error.code === 'invalid_auth_response');
            this.session.invalidate(uncertain ? 'uncertain' : 'unauthorized');
            if (uncertain) throw authError('refresh_outcome_unknown', 'Refresh Outcome Unknown',
                'The authentication response could not be confirmed. Sign in again.');
            throw error;
        }
    }

    private assertContext(expected: string): void {
        if (this.session.getSnapshot().status === 'context-changed' || this.session.getSessionId() !== expected) {
            throw contextChanged();
        }
    }

    private onContextEvent(event: SessionContextEvent): void {
        const current = this.session.getSessionId();
        if (!current) return;
        if (event.type === 'login' && event.sessionId !== current) this.session.markContextChanged();
        if (event.type === 'logout' && event.sessionId === current &&
            this.session.getSnapshot().status !== 'context-changed') this.session.clear();
    }
}
