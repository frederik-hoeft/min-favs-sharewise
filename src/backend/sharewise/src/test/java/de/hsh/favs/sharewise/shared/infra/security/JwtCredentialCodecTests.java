package de.hsh.favs.sharewise.shared.infra.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import de.hsh.favs.sharewise.shared.infra.claims.AuthorizationCredential;
import de.hsh.favs.sharewise.shared.infra.claims.Claim;
import java.net.URI;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.spec.ECGenParameterSpec;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.RSAPrivateKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Real ES256 fixtures exercising the trust boundary rather than only claim parsing. */
class JwtCredentialCodecTests {
    private static final Instant NOW = Instant.parse("2026-11-20T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String IAM_ISSUER = "https://iam.sharewise.test";
    private static final String GROUP_ISSUER = "https://groups.sharewise.test";
    private static final UUID ALICE = UUID.fromString("12345678-1234-4123-8123-1234567890ab");
    private static final UUID BOB = UUID.fromString("87654321-4321-4432-8432-ba0987654321");
    private static final String SESSION = "iam-session-c4e1a87f";

    private static final KeyPair IAM_OLD = ec(1);
    private static final KeyPair IAM_NEW = ec(2);
    private static final KeyPair GROUP_KEY = ec(3);

    private static final JwtCredentialProfile IAM = new JwtCredentialProfile(
        "iam-access", IAM_ISSUER, "sw-iam-access+jwt", Set.of("sharewise-api"),
        JwtCredentialProfile.Kind.PRIMARY, Set.of(), Duration.ofMinutes(5), Duration.ofSeconds(30), 4096);
    private static final JwtCredentialProfile GROUP = new JwtCredentialProfile(
        "group-attestation", GROUP_ISSUER, "sw-group-attestation+jwt", Set.of("sharewise-finance"),
        JwtCredentialProfile.Kind.SUBORDINATE, Set.of(IAM.id()), Duration.ofMinutes(5), Duration.ofSeconds(30), 16_384);

    @Test
    void signsAndVerifiesTypedPrimaryEvidenceWithOriginAndDirectClaimProvenance() {
        JwtCredentialCodec codec = primary(List.of(JwtEcKey.signing("old", IAM_OLD)), "old");
        VerifiedJwtCredentialCandidate verified = codec.verifyPrimary(codec.sign(issue(ALICE, SESSION)));
        AuthorizationCredential credential = verified.evidence();

        assertEquals(ALICE, verified.userId());
        assertEquals(SESSION, verified.sessionId());
        assertEquals(JwtCredentialProfile.Kind.PRIMARY, verified.kind());
        assertEquals(IAM_ISSUER, credential.origin().issuer());
        assertEquals("iam-access", credential.origin().profile());
        assertEquals(Set.of("sharewise-api"), credential.origin().audiences());
        assertEquals(Optional.of(ALICE.toString()), credential.origin().subject());
        assertEquals(Optional.of("test-token-1"), credential.origin().credentialId());
        assertEquals(Optional.of(NOW.minusSeconds(1)), credential.origin().issuedAt());
        assertEquals(Optional.of(NOW.plusSeconds(180)), credential.origin().expiresAt());
        assertEquals("desktop", claim(credential, "device"));
        assertEquals(SESSION, claim(credential, "sid"));
        assertEquals(ALICE.toString(), claim(credential, "sub"));
        assertTrue(credential.claims().stream().allMatch(c -> c.origin() == credential.origin()));
        assertThrows(UnsupportedOperationException.class,
            () -> credential.claims().add(new Claim("role", "admin", credential.origin())));
    }

    @Test
    void explicitKeyOverlapSupportsRotationAndRemovalRejectsOldKey() {
        JwtCredentialCodec oldSigner = primary(List.of(JwtEcKey.signing("old", IAM_OLD)), "old");
        JwtCredentialCodec overlap = primary(List.of(JwtEcKey.verification("old", ecPublic(IAM_OLD)),
            JwtEcKey.signing("new", IAM_NEW)), "new");
        String oldToken = oldSigner.sign(issue(ALICE, SESSION));
        String newToken = overlap.sign(issue(ALICE, SESSION));
        assertEquals("old", header(oldToken).getKeyID());
        assertEquals("new", header(newToken).getKeyID());
        assertEquals(ALICE, overlap.verifyPrimary(oldToken).userId());
        assertEquals(ALICE, overlap.verifyPrimary(newToken).userId());

        JwtCredentialCodec afterRemoval = primary(List.of(JwtEcKey.verification("new", ecPublic(IAM_NEW))), null);
        assertEquals(ALICE, afterRemoval.verifyPrimary(newToken).userId());
        assertRejected(() -> afterRemoval.verifyPrimary(oldToken));
        assertThrows(IllegalStateException.class, () -> afterRemoval.sign(issue(ALICE, SESSION)));
    }

    @Test
    void rejectsDisallowedHeaderAlgorithmsRemoteKeysAndUntrustedKeyIds() throws Exception {
        JwtCredentialCodec codec = primary(List.of(JwtEcKey.signing("old", IAM_OLD)), "old");
        String good = codec.sign(issue(ALICE, SESSION));
        String[] pieces = good.split("\\.");
        String tamperedSignature = pieces[0] + "." + pieces[1] + "."
            + (pieces[2].charAt(0) == 'A' ? 'B' : 'A') + pieces[2].substring(1);
        assertRejected(() -> codec.verifyPrimary(tamperedSignature));
        assertRejected(() -> codec.verifyPrimary(""));
        assertRejected(() -> codec.verifyPrimary(null));
        assertRejected(() -> codec.verifyPrimary("x".repeat(16_385)));
        String noneHeader = b64("{\"alg\":\"none\",\"typ\":\"sw-iam-access+jwt\"}");
        assertRejected(() -> codec.verifyPrimary(noneHeader + "." + pieces[1] + "."));
        String hsHeader = b64("{\"alg\":\"HS256\",\"kid\":\"old\",\"typ\":\"sw-iam-access+jwt\"}");
        assertRejected(() -> codec.verifyPrimary(hsHeader + "." + pieces[1] + "." + pieces[2]));
        assertRejected(() -> codec.verifyPrimary(manual(primaryHeader("not-trusted"), validPayload().build(), IAM_OLD)));
        JWSHeader remote = new JWSHeader.Builder(JWSAlgorithm.ES256)
            .type(new JOSEObjectType(IAM.tokenType())).keyID("old")
            .jwkURL(URI.create("https://evil.example.test/jwks.json")).build();
        assertRejected(() -> codec.verifyPrimary(manual(remote, validPayload().build(), IAM_OLD)));
    }

    @Test
    void enforcesIssuerAudienceProtectedTypeAndExactProfileSelection() {
        JwtCredentialCodec verifier = primary(List.of(JwtEcKey.verification("old", ecPublic(IAM_OLD))), null);
        JwtCredentialProfile wrongIssuer = new JwtCredentialProfile("iam-access", "https://other.test",
            IAM.tokenType(), IAM.audiences(), IAM.kind(), IAM.trustedPrimaryProfiles(), IAM.maximumLifetime(), IAM.clockSkew(), 4096);
        JwtCredentialProfile wrongAudience = new JwtCredentialProfile("iam-access", IAM_ISSUER,
            IAM.tokenType(), Set.of("other-service"), IAM.kind(), IAM.trustedPrimaryProfiles(), IAM.maximumLifetime(), IAM.clockSkew(), 4096);
        JwtCredentialProfile wrongType = new JwtCredentialProfile("iam-access", IAM_ISSUER,
            "sw-refresh+jwt", IAM.audiences(), IAM.kind(), IAM.trustedPrimaryProfiles(), IAM.maximumLifetime(), IAM.clockSkew(), 4096);
        for (JwtCredentialProfile profile : List.of(wrongIssuer, wrongAudience, wrongType)) {
            JwtCredentialCodec signer = new JwtCredentialCodec(profile,
                new Es256JwtSignatureService(List.of(JwtEcKey.signing("old", IAM_OLD)), Optional.of("old")), CLOCK);
            assertRejected(() -> verifier.verifyPrimary(signer.sign(issue(ALICE, SESSION))));
        }
        assertRejected(() -> verifier.verifyPrimary(manual(primaryHeader("old"),
            validPayload().audience(List.of("sharewise-api", "other-service")).build(), IAM_OLD)));
        JwtCredentialCodec groupSigner = subordinate();
        String groupToken = groupSigner.sign(subordinateIssue(ALICE, SESSION, IAM_ISSUER));
        assertRejected(() -> verifier.verifyPrimary(groupToken));
        assertRejected(() -> groupSigner.verifyPrimary(groupToken));
    }

    @Test
    void strictlyChecksExpiryNotBeforeClockSkewAndMaximumLifetime() {
        JwtCredentialCodec codec = primary(List.of(JwtEcKey.signing("old", IAM_OLD)), "old");
        // Clock skew is 30s: token expiring 29s ago passes, 31s ago fails.
        JwtIssueRequest insideSkew = new JwtIssueRequest(ALICE, SESSION, "old",
            NOW.minusSeconds(240), NOW.minusSeconds(240), NOW.minusSeconds(29), Optional.empty(), Map.of());
        JwtIssueRequest outsideSkew = new JwtIssueRequest(ALICE, SESSION, "old",
            NOW.minusSeconds(240), NOW.minusSeconds(240), NOW.minusSeconds(31), Optional.empty(), Map.of());
        assertNotNull(codec.verifyPrimary(codec.sign(insideSkew)));
        assertRejected(() -> codec.verifyPrimary(codec.sign(outsideSkew)));
        JwtIssueRequest notYet = new JwtIssueRequest(ALICE, SESSION, "future",
            NOW.plusSeconds(40), NOW.plusSeconds(40), NOW.plusSeconds(100), Optional.empty(), Map.of());
        assertRejected(() -> codec.verifyPrimary(codec.sign(notYet)));
        JwtIssueRequest tooLong = new JwtIssueRequest(ALICE, SESSION, "too-long",
            NOW, NOW, NOW.plusSeconds(301), Optional.empty(), Map.of());
        assertThrows(IllegalArgumentException.class, () -> codec.sign(tooLong));
        JwtIssueRequest expiredBeforeIssued = new JwtIssueRequest(ALICE, SESSION, "bad",
            NOW, NOW, NOW.minusSeconds(1), Optional.empty(), Map.of());
        assertThrows(IllegalArgumentException.class, () -> codec.sign(expiredBeforeIssued));
        assertRejected(() -> codec.verifyPrimary(manual(primaryHeader("old"),
            validPayload().expirationTime(Date.from(NOW.plusSeconds(301))).build(), IAM_OLD)));
    }

    @Test
    void rejectsMalformedMissingAndWronglyTypedPrincipalAndSessionClaims() throws Exception {
        JwtCredentialCodec codec = primary(List.of(JwtEcKey.verification("old", ecPublic(IAM_OLD))), null);
        assertRejected(() -> codec.verifyPrimary(manual(primaryHeader("old"),
            validPayload().subject("not-a-uuid").build(), IAM_OLD)));
        assertRejected(() -> codec.verifyPrimary(manual(primaryHeader("old"),
            validPayload().subject("1-2-3-4-5").build(), IAM_OLD)));
        assertRejected(() -> codec.verifyPrimary(manual(primaryHeader("old"),
            validPayload().claim("sid", 123).build(), IAM_OLD)));
        assertRejected(() -> codec.verifyPrimary(manual(primaryHeader("old"),
            validPayload().claim("sid", " ").build(), IAM_OLD)));
        assertRejected(() -> codec.verifyPrimary(manual(primaryHeader("old"),
            validPayload().jwtID("").build(), IAM_OLD)));
        assertRejected(() -> codec.verifyPrimary(manual(primaryHeader("old"),
            validPayload().claim("device", Map.of("arbitrary", "object")).build(), IAM_OLD)));
        assertRejected(() -> codec.verifyPrimary(manual(primaryHeader("old"),
            new JWTClaimsSet.Builder().issuer(IAM_ISSUER).subject(ALICE.toString())
                .audience("sharewise-api").issueTime(Date.from(NOW.minusSeconds(1)))
                .expirationTime(Date.from(NOW.plusSeconds(100)))
                .jwtID("missing-nbf").claim("sid", SESSION).build(), IAM_OLD)));
    }

    @Test
    void rejectsInvalidRegisteredTimeTypesAndMissingExpiryOrIssueTime() {
        JwtCredentialCodec codec = primary(List.of(JwtEcKey.verification("old", ecPublic(IAM_OLD))), null);
        assertRejected(() -> codec.verifyPrimary(manual(primaryHeader("old"),
            validPayload().claim("exp", "2026-11-20T12:03:00Z").build(), IAM_OLD)));
        assertRejected(() -> codec.verifyPrimary(manual(primaryHeader("old"),
            validPayload().claim("iat", "2026-11-20T12:00:00Z").build(), IAM_OLD)));
        assertRejected(() -> codec.verifyPrimary(manual(primaryHeader("old"),
            validPayload().claim("nbf", "2026-11-20T12:00:00Z").build(), IAM_OLD)));
        assertRejected(() -> codec.verifyPrimary(manual(primaryHeader("old"),
            validPayload().claim("jti", 42).build(), IAM_OLD)));
        assertRejected(() -> codec.verifyPrimary(manual(primaryHeader("old"),
            new JWTClaimsSet.Builder().issuer(IAM_ISSUER).subject(ALICE.toString())
                .audience("sharewise-api").issueTime(Date.from(NOW.minusSeconds(1)))
                .notBeforeTime(Date.from(NOW.minusSeconds(1)))
                .jwtID("missing-exp").claim("sid", SESSION).build(), IAM_OLD)));
        assertRejected(() -> codec.verifyPrimary(manual(primaryHeader("old"),
            new JWTClaimsSet.Builder().issuer(IAM_ISSUER).subject(ALICE.toString())
                .audience("sharewise-api").expirationTime(Date.from(NOW.plusSeconds(100)))
                .notBeforeTime(Date.from(NOW.minusSeconds(1)))
                .jwtID("missing-iat").claim("sid", SESSION).build(), IAM_OLD)));
    }

    @Test
    void explicitSigningKeyIdIsUsedEvenWhenMultipleTrustedKeysCanSign() {
        JwtCredentialCodec signer = primary(List.of(JwtEcKey.signing("old", IAM_OLD),
            JwtEcKey.signing("new", IAM_NEW)), "new");
        String token = signer.sign(issue(ALICE, SESSION));
        assertEquals("new", header(token).getKeyID());
        assertEquals(ALICE, signer.verifyPrimary(token).userId());
    }

    private static VerifiedJwtCredentialCandidate verifyAndBind(JwtCredentialCodec codec, String token,
                                                                 VerifiedJwtCredentialCandidate primary) {
        VerifiedJwtCredentialCandidate candidate = codec.verify(token);
        JwtPrincipalBinding.requireBound(primary, candidate);
        return candidate;
    }

    @Test
    void subordinateVerificationRequiresMatchingVerifiedPrimaryIssuerUserAndSession() {
        JwtCredentialCodec iam = primary(List.of(JwtEcKey.signing("old", IAM_OLD)), "old");
        JwtCredentialCodec group = subordinate();
        VerifiedJwtCredentialCandidate primary = iam.verifyPrimary(iam.sign(issue(ALICE, SESSION)));
        String matching = group.sign(subordinateIssue(ALICE, SESSION, IAM_ISSUER));
        VerifiedJwtCredentialCandidate valid = group.verify(matching);
        assertEquals(JwtCredentialProfile.Kind.SUBORDINATE, valid.kind());
        assertEquals(GROUP, valid.profile());
        assertEquals(Optional.of(IAM_ISSUER), valid.principalIssuer());
        JwtPrincipalBinding.requireBound(primary, valid);
        assertEquals(GROUP_ISSUER, valid.evidence().origin().issuer());
        assertEquals("group-attestation", valid.evidence().origin().profile());
        assertEquals(ALICE, valid.userId());
        assertEquals(SESSION, valid.sessionId());
        assertEquals("admin", claim(valid.evidence(), "role"));
        assertEquals(IAM_ISSUER, claim(valid.evidence(), "principal_iss"));
        assertEquals(SESSION, claim(valid.evidence(), "principal_sid"));

        assertRejected(() -> verifyAndBind(group, matching, null));
        assertRejected(() -> verifyAndBind(group, group.sign(subordinateIssue(BOB, SESSION, IAM_ISSUER)), primary));
        assertRejected(() -> verifyAndBind(group, group.sign(subordinateIssue(ALICE, "other-session", IAM_ISSUER)), primary));
        assertRejected(() -> verifyAndBind(group, group.sign(subordinateIssue(ALICE, SESSION, "https://other.test")), primary));
        assertRejected(() -> verifyAndBind(group, matching, valid));
        assertRejected(() -> verifyAndBind(iam, iam.sign(issue(ALICE, SESSION)), primary));
    }

    @Test
    void subordinateRequiresTrustedPrimaryProfileButDoesNotRecheckPrimaryExpiry() {
        JwtCredentialCodec iam = primary(List.of(JwtEcKey.signing("old", IAM_OLD)), "old");
        JwtCredentialCodec group = subordinate();
        VerifiedJwtCredentialCandidate verified = iam.verifyPrimary(iam.sign(issue(ALICE, SESSION)));

        JwtCredentialCodec laterGroup = new JwtCredentialCodec(GROUP,
            new Es256JwtSignatureService(List.of(JwtEcKey.verification("group-1", ecPublic(GROUP_KEY))), Optional.empty()),
            Clock.offset(CLOCK, Duration.ofSeconds(250)));
        JwtIssueRequest laterIssue = new JwtIssueRequest(ALICE, SESSION, "later-group",
            NOW.plusSeconds(249), NOW.plusSeconds(249), NOW.plusSeconds(330),
            Optional.of(IAM_ISSUER), Map.of("role", "member"));
        // Both credentials were trusted at their respective admissions. Primary expiry
        // cannot revoke an already admitted workflow's identity between operations.
        assertEquals(ALICE, verifyAndBind(laterGroup, group.sign(laterIssue), verified).userId());
        // The subordinate token still has its own independent admission-time expiry.
        assertRejected(() -> verifyAndBind(laterGroup,
            group.sign(subordinateIssue(ALICE, SESSION, IAM_ISSUER)), verified));

        JwtCredentialProfile otherPrimary = new JwtCredentialProfile("external-identity",
            "https://other-iam.test", IAM.tokenType(), IAM.audiences(),
            JwtCredentialProfile.Kind.PRIMARY, Set.of(), Duration.ofMinutes(5), Duration.ofSeconds(30), 4096);
        JwtCredentialCodec externalIam = new JwtCredentialCodec(otherPrimary,
            new Es256JwtSignatureService(List.of(JwtEcKey.signing("old", IAM_OLD)), Optional.of("old")), CLOCK);
        VerifiedJwtCredentialCandidate externalPrimary = externalIam.verifyPrimary(externalIam.sign(issue(ALICE, SESSION)));
        String matchingForeignIssuer = group.sign(subordinateIssue(ALICE, SESSION, otherPrimary.issuer()));
        assertRejected(() -> verifyAndBind(group, matchingForeignIssuer, externalPrimary));
    }

    @Test
    void subordinateRejectsOtherPrimaryProfileEvenFromTheSameTrustedIssuer() {
        JwtCredentialProfile specialPurpose = new JwtCredentialProfile("iam-special-purpose",
            IAM_ISSUER, "sw-iam-special+jwt", Set.of("sharewise-api"),
            JwtCredentialProfile.Kind.PRIMARY, Set.of(), Duration.ofMinutes(5), Duration.ofSeconds(30), 4096);
        JwtCredentialCodec otherCodec = new JwtCredentialCodec(specialPurpose,
            new Es256JwtSignatureService(List.of(JwtEcKey.signing("old", IAM_OLD)), Optional.of("old")), CLOCK);
        VerifiedJwtCredentialCandidate otherPrimary = otherCodec.verifyPrimary(otherCodec.sign(issue(ALICE, SESSION)));
        assertEquals(IAM_ISSUER, otherPrimary.evidence().origin().issuer());
        assertEquals("iam-special-purpose", otherPrimary.evidence().origin().profile());

        JwtCredentialCodec group = subordinate();
        String attestation = group.sign(subordinateIssue(ALICE, SESSION, IAM_ISSUER));
        assertRejected(() -> verifyAndBind(group, attestation, otherPrimary));
    }

    @Test
    void subordinateCanExplicitlyTrustMultipleQualifiedPrimaryProfiles() {
        JwtCredentialProfile specialPurpose = new JwtCredentialProfile("iam-special-purpose",
            IAM_ISSUER, "sw-iam-special+jwt", Set.of("sharewise-api"),
            JwtCredentialProfile.Kind.PRIMARY, Set.of(), Duration.ofMinutes(5), Duration.ofSeconds(30), 4096);
        JwtCredentialCodec otherCodec = new JwtCredentialCodec(specialPurpose,
            new Es256JwtSignatureService(List.of(JwtEcKey.signing("old", IAM_OLD)), Optional.of("old")), CLOCK);
        VerifiedJwtCredentialCandidate otherPrimary = otherCodec.verifyPrimary(otherCodec.sign(issue(ALICE, SESSION)));

        JwtCredentialProfile groupWithTwoPrimaries = new JwtCredentialProfile(
            GROUP.name(), GROUP.issuer(), GROUP.tokenType(), GROUP.audiences(), GROUP.kind(),
            Set.of(IAM.id(), specialPurpose.id()), GROUP.maximumLifetime(), GROUP.clockSkew(), 16_384);
        JwtCredentialCodec group = new JwtCredentialCodec(groupWithTwoPrimaries,
            new Es256JwtSignatureService(List.of(JwtEcKey.signing("group-1", GROUP_KEY)),
                Optional.of("group-1")), CLOCK);
        String attestation = group.sign(subordinateIssue(ALICE, SESSION, IAM_ISSUER));
        assertEquals(ALICE, verifyAndBind(group, attestation, otherPrimary).userId());
    }

    @Test
    void validatesIssuerQualifiedTrustedPrimaryProfileConfiguration() {
        assertEquals(new JwtCredentialProfile.Id(IAM_ISSUER, "iam-access"), IAM.id());
        assertThrows(IllegalArgumentException.class, () -> new JwtCredentialProfile.Id(" ", "iam-access"));
        assertThrows(IllegalArgumentException.class, () -> new JwtCredentialProfile.Id(IAM_ISSUER, " "));
        assertThrows(IllegalArgumentException.class, () -> new JwtCredentialProfile(
            GROUP.name(), GROUP.issuer(), GROUP.tokenType(), GROUP.audiences(), GROUP.kind(),
            Set.of(), GROUP.maximumLifetime(), GROUP.clockSkew(), 16_384));
        assertThrows(IllegalArgumentException.class, () -> new JwtCredentialProfile(
            IAM.name(), IAM.issuer(), IAM.tokenType(), IAM.audiences(), IAM.kind(),
            Set.of(IAM.id()), IAM.maximumLifetime(), IAM.clockSkew(), 4096));
    }

    @Test
    void primaryIssuerLengthIsCompatibleWithSubordinateBinding() {
        String issuerPrefix = "https://iam.sharewise.test/";
        String maximumIssuer = issuerPrefix + "x".repeat(256 - issuerPrefix.length());
        String oversizedIssuer = maximumIssuer + "x";

        assertThrows(IllegalArgumentException.class,
            () -> new JwtCredentialProfile.Id(oversizedIssuer, IAM.name()));
        assertThrows(IllegalArgumentException.class, () -> new JwtCredentialProfile(
            IAM.name(), oversizedIssuer, IAM.tokenType(), IAM.audiences(), IAM.kind(),
            IAM.trustedPrimaryProfiles(), IAM.maximumLifetime(), IAM.clockSkew(), IAM.maximumTokenLength()));
        assertThrows(IllegalArgumentException.class, () -> subordinateIssue(ALICE, SESSION, oversizedIssuer));

        JwtCredentialProfile primaryProfile = new JwtCredentialProfile(
            IAM.name(), maximumIssuer, IAM.tokenType(), IAM.audiences(), IAM.kind(),
            IAM.trustedPrimaryProfiles(), IAM.maximumLifetime(), IAM.clockSkew(), IAM.maximumTokenLength());
        JwtCredentialProfile subordinateProfile = new JwtCredentialProfile(
            GROUP.name(), GROUP.issuer(), GROUP.tokenType(), GROUP.audiences(), GROUP.kind(),
            Set.of(primaryProfile.id()), GROUP.maximumLifetime(), GROUP.clockSkew(), GROUP.maximumTokenLength());
        JwtCredentialCodec iam = new JwtCredentialCodec(primaryProfile,
            new Es256JwtSignatureService(List.of(JwtEcKey.signing("old", IAM_OLD)), Optional.of("old")), CLOCK);
        JwtCredentialCodec group = new JwtCredentialCodec(subordinateProfile,
            new Es256JwtSignatureService(List.of(JwtEcKey.signing("group-1", GROUP_KEY)),
                Optional.of("group-1")), CLOCK);
        VerifiedJwtCredentialCandidate verifiedPrimary = iam.verifyPrimary(iam.sign(issue(ALICE, SESSION)));
        VerifiedJwtCredentialCandidate verifiedSubordinate = verifyAndBind(group,
            group.sign(subordinateIssue(ALICE, SESSION, maximumIssuer)), verifiedPrimary);

        assertEquals(maximumIssuer, verifiedPrimary.evidence().origin().issuer());
        assertEquals(maximumIssuer, claim(verifiedSubordinate.evidence(), "principal_iss"));
        assertEquals(ALICE, verifiedSubordinate.userId());
    }

    @Test
    void profileSpecificCompactJwtLimitAppliesSymmetricallyToIssuanceAndVerification() {
        JwtCredentialCodec standard = primary(List.of(JwtEcKey.signing("old", IAM_OLD)), "old");
        JwtCredentialProfile largeProfile = new JwtCredentialProfile(
            IAM.name(), IAM.issuer(), IAM.tokenType(), IAM.audiences(), IAM.kind(),
            IAM.trustedPrimaryProfiles(), IAM.maximumLifetime(), IAM.clockSkew(), 16_384);
        JwtCredentialCodec largeCodec = new JwtCredentialCodec(largeProfile,
            new Es256JwtSignatureService(List.of(JwtEcKey.signing("old", IAM_OLD)), Optional.of("old")), CLOCK);
        JwtIssueRequest oversized = new JwtIssueRequest(ALICE, SESSION, "oversized", NOW.minusSeconds(1),
            NOW.minusSeconds(1), NOW.plusSeconds(100), Optional.empty(), Map.of("data", "x".repeat(6300)));
        String largeToken = largeCodec.sign(oversized);
        assertTrue(largeToken.length() > 8192);
        assertTrue(largeToken.length() < 16_384);
        assertEquals(ALICE, largeCodec.verifyPrimary(largeToken).userId());
        assertThrows(IllegalArgumentException.class, () -> standard.sign(oversized));
        assertRejected(() -> standard.verifyPrimary(largeToken));
        assertThrows(IllegalArgumentException.class, () -> new JwtCredentialProfile(
            IAM.name(), IAM.issuer(), IAM.tokenType(), IAM.audiences(), IAM.kind(),
            IAM.trustedPrimaryProfiles(), IAM.maximumLifetime(), IAM.clockSkew(), 16_385));
    }

    @Test
    void preservesRepeatedStringAssertionsWithoutFlatteningOrLosingOrigins() {
        JwtCredentialCodec codec = primary(List.of(JwtEcKey.verification("old", ecPublic(IAM_OLD))), null);
        String token = manual(primaryHeader("old"), validPayload()
            .claim("groups", List.of("group-a", "group-b", "group-a")).build(), IAM_OLD);
        AuthorizationCredential credential = codec.verifyPrimary(token).evidence();
        List<Claim> groups = credential.claims().stream().filter(c -> c.type().equals("groups")).toList();
        assertEquals(List.of("group-a", "group-b", "group-a"), groups.stream().map(Claim::value).toList());
        assertTrue(groups.stream().allMatch(c -> c.origin().equals(credential.origin())));
        assertRejected(() -> codec.verifyPrimary(manual(primaryHeader("old"), validPayload()
            .claim("groups", List.of("good", 123)).build(), IAM_OLD)));
    }

    @Test
    void rejectsNonP256OrMismatchedKeysAtConfigurationTime() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp384r1"));
        KeyPair p384 = generator.generateKeyPair();
        assertThrows(IllegalArgumentException.class, () -> JwtEcKey.signing("p384", p384));
        assertThrows(IllegalArgumentException.class, () -> JwtEcKey.verification("p384", ecPublic(p384)));
        assertThrows(IllegalArgumentException.class,
            () -> JwtEcKey.signing("wrong-pair", new KeyPair(IAM_OLD.getPublic(), IAM_NEW.getPrivate())));

        KeyPairGenerator rsa = KeyPairGenerator.getInstance("RSA");
        rsa.initialize(2048);
        assertThrows(IllegalArgumentException.class, () -> JwtEcKey.signing("rsa", rsa.generateKeyPair()));
    }

