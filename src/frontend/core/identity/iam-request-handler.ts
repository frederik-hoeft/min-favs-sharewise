import { sessionContextChanged, type ApiRequestHandler } from '@/lib/api';
import type { AuthCoordinator } from './auth-coordinator.ts';
import type { AuthSession } from './auth-session.ts';
import { ANONYMOUS_IDENTITY_PATHS, PASSWORD_CHANGE_PATH } from './identity-api.ts';

const PRE_DISPATCH_401_CODES = new Set(['invalid_credential', 'authentication_required']);

/** IAM owns only primary bearer preparation, renewal and terminal IAM denial semantics. */
export function iamRequestHandler(session: AuthSession, coordinator: () => AuthCoordinator): ApiRequestHandler {
    return {
        kind: 'primary',
        begin(request) {
            if (ANONYMOUS_IDENTITY_PATHS.has(request.pathname)) return;
            let sessionId = session.getSessionId();
            let lifetime = session.getAuthenticatedLifetime();
            const assertCurrent = () => {
                if (lifetime !== undefined && (session.getAuthenticatedLifetime() !== lifetime ||
                    session.getSessionId() !== sessionId)) throw sessionContextChanged();
                if (lifetime === undefined && sessionId !== undefined && session.getSessionId() !== sessionId) {
                    throw sessionContextChanged();
                }
            };
            const passwordDenial = (code: string) => request.pathname === PASSWORD_CHANGE_PATH &&
                request.method === 'POST' && code === 'session_unavailable';
            return {
                assertCurrent,
                async prepare(headers) {
                    await coordinator().ensureAuthenticated(request.signal);
                    assertCurrent();
                    const token = session.getAccessToken();
                    const state = session.getSnapshot();
                    if (!token || state.status !== 'authenticated') throw sessionContextChanged();
                    sessionId = state.sessionId;
                    lifetime = session.getAuthenticatedLifetime();
                    if (lifetime === undefined) throw sessionContextChanged();
                    headers.set('Authorization', `Bearer ${token}`);
                },
                recover(failure, sent) {
                    const rejected = sent.headers.get('Authorization');
                    const expected = sessionId;
                    if (!expected || !rejected?.startsWith('Bearer ') || !PRE_DISPATCH_401_CODES.has(failure.code)) return;
                    return { key: 'primary', run: () => coordinator().recover(rejected.slice(7), expected, request.signal) };
                },
                onTerminalFailure(failure, sent, progress) {
                    const rejected = sent.headers.get('Authorization');
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
