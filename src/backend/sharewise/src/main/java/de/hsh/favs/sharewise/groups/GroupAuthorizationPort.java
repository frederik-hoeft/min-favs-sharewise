package de.hsh.favs.sharewise.groups;

import de.hsh.favs.sharewise.shared.result.Result;
import java.util.UUID;

/** Current-state Group authority for the admitted caller; no attestation or IAM freshness check is required. */
public interface GroupAuthorizationPort {
    /** Runs outside an active transaction segment. A successful result is point-in-time evidence, not a lease. */
    Result<VerifiedGroupMembership, GroupAuthorizationVerificationFailure> verifyCurrent(UUID groupId);
}
