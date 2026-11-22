import { ApiError } from '@/lib/api';
import type { AuthenticationResponse, AuthState } from './models.ts';

const ISO_INSTANT = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})$/;
export const SESSION_ID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

/**
 * Tab-local access bearer and session binding. Timestamps are advisory: only
 * server ingress decides whether an issued JWT has expired.
 */
export class AuthSession {
    private accessToken: string | undefined;
    private sessionId: string | undefined;
    private readonly listeners = new Set<() => void>();
    private state: AuthState = { status: 'reauthentication-required', reason: 'initial' };

    readonly subscribe = (listener: () => void): (() => void) => {
        this.listeners.add(listener);
        return () => this.listeners.delete(listener);
    };

    readonly getSnapshot = (): AuthState => this.state;

    /** Never persists or exposes the bearer through observable React state. */
    getAccessToken(): string | undefined {
        return this.accessToken;
    }

    /** Remains bound to the original session even when recovery is suspended. */
    getSessionId(): string | undefined {
        return this.sessionId;
    }

    matchesAccessToken(token: string): boolean {
        return this.accessToken !== undefined && this.accessToken === token;
    }

    /** Validate before publishing credentials or interpreting the response session. */
    static validateResponse(response: AuthenticationResponse): void {
        const expiresAt = typeof response?.expiresAt === 'string' && ISO_INSTANT.test(response.expiresAt)
            ? Date.parse(response.expiresAt)
            : NaN;
        if (
            typeof response?.accessToken !== 'string' || !response.accessToken.trim() ||
            response.tokenType !== 'Bearer' || !Number.isFinite(expiresAt) ||
            typeof response.sessionId !== 'string' || !SESSION_ID_PATTERN.test(response.sessionId)
        ) {
            throw new ApiError({
                status: 0,
                code: 'invalid_auth_response',
                title: 'Invalid Authentication Response',
                detail: 'The server returned an unusable authentication response. Please sign in again.',
            });
        }
    }

    accept(response: AuthenticationResponse): void {
        AuthSession.validateResponse(response);
        this.accessToken = response.accessToken;
        this.sessionId = response.sessionId;
        this.update({ status: 'authenticated', expiresAt: response.expiresAt });
    }

    markRestoring(): void {
        if (this.state.status === 'reauthentication-required' && this.state.reason === 'initial') {
            this.update({ status: 'restoring' });
        }
    }

    /** A canceled/failed pre-submission restoration has not consumed the cookie and can be tried again. */
    markRestorationRetryable(): void {
        if (this.state.status === 'restoring') {
            this.update({ status: 'reauthentication-required', reason: 'initial' });
        }
    }

    /** Do not clear or revoke another tab's current cookie on a context switch. */
    markContextChanged(): void {
        this.accessToken = undefined;
        this.update({ status: 'context-changed' });
    }

    invalidate(reason: 'expired' | 'unauthorized' | 'uncertain'): void {
        this.accessToken = undefined;
        if (this.state.status !== 'reauthentication-required' || this.state.reason !== reason) {
            this.update({ status: 'reauthentication-required', reason });
        }
    }

    /** Discard a confirmed logged-out context without touching the browser cookie. */
    clear(): void {
        this.sessionId = undefined;
        this.invalidate('unauthorized');
    }

    dispose(): void {
        this.listeners.clear();
        this.accessToken = undefined;
        this.sessionId = undefined;
        this.state = { status: 'reauthentication-required', reason: 'initial' };
    }

    private update(next: AuthState): void {
        this.state = next;
        for (const listener of this.listeners) listener();
    }
}
