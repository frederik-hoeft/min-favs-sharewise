import type { AuthorizationSnapshot, CredentialDiagnostic, RequirementDiagnostic } from '@/core/authorization/diagnostics';

interface AuthorizationDebug {
    snapshot(): AuthorizationSnapshot;
    credentials(): readonly CredentialDiagnostic[];
    requirements(): readonly RequirementDiagnostic[];
}

interface ShareWiseDebug {
    readonly authorization: AuthorizationDebug;
}

declare global {
    interface Window { sharewiseDebug?: ShareWiseDebug }
}

/** The root provider owns registration; no cache or runtime reference is published. */
export function installAuthorizationDebug(target: Window, snapshot: () => AuthorizationSnapshot): () => void {
    const debug: ShareWiseDebug = Object.freeze({ authorization: Object.freeze({
        snapshot,
        credentials: (): readonly CredentialDiagnostic[] => snapshot().credentials,
        requirements: (): readonly RequirementDiagnostic[] => snapshot().requirements,
    }) });
    target.sharewiseDebug = debug;
    return (): void => { if (target.sharewiseDebug === debug) delete target.sharewiseDebug; };
}
