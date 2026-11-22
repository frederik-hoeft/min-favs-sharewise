package de.hsh.favs.sharewise.identity.internal.auth.credential;

import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;

/** Fixed PBKDF2-HMAC-SHA256 profile for initial IAM password storage. */
public final class Pbkdf2CredentialEncoder implements CredentialEncoder {
    public static final int ITERATIONS = 600_000;
    public static final int SALT_BYTES = 16;
    public static final int DERIVED_KEY_BYTES = 32;
    private static final Pattern ENCODED = Pattern.compile("[0-9a-f]{96}");

    private final PasswordEncoder encoder = new Pbkdf2PasswordEncoder(
            "", SALT_BYTES, ITERATIONS,
            Pbkdf2PasswordEncoder.SecretKeyFactoryAlgorithm.PBKDF2WithHmacSHA256);
    private final PasswordPolicy policy;

    public Pbkdf2CredentialEncoder(PasswordPolicy policy) {
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    @Override
    public String encode(String rawPassword) {
        policy.validateRegistration(rawPassword);
        return encoder.encode(rawPassword);
    }

    @Override
    public boolean matches(String rawPassword, String encodedPassword) {
        policy.validateVerificationInput(rawPassword);
        // Spring's fixed profile stores lowercase hex(salt || derivedKey), not a
        // self-describing or versioned hash. Reject incompatible/invalid formats.
        if (encodedPassword == null || !ENCODED.matcher(encodedPassword).matches()) {
            return false;
        }
        return encoder.matches(rawPassword, encodedPassword);
    }
}
