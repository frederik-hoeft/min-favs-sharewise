package de.hsh.favs.sharewise.groups.internal.authorization;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import de.hsh.favs.sharewise.ShareWiseApplication;
import de.hsh.favs.sharewise.shared.infra.health.ReadinessIndicator;
import de.hsh.favs.sharewise.shared.infra.web.AttestationTransport;
import de.hsh.favs.sharewise.shared.test.ComponentTest;
import de.hsh.favs.sharewise.testsupport.configuration.DatabaseFreeContext;
import de.hsh.favs.sharewise.testsupport.identity.TestAccessTokens;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import sharewise.deploymentfixture.AuthorizationSmokeApplication;

/** Exercises the actual deployed fixture configuration without pretending to have PostgreSQL or Docker. */
@ComponentTest
@SpringBootTest(classes = { ShareWiseApplication.class, AuthorizationSmokeApplication.class,
        GroupAuthorizationHttpTests.Fixture.class, GroupAuthorizationHttpTests.MembershipFixture.class }, properties = {
    DatabaseFreeContext.EXCLUDE_DATABASE_AUTOCONFIGURATION,
    "spring.main.lazy-initialization=true"
})
@AutoConfigureMockMvc
class AuthorizationSmokeFixtureTests {
    private static final UUID X = UUID.fromString("40000000-4000-7000-8000-000000000001");
    private static final UUID Y = UUID.fromString("40000000-4000-7000-8000-000000000002");
    private static final String ROOT = "/api/smoke/v1";
    private static final String HEADER = AttestationTransport.HEADER;

    @DynamicPropertySource
    static void trust(DynamicPropertyRegistry registry) {
        GroupAuthorizationHttpTests.signingSettings().forEach((key, value) -> registry.add(key, () -> value));
        registry.add("sharewise.iam.auth.issuer", TestAccessTokens.PROFILE::issuer);
    }

    @Autowired MockMvc mvc;
    @MockitoBean DataSource dataSource;
    @MockitoBean(name = "dataSourceReadinessIndicator", enforceOverride = true) ReadinessIndicator readiness;

    @Test
    void realFixtureAccumulatesTwoGroupScopesAndSecondAuthorityBeforeEffect() throws Exception {
        String bearer = TestAccessTokens.bearer(UUID.randomUUID());
        String path = ROOT + "/transfer/" + X;
        mvc.perform(post(path).param("destination", Y.toString()).header(HttpHeaders.AUTHORIZATION, bearer))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.authorization.requirementId").value("source"));
        String source = issue(bearer, "/api/groups/v1", "membership", X.toString());
        mvc.perform(post(path).param("destination", Y.toString()).header(HttpHeaders.AUTHORIZATION, bearer).header(HEADER, source))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.authorization.requirementId").value("destination"));
        String destination = issue(bearer, "/api/groups/v1", "membership", Y.toString());
        mvc.perform(post(path).param("destination", Y.toString()).header(HttpHeaders.AUTHORIZATION, bearer).header(HEADER, source, destination))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.authorization.credential.authority").value("smoke"));
        String secondary = issue(bearer, ROOT, "fixture", "transfer");
        mvc.perform(get(ROOT + "/effects").header(HttpHeaders.AUTHORIZATION, bearer))
            .andExpect(status().isOk()).andExpect(jsonPath("$.effects").value(0));
        mvc.perform(post(path).param("destination", Y.toString()).header(HttpHeaders.AUTHORIZATION, bearer).header(HEADER, source, destination, secondary))
            .andExpect(status().isOk()).andExpect(jsonPath("$.effects").value(1));
        mvc.perform(post(ROOT + "/domain-principal/attestations").header(HttpHeaders.AUTHORIZATION, bearer).header(HEADER, source)
            .contentType(MediaType.APPLICATION_JSON).content("{\"kind\":\"fixture\",\"scope\":\"transfer\"}"))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("invalid_attestation"));
    }

    private String issue(String bearer, String root, String kind, String scope) throws Exception {
        var response = mvc.perform(post(root + "/domain-principal/attestations").header(HttpHeaders.AUTHORIZATION, bearer)
            .contentType(MediaType.APPLICATION_JSON).content("{\"kind\":\"" + kind + "\",\"scope\":\"" + scope + "\"}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.attestation");
    }
}
