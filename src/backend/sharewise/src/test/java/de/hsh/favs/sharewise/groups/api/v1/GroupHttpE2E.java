package de.hsh.favs.sharewise.groups.api.v1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jayway.jsonpath.JsonPath;
import de.hsh.favs.sharewise.ShareWiseApplication;
import de.hsh.favs.sharewise.groups.GroupRole;
import de.hsh.favs.sharewise.groups.internal.persistence.GroupTestFixture;
import de.hsh.favs.sharewise.shared.infra.web.ApiProblems;
import de.hsh.favs.sharewise.testsupport.identity.TestAccessTokenConfiguration;
import de.hsh.favs.sharewise.testsupport.identity.TestAccessTokens;
import de.hsh.favs.sharewise.testsupport.integration.IntegrationTestContext;
import de.hsh.favs.sharewise.testsupport.postgres.PostgresTestDatabase;
import de.hsh.favs.sharewise.testsupport.postgres.ProductionEntityScanConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;

/**
 * The Group HTTP API through the real servlet application, API security chain, IAM bearer
 * ingress and PostgreSQL (GM-T01, GM-T03–GM-T05, GM-T07–GM-T11, GM-T14). Callers present
 * test-signed primary access tokens; the only test trust is {@link TestAccessTokenConfiguration}.
 * Each scenario owns a disposable migrated database, and state is asserted on independent
 * application-role connections after the responses.
 */
@Tag("e2e")
class GroupHttpE2E {

    private static final String GROUPS = GroupApiPaths.GROUPS;
    private static final UUID ALICE = UUID.fromString("018f8706-7b6e-7acc-9061-c3e78a1be401");
    private static final UUID BOB = UUID.fromString("018f8706-7b6e-7acc-9061-c3e78a1be402");
    private static final UUID CAROL = UUID.fromString("018f8706-7b6e-7acc-9061-c3e78a1be403");
    /** Holds a valid IAM token but has no {@code groups.users} row. */
    private static final UUID UNKNOWN = UUID.fromString("018f8706-7b6e-7acc-9061-c3e78a1be404");

    @Test
    void creationReturnsTheCommittedGroupWithTheCallerAsOnlyAdministrator() throws Exception {
        withServer(server -> {
            GroupTestFixture.provision(server.fixture(), ALICE, BOB);

            // Request fields that try to choose the creator, role or count are rejected without writes.
            HttpResponse<String> overreaching = server.send("POST", GROUPS, ALICE, """
                {"name": "Flat share", "description": "Rent and groceries",
                 "userId": "%s", "role": "member", "callerRole": "member", "memberCount": 7}""".formatted(BOB));
            assertEquals(400, overreaching.statusCode(), overreaching.body());
            assertEquals(ApiProblems.CODE_MALFORMED_REQUEST, JsonPath.read(overreaching.body(), "$.code"));
            assertEquals(List.of(), server.groups());

            HttpResponse<String> created = server.send("POST", GROUPS, ALICE,
                "{\"name\": \"  Flat share  \", \"description\": \"Rent and groceries\"}");
            assertEquals(201, created.statusCode(), created.body());
            String id = JsonPath.read(created.body(), "$.id");
            assertEquals(GROUPS + "/" + id, created.headers().firstValue("Location").orElseThrow());
            assertEquals("Flat share", JsonPath.read(created.body(), "$.name"));
            assertEquals("Rent and groceries", JsonPath.read(created.body(), "$.description"));
            assertEquals(0, (int) JsonPath.read(created.body(), "$.version"));
            assertEquals(1, (int) JsonPath.read(created.body(), "$.memberCount"));
            assertEquals("admin", JsonPath.read(created.body(), "$.callerRole"));
            assertTrue(JsonPath.<String>read(created.body(), "$.createdAt").endsWith("Z"), created.body());

            assertEquals(List.of(id + "|Flat share|Rent and groceries|0"), server.rows(
                "SELECT id, name, description, version FROM groups.groups",
                row -> row.getString(1) + "|" + row.getString(2) + "|" + row.getString(3) + "|" + row.getLong(4)));
            assertEquals(List.of(id + "|" + ALICE + "|admin"), server.rows(
                "SELECT group_id, user_id, role FROM groups.group_members",
                row -> row.getString(1) + "|" + row.getString(2) + "|" + row.getString(3)));

            HttpResponse<String> read = server.send("GET", GROUPS + "/" + id, ALICE, null);
            assertEquals(200, read.statusCode(), read.body());
            assertEquals(created.body(), read.body());
        });
    }

