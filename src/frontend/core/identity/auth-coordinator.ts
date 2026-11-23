import { ApiError } from '@/lib/api';
import { AuthCookieLock } from './auth-cookie-lock.ts';
import { AuthSession } from './auth-session.ts';
import { IdentityApi } from './identity-api.ts';
import { SessionContextChannel } from './session-context-channel.ts';
import type { AuthenticationResponse, IdentityCredentials, SessionContextEvent } from './models.ts';

type AuthOperation = 'register' | 'login';

interface RenewalFlight {
    readonly controller: AbortController;
    action: Promise<void>;
    waiters: number;
    submitted: boolean;
}

function requestCanceled(): ApiError {
    return authError('request_aborted', 'Request Canceled', 'The request was canceled before completion.');
}

function assertInterested(flight: RenewalFlight): void {
    if (flight.controller.signal.aborted) throw requestCanceled();
}

function authError(code: string, title: string, detail: string): ApiError {
    return new ApiError({ status: 0, code, title, detail });
}

function contextChanged(): ApiError {
    return authError('session_context_changed', 'Sign-in Changed', 'The browser is signed in to a different session. Reload this page.');
}

function mayHaveCommitted(error: unknown): boolean {
    return error instanceof ApiError && (error.status >= 500 ||
        error.code === 'network_error' || error.code === 'invalid_response' ||
        error.code === 'invalid_auth_response');
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
    private renewal: RenewalFlight | undefined;
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
    async ensureAuthenticated(signal?: AbortSignal): Promise<void> {
        if (this.session.getSnapshot().status === 'context-changed') throw contextChanged();
        if (this.session.getAccessToken()) return;
        if (signal?.aborted) throw requestCanceled();
        if (this.renewal) return this.singleFlightRenewal(undefined, undefined, signal);
        const state = this.session.getSnapshot();
        if (state.status !== 'reauthentication-required' ||
            (state.reason !== 'initial' && state.reason !== 'retryable')) {
            throw reauthenticationRequired();
        }
        this.session.markRestoring();
        return this.singleFlightRenewal(undefined, undefined, signal);
    }

    /** Recover a pre-dispatch 401 without reviving a stale tab or changing accounts. */
    async recover(rejectedToken: string, expectedSessionId: string, signal?: AbortSignal): Promise<void> {
        if (signal?.aborted) throw requestCanceled();
        this.assertContext(expectedSessionId);
        const state = this.session.getSnapshot();
        if (state.status === 'reauthentication-required' && state.reason !== 'initial') {
            throw reauthenticationRequired();
        }
        if (this.session.getAccessToken() && !this.session.matchesAccessToken(rejectedToken)) return;
        await this.singleFlightRenewal(expectedSessionId, rejectedToken, signal);
        if (signal?.aborted) throw requestCanceled();
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
                if (mayHaveCommitted(error)) this.session.invalidate('uncertain');
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
                    if (mayHaveCommitted(error)) {
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

    /** A caller may stop waiting; submitted refreshes must still settle under their shared Web Lock. */
    private async singleFlightRenewal(expectedSessionId: string | undefined, rejectedToken?: string,
        signal?: AbortSignal): Promise<void> {
        if (signal?.aborted) throw requestCanceled();
        const previous = this.renewal;
        if (previous?.controller.signal.aborted && !previous.submitted) {
            // No new caller may join a canceled flight. Its in-flight CSRF request
            // must settle and release the Web Lock before we start a replacement.
            await this.awaitAbandonedRenewal(previous, signal);
            if (expectedSessionId === undefined) return this.ensureAuthenticated(signal);
            this.assertContext(expectedSessionId);
            const state = this.session.getSnapshot();
            if (state.status === 'reauthentication-required' && state.reason !== 'initial') {
                throw reauthenticationRequired();
            }
            if (this.session.getAccessToken() && rejectedToken !== undefined &&
                !this.session.matchesAccessToken(rejectedToken)) return;
            return this.singleFlightRenewal(expectedSessionId, rejectedToken, signal);
        }
        if (!this.renewal) {
            const flight: RenewalFlight = {
                controller: new AbortController(), action: Promise.resolve(), waiters: 0, submitted: false,
            };
            this.renewal = flight;
            flight.action = this.renewInsideLock(expectedSessionId, rejectedToken, flight);
            void flight.action.finally(() => {
                if (this.renewal === flight) this.renewal = undefined;
            }).catch(() => {});
        }
        return this.awaitRenewal(this.renewal, signal);
    }

    private async awaitAbandonedRenewal(flight: RenewalFlight, signal?: AbortSignal): Promise<void> {
        const settled = flight.action.then(() => {}, () => {});
        if (!signal) {
            await settled;
            return;
        }
        if (signal.aborted) throw requestCanceled();
        let onAbort: () => void = () => {};
        try {
            await Promise.race([
                settled,
                new Promise<never>((_resolve, reject) => {
                    onAbort = () => reject(requestCanceled());
                    signal.addEventListener('abort', onAbort, { once: true });
                }),
            ]);
        } finally {
            signal.removeEventListener('abort', onAbort);
        }
    }

    private awaitRenewal(flight: RenewalFlight, signal?: AbortSignal): Promise<void> {
        if (signal?.aborted) return Promise.reject(requestCanceled());
        flight.waiters++;
        return new Promise<void>((resolve, reject) => {
            let completed = false;
            const finish = () => {
                if (completed) return false;
                completed = true;
                signal?.removeEventListener('abort', onAbort);
                flight.waiters--;
                // Abandon an unneeded renewal before refresh is submitted, never after.
                if (flight.waiters === 0 && !flight.submitted) flight.controller.abort();
                return true;
            };
            const onAbort = () => { if (finish()) reject(requestCanceled()); };
            signal?.addEventListener('abort', onAbort, { once: true });
            if (signal?.aborted) { onAbort(); return; }
            void flight.action.then(
                () => { if (finish()) resolve(); },
                (error: unknown) => { if (finish()) reject(error); },
            );
        });
    }

    private async renewInsideLock(expectedSessionId: string | undefined, rejectedToken: string | undefined,
        flight: RenewalFlight): Promise<void> {
        try {
            await this.cookieLock.runExclusive(async () => {
                assertInterested(flight);
                // Another request may have renewed while we waited for the lock.
                this.assertRenewalContext(expectedSessionId);
                if (this.session.getAccessToken() &&
                    (rejectedToken === undefined || !this.session.matchesAccessToken(rejectedToken))) return;
                const csrf = await this.api.getCsrfToken(flight.controller.signal);
                assertInterested(flight);
                this.assertRenewalContext(expectedSessionId);
                try {
                    flight.submitted = true;
                    const response = await this.api.refresh(expectedSessionId, csrf);
                    // Never publish or interpret an old flight under a replacement login.
                    this.assertRenewalContext(expectedSessionId);
                    // Validate the response BEFORE comparing its session to the bound request.
                    AuthSession.validateResponse(response);
                    if (expectedSessionId && response.sessionId !== expectedSessionId) {
                        this.session.markContextChanged();
                        throw contextChanged();
                    }
                    this.session.accept(response);
                } catch (error) {
                    if (error instanceof ApiError && error.status === 409 && error.code === 'session_context_changed') {
                        this.assertRenewalContext(expectedSessionId);
                        this.session.markContextChanged();
                        throw contextChanged();
                    }
                    throw error;
                }
            }, flight.controller.signal);
        } catch (error) {
            if (error instanceof ApiError && error.code === 'session_context_changed') throw error;
            this.assertRenewalContext(expectedSessionId);
            if (!flight.submitted) {
                // No refresh generation was consumed. Retain the established bearer or allow restoration later.
                this.session.markRestorationRetryable();
                throw error;
            }
            const uncertain = mayHaveCommitted(error);
            this.session.invalidate(uncertain ? 'uncertain' : 'unauthorized');
            if (uncertain) throw authError('refresh_outcome_unknown', 'Refresh Outcome Unknown',
                'The authentication response could not be confirmed. Sign in again.');
            throw error;
        }
    }

    /** A renewal flight may finish under its original context only, not revive an invalidated login. */
    private assertRenewalContext(expected: string | undefined): void {
        const state = this.session.getSnapshot();
        if (expected === undefined ? state.status !== 'restoring' :
            state.status !== 'authenticated' || state.sessionId !== expected) {
            throw contextChanged();
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
