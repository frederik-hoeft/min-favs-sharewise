package de.hsh.favs.sharewise.groups.api.v1;

import de.hsh.favs.sharewise.groups.internal.application.GroupFailure;
import de.hsh.favs.sharewise.groups.internal.application.invitation.CreateInvitationCommand;
import de.hsh.favs.sharewise.groups.internal.application.invitation.CreatedInvitation;
import de.hsh.favs.sharewise.groups.internal.application.invitation.GroupInvitationService;
import de.hsh.favs.sharewise.groups.internal.application.invitation.InvitationRedemption;
import de.hsh.favs.sharewise.groups.internal.application.invitation.InvitationSummary;
import de.hsh.favs.sharewise.shared.infra.di.OperationIngressDispatcher;
import de.hsh.favs.sharewise.shared.infra.web.ApiAccess;
import de.hsh.favs.sharewise.shared.infra.web.RequestValidationException;
import de.hsh.favs.sharewise.shared.result.Result;
import de.hsh.favs.sharewise.shared.validation.ValidationErrors;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Invitation administration and explicit bearer redemption. */
@RestController
@ApiAccess
@RequestMapping(GroupApiPaths.BASE)
final class GroupInvitationController {
    private static final String INVITATIONS = "/groups/{groupId}/invitations";
    private final OperationIngressDispatcher ingress;

    GroupInvitationController(OperationIngressDispatcher ingress) {
        this.ingress = Objects.requireNonNull(ingress);
    }

    @GetMapping(INVITATIONS)
    ResponseEntity<?> list(@PathVariable UUID groupId) {
        return ingress.dispatch(GroupInvitationService.class, service -> service.list(groupId))
            .fold(items -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new InvitationListResponse(items.stream().map(InvitationResponse::of).toList())),
                GroupProblems::response);
    }

    @PostMapping(value = INVITATIONS, consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> create(@PathVariable UUID groupId, @RequestBody CreateInvitationRequest request) {
        CreateInvitationCommand command = new CreateInvitationCommand(groupId, parseExpiry(request.expiresAt()), request.maxUses());
        Result<CreatedInvitation, GroupFailure> result = ingress.dispatch(GroupInvitationService.class,
            service -> service.create(command));
        return result.fold(created -> ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
            .body(new CreatedInvitationResponse(InvitationResponse.of(created.invitation()), created.token().value())), GroupProblems::response);
    }

    @DeleteMapping(INVITATIONS + "/{invitationId}")
    ResponseEntity<?> revoke(@PathVariable UUID groupId, @PathVariable UUID invitationId) {
        return ingress.dispatch(GroupInvitationService.class, service -> service.revoke(groupId, invitationId))
            .fold(status -> ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build(), GroupProblems::response);
    }

    @PostMapping(value = "/invitations/preview", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> preview(@RequestBody InvitationTokenRequest request) {
        return ingress.dispatch(GroupInvitationService.class, service -> service.preview(request.token()))
            .fold(preview -> ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new InvitationPreviewResponse(preview.groupName())), GroupProblems::response);
    }

    @PostMapping(value = "/invitations/redeem", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> redeem(@RequestBody InvitationTokenRequest request) {
        Result<InvitationRedemption, GroupFailure> result = ingress.dispatch(GroupInvitationService.class,
            service -> service.redeem(request.token()));
        return result.fold(redeemed -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            new RedeemInvitationResponse(redeemed.groupId(), switch (redeemed.outcome()) {
                case JOINED -> "joined";
                case ALREADY_MEMBER -> "already_member";
            })), GroupProblems::response);
    }

    private static Instant parseExpiry(String value) {
        if (value == null) return null;
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException invalid) {
            throw RequestValidationException.from(ValidationErrors.single("expiresAt", "invalid",
                "Select a valid ISO expiration instant."));
        }
    }

    record CreateInvitationRequest(String expiresAt, Integer maxUses) { }
    record InvitationListResponse(List<InvitationResponse> items) { }
    record InvitationResponse(UUID id, Instant createdAt, Instant expiresAt, Integer maxUses, int useCount) {
        static InvitationResponse of(InvitationSummary summary) {
            return new InvitationResponse(summary.id(), summary.createdAt(), summary.expiresAt(),
                summary.maxUses(), summary.useCount());
        }
    }
    record InvitationPreviewResponse(String groupName) { }
    record RedeemInvitationResponse(UUID groupId, String outcome) { }

    record InvitationTokenRequest(String token) {
        @Override public String toString() { return "InvitationTokenRequest[redacted]"; }
    }

    record CreatedInvitationResponse(InvitationResponse invitation, String token) {
        @Override public String toString() { return "CreatedInvitationResponse[redacted]"; }
    }
}
