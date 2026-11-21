package de.hsh.favs.sharewise.identity.internal.auth.credential;

import java.util.Base64;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Bounded Argon2id only; no legacy/unknown hash fallback and no unbounded work queue. */
public final class Argon2CredentialEncoder implements CredentialEncoder {
    public static final int MEMORY_KIB = 65_536;
    public static final int ITERATIONS = 3;
    public static final int LANES = 4;
    private static final Pattern ENCODED = Pattern.compile(
            "^\\$argon2id\\$v=19\\$m=([0-9]+),t=([0-9]+),p=([0-9]+)\\$([A-Za-z0-9+/]+)\\$([A-Za-z0-9+/]+)$");
    private final PasswordEncoder encoder;
    private final PasswordPolicy policy;
    private final PasswordBlocklist blocklist;
    private final Semaphore admission;

    public Argon2CredentialEncoder(PasswordPolicy policy, PasswordBlocklist blocklist, int maxConcurrent) {
        this(policy, blocklist, maxConcurrent,
                new Argon2PasswordEncoder(16, 32, LANES, MEMORY_KIB, ITERATIONS));
    }

    Argon2CredentialEncoder(PasswordPolicy policy, PasswordBlocklist blocklist,
            int maxConcurrent, PasswordEncoder encoder) {
        if (maxConcurrent < 1 || maxConcurrent > 32) {
            throw new IllegalArgumentException("Invalid Argon2 concurrency budget");
        }
        // Bouncy Castle allocates Argon2 working memory on the Java heap. Reserve
        // 128 MiB for the application and allow 96 MiB per concurrent hash.
        long estimatedHeapRequirement = 128L * 1024 * 1024
                + maxConcurrent * 96L * 1024 * 1024;
        if (estimatedHeapRequirement > Runtime.getRuntime().maxMemory()) {
            throw new IllegalArgumentException("Argon2 concurrency exceeds the configured JVM heap budget");
        }
        this.policy = Objects.requireNonNull(policy);
        this.blocklist = Objects.requireNonNull(blocklist);
        this.encoder = Objects.requireNonNull(encoder);
        admission = new Semaphore(maxConcurrent, true);
    }

    @Override
    public String encode(String rawPassword) {
        policy.validateRegistration(rawPassword);
        String normalized = policy.normalized(rawPassword);
        if (blocklist.contains(normalized)) {
            throw new IllegalArgumentException("Password is not allowed");
        }
        return bounded(() -> {
            String hash = encoder.encode(normalized);
            if (!isSafeStoredHash(hash)) {
                throw new IllegalStateException("Argon2 provider returned an invalid hash");
            }
            return hash;
        });
    }

    @Override
    public boolean matches(String rawPassword, String encodedPassword) {
        String normalized = policy.normalized(rawPassword);
        if (!isSafeStoredHash(encodedPassword)) {
            // Stored credentials are never automatically interpreted as bcrypt or plaintext.
            return false;
        }
        return bounded(() -> {
            try {
                return encoder.matches(normalized, encodedPassword);
            } catch (IllegalArgumentException malformedVerifier) {
                // Corrupt encoded credentials cannot authenticate and cannot expose parser diagnostics.
                return false;
            }
        });
    }

    private <T> T bounded(java.util.function.Supplier<T> work) {
        if (!admission.tryAcquire()) {
            throw new PasswordWorkloadExhaustedException();
        }
        try {
            return work.get();
        } finally {
            admission.release();
        }
    }

    /** Accept only the provisioned cost profile. A future upgrade must explicitly retain previous reviewed profiles. */
    private static boolean isSafeStoredHash(String hash) {
        if (hash == null || hash.length() > 512) {
            return false;
        }
        Matcher match = ENCODED.matcher(hash);
        if (!match.matches()) {
            return false;
        }
        try {
            int memory = Integer.parseInt(match.group(1));
            int iterations = Integer.parseInt(match.group(2));
            int lanes = Integer.parseInt(match.group(3));
            if (memory != MEMORY_KIB || iterations != ITERATIONS || lanes != LANES) {
                return false;
            }
            return Base64.getDecoder().decode(match.group(4)).length == 16
                    && Base64.getDecoder().decode(match.group(5)).length == 32;
        } catch (IllegalArgumentException failure) {
            return false;
        }
    }

    /** Deliberately carries no raw password or hash. Map saturation to retryable HTTP failure in F1. */
    public static final class PasswordWorkloadExhaustedException extends RuntimeException {
        public PasswordWorkloadExhaustedException() {
            super("Credential verification capacity exhausted");
        }
    }
}
