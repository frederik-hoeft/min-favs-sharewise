package de.hsh.favs.sharewise.identity.internal.auth;

import de.hsh.favs.sharewise.identity.internal.auth.account.LoginLockoutPolicy;
import de.hsh.favs.sharewise.identity.internal.auth.session.SessionPolicy;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Deployment-supplied IAM trust and bounded authentication policies. */
@ConfigurationProperties(prefix = "sharewise.iam.auth")
public record IamAuthenticationSettings(
        String issuer, String audience, String activeKeyId, List<Key> keys,
        Session session, Lockout lockout) {

    public IamAuthenticationSettings {
        session = session == null ? new Session(null, null, null, null) : session;
        lockout = lockout == null ? new Lockout(null, null) : lockout;
    }

    public record Key(String id, String publicKeyFile, String privateKeyFile) { }

    public record Session(Duration accessLifetime, Duration minimumAccessLifetime,
            Duration idleLifetime, Duration absoluteLifetime) {
        public Session {
            accessLifetime = accessLifetime == null ? Duration.ofMinutes(5) : accessLifetime;
            minimumAccessLifetime = minimumAccessLifetime == null ? Duration.ofMinutes(1) : minimumAccessLifetime;
            idleLifetime = idleLifetime == null ? Duration.ofDays(7) : idleLifetime;
            absoluteLifetime = absoluteLifetime == null ? Duration.ofDays(30) : absoluteLifetime;
            // JWT NumericDate timestamps have whole-second precision. Keep a hard
            // safety floor even when the operator reduces the configured minimum.
            if (minimumAccessLifetime.compareTo(Duration.ofSeconds(2)) < 0
                    || accessLifetime.compareTo(minimumAccessLifetime) < 0
                    || accessLifetime.compareTo(Duration.ofHours(1)) > 0
                    || idleLifetime.compareTo(accessLifetime) < 0
                    || idleLifetime.compareTo(Duration.ofDays(90)) > 0
                    || absoluteLifetime.compareTo(idleLifetime) < 0
                    || absoluteLifetime.compareTo(Duration.ofDays(365)) > 0) {
                throw new IllegalArgumentException("Invalid IAM session lifetime configuration");
            }
        }

        public SessionPolicy toPolicy() {
            return new SessionPolicy(accessLifetime, idleLifetime, absoluteLifetime);
        }
    }

    public record Lockout(Integer threshold, Duration duration) {
        public Lockout {
            threshold = threshold == null ? 5 : threshold;
            duration = duration == null ? Duration.ofMinutes(15) : duration;
            if (threshold < 1 || threshold > 100 || duration.isNegative() || duration.isZero()
                    || duration.compareTo(Duration.ofDays(1)) > 0) {
                throw new IllegalArgumentException("Invalid IAM login lockout configuration");
            }
        }

        public LoginLockoutPolicy toPolicy() {
            return new LoginLockoutPolicy(threshold, duration);
        }
    }
}