    @Test
    void invitationHttpLifecycleKeepsSecretsOneTimeAndChecksLiveAuthorization() throws Exception {
        withServer(server -> {
            GroupTestFixture.provision(server.fixture(), ALICE, BOB, CAROL);
            String group = server.create(ALICE, "Trip");
            String invitations = GROUPS + "/" + group + "/invitations";
            String redeem = "/api/groups/v1/invitations/redeem";
            String preview = "/api/groups/v1/invitations/preview";
            assertEquals(404, server.send("GET", invitations, BOB, null).statusCode());
            assertEquals(400, server.send("POST", invitations, ALICE, "{\"maxUses\":0}").statusCode());
            for (String invalid : List.of("{\"maxUses\":1.5}", "{\"maxUses\":\"2\"}", "{\"expiresAt\":1234567890}")) {
                assertEquals(400, server.send("POST", invitations, ALICE, invalid).statusCode());
            }
            HttpResponse<String> created = server.send("POST", invitations, ALICE, "{\"maxUses\":2}");
            assertEquals(201, created.statusCode(), created.body());
            assertTrue(created.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
            String token = JsonPath.read(created.body(), "$.token");
            String id = JsonPath.read(created.body(), "$.invitation.id");
            assertTrue(token.startsWith(id + "."));
            String body = "{\"token\":\"" + token + "\"}";
            assertEquals(401, server.send("POST", preview, Optional.empty(), body).statusCode());
            HttpResponse<String> previewed = server.send("POST", preview, BOB, body);
            assertEquals(200, previewed.statusCode(), previewed.body());
            assertTrue(previewed.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
            assertEquals(Map.of("groupName", "Trip"), JsonPath.read(previewed.body(), "$"));
            assertEquals(404, server.send("POST", preview, BOB, "{\"token\":\"broken\"}").statusCode());
            assertEquals(404, server.send("GET", GROUPS + "/" + group, BOB, null).statusCode());
            HttpResponse<String> listed = server.send("GET", invitations, ALICE, null);
            assertEquals(200, listed.statusCode());
            assertEquals(id, JsonPath.read(listed.body(), "$.items[0].id"));
            assertFalse(listed.body().contains(token));
            assertFalse(listed.body().contains("verifier"));
            assertEquals(List.of(32), server.rows("SELECT octet_length(verifier_hash) FROM groups.invitations", row -> row.getInt(1)));
            assertEquals(401, server.send("POST", redeem, Optional.empty(), body).statusCode());
            for (String invalid : List.of("{\"token\":17}", "{\"token\":[]}", "{\"token\":{}}",
                    "{\"token\":\"" + token + "\",\"unexpected\":true}")) {
                HttpResponse<String> malformed = server.send("POST", redeem, BOB, invalid);
                assertEquals(400, malformed.statusCode());
                assertEquals("malformed_request", JsonPath.read(malformed.body(), "$.code"));
                assertFalse(malformed.body().contains(token));
            }
            assertEquals(List.of(0), server.rows("SELECT use_count FROM groups.invitations", row -> row.getInt(1)));
            HttpResponse<String> joined = server.send("POST", redeem, BOB, body);
            assertEquals(200, joined.statusCode(), joined.body());
            assertEquals(group, JsonPath.read(joined.body(), "$.groupId"));
            assertEquals("joined", JsonPath.read(joined.body(), "$.outcome"));
            assertEquals("already_member", JsonPath.read(server.send("POST", redeem, BOB, body).body(), "$.outcome"));
            assertEquals(403, server.send("GET", invitations, BOB, null).statusCode());
            assertEquals(403, server.send("DELETE", invitations + "/" + id, BOB, null).statusCode());
            assertEquals(List.of(1), server.rows("SELECT use_count FROM groups.invitations", row -> row.getInt(1)));
            assertEquals(204, server.send("DELETE", invitations + "/" + id, ALICE, null).statusCode());
            assertEquals(404, server.send("POST", preview, CAROL, body).statusCode());
            HttpResponse<String> rejected = server.send("POST", redeem, CAROL, body);
            assertEquals(404, rejected.statusCode());
            assertEquals("group_invitation_unavailable", JsonPath.read(rejected.body(), "$.code"));
            assertFalse(rejected.body().contains(token));
        });
    }

    @Test
    void callersUnknownToGroupManagementAreRejectedOnEveryEndpointWithoutWrites() throws Exception {
        withServer(server -> {
            GroupTestFixture.provision(server.fixture(), ALICE);
            String group = server.create(ALICE, "Trip");

            List<HttpResponse<String>> responses = List.of(
                server.send("POST", GROUPS, UNKNOWN, "{\"name\": \"Mine\"}"),
                server.send("GET", GROUPS, UNKNOWN, null),
                server.send("GET", GROUPS + "/" + group, UNKNOWN, null),
                server.send("PUT", GROUPS + "/" + group, UNKNOWN, "{\"name\": \"Taken\", \"expectedVersion\": 0}"));

            for (HttpResponse<String> response : responses) {
                assertEquals(403, response.statusCode(), response.body());
                assertEquals(GroupProblems.USER_UNKNOWN, JsonPath.read(response.body(), "$.code"));
            }
            assertEquals(List.of("Trip|0"), server.groups());
            assertEquals(List.of(ALICE.toString()), server.rows("SELECT id FROM groups.users", row -> row.getString(1)));
            assertEquals(List.of(1L), server.rows("SELECT count(*) FROM groups.group_members", row -> row.getLong(1)));
        });
    }

    @Test
    void requestsWithoutAValidBearerAreRejectedBeforeAnyOperation() throws Exception {
        withServer(server -> {
            GroupTestFixture.provision(server.fixture(), ALICE);
            String group = server.create(ALICE, "Trip");
            List<String[]> endpoints = List.of(
                new String[] {"POST", GROUPS, "{\"name\": \"Other\"}"},
                new String[] {"GET", GROUPS, null},
                new String[] {"GET", GROUPS + "/" + group, null},
                new String[] {"PUT", GROUPS + "/" + group, "{\"name\": \"Renamed\", \"expectedVersion\": 0}"});

            for (String[] endpoint : endpoints) {
                // Route admission rejects a request without a credential.
                HttpResponse<String> anonymous = server.send(endpoint[0], endpoint[1], Optional.empty(), endpoint[2]);
                assertEquals(401, anonymous.statusCode(), endpoint[0] + " " + endpoint[1]);
                assertEquals("authentication_required", JsonPath.read(anonymous.body(), "$.code"));
                assertEquals("Bearer", anonymous.headers().firstValue("WWW-Authenticate").orElseThrow());

                // A supplied but invalid credential never becomes anonymous access.
                for (String invalid : List.of("Bearer broken", "Bearer " + TestAccessTokens.expiredAccessToken(ALICE),
                        "Bearer " + TestAccessTokens.wrongProfileAccessToken(ALICE), "Basic YWxpY2U6c2VjcmV0")) {
                    HttpResponse<String> rejected = server.send(endpoint[0], endpoint[1], Optional.of(invalid), endpoint[2]);
                    assertEquals(401, rejected.statusCode(), endpoint[0] + " " + endpoint[1] + " " + invalid);
                    assertEquals("invalid_credential", JsonPath.read(rejected.body(), "$.code"));
                }
            }
            assertEquals(List.of("Trip|0"), server.groups());
        });
    }

    @Test
    void invalidRequestsAreRejectedWithFieldErrorsBeforeAdmissionAndWithoutWrites() throws Exception {
        withServer(server -> {
            GroupTestFixture.provision(server.fixture(), ALICE);
            String group = server.create(ALICE, "Trip");

            assertFieldErrors(server.send("POST", GROUPS, ALICE, "{\"name\": \"\"}"), "name:required");
            assertFieldErrors(server.send("POST", GROUPS, ALICE, "{\"description\": \"No name\"}"), "name:required");
            assertFieldErrors(server.send("POST", GROUPS, ALICE, "{\"name\": \" \\t\\n \"}"), "name:required");
            assertFieldErrors(server.send("POST", GROUPS, ALICE, json("x".repeat(65), null)), "name:too_long");
            assertFieldErrors(server.send("POST", GROUPS, ALICE, json("Bell\u0007", null)), "name:control_character");
            assertFieldErrors(server.send("POST", GROUPS, ALICE, json("Trip", "d".repeat(513))),
                "description:too_long");
            assertFieldErrors(server.send("POST", GROUPS, ALICE, json(" ", "Tab\tinside")),
                "name:required", "description:control_character");
            // Validation precedes caller admission: an unknown caller with invalid input gets 400.
            assertFieldErrors(server.send("POST", GROUPS, UNKNOWN, "{\"name\": \"\"}"), "name:required");
            // A replacement reports a missing version together with every invalid metadata field.
            assertFieldErrors(server.send("PUT", GROUPS + "/" + group, ALICE, "{\"name\": \"\"}"),
                "name:required", "expectedVersion:required");
            assertFieldErrors(server.send("PUT", GROUPS + "/" + group, ALICE,
                "{\"name\": \"Trip\", \"expectedVersion\": -1}"), "expectedVersion:invalid");
            assertFieldErrors(server.send("PUT", GROUPS + "/" + group, ALICE,
                json("x".repeat(65), null).replace("}", ", \"expectedVersion\": 0}")), "name:too_long");

            record InvalidMetadata(String name, String description, String fieldError) { }
            for (InvalidMetadata invalid : List.of(
                    new InvalidMetadata("Trip\u202e", "Summer", "name:unsupported_character"),
                    new InvalidMetadata("...", "Summer", "name:unsupported_character"),
                    new InvalidMetadata("Trip", "Summer\u200b", "description:unsupported_character"),
                    new InvalidMetadata("\tTrip", "Summer", "name:control_character"),
                    new InvalidMetadata("Trip\t", "Summer", "name:control_character"),
                    new InvalidMetadata("Trip", "\tSummer", "description:control_character"),
                    new InvalidMetadata("Trip", "Summer\t", "description:control_character"))) {
                assertFieldErrors(server.send("POST", GROUPS, ALICE, json(invalid.name(), invalid.description())),
                    invalid.fieldError());
                assertFieldErrors(server.send("PUT", GROUPS + "/" + group, ALICE,
                    replacementJson(invalid.name(), invalid.description(), 0)), invalid.fieldError());
            }
            assertEquals(List.of("Trip|0"), server.groups());
            assertEquals(List.of(true), server.rows("SELECT description IS NULL FROM groups.groups", row -> row.getBoolean(1)));
            assertEquals(List.of(1L), server.rows("SELECT count(*) FROM groups.group_members", row -> row.getLong(1)));

            for (HttpResponse<String> malformed : List.of(
                    server.send("POST", GROUPS, ALICE, "{\"name\": "),
                    server.send("POST", GROUPS, ALICE, ""),
                    server.send("POST", GROUPS, ALICE, "[]"),
                    server.send("GET", GROUPS + "/not-a-uuid", ALICE, null),
                    server.send("PUT", GROUPS + "/not-a-uuid", ALICE, "{\"name\": \"Trip\", \"expectedVersion\": 0}"),
                    server.send("PUT", GROUPS + "/" + group, ALICE, "{\"name\": \"Trip\", \"expectedVersion\": \"x\"}"))) {
                assertEquals(400, malformed.statusCode(), malformed.body());
                assertEquals("malformed_request", JsonPath.read(malformed.body(), "$.code"));
            }
            assertEquals(List.of("Trip|0"), server.groups());

            // 64 code points of supplementary characters are 128 UTF-16 units, still a valid name.
            String letters = "𐐀".repeat(64);
            HttpResponse<String> longest = server.send("POST", GROUPS, ALICE, json(letters, " \n "));
            assertEquals(201, longest.statusCode(), longest.body());
            assertEquals(letters, JsonPath.read(longest.body(), "$.name"));
            assertNull(JsonPath.read(longest.body(), "$.description"), "a blank description is stored as absent");
            assertEquals(List.of("64|true"), server.rows(
                "SELECT char_length(name), description IS NULL FROM groups.groups WHERE id = '"
                    + JsonPath.read(longest.body(), "$.id") + "'",
                row -> row.getInt(1) + "|" + row.getBoolean(2)));
        });
    }

    @Test
    void theListContainsExactlyTheCallersGroupsWithRolesCountsAndOrdering() throws Exception {
        withServer(server -> {
            GroupTestFixture.provision(server.fixture(), ALICE, BOB, CAROL);
            String beta = server.create(ALICE, "beta");
            String alpha = server.create(ALICE, "Alpha");
            String gamma = server.create(BOB, "Gamma");
            server.create(BOB, "Bob only");
            GroupTestFixture.addMember(server.fixture(), UUID.fromString(gamma), ALICE, GroupRole.MEMBER);

            HttpResponse<String> mine = server.send("GET", GROUPS, ALICE, null);
            assertEquals(200, mine.statusCode(), mine.body());
            assertEquals(List.of(alpha, beta, gamma), JsonPath.read(mine.body(), "$.items[*].id"));
            assertEquals(List.of("admin", "admin", "member"), JsonPath.read(mine.body(), "$.items[*].callerRole"));
            assertEquals(List.of(1, 1, 2), JsonPath.read(mine.body(), "$.items[*].memberCount"));
            assertEquals(List.of("Alpha", "beta", "Gamma"), JsonPath.read(mine.body(), "$.items[*].name"));

            HttpResponse<String> none = server.send("GET", GROUPS, CAROL, null);
            assertEquals(200, none.statusCode(), none.body());
            assertEquals("{\"items\":[]}", none.body());
        });
    }

    @Test
    void storedMetadataIsReadableWithoutApplyingTheCurrentInputPolicy() throws Exception {
        withServer(server -> {
            GroupTestFixture.provision(server.fixture(), ALICE);
            String group = server.create(ALICE, "Trip");
            String route = GROUPS + "/" + group;
            String historicalName = "Summer trip 🌴";
            String historicalDescription = "Bring sunscreen ☀";
            // Establish database-valid historical text independently of the current write policy.
            IntegrationTestContext.seedCommitted(server.fixture().database(), connection -> {
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE groups.groups SET name = ?, description = ? WHERE id = ?")) {
                    statement.setString(1, historicalName);
                    statement.setString(2, historicalDescription);
                    statement.setObject(3, UUID.fromString(group));
                    assertEquals(1, statement.executeUpdate());
                }
            });

            HttpResponse<String> listed = server.send("GET", GROUPS, ALICE, null);
            assertEquals(200, listed.statusCode(), listed.body());
            assertEquals(List.of(group), JsonPath.read(listed.body(), "$.items[*].id"));
            assertEquals(historicalName, JsonPath.read(listed.body(), "$.items[0].name"));
            assertEquals(historicalDescription, JsonPath.read(listed.body(), "$.items[0].description"));
            HttpResponse<String> details = server.send("GET", route, ALICE, null);
            assertEquals(200, details.statusCode(), details.body());
            assertEquals(historicalName, JsonPath.read(details.body(), "$.name"));
            assertEquals(historicalDescription, JsonPath.read(details.body(), "$.description"));
            assertEquals(0, (int) JsonPath.read(details.body(), "$.version"));
            HttpResponse<String> workspace = server.send("GET", route + "/workspace", ALICE, null);
            assertEquals(200, workspace.statusCode(), workspace.body());
            assertEquals(historicalName, JsonPath.read(workspace.body(), "$.group.name"));
            assertEquals(historicalDescription, JsonPath.read(workspace.body(), "$.group.description"));

            assertFieldErrors(server.send("POST", GROUPS, ALICE, json(historicalName, historicalDescription)),
                "name:unsupported_character", "description:unsupported_character");
            assertFieldErrors(server.send("PUT", route, ALICE, replacementJson(historicalName, historicalDescription, 0)),
                "name:unsupported_character", "description:unsupported_character");
            assertEquals(List.of(historicalName + "|0"), server.groups());
            assertEquals(List.of(historicalDescription), server.rows("SELECT description FROM groups.groups", row -> row.getString(1)));

            HttpResponse<String> replaced = server.send("PUT", route, ALICE,
                replacementJson("Summer trip", "Bring sunscreen", 0));
            assertEquals(200, replaced.statusCode(), replaced.body());
            assertEquals("Summer trip", JsonPath.read(replaced.body(), "$.name"));
            assertEquals("Bring sunscreen", JsonPath.read(replaced.body(), "$.description"));
            assertEquals(1, (int) JsonPath.read(replaced.body(), "$.version"));
            assertEquals(List.of("Summer trip|1"), server.groups());
            assertEquals(List.of("Bring sunscreen"), server.rows("SELECT description FROM groups.groups", row -> row.getString(1)));
            HttpResponse<String> updated = server.send("GET", route, ALICE, null);
            assertEquals(200, updated.statusCode(), updated.body());
            assertEquals(replaced.body(), updated.body());
        });
    }

    @Test
    void nonMembersCannotDistinguishAForeignGroupFromAMissingOne() throws Exception {
        withServer(server -> {
            GroupTestFixture.provision(server.fixture(), ALICE, CAROL);
            String group = server.create(ALICE, "Private trip");
            String missing = UUID.randomUUID().toString();

            assertEquals(200, server.send("GET", GROUPS + "/" + group, ALICE, null).statusCode());
            HttpResponse<String> foreign = server.send("GET", GROUPS + "/" + group, CAROL, null);
            HttpResponse<String> absent = server.send("GET", GROUPS + "/" + missing, CAROL, null);
            HttpResponse<String> foreignReplace = server.send("PUT", GROUPS + "/" + group, CAROL,
                "{\"name\": \"Mine now\", \"expectedVersion\": 0}");
            HttpResponse<String> absentReplace = server.send("PUT", GROUPS + "/" + missing, CAROL,
                "{\"name\": \"Mine now\", \"expectedVersion\": 0}");

            for (HttpResponse<String> response : List.of(foreign, absent, foreignReplace, absentReplace)) {
                assertEquals(404, response.statusCode(), response.body());
                assertEquals(GroupProblems.NOT_FOUND, JsonPath.read(response.body(), "$.code"));
                assertFalse(response.body().contains("Private trip"), response.body());
            }
            assertEquals(foreign.body(), absent.body());
            assertEquals(foreign.body(), foreignReplace.body());
            assertEquals(foreign.body(), absentReplace.body());
            assertEquals(List.of("Private trip|0"), server.groups());
        });
    }

    @Test
    void onlyACurrentAdministratorReplacesMetadataAndOnlyWithTheCurrentVersion() throws Exception {
        withServer(server -> {
            GroupTestFixture.provision(server.fixture(), ALICE, BOB);
            HttpResponse<String> created = server.send("POST", GROUPS, ALICE,
                "{\"name\": \"Trip\", \"description\": \"Summer\"}");
            String group = JsonPath.read(created.body(), "$.id");
            GroupTestFixture.addMember(server.fixture(), UUID.fromString(group), BOB, GroupRole.MEMBER);

            HttpResponse<String> byMember = server.send("PUT", GROUPS + "/" + group, BOB,
                "{\"name\": \"Bob's trip\", \"description\": null, \"expectedVersion\": 0}");
            assertEquals(403, byMember.statusCode(), byMember.body());
            assertEquals(GroupProblems.ADMIN_REQUIRED, JsonPath.read(byMember.body(), "$.code"));
            assertEquals(List.of("Trip|0"), server.groups());

            // Fields naming roles, members or the version are rejected without writes.
            HttpResponse<String> overreaching = server.send("PUT", GROUPS + "/" + group, ALICE, """
                {"name": "Winter trip", "description": null, "expectedVersion": 0,
                 "callerRole": "member", "memberCount": 9, "version": 5, "userId": "%s"}""".formatted(BOB));
            assertEquals(400, overreaching.statusCode(), overreaching.body());
            assertEquals(ApiProblems.CODE_MALFORMED_REQUEST, JsonPath.read(overreaching.body(), "$.code"));
            assertEquals(List.of("Trip|0"), server.groups());

            // A null description clears it.
            HttpResponse<String> replaced = server.send("PUT", GROUPS + "/" + group, ALICE,
                "{\"name\": \"Winter trip\", \"description\": null, \"expectedVersion\": 0}");
            assertEquals(200, replaced.statusCode(), replaced.body());
            assertEquals("Winter trip", JsonPath.read(replaced.body(), "$.name"));
            assertNull(JsonPath.read(replaced.body(), "$.description"));
            assertEquals(1, (int) JsonPath.read(replaced.body(), "$.version"));
            assertEquals(2, (int) JsonPath.read(replaced.body(), "$.memberCount"));
            assertEquals("admin", JsonPath.read(replaced.body(), "$.callerRole"));
            assertEquals(JsonPath.<String>read(created.body(), "$.createdAt"), JsonPath.read(replaced.body(), "$.createdAt"));

            HttpResponse<String> read = server.send("GET", GROUPS + "/" + group, ALICE, null);
            assertEquals(replaced.body(), read.body());
            HttpResponse<String> asMember = server.send("GET", GROUPS + "/" + group, BOB, null);
            assertEquals("member", JsonPath.read(asMember.body(), "$.callerRole"));

            HttpResponse<String> stale = server.send("PUT", GROUPS + "/" + group, ALICE,
                "{\"name\": \"Stale\", \"description\": \"Old form\", \"expectedVersion\": 0}");
            assertEquals(409, stale.statusCode(), stale.body());
            assertEquals(GroupProblems.VERSION_CONFLICT, JsonPath.read(stale.body(), "$.code"));
            assertEquals(List.of("Winter trip|1"), server.groups());
            assertEquals(List.of(true), server.rows("SELECT description IS NULL FROM groups.groups",
                row -> row.getBoolean(1)));
        });
    }

    @Test
    void workspaceAndMembershipEndpointsUseCurrentMembershipAndScopedTargets() throws Exception {
        withServer(server -> {
            GroupTestFixture.provision(server.fixture(), ALICE, BOB, CAROL);
            String group = server.create(ALICE, "Trip");
            String foreign = server.create(CAROL, "Other");
            GroupTestFixture.addMember(server.fixture(), UUID.fromString(group), BOB, GroupRole.MEMBER);
            String route = GROUPS + "/" + group;
            HttpResponse<String> alice = server.send("GET", route + "/workspace", ALICE, null);
            HttpResponse<String> bob = server.send("GET", route + "/workspace", BOB, null);
            assertEquals(200, alice.statusCode(), alice.body());
            assertEquals(200, bob.statusCode(), bob.body());
            assertEquals(2, (int) JsonPath.read(alice.body(), "$.group.memberCount"));
            assertEquals("member", JsonPath.read(bob.body(), "$.group.callerRole"));
            assertEquals(2, JsonPath.<List<String>>read(alice.body(), "$.members[*].userId").size());
            String aliceId = JsonPath.read(alice.body(), "$.callerMembershipId");
            String bobId = JsonPath.read(bob.body(), "$.callerMembershipId");
            String foreignId = JsonPath.read(server.send("GET", GROUPS + "/" + foreign + "/workspace", CAROL, null).body(),
                "$.callerMembershipId");
            HttpResponse<String> outsider = server.send("GET", route + "/workspace", CAROL, null);
            HttpResponse<String> absent = server.send("GET", GROUPS + "/" + UUID.randomUUID() + "/workspace", CAROL, null);
            assertEquals(404, outsider.statusCode());
            assertEquals(absent.body(), outsider.body());
            assertEquals(403, server.send("GET", route + "/workspace", UNKNOWN, null).statusCode());
            String memberRoute = route + "/members/" + bobId;
            assertFieldErrors(server.send("PUT", memberRoute + "/role", ALICE, "{\"role\":\"owner\"}"), "role:invalid");
            assertEquals(403, server.send("PUT", memberRoute + "/role", BOB, "{\"role\":\"admin\"}").statusCode());
            assertEquals(403, server.send("DELETE", route + "/members/" + foreignId, BOB, null).statusCode());
            assertEquals(404, server.send("DELETE", route + "/members/" + foreignId, ALICE, null).statusCode());
            assertEquals(409, server.send("DELETE", route + "/members/" + aliceId, ALICE, null).statusCode());
            assertEquals(204, server.send("PUT", memberRoute + "/role", ALICE, "{\"role\":\"admin\"}").statusCode());
            assertEquals(409, server.send("PUT", route + "/members/" + aliceId + "/role", ALICE, "{\"role\":\"member\"}").statusCode());
            assertEquals(204, server.send("DELETE", route + "/members/" + aliceId, ALICE, null).statusCode());
            GroupTestFixture.addMember(server.fixture(), UUID.fromString(group), ALICE, GroupRole.MEMBER);
            assertEquals(404, server.send("DELETE", route + "/members/" + aliceId, BOB, null).statusCode());
            assertEquals(2, (int) JsonPath.read(server.send("GET", route + "/workspace", BOB, null).body(), "$.group.memberCount"));
            assertEquals(List.of("Other|0", "Trip|0"), server.groups());
        });
    }

    private static void assertFieldErrors(HttpResponse<String> response, String... expected) {
        assertEquals(400, response.statusCode(), response.body());
        assertEquals("validation_failed", JsonPath.read(response.body(), "$.code"));
        List<String> fields = JsonPath.read(response.body(), "$.fieldErrors[*].field");
        List<String> codes = JsonPath.read(response.body(), "$.fieldErrors[*].code");
        List<String> actual = new ArrayList<>();
        for (int index = 0; index < fields.size(); index++) {
            actual.add(fields.get(index) + ":" + codes.get(index));
        }
        assertEquals(List.of(expected), actual, response.body());
        List<String> messages = JsonPath.read(response.body(), "$.fieldErrors[*].message");
        assertTrue(messages.stream().noneMatch(String::isBlank), response.body());
    }

    /** A creation or replacement body with JSON-escaped values; {@code null} omits the description. */
    private static String json(String name, String description) {
        return description == null
            ? "{\"name\": " + quote(name) + "}"
            : "{\"name\": " + quote(name) + ", \"description\": " + quote(description) + "}";
    }

    private static String replacementJson(String name, String description, long expectedVersion) {
        String body = json(name, description);
        return body.substring(0, body.length() - 1) + ", \"expectedVersion\": " + expectedVersion + "}";
    }

    private static String quote(String value) {
        StringBuilder quoted = new StringBuilder("\"");
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '"' || character == '\\') {
                quoted.append('\\').append(character);
            } else if (character < 0x20) {
                quoted.append("\\u%04x".formatted((int) character));
            } else {
                quoted.append(character);
            }
        }
        return quoted.append('"').toString();
    }

    private static void withServer(ServerTest test) throws Exception {
        try (IntegrationTestContext fixture = IntegrationTestContext.startServlet(
                PostgresTestDatabase.ConnectionSettings.fromEnvironment(), database -> { }, Map.of(),
                ShareWiseApplication.class, ProductionEntityScanConfiguration.class,
                TestAccessTokenConfiguration.class);
             HttpClient client = HttpClient.newHttpClient()) {
            int port = ((ServletWebServerApplicationContext) fixture.context()).getWebServer().getPort();
            test.run(new Server(fixture, client, URI.create("http://127.0.0.1:" + port)));
        }
    }

    @FunctionalInterface
    private interface ServerTest {
        void run(Server server) throws Exception;
    }

    @FunctionalInterface
    private interface RowReader<T> {
        T read(ResultSet row) throws SQLException;
    }

    private record Server(IntegrationTestContext fixture, HttpClient client, URI base) {

        HttpResponse<String> send(String method, String path, UUID caller, String json) throws Exception {
            return send(method, path, Optional.of(TestAccessTokens.bearer(caller)), json);
        }

        HttpResponse<String> send(String method, String path, Optional<String> authorization, String json)
                throws Exception {
            HttpRequest.Builder request = HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(20))
                .method(method, json == null
                    ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json));
            if (json != null) {
                request.header("Content-Type", "application/json");
            }
            authorization.ifPresent(value -> request.header("Authorization", value));
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }

        /** Creates a group through the API and returns its ID. */
        String create(UUID caller, String name) throws Exception {
            HttpResponse<String> response = send("POST", GROUPS, caller, json(name, null));
            assertEquals(201, response.statusCode(), response.body());
            return JsonPath.read(response.body(), "$.id");
        }

        /** Committed groups as {@code name|version}, ordered by name. */
        List<String> groups() throws SQLException {
            return rows("SELECT name, version FROM groups.groups ORDER BY name",
                row -> row.getString(1) + "|" + row.getLong(2));
        }

        <T> List<T> rows(String sql, RowReader<T> reader) throws SQLException {
            try (Connection connection = fixture.connectAsApplication();
                 PreparedStatement statement = connection.prepareStatement(sql);
                 ResultSet rows = statement.executeQuery()) {
                List<T> values = new ArrayList<>();
                while (rows.next()) {
                    values.add(reader.read(rows));
                }
                return values;
            }
        }
    }
}
