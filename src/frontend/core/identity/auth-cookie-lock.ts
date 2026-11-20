import { ApiError } from '@/lib/api';

/** All shared-refresh-cookie mutations, including future refresh/logout, use this lock. */
export const AUTH_COOKIE_LOCK = 'sharewise:iam:refresh-cookie';

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

        let entered = false;
        try {
            const result = await lockManager.request(AUTH_COOKIE_LOCK, { mode: 'exclusive' }, async (lock) => {
                if (!lock) throw lockUnavailable();
                entered = true;
                return action();
            });
            if (!entered) throw lockUnavailable();
            return result;
        } catch (error) {
            // Preserve transport/validation errors from inside the lock; hide
            // unsupported or failed lock-acquisition diagnostics from the user.
            if (!entered) throw lockUnavailable();
            throw error;
        }
    }
}
