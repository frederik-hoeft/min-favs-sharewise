package de.hsh.favs.sharewise.groups.api.v1;

import de.hsh.favs.sharewise.groups.internal.application.CreateGroupCommand;
import de.hsh.favs.sharewise.groups.internal.application.GroupCreationService;
import de.hsh.favs.sharewise.groups.internal.application.GroupDetails;
import de.hsh.favs.sharewise.groups.internal.application.GroupFailure;
import de.hsh.favs.sharewise.groups.internal.application.GroupMetadataService;
import de.hsh.favs.sharewise.groups.internal.application.GroupQueryService;
import de.hsh.favs.sharewise.groups.internal.application.GroupSummary;
import de.hsh.favs.sharewise.groups.internal.application.ReplaceGroupMetadataCommand;
import de.hsh.favs.sharewise.shared.infra.di.OperationIngressDispatcher;
import de.hsh.favs.sharewise.shared.infra.web.ApiAccess;
import de.hsh.favs.sharewise.shared.infra.web.RequestValidationException;
import de.hsh.favs.sharewise.shared.result.Result;
import de.hsh.favs.sharewise.shared.validation.ValidationErrors;
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
 * Group creation, the caller's group list, group details and metadata replacement.
 *
 * <p>Route admission requires a verified primary IAM bearer before a handler runs. Each handler
 * dispatches one operation through the request's ingress, so the operation receives the verified
 * caller as {@code IdentityContext}; caller admission and every Group authorization decision stay
 * in the application services. The controller only converts requests and maps results.
 */
@RestController
@ApiAccess
@RequestMapping(GroupsController.GROUPS_PATH)
final class GroupsController {

    static final String GROUPS_PATH = "/api/groups/v1/groups";

    private final OperationIngressDispatcher ingress;

    GroupsController(OperationIngressDispatcher ingress) {
        this.ingress = Objects.requireNonNull(ingress, "ingress");
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> create(@RequestBody CreateGroupRequest request) {
        CreateGroupCommand command = valid(GroupCommands.create(request));
        Result<GroupDetails, GroupFailure> result =
            ingress.dispatch(GroupCreationService.class, service -> service.create(command));
        return result.fold(
            group -> ResponseEntity.created(URI.create(GROUPS_PATH + "/" + group.id()))
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
        ReplaceGroupMetadataCommand command = valid(GroupCommands.replace(groupId, request));
        Result<GroupDetails, GroupFailure> result =
            ingress.dispatch(GroupMetadataService.class, service -> service.replace(command));
        return result.fold(group -> ResponseEntity.ok(GroupDetailsResponse.of(group)), GroupProblems::response);
    }

    private static <T> T valid(Result<T, ValidationErrors> input) {
        return input.fold(value -> value, errors -> {
            throw RequestValidationException.from(errors);
        });
    }
}
