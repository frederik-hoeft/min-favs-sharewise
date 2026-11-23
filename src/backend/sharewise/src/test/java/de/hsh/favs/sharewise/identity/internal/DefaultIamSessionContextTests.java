package de.hsh.favs.sharewise.identity.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.hsh.favs.sharewise.identity.UnauthenticatedCallerException;
import de.hsh.favs.sharewise.identity.internal.auth.VerifiedIamSession;
import de.hsh.favs.sharewise.shared.infra.security.VerifiedJwtCredential;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** IAM-private session proof is derived from ingress evidence rather than request input. */
class DefaultIamSessionContextTests {

    @Test
    void anonymousOperationsHaveNoPasswordChangeSession() {
        IamOperationSecurityConfiguration ingress = mock(IamOperationSecurityConfiguration.class);
        when(ingress.primary()).thenReturn(Optional.empty());
        assertThrows(UnauthenticatedCallerException.class,
                () -> new DefaultIamSessionContext(ingress).requireSession());
    }

    @Test
    void verifiedPrimaryProvidesCanonicalSubjectAndSessionTogether() {
        UUID user = UUID.fromString("019ab800-0000-7000-8000-000000000201");
        UUID session = UUID.fromString("019ab800-0000-7000-8000-000000000202");
        IamOperationSecurityConfiguration ingress = ingressWithSession(user, session.toString());
        assertEquals(new VerifiedIamSession(user, session),
                new DefaultIamSessionContext(ingress).requireSession());
    }

    @Test
    void malformedAndNoncanonicalPrimarySessionIdsFailClosed() {
        UUID user = UUID.fromString("019ab800-0000-7000-8000-000000000201");
        assertThrows(IllegalStateException.class,
                () -> new DefaultIamSessionContext(ingressWithSession(user, "not-a-uuid")).requireSession());
        assertThrows(IllegalStateException.class,
                () -> new DefaultIamSessionContext(ingressWithSession(user,
                        "019AB800-0000-7000-8000-000000000202")).requireSession());
    }

    private static IamOperationSecurityConfiguration ingressWithSession(UUID userId, String sessionId) {
        IamOperationSecurityConfiguration ingress = mock(IamOperationSecurityConfiguration.class);
        // Mocked only at the trusted ingress seam. A regular application DTO cannot
        // manufacture VerifiedJwtCredential; real signature validation remains ingress-owned.
        VerifiedJwtCredential credential = mock(VerifiedJwtCredential.class);
        when(credential.userId()).thenReturn(userId);
        when(credential.sessionId()).thenReturn(sessionId);
        when(ingress.primary()).thenReturn(Optional.of(credential));
        return ingress;
    }
}
