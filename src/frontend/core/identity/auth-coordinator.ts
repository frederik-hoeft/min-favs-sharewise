import { ApiError } from '@/lib/api';
import { AuthCookieLock } from './auth-cookie-lock.ts';
import { AuthSession } from './auth-session.ts';
import { IdentityApi } from './identity-api.ts';
import type { AuthenticationResponse, IdentityCredentials } from './models.ts';

type AuthOperation = 'register' | 'login';

function operationInProgress(): ApiError {
    return new ApiError({
        status: 0,
        code: 'auth_in_progress',
        title: 'Authentication Already In Progress',
        detail: 'An authentication request is already in progress in this tab.',
    });
}

/**
 * Rejects overlapping per-tab submissions (which may use different credentials).
 * CSRF bootstrap, cookie mutation and in-memory state transition occur under
 * one cross-tab lock, which P2.1b can reuse for refresh/logout.
 */
export class AuthCoordinator {
    private inFlight = false;

    constructor(
        private readonly api: IdentityApi,
        private readonly session: AuthSession,
        private readonly cookieLock: AuthCookieLock
    ) {}

    register(credentials: IdentityCredentials): Promise<void> {
        return this.authenticate('register', credentials);
    }

    login(credentials: IdentityCredentials): Promise<void> {
        return this.authenticate('login', credentials);
    }

    private async authenticate(operation: AuthOperation, credentials: IdentityCredentials): Promise<void> {
        if (this.inFlight) throw operationInProgress();
        this.inFlight = true;
        try {
            await this.cookieLock.runExclusive(async () => {
                const csrfToken = await this.api.getCsrfToken();
                const response: AuthenticationResponse = await this.api[operation](credentials, csrfToken);
                this.session.accept(response);
            });
        } finally {
            this.inFlight = false;
        }
    }
}
