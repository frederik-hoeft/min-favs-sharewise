package de.hsh.favs.sharewise.identity.internal.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jayway.jsonpath.JsonPath;
import de.hsh.favs.sharewise.ShareWiseApplication;
import de.hsh.favs.sharewise.shared.infra.security.JwtCredentialCodec;
import de.hsh.favs.sharewise.testsupport.integration.IntegrationTestContext;
import de.hsh.favs.sharewise.testsupport.postgres.PostgresTestDatabase;
import de.hsh.favs.sharewise.testsupport.postgres.ProductionEntityScanConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;

/** Real servlet + PostgreSQL acceptance for committed IAM credential entry. */
@Tag("e2e")
class IamHttpAuthenticationE2E {

    @TempDir Path keysDirectory;

    @Test
    void csrfProtectedHttpRegistrationAndLoginExposeOnlyCommittedCredentials() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair pair = generator.generateKeyPair();
        Path publicKey = keysDirectory.resolve("iam-public.pem");
        Path privateKey = keysDirectory.resolve("iam-private.pem");
        writePem(publicKey, "PUBLIC KEY", pair.getPublic().getEncoded());
        writePem(privateKey, "PRIVATE KEY", pair.getPrivate().getEncoded());

        Map<String, Object> settings = Map.of(
                "sharewise.iam.auth.enabled", "true",
                "sharewise.iam.auth.issuer", "https://iam.sharewise.test",
                "sharewise.iam.auth.audience", "sharewise-api",
                "sharewise.iam.auth.active-key-id", "e2e",
                "sharewise.iam.auth.keys[0].id", "e2e",
                "sharewise.iam.auth.keys[0].public-key-file", publicKey.toString(),
                "sharewise.iam.auth.keys[0].private-key-file", privateKey.toString());

