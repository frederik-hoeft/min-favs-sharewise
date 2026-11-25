package de.hsh.favs.sharewise.groups.internal.application;

import de.hsh.favs.sharewise.groups.internal.domain.MetadataViolation;
import java.util.List;

/** Expected, typed outcomes that end a Group operation without changing persistent state. */
public sealed interface GroupFailure {

    /** The submitted metadata violates one or more field rules. */
    record InvalidMetadata(List<MetadataViolation> violations) implements GroupFailure {
        public InvalidMetadata {
            violations = List.copyOf(violations);
            if (violations.isEmpty()) {
                throw new IllegalArgumentException("violations must not be empty");
            }
        }
    }

    /** The authenticated caller is not present in the local {@code groups.users} registry. */
    record UnknownUser() implements GroupFailure { }

    /** The group does not exist or the caller is not a member; both are indistinguishable. */
    record GroupNotFound() implements GroupFailure { }

    /** The caller is a member but the operation requires the {@code admin} role. */
    record AdministratorRequired() implements GroupFailure { }

    /** The caller's expected group version is not the current version. */
    record VersionConflict() implements GroupFailure { }

    record MemberNotFound() implements GroupFailure { }
    record LastMember() implements GroupFailure { }
    record LastAdministrator() implements GroupFailure { }
    record InvalidTransfer() implements GroupFailure { }
}
