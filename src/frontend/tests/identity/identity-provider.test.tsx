import { StrictMode, useEffect } from 'react';
import { render } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { IdentityProvider, useIdentity } from '@/app/identity-provider';
import { createIdentityRuntime } from '@/core/identity';
import { ControlledChannels, ControlledLocks, SESSION_TWO, authenticationResponse, credentials, jsonResponse } from './support';

afterEach(() => vi.unstubAllGlobals());

describe('IdentityProvider lifecycle', () => {
  it('keeps exactly one channel connected after Strict Mode effect replay and releases it on unmount', async () => {
    const channels = new ControlledChannels();
    vi.stubGlobal('BroadcastChannel', function MockBroadcastChannel() { return channels.create(); });
    const connectedRuntimes: ReturnType<typeof useIdentity>[] = [];
    function Observer() {
      const runtime = useIdentity();
      useEffect(() => { connectedRuntimes.push(runtime); }, [runtime]);
      return null;
    }

    const mounted = render(<StrictMode><IdentityProvider><Observer /></IdentityProvider></StrictMode>);
    expect(channels.activeCount).toBe(1);
    const first = connectedRuntimes.at(-1);
    expect(first).toBeDefined();
    first!.session.accept(authenticationResponse('alice'));

    const second = createIdentityRuntime({
      locks: () => new ControlledLocks().manager,
      channel: channels.create,
      fetchImpl: (async (input: RequestInfo | URL) => String(input).endsWith('/csrf')
        ? jsonResponse({ token: 'csrf' })
        : jsonResponse(authenticationResponse('bob', undefined, SESSION_TWO))) as typeof fetch,
    });
    second.connect();
    expect(channels.activeCount).toBe(2);
    try {
      await second.auth.login(credentials);
      expect(first!.session.getSnapshot()).toEqual({ status: 'context-changed' });
      mounted.unmount();
      expect(channels.activeCount).toBe(1);
    } finally {
      mounted.unmount();
      second.dispose();
    }
    expect(channels.activeCount).toBe(0);
  });
});