    @Test
    void rejectsActualRs256TokenDespiteTrustedKeyId() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair rsa = generator.generateKeyPair();
        SignedJWT signed = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256)
            .type(new JOSEObjectType(IAM.tokenType())).keyID("old").build(), validPayload().build());
        signed.sign(new RSASSASigner((RSAPrivateKey) rsa.getPrivate()));
        JwtCredentialCodec codec = primary(List.of(JwtEcKey.signing("old", IAM_OLD)), "old");
        assertRejected(() -> codec.verifyPrimary(signed.serialize()));
    }

    @Test
    void rejectsOversizedMandatoryCredentialFieldsBeforeSigning() {
        String overLimit = "x".repeat(257);
        assertThrows(IllegalArgumentException.class, () -> new JwtIssueRequest(ALICE,
            overLimit, "token", NOW, NOW, NOW.plusSeconds(60), Optional.empty(), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new JwtIssueRequest(ALICE,
            SESSION, overLimit, NOW, NOW, NOW.plusSeconds(60), Optional.empty(), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new JwtIssueRequest(ALICE,
            SESSION, "token", NOW, NOW, NOW.plusSeconds(60), Optional.of(overLimit), Map.of()));

        JwtCredentialCodec verifier = primary(List.of(JwtEcKey.signing("old", IAM_OLD)), "old");
        assertRejected(() -> verifier.verifyPrimary(manual(primaryHeader("old"),
            validPayload().claim("sid", overLimit).build(), IAM_OLD)));
        assertRejected(() -> verifier.verifyPrimary(manual(primaryHeader("old"),
            validPayload().jwtID(overLimit).build(), IAM_OLD)));
    }

    @Test
    void maximumLengthIdentifiersRoundTripForPrimaryAndSubordinateCredentials() {
        String maximum = "x".repeat(256);
        JwtCredentialCodec iam = primary(List.of(JwtEcKey.signing("old", IAM_OLD)), "old");
        JwtIssueRequest primaryRequest = new JwtIssueRequest(ALICE, maximum, maximum,
            NOW.minusSeconds(1), NOW.minusSeconds(1), NOW.plusSeconds(180),
            Optional.empty(), Map.of());
        VerifiedJwtCredentialCandidate primary = iam.verifyPrimary(iam.sign(primaryRequest));
        assertEquals(maximum, primary.sessionId());
        assertEquals(Optional.of(maximum), primary.evidence().origin().credentialId());

        JwtCredentialCodec group = subordinate();
        JwtIssueRequest subordinateRequest = new JwtIssueRequest(ALICE, maximum, maximum,
            NOW.minusSeconds(1), NOW.minusSeconds(1), NOW.plusSeconds(90),
            Optional.of(IAM_ISSUER), Map.of("role", "member"));
        VerifiedJwtCredentialCandidate subordinate = verifyAndBind(group, group.sign(subordinateRequest), primary);
        assertEquals(maximum, subordinate.sessionId());
        assertEquals(Optional.of(maximum), subordinate.evidence().origin().credentialId());
    }

    @Test
    void invalidInputsAndConfigurationFailBeforeSigningOrTrustPublication() {
        assertThrows(IllegalArgumentException.class, () -> new JwtCredentialProfile("x", IAM_ISSUER,
            "x+jwt", Set.of(), JwtCredentialProfile.Kind.PRIMARY, Set.of(), Duration.ofMinutes(5), Duration.ZERO, 4096));
        assertThrows(IllegalArgumentException.class, () -> new JwtCredentialProfile("x", IAM_ISSUER,
            "x+jwt", Set.of("sharewise-api"), JwtCredentialProfile.Kind.PRIMARY, Set.of(),
            Duration.ofMinutes(5), Duration.ofSeconds(61), 4096));
        assertThrows(IllegalArgumentException.class, () -> new Es256JwtSignatureService(List.of(), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new Es256JwtSignatureService(List.of(
            JwtEcKey.verification("old", ecPublic(IAM_OLD)), JwtEcKey.verification("old", ecPublic(IAM_NEW))), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new Es256JwtSignatureService(List.of(
            JwtEcKey.verification("old", ecPublic(IAM_OLD))), Optional.of("missing")));
        assertThrows(IllegalArgumentException.class, () -> new Es256JwtSignatureService(List.of(
            JwtEcKey.verification("old", ecPublic(IAM_OLD))), Optional.of("old")));
        assertThrows(IllegalArgumentException.class, () -> new JwtIssueRequest(ALICE, SESSION, "id",
            NOW, NOW, NOW.plusSeconds(60), Optional.empty(), Map.of("iss", "forged")));
        assertThrows(IllegalArgumentException.class, () -> subordinate().sign(issue(ALICE, SESSION)));
        assertThrows(IllegalArgumentException.class, () -> primary(
            List.of(JwtEcKey.signing("old", IAM_OLD)), "old")
            .sign(subordinateIssue(ALICE, SESSION, IAM_ISSUER)));
        assertFalse(JwtEcKey.signing("old", IAM_OLD).toString().contains("PRIVATE KEY"));
    }

    private static String claim(AuthorizationCredential credential, String name) {
        return credential.claims().stream().filter(c -> c.type().equals(name)).findFirst().orElseThrow().value();
    }

    private static JwtCredentialCodec primary(List<JwtEcKey> keys, String signingKeyId) {
        return new JwtCredentialCodec(IAM, new Es256JwtSignatureService(keys, Optional.ofNullable(signingKeyId)), CLOCK);
    }

    private static JwtCredentialCodec subordinate() {
        return new JwtCredentialCodec(GROUP,
            new Es256JwtSignatureService(List.of(JwtEcKey.signing("group-1", GROUP_KEY)), Optional.of("group-1")), CLOCK);
    }

    private static JwtIssueRequest issue(UUID subject, String session) {
        return new JwtIssueRequest(subject, session, "test-token-1", NOW.minusSeconds(1),
            NOW.minusSeconds(1), NOW.plusSeconds(180), Optional.empty(), Map.of("device", "desktop"));
    }

    private static JwtIssueRequest subordinateIssue(UUID subject, String session, String primaryIssuer) {
        return new JwtIssueRequest(subject, session, "group-token-1", NOW.minusSeconds(1),
            NOW.minusSeconds(1), NOW.plusSeconds(90), Optional.of(primaryIssuer), Map.of("role", "admin"));
    }

    private static JWTClaimsSet.Builder validPayload() {
        return new JWTClaimsSet.Builder().issuer(IAM_ISSUER).subject(ALICE.toString())
            .audience("sharewise-api").issueTime(Date.from(NOW.minusSeconds(1)))
            .notBeforeTime(Date.from(NOW.minusSeconds(1))).expirationTime(Date.from(NOW.plusSeconds(100)))
            .jwtID("token-123").claim("sid", SESSION);
    }

    private static JWSHeader primaryHeader(String kid) {
        return new JWSHeader.Builder(JWSAlgorithm.ES256)
            .type(new JOSEObjectType(IAM.tokenType())).keyID(kid).build();
    }

    private static JWSHeader header(String token) {
        try {
            return SignedJWT.parse(token).getHeader();
        } catch (java.text.ParseException exception) {
            throw new AssertionError(exception);
        }
    }

    private static String manual(JWSHeader header, JWTClaimsSet claims, KeyPair pair) {
        try {
            SignedJWT signed = new SignedJWT(header, claims);
            signed.sign(new ECDSASigner((ECPrivateKey) pair.getPrivate()));
            return signed.serialize();
        } catch (com.nimbusds.jose.JOSEException exception) {
            throw new AssertionError(exception);
        }
    }

    private static String b64(String value) {
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static void assertRejected(Runnable action) {
        JwtCredentialException rejected = assertThrows(JwtCredentialException.class, action::run);
        assertEquals("JWT credential verification failed", rejected.getMessage());
        assertEquals(null, rejected.getCause());
    }

    private static java.security.interfaces.ECPublicKey ecPublic(KeyPair pair) {
        return (java.security.interfaces.ECPublicKey) pair.getPublic();
    }

    private static KeyPair ec(int seed) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            SecureRandom random = SecureRandom.getInstance("SHA1PRNG");
            random.setSeed(new byte[] {(byte) seed, 0x45, 0x67, 0x12});
            generator.initialize(new java.security.spec.ECGenParameterSpec("secp256r1"), random);
            return generator.generateKeyPair();
        } catch (java.security.GeneralSecurityException exception) {
            throw new AssertionError(exception);
        }
    }
}
