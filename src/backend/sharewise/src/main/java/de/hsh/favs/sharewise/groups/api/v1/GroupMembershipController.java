package de.hsh.favs.sharewise.groups.api.v1;

import de.hsh.favs.sharewise.groups.internal.application.GroupFailure;
import de.hsh.favs.sharewise.groups.internal.application.membership.GroupMembershipService;
import de.hsh.favs.sharewise.groups.internal.application.membership.MembershipChange;
import de.hsh.favs.sharewise.groups.internal.application.membership.MembershipChangeCommand;
import de.hsh.favs.sharewise.groups.internal.application.membership.MembershipChangeStatus;
import de.hsh.favs.sharewise.shared.infra.di.OperationIngressDispatcher;
import de.hsh.favs.sharewise.shared.infra.web.ApiAccess;
import de.hsh.favs.sharewise.shared.result.Result;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


/** HTTP adapter for membership administration. */
@RestController
@ApiAccess
@RequestMapping(GroupApiPaths.GROUPS)
final class GroupMembershipController {
    private final OperationIngressDispatcher ingress;

    GroupMembershipController(OperationIngressDispatcher ingress) {
        this.ingress = Objects.requireNonNull(ingress, "ingress");
    }

    @PutMapping(value = "/{groupId}/members/{membershipId}/role", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> setRole(@PathVariable UUID groupId, @PathVariable UUID membershipId,
            @RequestBody SetMemberRoleRequest request) {
        return change(GroupCommands.valid(GroupCommands.setRole(groupId, membershipId, request)));
    }

    @DeleteMapping("/{groupId}/members/{membershipId}")
    ResponseEntity<?> remove(@PathVariable UUID groupId, @PathVariable UUID membershipId) {
        return change(new MembershipChangeCommand(groupId, membershipId, new MembershipChange.Remove()));
    }

    private ResponseEntity<?> change(MembershipChangeCommand command) {
        Result<MembershipChangeStatus, GroupFailure> result = ingress.dispatch(GroupMembershipService.class,
            service -> service.change(command));
        return result.fold(status -> ResponseEntity.noContent().build(), GroupProblems::response);
    }

}
