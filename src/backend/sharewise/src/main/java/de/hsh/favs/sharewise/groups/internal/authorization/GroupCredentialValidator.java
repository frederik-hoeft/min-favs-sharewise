package de.hsh.favs.sharewise.groups.internal.authorization;

import de.hsh.favs.sharewise.groups.GroupRole;
import de.hsh.favs.sharewise.shared.infra.claims.AuthorizationCredentialValidator;
import de.hsh.favs.sharewise.shared.infra.claims.CredentialValidationContext;
import de.hsh.favs.sharewise.shared.infra.security.JwtCredentialException;
import de.hsh.favs.sharewise.shared.infra.security.SupplementaryCredentialRejected;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Validates Group-owned structure and rejects conflicting assertions before publication. */
final class GroupCredentialValidator implements AuthorizationCredentialValidator {
    private final GroupMembershipTrust trust;

    GroupCredentialValidator(GroupMembershipTrust trust) { this.trust = trust; }

    @Override public void validate(CredentialValidationContext context) {
        Map<UUID, GroupRole> roles = new HashMap<>();
        for (var candidate : context.credentials()) {
            if (!trust.codec().profile().id().equals(candidate.profile().id())) continue;
            if (!trust.codec().profile().equals(candidate.profile())
                    || !candidate.hasScalarClaim("group_id") || !candidate.hasScalarClaim("role")) {
                throw new SupplementaryCredentialRejected(candidate);
            }
            GroupMembershipEvidence assertion;
            try {
                assertion = GroupMembershipEvidence.parse(candidate.evidence());
            } catch (JwtCredentialException invalid) {
                throw new SupplementaryCredentialRejected(candidate);
            }
            GroupRole previous = roles.putIfAbsent(assertion.groupId(), assertion.role());
            if (previous != null && previous != assertion.role()) {
                throw new SupplementaryCredentialRejected();
            }
        }
    }
}
