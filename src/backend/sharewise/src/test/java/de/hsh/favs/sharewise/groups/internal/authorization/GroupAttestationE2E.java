package de.hsh.favs.sharewise.groups.internal.authorization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jayway.jsonpath.JsonPath;
import de.hsh.favs.sharewise.ShareWiseApplication;
import de.hsh.favs.sharewise.groups.GroupRole;
import de.hsh.favs.sharewise.groups.internal.persistence.GroupTestFixture;
import de.hsh.favs.sharewise.shared.infra.web.AttestationTransport;
import de.hsh.favs.sharewise.testsupport.identity.TestAccessTokens;
import de.hsh.favs.sharewise.testsupport.integration.IntegrationTestContext;
import de.hsh.favs.sharewise.testsupport.postgres.PostgresTestDatabase;
import de.hsh.favs.sharewise.testsupport.postgres.ProductionEntityScanConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;

/** Real HTTP issuance and consumption against committed membership changes in PostgreSQL. */
@Tag("e2e")
class GroupAttestationE2E {
    private static final String GROUPS = "/api/groups/v1/groups";
    private static final String ISSUE = "/api/groups/v1/domain-principal/attestations";
    private static final UUID ALICE = UUID.fromString("018f8706-7b6e-7acc-9061-c3e78a1be401");
    private static final UUID BOB = UUID.fromString("018f8706-7b6e-7acc-9061-c3e78a1be402");

    @Test
    void committedDowngradeAndRemovalAffectFreshIssuanceButNotAlreadyIssuedEvidence() throws Exception {
        try (IntegrationTestContext fixture = IntegrationTestContext.startServlet(
                PostgresTestDatabase.ConnectionSettings.fromEnvironment(), database -> { },
                GroupAuthorizationHttpTests.signingSettings(), ShareWiseApplication.class,
                ProductionEntityScanConfiguration.class, GroupAuthorizationHttpTests.Fixture.class);
             HttpClient client = HttpClient.newHttpClient()) {
            int port = ((ServletWebServerApplicationContext) fixture.context()).getWebServer().getPort();
            URI base = URI.create("http://127.0.0.1:" + port);
            GroupTestFixture.provision(fixture, ALICE, BOB);
            HttpResponse<String> created = send(client, base, "POST", GROUPS, ALICE, "{\"name\":\"Trip\"}", null);
            assertEquals(201, created.statusCode());
            String group = JsonPath.read(created.body(), "$.id");
            GroupTestFixture.addMember(fixture, UUID.fromString(group), BOB, GroupRole.ADMIN);
            String route = GROUPS + "/" + group;
            String membership = JsonPath.read(send(client, base, "GET", route + "/workspace", BOB, null, null).body(), "$.callerMembershipId");
            String body = "{\"kind\":\"membership\",\"scope\":\"" + group + "\"}";
            String consumer = "/api/__test/distributed-authorization/role/" + group;

            // Seed a revoked IAM session matching the still-valid fixture bearer. Group must
            // not query IAM eligibility while issuing its own current-state assertion.
            IntegrationTestContext.seedCommitted(fixture.database(), connection -> {
                try (java.sql.PreparedStatement user = connection.prepareStatement("INSERT INTO identity.users(id,email,normalized_email,password_hash) VALUES (?, 'bob@example.test', 'bob@example.test', 'fixture')");
                     java.sql.PreparedStatement profile = connection.prepareStatement("INSERT INTO identity.user_profiles(user_id,display_name) VALUES (?, 'Bob')");
                     java.sql.PreparedStatement session = connection.prepareStatement("INSERT INTO identity.sessions(id,user_id,created_at,idle_expires_at,absolute_expires_at,revoked_at) VALUES (?, ?, '2026-11-20T11:00:00Z', '2026-11-20T13:00:00Z', '2026-11-20T14:00:00Z', '2026-11-20T11:59:59Z')")) {
                    user.setObject(1, BOB);
                    user.executeUpdate();
                    profile.setObject(1, BOB);
                    profile.executeUpdate();
                    session.setObject(1, TestAccessTokens.sessionFor(BOB));
                    session.setObject(2, BOB);
                    session.executeUpdate();
                }
            });
            String admin = issue(client, base, body);
            assertRole(client, base, consumer, admin, "ADMIN");
            assertEquals(204, send(client, base, "PUT", route + "/members/" + membership + "/role", ALICE, "{\"role\":\"member\"}", null).statusCode());
            String member = issue(client, base, body);
            assertRole(client, base, consumer, member, "MEMBER");
            assertRole(client, base, consumer, admin, "ADMIN");
            assertEquals(204, send(client, base, "DELETE", route + "/members/" + membership, ALICE, null, null).statusCode());
            assertEquals(404, send(client, base, "POST", ISSUE, BOB, body, null).statusCode());
            assertRole(client, base, consumer, member, "MEMBER");
        }
    }

    private static String issue(HttpClient client, URI base, String body) throws Exception {
        var response = send(client, base, "POST", ISSUE, BOB, body, null);
        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
        return JsonPath.read(response.body(), "$.attestation");
    }

    private static void assertRole(HttpClient client, URI base, String consumer, String token, String expected) throws Exception {
        var response = send(client, base, "GET", consumer, BOB, null, token);
        assertEquals(200, response.statusCode());
        assertEquals(expected, JsonPath.read(response.body(), "$.role"));
    }

    private static HttpResponse<String> send(HttpClient client, URI base, String method, String path, UUID user,
            String body, String attestation) throws Exception {
        var request = HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(20))
            .header("Authorization", TestAccessTokens.bearer(user))
            .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (body != null) request.header("Content-Type", "application/json");
        if (attestation != null) request.header(AttestationTransport.HEADER, attestation);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
