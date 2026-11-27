package de.hsh.favs.sharewise.groups.internal.authorization;

import de.hsh.favs.sharewise.groups.GroupRole;
import de.hsh.favs.sharewise.shared.infra.claims.AuthorizationCredential;
import de.hsh.favs.sharewise.shared.infra.claims.Claim;
import de.hsh.favs.sharewise.shared.infra.security.JwtCredentialException;
import java.util.UUID;

/** Parses correlated assertions from one credential only; never flattens resource and role across proofs. */
record GroupMembershipEvidence(UUID groupId, GroupRole role) {
    static GroupMembershipEvidence parse(AuthorizationCredential credential) {
        try {
            String scope = single(credential, "group_id");
            UUID groupId = UUID.fromString(scope);
            if (!groupId.toString().equals(scope)) throw new JwtCredentialException();
            return new GroupMembershipEvidence(groupId, GroupRole.valueOf(single(credential, "role")));
        } catch (IllegalArgumentException rejected) {
            throw new JwtCredentialException();
        }
    }

    private static String single(AuthorizationCredential credential, String type) {
        var values = credential.claims().stream().filter(claim -> claim.type().equals(type)).map(Claim::value).toList();
        if (values.size() != 1) throw new JwtCredentialException();
        return values.getFirst();
    }
}
