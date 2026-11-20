import { ApiError } from '@/lib/api';
import type { AuthenticationResponse, AuthState } from './models.ts';

const MAX_TIMEOUT_MS = 2_147_483_647;
const ISO_INSTANT = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})$/;

/**
 * Tab-local bearer storage. Only status and expiry are observable; the JWT never
 * enters React state, browser storage, or server-rendered data.
 */
export class AuthSession {
    private accessToken: string | undefined;
    private expiresAt = 0;
    private expiryTimer: ReturnType<typeof setTimeout> | undefined;
    private readonly listeners = new Set<() => void>();
    private state: AuthState = { status: 'reauthentication-required', reason: 'initial' };

    constructor(private readonly now: () => number = Date.now) {}

    readonly subscribe = (listener: () => void): (() => void) => {
        this.listeners.add(listener);
        return () => this.listeners.delete(listener);
    };

    readonly getSnapshot = (): AuthState => this.state;

    /** Never returns an expired token, even when a background timer was throttled. */
    getAccessToken(): string | undefined {
        if (this.accessToken && this.now() >= this.expiresAt) {
            this.invalidate('expired');
        }
        return this.accessToken;
    }

    /** Compare without accessing or invalidating the active token; used by 401 handling. */
    matchesAccessToken(token: string): boolean {
        return this.accessToken !== undefined && this.accessToken === token;
    }

    accept(response: AuthenticationResponse): void {
        const expiresAt = typeof response?.expiresAt === 'string' && ISO_INSTANT.test(response.expiresAt)
            ? Date.parse(response.expiresAt)
            : NaN;

        if (
            typeof response?.accessToken !== 'string' || !response.accessToken.trim() ||
            response.tokenType !== 'Bearer' || !Number.isFinite(expiresAt) || expiresAt <= this.now()
        ) {
            // Do not include response data: it may contain a raw bearer credential.
            throw new ApiError({
                status: 0,
                code: 'invalid_auth_response',
                title: 'Invalid Authentication Response',
                detail: 'The server returned an unusable authentication response. Please sign in again.',
            });
        }

        this.clearTimer();
        this.accessToken = response.accessToken;
        this.expiresAt = expiresAt;
        this.update({ status: 'authenticated', expiresAt: response.expiresAt });
        this.scheduleExpiry();
    }

    invalidate(reason: 'expired' | 'unauthorized'): void {
        this.clearTimer();
        this.accessToken = undefined;
        this.expiresAt = 0;
        if (this.state.status !== 'reauthentication-required' || this.state.reason !== reason) {
            this.update({ status: 'reauthentication-required', reason });
        }
    }

    dispose(): void {
        this.clearTimer();
        this.listeners.clear();
        this.accessToken = undefined;
        this.expiresAt = 0;
        this.state = { status: 'reauthentication-required', reason: 'initial' };
    }

    private update(next: AuthState): void {
        this.state = next;
        for (const listener of this.listeners) listener();
    }

    private clearTimer(): void {
        if (this.expiryTimer !== undefined) clearTimeout(this.expiryTimer);
        this.expiryTimer = undefined;
    }

    private scheduleExpiry(): void {
        const remaining = this.expiresAt - this.now();
        if (remaining <= 0) {
            this.invalidate('expired');
            return;
        }
        // Timers are advisory: checks at the API boundary also enforce expiry.
        this.expiryTimer = setTimeout(() => this.scheduleExpiry(), Math.min(remaining, MAX_TIMEOUT_MS));
    }
}
