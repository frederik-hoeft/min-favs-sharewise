import type { ApiError } from './api-error.ts';
import type { ApiClient } from './client.ts';

/** Immutable description of the dispatched request, without application body or credential contents. */
export interface ApiRequestContext {
    readonly origin: string;
    readonly pathname: string;
    readonly target: string;
    readonly method: string;
    readonly replayable: boolean;
    readonly signal?: AbortSignal;
}

export interface SentRequest {
    readonly headers: Readonly<Headers>;
    /** Zero-based HTTP attempt within this request (detours have their own attempt numbers). */
    readonly attempt: number;
}

export type HandlerKind = 'primary' | 'supplementary';

/** Recovery is proposed without side effects and executed only after transport admission. */
export interface RecoveryAction {
    readonly key: string;
    run(primaryOnly: ApiClient): Promise<void>;
}

export interface RecoveryProgress {
    readonly primaryRecoveryAttempted: boolean;
}

/** A handler owns its per-logical-request state; the transport owns all dispatch and replay. */
export interface RequestHandler {
    prepare?(headers: Headers): Promise<void> | void;
    assertCurrent?(): void;
    recover?(failure: ApiError, sent: SentRequest): RecoveryAction | undefined;
    onTerminalFailure?(failure: ApiError, sent: SentRequest, progress: RecoveryProgress): void;
    onSuccess?(sent: SentRequest): void;
}

export interface ApiRequestHandler {
    readonly kind: HandlerKind;
    /** Called synchronously before body serialization or any credential preparation. */
    begin(context: ApiRequestContext): RequestHandler | undefined;
}

/** Shared by the original request and all of its admitted acquisition detours. */
export class RecoveryBudget implements RecoveryProgress {
    private primaryAttempted = false;
    private readonly supplementary = new Set<string>();
    private static readonly MAX_SUPPLEMENTARY = 3;
    private static readonly MAX_TOTAL = 4;

    get primaryRecoveryAttempted(): boolean { return this.primaryAttempted; }

    reserve(kind: HandlerKind, key: string): boolean {
        if (this.supplementary.size + Number(this.primaryAttempted) >= RecoveryBudget.MAX_TOTAL) return false;
        if (kind === 'primary') {
            if (this.primaryAttempted) return false;
            this.primaryAttempted = true;
            return true;
        }
        if (this.supplementary.size >= RecoveryBudget.MAX_SUPPLEMENTARY || this.supplementary.has(key)) return false;
        this.supplementary.add(key);
        return true;
    }
}
