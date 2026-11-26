import { sessionContextChanged, type ApiError, type ApiRequestContext, type ApiRequestHandler,
    type RequestHandler, type SentRequest, type RecoveryAction, type RecoveryProgress } from '@/lib/api';
import type { AuthCoordinator } from './auth-coordinator.ts';
import type { AuthState } from './models.ts';
import type { AuthSession } from './auth-session.ts';
import { ANONYMOUS_IDENTITY_PATHS, PASSWORD_CHANGE_PATH } from './identity-api.ts';

const PRE_DISPATCH_401_CODES: Set<string> = new Set(['invalid_credential', 'authentication_required']);

/** IAM owns only primary bearer preparation, renewal and terminal IAM denial semantics. */
export function iamRequestHandler(session: AuthSession, coordinator: () => AuthCoordinator): ApiRequestHandler {
    return {
        kind: 'primary',
        begin(request: ApiRequestContext): RequestHandler | undefined {
            if (ANONYMOUS_IDENTITY_PATHS.has(request.pathname)) return;
            let sessionId: string | undefined = session.getSessionId();
            let lifetime: number | undefined = session.getAuthenticatedLifetime();
            const assertCurrent: () => void = () => {
                if (lifetime !== undefined && (session.getAuthenticatedLifetime() !== lifetime ||
                    session.getSessionId() !== sessionId)) throw sessionContextChanged();
                if (lifetime === undefined && sessionId !== undefined && session.getSessionId() !== sessionId) {
                    throw sessionContextChanged();
                }
            };
            const passwordDenial: (code: string) => boolean = (code: string) => request.pathname === PASSWORD_CHANGE_PATH &&
                request.method === 'POST' && code === 'session_unavailable';
            return {
                assertCurrent,
                async prepare(headers: Headers): Promise<void> {
                    await coordinator().ensureAuthenticated(request.signal);
                    assertCurrent();
                    const token: string | undefined = session.getAccessToken();
                    const state: AuthState = session.getSnapshot();
                    if (!token || state.status !== 'authenticated') throw sessionContextChanged();
                    sessionId = state.sessionId;
                    lifetime = session.getAuthenticatedLifetime();
                    if (lifetime === undefined) throw sessionContextChanged();
                    headers.set('Authorization', `Bearer ${token}`);
                },
                recover(failure: ApiError, sent: SentRequest): RecoveryAction | undefined {
                    const rejected: string | null = sent.headers.get('Authorization');
                    const expected: string | undefined = sessionId;
                    if (!expected || !rejected?.startsWith('Bearer ') || !PRE_DISPATCH_401_CODES.has(failure.code)) return;
                    return { key: 'primary', run: (): Promise<void> => coordinator().recover(rejected.slice(7), expected, request.signal) };
                },
                onTerminalFailure(failure: ApiError, sent: SentRequest, progress: RecoveryProgress): void {
                    const rejected: string | null = sent.headers.get('Authorization');
                    if (!sessionId || !rejected?.startsWith('Bearer ') || session.getAuthenticatedLifetime() !== lifetime) return;
                    if (passwordDenial(failure.code) || (progress.primaryRecoveryAttempted &&
                        PRE_DISPATCH_401_CODES.has(failure.code) && session.matchesAccessToken(rejected.slice(7)))) {
                        session.invalidate('unauthorized');
                    }
                },
            };
        },
    };
}
