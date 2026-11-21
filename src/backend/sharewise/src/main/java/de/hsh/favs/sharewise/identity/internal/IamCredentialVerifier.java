package de.hsh.favs.sharewise.identity.internal;

import de.hsh.favs.sharewise.shared.infra.security.JwtCredentialCodec;
import de.hsh.favs.sharewise.shared.infra.security.JwtCredentialProfile;
import de.hsh.favs.sharewise.shared.infra.security.VerifiedJwtCredential;
import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.stereotype.Component;

/** Trusted IAM profile selection; production keys/codecs are provisioned by later IAM configuration. */
@Component
public final class IamCredentialVerifier {
    /** Maximum compact IAM access JWT length permitted by the HTTP transport contract. */
    public static final int MAX_HTTP_ACCESS_TOKEN_LENGTH = 4096;
    private final ObjectProvider<JwtCredentialCodec> codecs;
    private final ConfigurableListableBeanFactory beans;

    IamCredentialVerifier(
        @Qualifier("iamPrimaryJwtCredentialCodec") ObjectProvider<JwtCredentialCodec> codecs,
        ConfigurableListableBeanFactory beans
    ) {
        this.codecs = Objects.requireNonNull(codecs);
        this.beans = Objects.requireNonNull(beans);
    }

    public JwtCredentialCodec requireCodec() {
        // Check presence without resolving operation-scoped beans outside their scope.
        if (beans.getBeanNamesForType(IamPrimaryCredentialProvider.class, true, false).length != 1) {
            throw new IllegalStateException("Exactly one IAM credential provider must be configured");
        }
        JwtCredentialCodec codec = codecs.getIfAvailable();
        if (codec == null || codec.profile().kind() != JwtCredentialProfile.Kind.PRIMARY
            || codec.profile().maximumTokenLength() > MAX_HTTP_ACCESS_TOKEN_LENGTH) {
            throw new IllegalStateException("A trusted primary IAM JWT profile within the HTTP size budget must be configured");
        }
        return codec;
    }

    public VerifiedJwtCredential verify(String token) {
        return requireCodec().verifyPrimary(token);
    }
}
