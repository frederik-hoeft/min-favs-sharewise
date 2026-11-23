import { SESSION_ID_PATTERN } from './auth-session.ts';
import type { SessionContextEvent } from './models.ts';

export const SESSION_CONTEXT_CHANNEL = 'sharewise:iam:session-context';

/** Broadcasts only non-secret login/logout context changes, never refresh rotations. */
export class SessionContextChannel {
    private channel: BroadcastChannel | undefined;
    private readonly listeners = new Set<(event: SessionContextEvent) => void>();

    constructor(private readonly factory: () => BroadcastChannel | undefined) {}

    connect(): void {
        if (this.channel) return;
        let channel: BroadcastChannel | undefined;
        try {
            channel = this.factory();
            channel?.addEventListener('message', this.receive);
        } catch {
            // BroadcastChannel is an optimization; bound refresh is authoritative.
            try { channel?.close(); } catch { /* The channel was never connected. */ }
            return;
        }
        this.channel = channel;
    }

    readonly subscribe = (listener: (event: SessionContextEvent) => void): (() => void) => {
        this.listeners.add(listener);
        return () => this.listeners.delete(listener);
    };

    publish(event: SessionContextEvent): void {
        try {
            this.channel?.postMessage(event);
        } catch {
            // No credential or session mutation depends on delivery.
        }
    }

    disconnect(): void {
        this.channel?.removeEventListener('message', this.receive);
        this.channel?.close();
        this.channel = undefined;
    }

    dispose(): void {
        this.disconnect();
        this.listeners.clear();
    }

    private readonly receive = (message: MessageEvent): void => {
        const event: unknown = message.data;
        if (!event || typeof event !== 'object') return;
        if (!('type' in event) || !('sessionId' in event)) return;
        if ((event.type !== 'login' && event.type !== 'logout') ||
            typeof event.sessionId !== 'string' || !SESSION_ID_PATTERN.test(event.sessionId)) return;
        for (const listener of this.listeners) listener({ type: event.type, sessionId: event.sessionId });
    };
}
