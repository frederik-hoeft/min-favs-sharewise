package de.hsh.favs.sharewise.groups.api.v1;

import de.hsh.favs.sharewise.groups.GroupAuthorizationVerificationFailure;
import de.hsh.favs.sharewise.groups.internal.application.GroupFailure;
import de.hsh.favs.sharewise.groups.internal.authorization.GroupAttestationIssuer;
import de.hsh.favs.sharewise.groups.internal.authorization.IssuedGroupAttestation;
import de.hsh.favs.sharewise.shared.infra.di.OperationIngressDispatcher;
import de.hsh.favs.sharewise.shared.infra.web.ApiAccess;
import de.hsh.favs.sharewise.shared.infra.web.ApiProblems;
import de.hsh.favs.sharewise.shared.infra.web.AttestationIssueRequest;
import de.hsh.favs.sharewise.shared.infra.web.AttestationIssueResponse;
import de.hsh.favs.sharewise.shared.infra.web.AttestationProblems;
import de.hsh.favs.sharewise.shared.infra.web.AttestationTransport;
import de.hsh.favs.sharewise.shared.infra.web.RequestValidationException;
import de.hsh.favs.sharewise.shared.result.Result;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Standard domain-principal protocol; the application owns issuance and membership decisions. */
@RestController
@ApiAccess
@RequestMapping(GroupApiPaths.BASE + "/domain-principal")
final class GroupDomainPrincipalController {
    private final OperationIngressDispatcher ingress;

    GroupDomainPrincipalController(OperationIngressDispatcher ingress) {
        this.ingress = Objects.requireNonNull(ingress);
    }

    @PostMapping(value = "/attestations", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> issue(@RequestBody AttestationIssueRequest request,
            @RequestHeader(name = AttestationTransport.HEADER, required = false) String supplementary) {
        if (supplementary != null) return AttestationProblems.invalid(OptionalInt.empty());
        if (!"membership".equals(request.kind())) {
            throw RequestValidationException.ofSingleField("kind", "invalid_kind", "Unsupported assertion kind.");
        }
        UUID groupId = scope(request.scope());
        Result<IssuedGroupAttestation, GroupAuthorizationVerificationFailure> result =
            ingress.dispatch(GroupAttestationIssuer.class, issuer -> issuer.issue(groupId));
        return result.fold(issued -> ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new AttestationIssueResponse(issued.attestation(), issued.expiresAt())),
            GroupDomainPrincipalController::failure);
    }

    private static UUID scope(String scope) {
        if (scope != null && scope.length() == 36) {
            try {
                UUID id = UUID.fromString(scope);
                if (id.toString().equals(scope)) return id;
            } catch (IllegalArgumentException ignored) { }
        }
        throw RequestValidationException.ofSingleField("scope", "invalid_scope", "Expected a canonical group UUID.");
    }

    private static ResponseEntity<?> failure(GroupAuthorizationVerificationFailure failure) {
        return switch (failure) {
            case UNKNOWN_USER -> noStore(GroupProblems.response(new GroupFailure.UnknownUser()));
            case INELIGIBLE -> noStore(GroupProblems.response(new GroupFailure.GroupNotFound()));
            case UNAVAILABLE -> noStore(ApiProblems.response(HttpStatus.SERVICE_UNAVAILABLE, "attestation_unavailable",
                "Attestation Unavailable", "A current membership attestation could not be issued."));
        };
    }

    private static ResponseEntity<?> noStore(ResponseEntity<?> response) {
        return ResponseEntity.status(response.getStatusCode()).headers(response.getHeaders())
            .cacheControl(CacheControl.noStore()).body(response.getBody());
    }
}