        // This fixture owns a disposable migrated database and launches the full real servlet application.
        try (IntegrationTestContext fixture = IntegrationTestContext.startServlet(
                PostgresTestDatabase.ConnectionSettings.fromEnvironment(), database -> { }, settings,
                ShareWiseApplication.class, ProductionEntityScanConfiguration.class);
             HttpClient client = HttpClient.newHttpClient()) {
            int port = ((ServletWebServerApplicationContext) fixture.context()).getWebServer().getPort();
            String base = "http://127.0.0.1:" + port;
            String origin = base;
            CsrfPair initial = csrf(client, base);
            String body = "{\"email\":\"user@example.com\",\"password\":\"correct-password\"}";

            HttpResponse<String> missingOrigin = post(client, base, origin, "/api/iam/v1/auth/register",
                    body, initial, false, true);
            assertEquals(403, missingOrigin.statusCode());
            assertEquals("invalid_origin", JsonPath.read(missingOrigin.body(), "$.code"));
            HttpResponse<String> missingCsrf = post(client, base, origin, "/api/iam/v1/auth/register",
                    body, initial, true, false);
            assertEquals(403, missingCsrf.statusCode());
            assertEquals("csrf_invalid", JsonPath.read(missingCsrf.body(), "$.code"));
            assertEquals(0, count(fixture, "identity.users"));

            HttpResponse<String> registered = post(client, base, origin, "/api/iam/v1/auth/register",
                    body, initial, true, true);
            assertEquals(201, registered.statusCode(), registered.body());
            String firstToken = JsonPath.read(registered.body(), "$.accessToken");
            String firstRefresh = findCookie(registered, "__Host-sharewise-refresh");
            assertTrue(firstRefresh.contains("HttpOnly"));
            assertTrue(firstRefresh.contains("Secure"));
            assertTrue(firstRefresh.contains("SameSite=Strict"));
            assertTrue(firstRefresh.contains("Path=/"));
            assertFalse(firstRefresh.contains("Domain="));
            assertFalse(registered.body().contains(firstRefresh.split("=", 2)[1].split(";", 2)[0]));
            assertEquals("no-store", registered.headers().firstValue("Cache-Control").orElseThrow());
            JwtCredentialCodec verifier = fixture.context().getBean("iamPrimaryJwtCredentialCodec", JwtCredentialCodec.class);
            assertEquals("https://iam.sharewise.test", verifier.verifyPrimary(firstToken).credential().origin().issuer());
            assertEquals(1, count(fixture, "identity.users"));
            assertEquals(1, count(fixture, "identity.sessions"));
            assertEquals(1, count(fixture, "identity.refresh_tokens"));
            // The response drops the bootstrap CSRF cookie. A subsequent credential mutation
            // must obtain another CSRF pair, as the browser coordinator already does.
            assertDeletedCookie(registered, "__Host-sharewise-csrf");

            CsrfPair second = csrf(client, base);
            HttpResponse<String> duplicate = post(client, base, origin, "/api/iam/v1/auth/register",
                    body, second, true, true);
            assertEquals(409, duplicate.statusCode());
            assertEquals("email_unavailable", JsonPath.read(duplicate.body(), "$.code"));
            assertFalse(duplicate.headers().allValues("Set-Cookie").stream()
                    .anyMatch(value -> value.contains("__Host-sharewise-refresh=")));
            assertEquals(1, count(fixture, "identity.sessions"));

            HttpResponse<String> denied = post(client, base, origin, "/api/iam/v1/auth/login",
                    "{\"email\":\"user@example.com\",\"password\":\"wrong-password\"}", second, true, true);
            assertEquals(401, denied.statusCode());
            assertEquals("invalid_credentials", JsonPath.read(denied.body(), "$.code"));
            assertEquals(1, count(fixture, "identity.sessions"));

            HttpResponse<String> loggedIn = post(client, base, origin, "/api/iam/v1/auth/login",
                    body, second, true, true);
            assertEquals(200, loggedIn.statusCode(), loggedIn.body());
            assertNotEquals(firstToken, JsonPath.read(loggedIn.body(), "$.accessToken"));
            assertEquals(2, count(fixture, "identity.sessions"));
            assertEquals(2, count(fixture, "identity.refresh_tokens"));
        }
    }

    private static CsrfPair csrf(HttpClient client, String base) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/api/iam/v1/auth/csrf"))
                .GET().timeout(Duration.ofSeconds(10)).build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        String token = JsonPath.read(response.body(), "$.token");
        String cookie = findCookie(response, "__Host-sharewise-csrf");
        assertTrue(cookie.contains("HttpOnly"));
        assertTrue(cookie.contains("Secure"));
        assertTrue(cookie.contains("SameSite=Strict"));
        return new CsrfPair(token, cookie.split(";", 2)[0]);
    }

    private static HttpResponse<String> post(HttpClient client, String base, String origin,
            String path, String json, CsrfPair csrf, boolean includeOrigin, boolean includeCsrf) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .header("Cookie", csrf.cookie())
                .POST(HttpRequest.BodyPublishers.ofString(json));
        if (includeOrigin) {
            builder.header("Origin", origin);
        }
        if (includeCsrf) {
            builder.header("X-XSRF-TOKEN", csrf.token());
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String findCookie(HttpResponse<?> response, String name) {
        return response.headers().allValues("Set-Cookie").stream()
                .filter(value -> value.startsWith(name + "="))
                .findFirst().orElseThrow(() -> new AssertionError("Missing cookie " + name));
    }

    private static void assertDeletedCookie(HttpResponse<?> response, String name) {
        String cookie = findCookie(response, name);
        List<String> attributes = List.of(cookie.split(";\\s*"));
        // The original cookie value must be cleared; simply expiring a nonempty
        // credential is a weaker and less explicit protocol assertion.
        assertEquals(name + "=", attributes.getFirst(), cookie);

        assertTrue(attributes.contains("Path=/"), cookie);
        assertTrue(attributes.contains("Secure"), cookie);
        assertTrue(attributes.contains("HttpOnly"), cookie);
        assertTrue(attributes.contains("SameSite=Strict"), cookie);
        assertFalse(attributes.stream().anyMatch(value -> value.startsWith("Domain=")), cookie);

        String maxAge = null;
        String expires = null;
        for (String attribute : attributes.subList(1, attributes.size())) {
            if (attribute.regionMatches(true, 0, "Max-Age=", 0, "Max-Age=".length())) {
                maxAge = attribute.substring("Max-Age=".length());
            } else if (attribute.regionMatches(true, 0, "Expires=", 0, "Expires=".length())) {
                expires = attribute.substring("Expires=".length());
            }
        }
        // MockMvc preserves Max-Age=0; Tomcat 11 emits a past Expires date.
        // Per cookie precedence rules, Max-Age takes priority when both exist.
        if (maxAge != null) {
            assertEquals(0L, Long.parseLong(maxAge), cookie);
        } else {
            assertTrue(expires != null, "Missing cookie expiration: " + cookie);
            Instant deadline = OffsetDateTime.parse(expires, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            assertTrue(deadline.isBefore(Instant.now()), "Cookie has not expired: " + cookie);
        }
    }

    private static int count(IntegrationTestContext fixture, String table) throws Exception {
        // The only call sites supply hard-coded schema-qualified table names.
        try (Connection connection = fixture.connectAsApplication();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT count(*) FROM " + table)) {
            assertTrue(rows.next());
            return rows.getInt(1);
        }
    }

    private static void writePem(Path file, String kind, byte[] data) throws Exception {
        Files.writeString(file, "-----BEGIN " + kind + "-----\n"
                + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(data)
                + "\n-----END " + kind + "-----\n");
    }

    private record CsrfPair(String token, String cookie) { }
}
