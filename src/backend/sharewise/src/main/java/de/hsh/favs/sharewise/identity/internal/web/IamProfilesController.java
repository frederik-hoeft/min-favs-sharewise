package de.hsh.favs.sharewise.identity.internal.web;

import de.hsh.favs.sharewise.identity.internal.profile.ProfileLookup;
import de.hsh.favs.sharewise.identity.internal.profile.ProfileLookupService;
import de.hsh.favs.sharewise.identity.internal.profile.PublicProfile;
import de.hsh.favs.sharewise.shared.infra.di.OperationIngressDispatcher;
import de.hsh.favs.sharewise.shared.infra.web.ApiAccess;
import de.hsh.favs.sharewise.shared.infra.web.RequestValidationException;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ApiAccess(ApiAccess.Mode.AUTHENTICATED_IAM)
@RequestMapping("/api/iam/v1/profiles")
@ConditionalOnProperty(prefix = "sharewise.iam.auth", name = "enabled", havingValue = "true")
final class IamProfilesController {
    private final OperationIngressDispatcher ingress;
    IamProfilesController(OperationIngressDispatcher ingress) { this.ingress = ingress; }
    record LookupRequest(List<String> userIds) { }
    record LookupResponse(List<PublicProfile> profiles) { }
    @PostMapping(value = "/lookup", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<LookupResponse> lookup(@RequestBody LookupRequest request) {
        ProfileLookup command = ProfileLookup.tryFrom(request == null ? null : request.userIds()).fold(
                validated -> validated, errors -> { throw RequestValidationException.from(errors); });
        List<PublicProfile> profiles = ingress.dispatch(ProfileLookupService.class, service -> service.lookup(command));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new LookupResponse(profiles));
    }
}
