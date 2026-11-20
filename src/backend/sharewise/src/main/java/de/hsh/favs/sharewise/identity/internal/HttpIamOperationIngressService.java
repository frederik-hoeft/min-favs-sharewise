package de.hsh.favs.sharewise.identity.internal;

import de.hsh.favs.sharewise.identity.web.HttpIamOperationIngress;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Objects;
import java.util.function.Function;
import org.springframework.stereotype.Component;

/** Binds middleware-established, immutable request evidence to the operation. */
@Component
final class HttpIamOperationIngressService implements HttpIamOperationIngress {

    private final IamOperationIngressService ingress;

    HttpIamOperationIngressService(IamOperationIngressService ingress) {
        this.ingress = ingress;
    }

    @Override
    public <S, R> R dispatch(HttpServletRequest request, Class<S> entryType, Function<S, R> action) {
        Objects.requireNonNull(request);
        Object attribute = request.getAttribute(IamBearerIngressFilter.VERIFIED_INGRESS_ATTRIBUTE);
        if (!(attribute instanceof VerifiedIdentityIngress verified)) {
            throw new IllegalStateException("Trusted IAM HTTP middleware did not process this request");
        }
        return ingress.dispatch(verified, entryType, action);
    }
}
