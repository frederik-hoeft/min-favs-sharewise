package de.hsh.favs.sharewise.groups.api.v1;

import de.hsh.favs.sharewise.groups.internal.application.GroupFailure;
import de.hsh.favs.sharewise.groups.internal.domain.MetadataViolation;
import de.hsh.favs.sharewise.shared.infra.web.ApiProblems;
import de.hsh.favs.sharewise.shared.infra.web.RequestValidationException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

/**
 * Maps typed Group failures to the API's problem responses.
 *
 * <p>Every response for a given failure is fixed text without request-specific detail. In
 * particular, a missing group and a group in which the caller is not a member produce the same
 * {@code 404} body, so a response never reveals that a group exists.
 */
final class GroupProblems {

    static final String USER_UNKNOWN = "group_user_unknown";
    static final String NOT_FOUND = "group_not_found";
    static final String ADMIN_REQUIRED = "group_admin_required";
    static final String VERSION_CONFLICT = "group_version_conflict";

    private GroupProblems() {
    }

    /**
     * The problem response for a failure. Invalid metadata becomes the shared request
     * validation failure instead, which the global handler renders with field errors.
     */
    static ResponseEntity<ProblemDetail> response(GroupFailure failure) {
        return switch (failure) {
            case GroupFailure.InvalidMetadata(List<MetadataViolation> violations) ->
                throw RequestValidationException.from(GroupCommands.validationErrors(violations));
            case GroupFailure.UnknownUser() -> ApiProblems.response(HttpStatus.FORBIDDEN, USER_UNKNOWN,
                "Group User Unknown", "This account is not yet available to Group Management.");
            case GroupFailure.GroupNotFound() -> ApiProblems.response(HttpStatus.NOT_FOUND, NOT_FOUND,
                "Group Not Found", "The group does not exist or you are not a member.");
            case GroupFailure.AdministratorRequired() -> ApiProblems.response(HttpStatus.FORBIDDEN, ADMIN_REQUIRED,
                "Group Administrator Required", "Only a group administrator can change the group.");
            case GroupFailure.MemberNotFound() -> ApiProblems.response(HttpStatus.NOT_FOUND, "group_member_not_found",
                "Group Member Not Found", "This membership is no longer available in the group.");
            case GroupFailure.LastMember() -> ApiProblems.response(HttpStatus.CONFLICT, "group_last_member",
                "Last Group Member", "A group must retain at least one member.");
            case GroupFailure.LastAdministrator() -> ApiProblems.response(HttpStatus.CONFLICT, "group_last_administrator",
                "Last Group Administrator", "A group must retain at least one administrator.");
            case GroupFailure.InvalidTransfer() -> ApiProblems.response(HttpStatus.CONFLICT, "group_invalid_transfer",
                "Invalid Administration Transfer", "Select another member to transfer administration.");
            case GroupFailure.InvalidInvitation invalid -> throw RequestValidationException.from(invalid.errors());
            case GroupFailure.InvitationNotFound() -> ApiProblems.response(HttpStatus.NOT_FOUND, "group_invitation_not_found",
                "Invitation Not Found", "This invitation is no longer available in the group.");
            case GroupFailure.InvitationUnavailable() -> ApiProblems.response(HttpStatus.NOT_FOUND, "group_invitation_unavailable",
                "Invitation Unavailable", "This invitation is invalid or no longer available.");
            case GroupFailure.VersionConflict() -> ApiProblems.response(HttpStatus.CONFLICT, VERSION_CONFLICT,
                "Group Version Conflict", "The group has changed since you last read it.");
        };
    }
}
