package de.hsh.favs.sharewise.identity.internal.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class IamAuthenticationPolicyBindingTests {
    @Test
    void missingPolicyConfigurationUsesSafeDocumentedDefaults() {
        IamAuthenticationSettings settings = bind(Map.of("sharewise.iam.auth.issuer", "https://iam.example.test"));
        assertEquals(Duration.ofMinutes(5), settings.session().accessLifetime());
        assertEquals(Duration.ofMinutes(1), settings.session().minimumAccessLifetime());
        assertEquals(Duration.ofDays(7), settings.session().idleLifetime());
        assertEquals(Duration.ofDays(30), settings.session().absoluteLifetime());
        assertEquals(5, settings.lockout().threshold());
        assertEquals(Duration.ofMinutes(15), settings.lockout().duration());
    }

    @Test
    void independentlyConfiguredSessionAndLockoutPoliciesAreBound() {
        IamAuthenticationSettings settings = bind(Map.of(
                "sharewise.iam.auth.session.access-lifetime", "10m",
                "sharewise.iam.auth.session.minimum-access-lifetime", "2m",
                "sharewise.iam.auth.session.idle-lifetime", "2d",
                "sharewise.iam.auth.session.absolute-lifetime", "20d",
                "sharewise.iam.auth.lockout.threshold", "8",
                "sharewise.iam.auth.lockout.duration", "30m"));
        assertEquals(Duration.ofMinutes(10), settings.session().toPolicy().accessLifetime());
        assertEquals(Duration.ofMinutes(2), settings.session().minimumAccessLifetime());
        assertEquals(Duration.ofDays(2), settings.session().toPolicy().idleLifetime());
        assertEquals(Duration.ofDays(20), settings.session().toPolicy().absoluteLifetime());
        assertEquals(8, settings.lockout().toPolicy().threshold());
        assertEquals(Duration.ofMinutes(30), settings.lockout().toPolicy().duration());
    }

    @Test
    void invalidExplicitConfigurationDoesNotFallBackToDefaults() {
        for (Map.Entry<String, String> invalid : Map.of(
                "sharewise.iam.auth.session.access-lifetime", "0s",
                "sharewise.iam.auth.session.minimum-access-lifetime", "500ms",
                "sharewise.iam.auth.session.idle-lifetime", "1s",
                "sharewise.iam.auth.session.absolute-lifetime", "400d",
                "sharewise.iam.auth.lockout.threshold", "0",
                "sharewise.iam.auth.lockout.duration", "-1s").entrySet()) {
            assertThrows(Exception.class, () -> bind(Map.of(invalid.getKey(), invalid.getValue())));
        }
    }

    @Test
    void rejectsAccessLifetimeShorterThanConfiguredMinimum() {
        assertThrows(Exception.class, () -> bind(Map.of(
                "sharewise.iam.auth.session.access-lifetime", "500ms")));
        assertThrows(Exception.class, () -> bind(Map.of(
                "sharewise.iam.auth.session.access-lifetime", "1m",
                "sharewise.iam.auth.session.minimum-access-lifetime", "2m")));
    }

    @Test
    void acceptsAnExplicitLowerMinimumOnlyAboveJwtPrecisionSafetyFloor() {
        IamAuthenticationSettings settings = bind(Map.of(
                "sharewise.iam.auth.session.access-lifetime", "30s",
                "sharewise.iam.auth.session.minimum-access-lifetime", "30s"));
        assertEquals(Duration.ofSeconds(30), settings.session().toPolicy().accessLifetime());
        assertEquals(Duration.ofSeconds(30), settings.session().minimumAccessLifetime());
    }

    private static IamAuthenticationSettings bind(Map<String, String> configuration) {
        Binder binder = new Binder(new MapConfigurationPropertySource(configuration));
        return binder.bind("sharewise.iam.auth", Bindable.of(IamAuthenticationSettings.class)).get();
    }
}
