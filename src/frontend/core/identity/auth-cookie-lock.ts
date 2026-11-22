import { ApiError } from '@/lib/api';

/** All shared-refresh-cookie mutations, including future refresh/logout, use this lock. */
export const AUTH_COOKIE_LOCK = 'sharewise:iam:refresh-cookie';
export const AUTH_COOKIE_LOCK_WAIT_MS = 30_000;

function lockUnavailable(): ApiError {
    return new ApiError({
        status: 0,
        code: 'auth_lock_unavailable',
        title: 'Secure Authentication Unavailable',
        detail: 'Your browser cannot safely coordinate authentication. Please use a supported browser and try again.',
    });
}

/**
 * Exclusive same-origin Web Lock boundary. Never runs the callback when locking
 * is unavailable or acquisition fails; no unlocked fallback is permitted.
 */
export class AuthCookieLock {
    constructor(private readonly getLocks: () => LockManager | undefined) {}

    async runExclusive<T>(action: () => Promise<T>): Promise<T> {
        let lockManager: LockManager | undefined;
        try {
            lockManager = this.getLocks();
        } catch {
            throw lockUnavailable();
        }
        if (!lockManager || typeof lockManager.request !== 'function') throw lockUnavailable();

        const acquisition = new AbortController();
        const deadline = setTimeout(() => acquisition.abort(), AUTH_COOKIE_LOCK_WAIT_MS);
        let entered = false;
        try {
            const result = await lockManager.request(
                AUTH_COOKIE_LOCK, { mode: 'exclusive', signal: acquisition.signal }, async (lock) => {
                    // A non-conforming lock provider must not run timed-out work later.
                    if (!lock || acquisition.signal.aborted) throw lockUnavailable();
                    entered = true;
                    clearTimeout(deadline);
                    // The lock remains held until the HTTP operation settles,
                    // including its abort. A UI timeout never releases it early.
                    return action();
                }
            );
            if (!entered) throw lockUnavailable();
            return result;
        } catch (error) {
            // Preserve transport/validation errors from inside the lock; hide
            // unsupported or failed lock-acquisition diagnostics from the user.
            if (!entered) throw lockUnavailable();
            throw error;
        } finally {
            clearTimeout(deadline);
        }
    }
}
