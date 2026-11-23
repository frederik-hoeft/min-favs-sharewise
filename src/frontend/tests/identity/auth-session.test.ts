import { describe, expect, it, vi } from 'vitest';
import { AuthSession } from '@/core/identity';
import { authenticationResponse, SESSION_ONE } from './support';

describe('AuthSession (tab-local bearer and stable session binding)', () => {
  it('keeps bearer data out of observable state and browser storage', () => {
    const set = vi.spyOn(Storage.prototype, 'setItem');
    const session = new AuthSession();
    const listener = vi.fn();
    const unsubscribe = session.subscribe(listener);
    try {
      expect(session.getSnapshot()).toEqual({ status: 'reauthentication-required', reason: 'initial' });
      const response = authenticationResponse();
      session.accept(response);
      expect(session.getAccessToken()).toBe(response.accessToken);
      expect(session.getSessionId()).toBe(SESSION_ONE);
      expect(session.getSnapshot()).toEqual({ status: 'authenticated', sessionId: response.sessionId, expiresAt: response.expiresAt });
      expect(JSON.stringify(session.getSnapshot())).not.toContain(response.accessToken);
      expect(listener).toHaveBeenCalledTimes(1);
      expect(set).not.toHaveBeenCalled();
      unsubscribe();
      session.invalidate('unauthorized');
      expect(listener).toHaveBeenCalledTimes(1);
      expect(session.getAccessToken()).toBeUndefined();
      expect(session.getSessionId()).toBe(SESSION_ONE);
    } finally {
      session.dispose();
      set.mockRestore();
    }
  });

  it('treats expiry metadata as advisory even when client time is far ahead', () => {
    const session = new AuthSession();
    const past = '2020-01-01T00:00:00Z';
    try {
      session.accept(authenticationResponse('server-issued', past));
      expect(session.getAccessToken()).toBe('server-issued');
      expect(session.getSnapshot()).toEqual({ status: 'authenticated', sessionId: SESSION_ONE, expiresAt: past });
    } finally { session.dispose(); }
  });

  it.each([
    ['blank token', { accessToken: ' ', tokenType: 'Bearer', expiresAt: '2026-12-01T00:00:00Z', sessionId: SESSION_ONE }],
    ['wrong token type', { ...authenticationResponse(), tokenType: 'Refresh' }],
    ['invalid expiry', { ...authenticationResponse(), expiresAt: 'tomorrow' }],
    ['invalid session ID', { ...authenticationResponse(), sessionId: 'secret' }],
    ['missing fields', {}],
    ['null payload', null],
  ])('rejects %s without exposing a bearer', (_label, response) => {
    const session = new AuthSession();
    try {
      expect(() => session.accept(response as never)).toThrowError(/unusable authentication response/i);
      expect(session.getAccessToken()).toBeUndefined();
    } finally { session.dispose(); }
  });

  it('suspends stale tabs and discards the session only on confirmed logout', () => {
    const session = new AuthSession();
    try {
      session.accept(authenticationResponse());
      session.markContextChanged();
      expect(session.getSnapshot()).toEqual({ status: 'context-changed' });
      expect(session.getSessionId()).toBe(SESSION_ONE);
      expect(session.getAccessToken()).toBeUndefined();
      session.clear();
      expect(session.getSessionId()).toBeUndefined();
      expect(session.getSnapshot()).toEqual({ status: 'reauthentication-required', reason: 'unauthorized' });
    } finally { session.dispose(); }
  });

  it('a reloaded tab begins without bearer or session identity', () => {
    const first = new AuthSession();
    const second = new AuthSession();
    try {
      first.accept(authenticationResponse());
      expect(second.getAccessToken()).toBeUndefined();
      expect(second.getSessionId()).toBeUndefined();
      expect(second.getSnapshot()).toEqual({ status: 'reauthentication-required', reason: 'initial' });
    } finally { first.dispose(); second.dispose(); }
  });
});
