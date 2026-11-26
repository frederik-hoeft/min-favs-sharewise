import type { AffinityPolicy, CredentialDescriptor } from '@/lib/api/authorization-protocol';

/** Detached diagnostic metadata only; never includes bearer or attestation bytes. */
export interface CredentialDiagnostic extends CredentialDescriptor {
    readonly key: string;
    readonly generation: number;
    readonly expiresAt: number;
    readonly remainingMs: number;
    readonly usable: boolean;
}

export interface AcquisitionDiagnostic {
    readonly key: string;
    readonly waiters: number;
}

export interface RequirementDiagnostic {
    readonly bucketKey: string;
    readonly requirementId: string;
    readonly policy: AffinityPolicy['policy'];
    readonly parameters: readonly string[];
    readonly sequence: number;
    readonly projectedKey: string | null;
    readonly credentialKey: string | null;
}

export interface AuthorizationSnapshot {
    readonly lifetime: number | null;
    readonly capturedAt: number;
    readonly credentials: readonly CredentialDiagnostic[];
    readonly requirements: readonly RequirementDiagnostic[];
    readonly inFlight: readonly AcquisitionDiagnostic[];
}
