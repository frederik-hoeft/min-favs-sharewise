package de.hsh.favs.sharewise.identity.internal;

import de.hsh.favs.sharewise.identity.IamOperationIngress;
import de.hsh.favs.sharewise.shared.infra.di.OperationDispatcher;
import java.util.Objects;
import java.util.function.Function;
import org.springframework.stereotype.Component;

/** Single ingress registration path shared by HTTP and non-HTTP callers. */
@Component
final class IamOperationIngressService implements IamOperationIngress {

    private final OperationDispatcher dispatcher;
    private final IamCredentialVerifier verifier;

    IamOperationIngressService(OperationDispatcher dispatcher, IamCredentialVerifier verifier) {
        this.dispatcher = Objects.requireNonNull(dispatcher);
        this.verifier = Objects.requireNonNull(verifier);
    }

    @Override
    public <S, R> R dispatchAnonymous(Class<S> entryType, Function<S, R> action) {
        verifier.requireCodec(); // Missing trusted issuer configuration must not silently allow anonymous execution.
        return dispatch(VerifiedIdentityIngress.anonymous(), entryType, action);
    }

    @Override
    public <S, R> R dispatchBearer(String token, Class<S> entryType, Function<S, R> action) {
        return dispatch(verify(token), entryType, action);
    }

    void ensureConfigured() {
        verifier.requireCodec();
    }

    VerifiedIdentityIngress verify(String token) {
        return VerifiedIdentityIngress.authenticated(verifier.verify(token));
    }

    <S, R> R dispatch(VerifiedIdentityIngress input, Class<S> entryType, Function<S, R> action) {
        Objects.requireNonNull(input);
        return dispatcher.dispatch(inputs -> inputs.add(VerifiedIdentityIngress.class, input), entryType, action);
    }
}
