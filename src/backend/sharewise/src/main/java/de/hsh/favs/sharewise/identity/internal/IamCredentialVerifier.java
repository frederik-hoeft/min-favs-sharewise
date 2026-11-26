package de.hsh.favs.sharewise.identity.internal;

import de.hsh.favs.sharewise.shared.infra.security.JwtCredentialCodec;
import de.hsh.favs.sharewise.shared.infra.security.JwtCredentialException;
import de.hsh.favs.sharewise.shared.infra.security.JwtCredentialProfile;
import de.hsh.favs.sharewise.shared.infra.security.VerifiedJwtCredentialCandidate;
import java.util.Objects;
import java.util.UUID;
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

    public VerifiedJwtCredentialCandidate verify(String token) {
        JwtCredentialCodec codec = requireCodec();
        VerifiedJwtCredentialCandidate candidate = codec.verifyPrimary(token);
        requireIamPrimary(candidate, codec.profile());
        return candidate;
    }

    /** Recheck IAM-specific shape/profile for non-HTTP inputs without redoing cryptography. */
    void requireIamPrimary(VerifiedJwtCredentialCandidate candidate) {
        requireIamPrimary(candidate, requireCodec().profile());
    }

    private static void requireIamPrimary(VerifiedJwtCredentialCandidate candidate, JwtCredentialProfile profile) {
        if (!profile.equals(candidate.profile())) {
            throw new JwtCredentialException();
        }
        try {
            parseSessionId(candidate.sessionId());
        } catch (IllegalStateException exception) {
            throw new JwtCredentialException();
        }
    }

    static UUID parseSessionId(String value) {
        try {
            UUID id = UUID.fromString(value);
            if (id.toString().equals(value)) {
                return id;
            }
        } catch (IllegalArgumentException invalid) {
            // A verified JWT may contain a non-UUID sid; it is not a valid IAM session identity.
        }
        throw new IllegalStateException("Invalid primary IAM session evidence");
    }
}
