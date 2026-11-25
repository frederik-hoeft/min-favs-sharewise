package de.hsh.favs.sharewise.groups.api.v1;

import de.hsh.favs.sharewise.groups.internal.application.GroupFailure;
import de.hsh.favs.sharewise.groups.internal.application.group.CreateGroupCommand;
import de.hsh.favs.sharewise.groups.internal.application.group.GroupCreationService;
import de.hsh.favs.sharewise.groups.internal.application.group.GroupDetails;
import de.hsh.favs.sharewise.groups.internal.application.group.GroupMetadataService;
import de.hsh.favs.sharewise.groups.internal.application.group.GroupQueryService;
import de.hsh.favs.sharewise.groups.internal.application.group.GroupSummary;
import de.hsh.favs.sharewise.groups.internal.application.group.GroupWorkspace;
import de.hsh.favs.sharewise.groups.internal.application.group.ReplaceGroupMetadataCommand;
import de.hsh.favs.sharewise.shared.infra.di.OperationIngressDispatcher;
import de.hsh.favs.sharewise.shared.infra.web.ApiAccess;
import de.hsh.favs.sharewise.shared.result.Result;
import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Group creation, member-scoped reads, metadata replacement.
 *
 * <p>Route admission requires a verified primary IAM bearer before a handler runs. Each handler
 * dispatches one operation through the request's ingress, so the operation receives the verified
 * caller as {@code IdentityContext}; caller admission and every Group authorization decision stay
 * in the application services. The controller only converts requests and maps results.
 */
@RestController
@ApiAccess
@RequestMapping(GroupApiPaths.GROUPS)
final class GroupsController {

    private final OperationIngressDispatcher ingress;

    GroupsController(OperationIngressDispatcher ingress) {
        this.ingress = Objects.requireNonNull(ingress, "ingress");
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> create(@RequestBody CreateGroupRequest request) {
        CreateGroupCommand command = GroupCommands.valid(GroupCommands.create(request));
        Result<GroupDetails, GroupFailure> result =
            ingress.dispatch(GroupCreationService.class, service -> service.create(command));
        return result.fold(
            group -> ResponseEntity.created(URI.create(GroupApiPaths.GROUPS + "/" + group.id()))
                .body(GroupDetailsResponse.of(group)),
            GroupProblems::response);
    }

    @GetMapping
    ResponseEntity<?> listMine() {
        Result<List<GroupSummary>, GroupFailure> result =
            ingress.dispatch(GroupQueryService.class, GroupQueryService::listMine);
        return result.fold(
            groups -> ResponseEntity.ok(new GroupListResponse(groups.stream().map(GroupSummaryResponse::of).toList())),
            GroupProblems::response);
    }

    @GetMapping("/{groupId}")
    ResponseEntity<?> get(@PathVariable UUID groupId) {
        Result<GroupDetails, GroupFailure> result =
            ingress.dispatch(GroupQueryService.class, service -> service.get(groupId));
        return result.fold(group -> ResponseEntity.ok(GroupDetailsResponse.of(group)), GroupProblems::response);
    }

    @PutMapping(value = "/{groupId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> replaceMetadata(@PathVariable UUID groupId, @RequestBody ReplaceGroupMetadataRequest request) {
        ReplaceGroupMetadataCommand command = GroupCommands.valid(GroupCommands.replace(groupId, request));
        Result<GroupDetails, GroupFailure> result =
            ingress.dispatch(GroupMetadataService.class, service -> service.replace(command));
        return result.fold(group -> ResponseEntity.ok(GroupDetailsResponse.of(group)), GroupProblems::response);
    }

    @GetMapping("/{groupId}/workspace")
    ResponseEntity<?> workspace(@PathVariable UUID groupId) {
        Result<GroupWorkspace, GroupFailure> result =
            ingress.dispatch(GroupQueryService.class, service -> service.workspace(groupId));
        return result.fold(workspace -> ResponseEntity.ok(GroupWorkspaceResponse.of(workspace)), GroupProblems::response);
    }

}
